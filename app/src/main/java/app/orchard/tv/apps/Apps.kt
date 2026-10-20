package app.orchard.tv.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import app.orchard.tv.system.TvSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    /** Launched with CATEGORY_LEANBACK_LAUNCHER when the app has a TV entry, else CATEGORY_LAUNCHER. */
    val leanback: Boolean,
    /** False for apps on the system image, which can be disabled but not uninstalled. */
    val removable: Boolean,
    val art: TileArt,
    /** Set for a TV input pinned to Home (HDMI, tuner...) rather than an app. */
    val source: TvSource? = null,
) {
    val key: String get() = packageName

    fun launchIntent(): Intent = source?.let { Intent(it.intent) } ?: Intent(Intent.ACTION_MAIN)
        .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
        .setComponent(component)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/** A Home tile for a TV input. Its key never collides with a package name. */
fun sourceEntry(source: TvSource): AppEntry = AppEntry(
    packageName = SOURCE_KEY_PREFIX + source.id,
    label = source.label,
    component = ComponentName("input", source.id),
    leanback = false,
    removable = false,
    art = TileArtFactory.forSource(source.kind, source.label, source.deviceName),
    source = source,
)

const val SOURCE_KEY_PREFIX = "input:"

object AppCatalog {
    /** Xiaomi's app store declares only this category, not CATEGORY_LAUNCHER (MiTV-MFTR0). */
    private const val CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"

    /**
     * Firmware apps that the maker's own launcher shows but that declare no launcher category at
     * all, so no query finds them. Verified on Xiaomi MiTV-MFTR0.
     */
    private val UNLISTED_ENTRIES = listOf(
        ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"), // 高清播放器
        ComponentName("com.xiaomi.smarthome.tv", "com.xiaomi.smarthome.tv.ui.MainActivity"), // 米家
    )

    private class Cached(val stamp: Long, val label: String, val art: TileArt)
    private val cache = HashMap<String, Cached>()

    /**
     * Every app with a TV entry, plus apps that only have a phone-style entry. China-market firmwares
     * (Xiaomi's settings, gallery, weather...) expose many of their own apps with CATEGORY_LAUNCHER only,
     * and a few with no launcher category at all (see [UNLISTED_ENTRIES]).
     * One tile per package; the TV entry wins.
     */
    suspend fun load(context: Context): List<AppEntry> = withContext(Dispatchers.Default) {
        val pm = context.packageManager
        val found = LinkedHashMap<String, Pair<ActivityInfo, Boolean>>()
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to true) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to false) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_APPSTORE_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to false) }
        for (component in UNLISTED_ENTRIES) {
            val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull() ?: continue
            if (info.exported && info.isEnabled && info.applicationInfo.enabled) found.putIfAbsent(info.packageName, info to false)
        }
        found.remove(context.packageName)

        coroutineScope {
            found.values.map { (info, leanback) ->
                async { runCatching { entry(pm, info, leanback) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Before Android 8 there are no adaptive icons, and loadIcon picks the density bucket of the
     * screen: 96 px at 320 dpi, stretched to the 198 px tile it looks soft (Xiaomi MiTV4-ANSM0,
     * Android 6, issue #17). Ask for the densest bucket the app ships instead.
     */
    private fun bestIcon(pm: PackageManager, info: ActivityInfo): android.graphics.drawable.Drawable {
        if (Build.VERSION.SDK_INT < 26) {
            val res = info.iconResource
            if (res != 0) runCatching {
                pm.getResourcesForApplication(info.applicationInfo)
                    .getDrawableForDensity(res, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
            }.getOrNull()?.let { return it }
        }
        return info.loadIcon(pm)
    }

    private fun entry(pm: PackageManager, info: ActivityInfo, leanback: Boolean): AppEntry {
        val pkg = info.packageName
        val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val cached = synchronized(cache) { cache[pkg] }?.takeIf { it.stamp == stamp }
        val label: String
        val art: TileArt
        if (cached != null) {
            label = cached.label
            art = cached.art
        } else {
            label = info.loadLabel(pm).toString().trim().ifEmpty { pkg }
            // Banner first: it is the artwork the app designed for a TV. ActivityInfo falls back
            // to the application's banner by itself.
            val banner = if (leanback) runCatching { info.loadBanner(pm) }.getOrNull() else null
            art = if (banner != null) TileArtFactory.fromBanner(banner) else TileArtFactory.fromIcon(bestIcon(pm, info))
            synchronized(cache) { cache[pkg] = Cached(stamp, label, art) }
        }
        val removable = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        return AppEntry(pkg, label, ComponentName(pkg, info.name), leanback, removable, art)
    }
}

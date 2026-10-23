package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orchard.tv.settings.AerialSettings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The pictures that come from outside: Bing, Wikimedia Commons, NASA, Unsplash, and "My Photos".
 * They show up as the auto wallpaper (a new one every few minutes while Home is up, never the same
 * twice until all have been shown), as rows of pictures to pick from, and as the Control Center's
 * "next wallpaper". The choosing and the filters are in [MixPick] and [MixSources]; this is the
 * files, the network and the clock.
 *
 * In `filesDir/mix`: `pool.json` (candidates), `history.json` (ids shown in this round),
 * `hidden.json` (ids never to show), `state.json` (the picture on the wall, when it went up, the
 * one fetched for next, and the one picked to stay), `thumbs/` and the pictures themselves. A saved
 * picture is a copy in "My Photos" (`filesDir/photos`). State is Compose snapshot state, written
 * on the main thread only, as in [Wallpapers].
 */
object Mix {
    private const val TAG = "AerialMix"
    private const val POOL_REFRESH_MS = 6 * 60 * 60 * 1000L
    private const val RETRY_MS = 5 * 60 * 1000L
    private const val MAX_BYTES = 25 * 1024 * 1024
    private const val MAX_PER_SOURCE = 300
    private const val GALLERY = 24
    private const val TRIES = 5
    private const val USER_AGENT = "aerial-tv (https://github.com/pengchujin/aerial-tv)"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dir by lazy { File(app.filesDir, "mix").apply { mkdirs() } }
    private val thumbDir by lazy { File(dir, "thumbs").apply { mkdirs() } }
    private val photoDir by lazy { File(app.filesDir, "photos").apply { mkdirs() } }
    private var started = false

    /** The picture on the wall now, as a wallpaper of its own (id "mix:<picture id>"); null until the first. */
    var current by mutableStateOf<Wallpaper?>(null); private set

    /** Counts changes the rows of pictures have to redraw for: a thumbnail arrived, a picture was hidden or saved. */
    var galleryVersion by mutableStateOf(0); private set

    private var currentPicture: MixPicture? = null
    private var changedAt = 0L
    private var next: MixPicture? = null
    private var pinned: MixPicture? = null
    private var pool = emptyList<MixPicture>()
    private var shown = emptySet<String>()
    private var hidden = emptySet<String>()
    private var preparing = false
    private var poolBusy = false
    private var poolCheckedAt = 0L
    private var poolFetchedFor: Triple<Set<MixSource>, Set<MixTheme>, Boolean>? = null
    private var poolThemes = ""
    private var failedAt = 0L
    private val thumbsLoading = HashSet<String>()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        pool = MixPick.readPictures(File(dir, "pool.json").takeIf { it.exists() }?.readText())
        shown = MixPick.parseHistory(File(dir, "history.json").takeIf { it.exists() }?.readText())
        hidden = MixPick.parseHistory(File(dir, "hidden.json").takeIf { it.exists() }?.readText())
        poolThemes = File(dir, "themes.txt").takeIf { it.exists() }?.readText().orEmpty()
        runCatching {
            val state = JSONObject(File(dir, "state.json").readText())
            fun picture(name: String) = MixPick.readPicture(state.optJSONObject(name)?.toString())
            currentPicture = picture("current")?.takeIf { fileOf(it).exists() }
            changedAt = state.optLong("at")
            next = picture("next")?.takeIf { fileOf(it).exists() }
            pinned = picture("pinned")
        }
        current = currentPicture?.let(::wallpaperOf)
    }

    // ---- what the wallpapers ask ----

    /** The picture with this id (of any source) if we know it. */
    private fun find(pictureId: String): MixPicture? =
        (listOfNotNull(pinned, currentPicture, next) + pool).firstOrNull { it.id == pictureId }

    /** Where a wallpaper "mix:<picture id>" keeps its picture; null if it is not one of ours. */
    fun fileFor(id: String): File? = find(id.removePrefix("mix:"))?.let(::fileOf)

    fun wallpaperFor(id: String): Wallpaper? = find(id.removePrefix("mix:"))?.let(::wallpaperOf)

    /** The picture behind a Mix wallpaper, e.g. for its thumbnail. */
    fun pictureOf(id: String): MixPicture? = find(id.removePrefix("mix:"))

    private fun fileOf(p: MixPicture): File =
        if (p.source == MixSource.Mine) File(photoDir, p.id.removePrefix("photo:") + ".jpg")
        else File(dir, Integer.toHexString(p.id.hashCode()) + ".jpg")

    private fun wallpaperOf(p: MixPicture) = Wallpaper("mix:${p.id}", WallpaperKind.Mix, p.title, p.credit)

    /** Whether "next wallpaper" has anything to do for this selection: a picture, not a moving or drawn one. */
    fun canNext(selectedId: String): Boolean =
        selectedId == Wallpapers.MIX || selectedId.startsWith("mix:") || selectedId.startsWith("photo:") ||
            selectedId.startsWith("daily:") && selectedId != Wallpapers.LATEST_DAILY

    // ---- the auto wallpaper ----

    /** The auto wallpaper was chosen: have a picture, and the pool behind it, ready. */
    fun onSelected() {
        refreshPoolIfStale()
        rotateIfDue()
    }

    /** A picture chosen to stay (from a row, or by "next wallpaper"): fetched if it is not here yet. */
    fun pin(pictureId: String) {
        val p = find(pictureId) ?: return
        pinned = p
        saveState()
        if (fileOf(p).exists()) {
            Wallpapers.mixChanged()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { download(p) }
            if (ok) Wallpapers.mixChanged()
        }
    }

    /** Called whenever Home is up, and by a timer while it stays up. */
    fun rotateIfDue() {
        if (!started || Wallpapers.selectedId != Wallpapers.MIX) return
        val due = current == null || MixPick.isDue(changedAt, System.currentTimeMillis(), AerialSettings.mixMinutes)
        val ready = next
        when {
            due && ready != null -> show(ready)
            due || ready == null -> prepare(showWhenReady = due)
        }
    }

    /** The Control Center's "next wallpaper": a random picture from the chosen sources, the mode as it was. */
    fun next() {
        val selected = Wallpapers.selectedId
        when {
            selected == Wallpapers.MIX -> next?.let(::show) ?: prepare(showWhenReady = true)
            canNext(selected) -> replacePinned()
        }
    }

    private fun replacePinned() {
        if (preparing) return
        val candidates = candidates()
        if (candidates.isEmpty()) {
            refreshPoolIfStale(force = true)
            return
        }
        preparing = true
        scope.launch {
            val p = fetchOne(candidates, Wallpapers.selectedId.removePrefix("mix:"))
            preparing = false
            saveHistory()
            if (p != null) Wallpapers.select("mix:${p.id}")
        }
    }

    private fun show(p: MixPicture) {
        currentPicture = p
        changedAt = System.currentTimeMillis()
        next = null
        current = wallpaperOf(p)
        saveState()
        Wallpapers.mixChanged()
        val keep = setOfNotNull(fileOf(p).name, pinned?.let(::fileOf)?.name)
        scope.launch(Dispatchers.IO) {
            dir.listFiles { f -> f.extension == "jpg" && f.name !in keep }?.forEach { it.delete() }
            if (p.ping.isNotEmpty()) ping(p.ping)
        }
        prepare(showWhenReady = false)
    }

    /** Unsplash asks to be told when one of its pictures is used. */
    private fun ping(url: String) {
        val key = AerialSettings.unsplashKey
        if (key.isBlank()) return
        runCatching { fetch(url, mapOf("Authorization" to "Client-ID $key")) }
    }

    /** What may be shown now: the chosen sources' pictures that are big enough and not hidden. */
    private fun candidates(): List<MixPicture> {
        val active = activeSources()
        val fromWeb = pool.filter { it.source in active }
        val mine = if (MixSource.Mine in active) minePictures() else emptyList()
        return MixPick.withoutHidden(MixPick.pool(fromWeb + mine), hidden)
    }

    private fun activeSources() = MixSources.activeSources(AerialSettings.mixThemes, AerialSettings.mixSources, AerialSettings.unsplashKey)

    private fun minePictures(): List<MixPicture> = Wallpapers.items.filter { it.kind == WallpaperKind.Photo }
        .map { MixPicture(it.id, MixSource.Mine, "", 0, 0, it.title, it.caption.orEmpty()) }

    /** Fetches the next picture; the first of a few candidates to download and decode wins. */
    private fun prepare(showWhenReady: Boolean) {
        if (preparing || System.currentTimeMillis() - failedAt < RETRY_MS && failedAt != 0L) return
        val candidates = candidates()
        if (candidates.isEmpty()) {
            refreshPoolIfStale(force = true)
            return
        }
        preparing = true
        scope.launch {
            val p = fetchOne(candidates, currentPicture?.id)
            preparing = false
            saveHistory()
            if (p == null) {
                failedAt = System.currentTimeMillis()
                Log.w(TAG, "no picture could be fetched; trying again in a few minutes")
                return@launch
            }
            failedAt = 0L
            if (showWhenReady) show(p) else { next = p; saveState() }
        }
    }

    /** One picture, not shown in this round, in its file; null if [TRIES] candidates did not download. */
    private suspend fun fetchOne(candidates: List<MixPicture>, last: String?): MixPicture? {
        repeat(TRIES) {
            val pick = MixPick.next(candidates, shown, last, Random) ?: return null
            shown = pick.shown
            val ok = withContext(Dispatchers.IO) { download(pick.picture) }
            shown = MixPick.afterDownload(ok, pick.picture.id, shown)
            if (ok) return pick.picture
        }
        return null
    }

    /** True if [p] is in its file now: a picture of at least 1920 px that decodes. */
    private fun download(p: MixPicture): Boolean = runCatching {
        if (p.source == MixSource.Mine) return@runCatching fileOf(p).exists()
        val bytes = fetch(p.url)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1920) {
            Log.i(TAG, "${p.id}: ${bounds.outWidth} px wide, skipped")
            return@runCatching false
        }
        if (bounds.outWidth < 3840) Log.i(TAG, "${p.id}: only ${bounds.outWidth} px wide")
        val file = fileOf(p)
        val tmp = File(dir, file.name + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }.onFailure { Log.w(TAG, "${p.id} not fetched", it) }.getOrDefault(false)

    private fun fetch(url: String, headers: Map<String, String> = emptyMap(), redirects: Int = 3): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            // Only to the host the request began with: a redirect must not carry a key elsewhere.
            if (redirects == 3) headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.instanceFollowRedirects = false
            if (c.responseCode in 301..308 && redirects > 0) {
                val to = URL(URL(url), c.getHeaderField("Location")).toString()
                return fetch(to, emptyMap(), redirects - 1)
            }
            if (c.responseCode !in 200..299) throw HttpFailure(c.responseCode)
            // contentLengthLong needs API 24; the header says the same on every level.
            val length = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            if (length > MAX_BYTES) error("too big: $length")
            return c.inputStream.use { it.readBytes() }.also { if (it.size > MAX_BYTES) error("too big") }
        } finally {
            c.disconnect()
        }
    }

    // ---- rows of pictures, and what can be done to them ----

    /** The pictures of one source to choose from: those in its pool, not hidden. Reading it subscribes to [galleryVersion]. */
    fun gallery(source: MixSource): List<MixPicture> {
        galleryVersion
        return MixPick.withoutHidden(pool.filter { it.source == source }, hidden).take(GALLERY)
    }

    /** The small picture for a row; null until it has arrived (it is asked for, and [galleryVersion] changes when it is here). */
    fun thumbFile(p: MixPicture): File? {
        if (p.source == MixSource.Mine || p.thumb.isBlank()) return null
        val file = File(thumbDir, Integer.toHexString(p.id.hashCode()) + ".jpg")
        if (file.exists()) return file
        if (thumbsLoading.add(p.id)) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val tmp = File(thumbDir, file.name + ".part")
                        tmp.writeBytes(fetch(p.thumb))
                        tmp.renameTo(file)
                    }.onFailure { Log.w(TAG, "thumbnail of ${p.id} not fetched", it) }
                }
                thumbsLoading.remove(p.id)
                galleryVersion++
            }
        }
        return null
    }

    /** Never show this picture again (it is also gone from the rows). Where it is on the wall it stays there. */
    fun hide(pictureId: String) {
        hidden = hidden + pictureId
        writeHidden()
        if (next?.id == pictureId) {
            next = null
            saveState()
        }
        if (currentPicture?.id == pictureId && Wallpapers.selectedId == Wallpapers.MIX) next()
        else if (next == null && Wallpapers.selectedId == Wallpapers.MIX) prepare(showWhenReady = false)
        galleryVersion++
    }

    /** Keeps a copy of this picture in "My Photos", with who made it; once only, however many times it is asked. */
    fun save(pictureId: String) {
        val p = find(pictureId)?.takeIf { it.source != MixSource.Mine } ?: return
        val name = MixPick.savedName(p.id)
        val target = File(photoDir, "$name.jpg")
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (target.exists()) return@withContext true
                val source = fileOf(p)
                if (!source.exists() && !download(p)) return@withContext false
                source.copyTo(target, overwrite = false)
                File(photoDir, "$name.json").writeText(JSONObject().put("title", p.title).put("credit", p.credit).toString())
                true
            }
            if (!ok) return@launch
            // The copy takes the picture's place in the rotation, so it does not come round twice.
            hidden = hidden + p.id
            writeHidden()
            Wallpapers.photosChanged()
            galleryVersion++
        }
    }

    // ---- the pool ----

    /** The candidates are asked for again every six hours, or at once when sources or themes changed or there are none. */
    fun refreshPoolIfStale(force: Boolean = false) {
        if (!started || poolBusy) return
        val sources = activeSources() - MixSource.Mine
        val themes = AerialSettings.mixThemes
        val signature = Triple(sources, themes, AerialSettings.unsplashKey.isNotBlank())
        val now = System.currentTimeMillis()
        val stale = now - poolCheckedAt >= POOL_REFRESH_MS || poolFetchedFor != signature
        val retry = sources.isNotEmpty() && pool.none { it.source in sources } && now - poolCheckedAt >= RETRY_MS
        if (!stale && !retry && !force) return
        if (force && !stale && now - poolCheckedAt < RETRY_MS) return
        poolBusy = true
        poolCheckedAt = now
        scope.launch {
            val themesKey = themes.map { it.key }.sorted().joinToString(",")
            val themesChanged = themesKey != poolThemes
            val fresh = withContext(Dispatchers.IO) {
                if (themesChanged) File(dir, "cursors.json").delete()
                sources.flatMap { runCatching { load(it) }.onFailure { e -> Log.w(TAG, "$it unavailable", e) }.getOrDefault(emptyList()) }
            }
            poolBusy = false
            poolFetchedFor = signature
            // Pictures found under other themes do not belong any more; Bing has none.
            val old = if (themesChanged) pool.filter { it.source == MixSource.Bing } else pool
            val merged = (fresh + old).distinctBy { it.id }
                .groupBy { it.source }.values.flatMap { it.take(MAX_PER_SOURCE) }
            if (merged.isNotEmpty() || themesChanged) {
                pool = merged
                poolThemes = themesKey
                withContext(Dispatchers.IO) {
                    File(dir, "pool.json").writeText(MixPick.writePictures(merged))
                    File(dir, "themes.txt").writeText(themesKey)
                }
            }
            galleryVersion++
            if (Wallpapers.selectedId == Wallpapers.MIX) rotateIfDue()
        }
    }

    private fun load(source: MixSource): List<MixPicture> = when (source) {
        MixSource.Bing -> {
            val market = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
            listOf(0, 8).flatMap { runCatching { MixSources.parseBing(text(MixSources.bingUrl(it, market))) }.getOrDefault(emptyList()) }
        }
        MixSource.Commons -> MixSources.commonsCategories(AerialSettings.mixThemes).flatMap { category ->
            val cursors = cursors()
            val json = text(MixSources.commonsUrl(category, cursors.optString(category).ifEmpty { null }))
            val next = JSONObject(json).optJSONObject("continue")?.optString("gcmcontinue").orEmpty()
            saveCursor(category, next)
            MixSources.parseCommons(json)
        }
        MixSource.Nasa -> {
            val query = MixSources.nasaQueries(AerialSettings.mixThemes).random()
            MixSources.parseNasa(text(MixSources.nasaUrl(query, Random.nextInt(1, 4))))
        }
        MixSource.Unsplash -> {
            val theme = AerialSettings.mixThemes.toList().randomOrNull()
            val json = try {
                text(MixSources.unsplashUrl(theme), mapOf("Authorization" to "Client-ID ${AerialSettings.unsplashKey}"))
            } catch (e: HttpFailure) {
                // Refused: the key was revoked or never right. Unsplash stays off until a valid one is saved.
                if (e.code == 401) Handler(Looper.getMainLooper()).post { AerialSettings.chooseUnsplashKey("") }
                throw e
            }
            MixSources.parseUnsplash(json)
        }
        MixSource.Mine -> emptyList()
    }

    /** The key as Unsplash sees it, for the phone page to say whether it was taken. Blocks: not for the main thread. */
    fun checkUnsplash(key: String): KeyCheck = MixSources.unsplashKeyVerdict(
        try {
            fetch(MixSources.UNSPLASH_CHECK_URL, mapOf("Authorization" to "Client-ID $key"))
            200
        } catch (e: HttpFailure) {
            e.code
        } catch (e: Exception) {
            null
        },
    )

    private class HttpFailure(val code: Int) : java.io.IOException("HTTP $code")

    private fun text(url: String, headers: Map<String, String> = emptyMap()) = fetch(url, headers).decodeToString()

    private fun cursors(): JSONObject = runCatching { JSONObject(File(dir, "cursors.json").readText()) }.getOrDefault(JSONObject())

    /** Where the next page of a Commons category starts; an empty cursor sends the list back to its beginning. */
    private fun saveCursor(category: String, cursor: String) {
        File(dir, "cursors.json").writeText(cursors().put(category, cursor).toString())
    }

    // ---- files ----

    private fun saveHistory() {
        val text = MixPick.writeHistory(shown)
        scope.launch(Dispatchers.IO) { File(dir, "history.json").writeText(text) }
    }

    private fun writeHidden() {
        val text = MixPick.writeHistory(hidden)
        scope.launch(Dispatchers.IO) { File(dir, "hidden.json").writeText(text) }
    }

    private fun saveState() {
        val state = JSONObject().put("at", changedAt)
        currentPicture?.let { state.put("current", JSONObject(MixPick.writePicture(it))) }
        next?.let { state.put("next", JSONObject(MixPick.writePicture(it))) }
        pinned?.let { state.put("pinned", JSONObject(MixPick.writePicture(it))) }
        val text = state.toString()
        scope.launch(Dispatchers.IO) { File(dir, "state.json").writeText(text) }
    }
}

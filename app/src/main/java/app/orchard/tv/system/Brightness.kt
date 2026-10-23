package app.orchard.tv.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Panel brightness. TVs keep it in different places:
 *
 * - Xiaomi with "picture_backlight": Settings.Global picture_backlight, 0-100.
 * - MiTV China firmware without "picture_backlight" (MiTV-ASTP0, Mi TV 4A, recent Redmi): the
 *   panel is driven by the mitv middleware and the firmware keeps the level in
 *   Settings.System "backLightForNormal" (0-100). See [MitvPanel]: the setting alone does not move
 *   the backlight, the value has to be pushed to TvService as well.
 * - TCL and Sony: their own TV service drives the panel; Control Center opens their picture panel.
 * - Others: the standard Settings.System screen brightness, 0-255. Writing it needs the "modify
 *   system settings" app-op.
 */
object Brightness {
    private const val XIAOMI_BACKLIGHT = "picture_backlight"

    private fun usesXiaomiBacklight(context: Context) =
        Settings.Global.getString(context.contentResolver, XIAOMI_BACKLIGHT) != null

    private fun isTcl(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.tcl.settings", 0) }.isSuccess

    private fun isSony(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.sony.dtv.configsettings", 0) }.isSuccess

    /**
     * Level Aerial last pushed to the MiTV panel. The firmware refuses third-party writes to its
     * `backLightForNormal` key and its getter reports animated values, so Aerial keeps the level it
     * set (seeded from the TV's own menu value once per process).
     */
    private var mitvLevel: Int? = null

    private const val PREFS = "aerial"
    private const val KEY_MITV_LEVEL = "mitv_backlight"

    private fun storedLevel(context: Context): Int? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MITV_LEVEL, -1)
        return if (value in 1..100) value else null
    }

    /** Current level as 0..1, or null when Aerial cannot change it on this TV. */
    fun level(context: Context): Float? {
        val r = context.contentResolver
        return when {
            usesXiaomiBacklight(context) -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return null
                Settings.Global.getInt(r, XIAOMI_BACKLIGHT, 50) / 100f
            }
            // The MiTV middleware is driven over its own binder and Aerial keeps the level in its own
            // preferences, so this path does not need WRITE_SETTINGS.
            MitvPanel.isAvailable(context) ->
                (mitvLevel ?: MitvPanel.readBacklight(context) ?: storedLevel(context)
                    ?: MitvPanel.level(context) ?: 50) / 100f
            // TCL and Sony drive the backlight through their own TV services and ignore the system
            // setting; Control Center opens their picture panels there.
            // Before Android 6.0 WRITE_SETTINGS is granted at install.
            isTcl(context) || isSony(context) || Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context) -> null
            else -> Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        }
    }

    /**
     * Steps brightness one notch (5%) up or down and returns the new level. Never to 0: Xiaomi turns
     * the backlight off at 0 and the screen goes black, with Control Center still open on it
     * (HyperOS, issue #174); its own slider stops at 1. So the lowest notch is 1, and the steps stay
     * on multiples of 5 above it (1, 5, 10 ...).
     */
    fun adjust(context: Context, direction: Int): Float? {
        val current = level(context) ?: return null
        val percent = Math.round(current * 100)
        val next = (if (direction > 0) (percent / 5 + 1) * 5 else ((percent + 4) / 5 - 1) * 5).coerceIn(1, 100)
        val r = context.contentResolver
        runCatching {
            when {
                usesXiaomiBacklight(context) -> Settings.Global.putInt(r, XIAOMI_BACKLIGHT, next)
                MitvPanel.isAvailable(context) -> {
                    MitvPanel.setBacklight(context, next)
                    mitvLevel = next
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putInt(KEY_MITV_LEVEL, next).apply()
                }
                else -> {
                    Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, (next * 255 / 100).coerceAtLeast(1))
                }
            }
        }
        // Return what was asked for rather than reading it back: the MiTV middleware animates the
        // panel and its getter reports the transient level, which made the slider jump around.
        return next / 100f
    }
}

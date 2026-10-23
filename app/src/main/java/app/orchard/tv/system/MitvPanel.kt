package app.orchard.tv.system

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log

/**
 * MiTV panel backlight (China firmware on Android 9, e.g. MiTV-ASTP0 `mulan`).
 *
 * There is no `picture_backlight` global on these TVs. The firmware's own 图像设置 → 背光强度
 * keeps the level in `Settings.System` `backLightForNormal` (0–100) and pushes it to the panel
 * through the mitv middleware, so writing the setting alone only changes what the menu shows.
 * Control Center talks to the same service the TV settings app uses:
 *
 *  1. `ServiceManager.getService("TvService")` returns the `mitv.internal.ITvService` binder.
 *  2. `getAccessoryService("TvSettingService")` (transaction 80) returns the
 *     `mitv.impl.tv.ITvSettingManager` binder.
 *  3. `setBacklightValue(int)` (transaction 42) moves the panel, 1–100.
 *
 * Verified on MiTV-ASTP0: the TV's 背光强度 page and the actual panel follow these calls. Every
 * step is best-effort; if the binder is unavailable the setting write still happens, so the
 * slider shows a sensible value and the firmware keeps it for its own menu.
 */
object MitvPanel {
    private const val TAG = "AerialMitvPanel"

    /** `Settings.System` key the TV's own backlight UI reads and writes. */
    const val SETTING = "backLightForNormal"

    private const val ITV_SERVICE = "mitv.internal.ITvService"
    private const val ITV_SETTING_MANAGER = "mitv.impl.tv.ITvSettingManager"
    private const val ACCESSORY_NAME = "TvSettingService"
    private const val TX_GET_ACCESSORY_SERVICE = 80
    private const val TX_GET_BACKLIGHT_VALUE = 43
    private const val TX_SET_BACKLIGHT_VALUE = 42

    /** True when this firmware keeps its panel level in [SETTING] instead of the AOSP brightness. */
    fun isAvailable(context: Context): Boolean =
        Settings.System.getString(context.contentResolver, SETTING) != null

    /**
     * Current panel level 1-100 as the TV's own picture menu last left it.
     *
     * Read from [SETTING] rather than from the middleware: its `getBacklightStatus` reports a
     * transient/animated value (seen bouncing 85 -> 55 -> 10 while the slider was being stepped), and
     * the firmware refuses third-party writes to [SETTING], so there is no way to keep the two in
     * sync. Aerial keeps its own cached level while the process lives; this is only the seed.
     */
    fun level(context: Context): Int? =
        runCatching { Settings.System.getInt(context.contentResolver, SETTING, 50) }.getOrNull()

    /**
     * Sets the panel level (1–100) and records it where the TV's own picture menu reads it.
     *
     * The middleware's `setBacklightValue` is a **relative step**, not an absolute level: the
     * firmware's impl does `SetBacklight(getBacklightStatus() + value)`. Sending an absolute value
     * therefore slams the panel around (25 -> 119, 30 -> 196 …). We send `target - current` and
     * then persist the absolute level in [SETTING] for the TV's own menu and our slider.
     */
    fun setBacklight(context: Context, percent: Int) {
        val value = percent.coerceIn(1, 100)
        val current = readBacklight(context) ?: level(context) ?: value
        val delta = value - current
        if (delta != 0) {
            val settingService = settingService()
            if (settingService == null) {
                Log.w(TAG, "TvService binder missing")
            } else {
                runCatching {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(ITV_SETTING_MANAGER)
                        data.writeInt(delta)
                        settingService.transact(TX_SET_BACKLIGHT_VALUE, data, reply, 0)
                        reply.readException()
                    } finally {
                        reply.recycle()
                        data.recycle()
                    }
                    Log.d(TAG, "setBacklight $current -> $value (delta $delta)")
                }.onFailure { Log.w(TAG, "setBacklightValue failed: $it") }
            }
        }
        runCatching { Settings.System.putInt(context.contentResolver, SETTING, value) }
            .onFailure { Log.w(TAG, "setting write failed: $it") }
    }

    /** The panel level the firmware currently reports (its own `getBacklightStatus`), 1-100. */
    fun readBacklight(context: Context): Int? {
        val settingService = settingService() ?: return null
        return runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITV_SETTING_MANAGER)
                settingService.transact(TX_GET_BACKLIGHT_VALUE, data, reply, 0)
                reply.readException()
                reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.onFailure { Log.w(TAG, "getBacklightStatus failed: $it") }.getOrNull()
    }

    /** The `ITvSettingManager` binder behind `TvService`, or null when the binder is missing. */
    private fun settingService(): IBinder? {
        val tvService = runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            getService.invoke(null, "TvService") as? IBinder
        }.onFailure { Log.w(TAG, "TvService lookup failed: $it") }.getOrNull()
        if (tvService == null) {
            Log.w(TAG, "TvService binder missing")
            return null
        }
        return runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ITV_SERVICE)
                data.writeString(ACCESSORY_NAME)
                tvService.transact(TX_GET_ACCESSORY_SERVICE, data, reply, 0)
                reply.readException()
                reply.readStrongBinder()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }.onFailure { Log.w(TAG, "accessory lookup failed: $it") }.getOrNull()
    }
}

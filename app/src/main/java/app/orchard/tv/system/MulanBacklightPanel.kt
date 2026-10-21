package app.orchard.tv.system

/** 仅用于已验证的 ASTP0 Android 9 固件，不推广到其他小米电视或系统版本。 */
internal object MulanBacklightPanel {
    const val ACTION = "com.xiaomi.mitv.settings.BACKLIGHT_POPUP"
    const val PACKAGE = "com.xiaomi.mitv.settings"
    const val RECEIVER = "com.xiaomi.mitv.settings.SettingsBroadcastReceiver"

    fun supports(model: String, device: String, sdk: Int, fingerprint: String): Boolean =
        model == "MiTV-ASTP0" && device == "mulan" && sdk == 28 &&
            fingerprint == "Xiaomi/mulan/mulan:9/PPR1.180610.011/3982:user/release-keys"
}

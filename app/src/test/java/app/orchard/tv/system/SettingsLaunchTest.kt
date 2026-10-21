package app.orchard.tv.system

import android.app.Activity
import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class SettingsLaunchTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun register(
        intent: Intent,
        pkg: String = "com.android.tv.settings",
        name: String = "com.android.tv.settings.MainSettings",
        system: Boolean = true,
    ) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                this.name = name
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
            }
        }
        shadowOf(activity.packageManager).addResolveInfoForIntent(intent, info)
    }

    private fun settings() = Intent(Settings.ACTION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra("preserved", "value")

    @Test fun activityKeepsPanelInItsTaskWithoutMutatingOriginal() {
        val original = settings()
        register(original)
        val result = settingsLaunchIntent(activity, original)
        assertNotSame(original, result)
        assertEquals(0, result.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, result.flags)
        assertEquals("value", result.getStringExtra("preserved"))
        assertTrue(original.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun wrappedActivityKeepsPanelInItsTask() {
        val intent = settings()
        register(intent)
        assertEquals(0, settingsLaunchIntent(ContextWrapper(activity), intent).flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun explicitAppTileKeepsComponentCategoryAndExtras() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .putExtra("tile", true)
        register(intent)
        val result = settingsLaunchIntent(activity, intent)
        assertEquals(0, result.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED, result.flags)
        assertEquals(intent.component, result.component)
        assertTrue(result.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
        assertTrue(result.getBooleanExtra("tile", false))
    }

    @Test fun applicationContextRetainsRequiredNewTask() {
        val intent = settings()
        register(intent)
        assertSame(intent, settingsLaunchIntent(RuntimeEnvironment.getApplication(), intent))
    }

    @Test fun otherSettingsAndOrdinaryAppsAreUnchanged() {
        for ((pkg, name) in listOf(
            "com.android.settings" to "com.android.settings.Settings",
            "com.tcl.settings" to "com.tcl.settings.ui.MainActivity",
            "com.xiaomi.mitv.settings" to "com.xiaomi.mitv.settings.entry.MainActivity",
            "com.android.tv.settings" to "com.android.tv.settings.connectivity.NetworkActivity",
            "example.player" to "example.player.MainActivity",
        )) {
            val intent = Intent("test.$pkg.$name").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            register(intent, pkg, name)
            assertSame(intent, settingsLaunchIntent(activity, intent))
        }
    }

    @Test fun nonSystemPackageWithSameNameIsUnchanged() {
        val intent = settings()
        register(intent, system = false)
        assertSame(intent, settingsLaunchIntent(activity, intent))
    }

    @Test fun unresolvedIntentIsUnchanged() {
        val intent = Intent("example.missing").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertSame(intent, settingsLaunchIntent(activity, intent))
    }

    @Test fun controlCenterAndPictureFallbackUseSamePolicy() {
        val intent = settings()
        register(intent)
        assertEquals(0, SystemProbe.settingsIntent(activity).flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertTrue(SystemProbe.settingsIntent(activity.applicationContext).flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(SystemProbe.openPictureSettings(activity))
        assertEquals(0, shadowOf(activity).nextStartedActivity.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

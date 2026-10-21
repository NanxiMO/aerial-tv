package app.orchard.tv.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MulanBacklightPanelTest {
    private val firmware = "Xiaomi/mulan/mulan:9/PPR1.180610.011/3982:user/release-keys"

    @Test fun verifiedFirmwareUsesBacklightPanel() {
        assertTrue(MulanBacklightPanel.supports("MiTV-ASTP0", "mulan", 28, firmware))
    }

    @Test fun otherModelsKeepTheirExistingRoute() {
        for (model in listOf("MiTV-MFTP0", "MiTV-MFTR0", "MiTV_ASTP0", "TCL", "")) {
            assertFalse(model, MulanBacklightPanel.supports(model, "mulan", 28, firmware))
        }
    }

    @Test fun otherDevicesKeepTheirExistingRoute() {
        assertFalse(MulanBacklightPanel.supports("MiTV-ASTP0", "alita", 28, firmware))
    }

    @Test fun otherAndroidVersionsKeepTheirExistingRoute() {
        for (sdk in listOf(21, 27, 29, 34, 36)) {
            assertFalse("SDK $sdk", MulanBacklightPanel.supports("MiTV-ASTP0", "mulan", sdk, firmware))
        }
    }

    @Test fun otherFirmwareBuildsKeepTheirExistingRoute() {
        for (fingerprint in listOf("", firmware.replace("3982", "3932"), firmware.replace("release-keys", "test-keys"))) {
            assertFalse(fingerprint, MulanBacklightPanel.supports("MiTV-ASTP0", "mulan", 28, fingerprint))
        }
    }
}

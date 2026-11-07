package dev.n2k02.adb.core

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Generic UI and pairing logic remain in the library, not host apps. */
class AdbUnifiedUiContractTest {
    private fun source(name: String): String {
        val dir = listOf(File("src/main/java/dev/n2k02/adb/core"),
            File("adb-core/src/main/java/dev/n2k02/adb/core")).first { it.isDirectory }
        return dir.resolve(name).readText()
    }

    @Test fun manualPairingInputsAreRemovedFromCanonicalPages() {
        val page = source("AdbPages.kt")
        assertTrue(page.contains("fun AdbHomeSetupPage("))
        assertTrue(page.contains("AdbConnectionSettingsPage("))
        assertTrue(page.contains("AdbDiagnosticsPage("))
        assertTrue(page.contains("Pair device with pairing code"))
        assertFalse(page.contains("Manual pairing (fallback)"))
        assertFalse(page.contains("OutlinedTextField("))
        assertFalse(page.contains("adb.pair("))
    }

    @Test fun accessibilityCanBootstrapHostAndHoldsTemporaryDiscoveryLease() {
        val service = source("AdbPairingAccessibilityService.kt")
        assertTrue(service.contains("open class SharedAdbPairingAccessibilityService"))
        assertTrue(service.contains("protected open fun acquireCore()"))
        assertTrue(service.contains("core.beginAutomaticPairingDiscovery()"))
        assertTrue(service.contains("AdbPairingCodePolicy.isPairingDialog(labels)"))
        assertTrue(service.contains("notifyPairingPortDiscovered()"))
        assertFalse(service.contains("performAction("))
        assertFalse(service.contains("putString("))
    }

    @Test fun permissionAndWifiDiscoveryLogicAreShared() {
        val permission = source("AdbNetworkAccess.kt")
        assertTrue(permission.contains("Build.VERSION.SDK_INT >= 37"))
        assertTrue(permission.contains("context.applicationInfo.targetSdkVersion >= 37"))
        assertTrue(permission.contains("ACCESS_LOCAL_NETWORK"))
        val discovery = source("AdbDiscovery.kt")
        assertTrue(discovery.contains("AdbNetworkAccess.blocker(app)"))
        assertTrue(discovery.contains("createMulticastLock("))
        assertTrue(discovery.contains("if (lock.isHeld) lock.release()"))
    }
}

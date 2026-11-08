package dev.n2k02.adb.core

import org.junit.Assert.*
import org.junit.Test

class AdbPairingCodePolicyTest {
    @Test fun pairingPortRequiresRealDialogAndValidPort() {
        assertNull(AdbPairingCodePolicy.pairingPort(listOf("Wi-Fi settings", "192.168.1.5:37123")))
        assertEquals(37123, AdbPairingCodePolicy.pairingPort(
            listOf("Pair device with pairing code", "IP address & port", "192.168.1.5:37123")))
        assertEquals(37001, AdbPairingCodePolicy.pairingPort(
            listOf("Pairing code", "Pairing port: 37001")))
        assertNull(AdbPairingCodePolicy.pairingPort(
            listOf("Pairing code", "Pairing port: 99999")))
    }

    @Test fun settingsOnly() {
        assertTrue(AdbPairingCodePolicy.isAndroidSettings("com.android.settings"))
        assertFalse(AdbPairingCodePolicy.isAndroidSettings("com.example.malicious"))
        assertFalse(AdbPairingCodePolicy.isAndroidSettings(null))
    }
    @Test fun codesRequireVisiblePairingDialogHint() {
        assertNull(AdbPairingCodePolicy.extract(listOf("123456", "Wi-Fi settings")))
        assertEquals("123456", AdbPairingCodePolicy.extract(listOf(
            "Pair device with pairing code", "123456")))
        assertEquals("234567", AdbPairingCodePolicy.extract(listOf("Pairing code: 234567")))
        assertNull(AdbPairingCodePolicy.extract(listOf("Pairing code", "01234x")))
        assertNull(AdbPairingCodePolicy.extract(listOf("Pairing code", "1234567")))
    }
    @Test fun onlyActualPairingDialogCanInitiateAutomaticDiscovery() {
        assertFalse(AdbPairingCodePolicy.isPairingDialog(listOf("Wi-Fi settings", "123456")))
        assertTrue(AdbPairingCodePolicy.isPairingDialog(listOf("Pair device with pairing code", "123456")))
        assertNull(AdbPairingCodePolicy.extract(listOf("Not pairing", "123456")))
    }

}

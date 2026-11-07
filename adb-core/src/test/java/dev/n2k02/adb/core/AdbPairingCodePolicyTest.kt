package dev.n2k02.adb.core

import org.junit.Assert.*
import org.junit.Test

class AdbPairingCodePolicyTest {
    @Test fun settingsOnly() {
        assertTrue(AdbPairingCodePolicy.isAndroidSettings("com.android.settings"))
        assertFalse(AdbPairingCodePolicy.isAndroidSettings("com.example.malicious"))
        assertFalse(AdbPairingCodePolicy.isAndroidSettings(null))
    }
    @Test fun automaticPortExtractionUsesOnlyAndroidPairingDialog() {
        assertNull(AdbPairingCodePolicy.pairingPort(listOf("Wi-Fi settings", "192.168.1.9:43567")))
        assertEquals(43567, AdbPairingCodePolicy.pairingPort(listOf(
            "Pair device with pairing code", "Pairing code", "123456", "IP address & port",
            "192.168.1.9:43567")))
        assertEquals(38760, AdbPairingCodePolicy.pairingPort(listOf(
            "Pairing code: 123456", "Pairing port: 38760")))
        assertNull(AdbPairingCodePolicy.pairingPort(listOf(
            "Pairing code: 123456", "IP address & port: 192.168.1.8:99999")))
        assertNull(AdbPairingCodePolicy.pairingPort(listOf(
            "Pairing code: 123456", "192.168.1.5")))
    }
    @Test fun codesRequireVisiblePairingDialogHint() {
        assertNull(AdbPairingCodePolicy.extract(listOf("123456", "Wi-Fi settings")))
        assertEquals("123456", AdbPairingCodePolicy.extract(listOf(
            "Pair device with pairing code", "123456")))
        assertEquals("234567", AdbPairingCodePolicy.extract(listOf("Pairing code: 234567")))
        assertNull(AdbPairingCodePolicy.extract(listOf("Pairing code", "01234x")))
        assertNull(AdbPairingCodePolicy.extract(listOf("Pairing code", "1234567")))
    }
}

package dev.n2k02.adb.core

import org.junit.Assert.*
import org.junit.Test

class AdbPairingCodePolicyTest {
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
}

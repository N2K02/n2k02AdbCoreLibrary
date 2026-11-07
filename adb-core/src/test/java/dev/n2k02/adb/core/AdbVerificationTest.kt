package dev.n2k02.adb.core

import org.junit.Assert.*
import org.junit.Test

class AdbVerificationTest {
    @Test fun uidMustBeExactlyShell() {
        assertEquals(2000, AdbVerification.verifiedUid("2000\n"))
        listOf("", "200", "20000", "1000", "shell 2000", "2000 extra", "2000\n1000")
            .forEach { assertNull("Invalid UID accepted: $it", AdbVerification.verifiedUid(it)) }
    }
    @Test fun pairingRejectsMalformedOrRemote() {
        assertTrue(AdbVerification.validPairing(37000, "123456"))
        assertFalse(AdbVerification.validPairing(37000, "12345"))
        assertFalse(AdbVerification.validPairing(37000, "1234567"))
        assertFalse(AdbVerification.validPairing(37000, "12345x"))
        assertFalse(AdbVerification.validPairing(-1, "123456"))
        assertFalse(AdbVerification.validPairing(65536, "123456"))
    }
    @Test fun tcpAndRecoveryRespectManualStop() {
        assertFalse(AdbVerification.mayConnectTcp(true, true))
        assertFalse(AdbVerification.mayConnectTcp(false, false))
        assertTrue(AdbVerification.mayConnectTcp(true, false))
        assertFalse(AdbVerification.shouldRecover(true, true))
        assertFalse(AdbVerification.shouldRecover(false, false))
        assertTrue(AdbVerification.shouldRecover(true, false))
    }
}

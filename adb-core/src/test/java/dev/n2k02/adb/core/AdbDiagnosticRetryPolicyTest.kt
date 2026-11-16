package dev.n2k02.adb.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AdbDiagnosticRetryPolicyTest {
    @Test fun allowsOnlyReadOnlySnapshots() {
        assertTrue(AdbDiagnosticRetryPolicy.allowsReplay("dumpsys jobscheduler | head -c 196608"))
        assertTrue(AdbDiagnosticRetryPolicy.allowsReplay("logcat -d -t 100 -v threadtime"))
        assertFalse(AdbDiagnosticRetryPolicy.allowsReplay("dumpsys SurfaceFlinger --timestats -clear -enable"))
        assertFalse(AdbDiagnosticRetryPolicy.allowsReplay("dumpsys SurfaceFlinger --timestats -disable"))
        assertFalse(AdbDiagnosticRetryPolicy.allowsReplay("pm grant pkg android.permission.DUMP"))
        assertFalse(AdbDiagnosticRetryPolicy.allowsReplay("dumpsys battery --reset"))
        assertFalse(AdbDiagnosticRetryPolicy.allowsReplay("id -u"))
        assertFalse(AdbDiagnosticRetryPolicy.allowsReplay(
            "dumpsys battery; settings put global adb_enabled 0"))
        assertFalse(AdbDiagnosticRetryPolicy.allowsReplay(
            "dumpsys jobscheduler | sh"))
    }

    @Test fun onlyDisconnectedTransportErrorsAreRecoverable() {
        assertTrue(AdbDiagnosticRetryPolicy.disconnected(IOException("Socket closed")))
        assertFalse(AdbDiagnosticRetryPolicy.disconnected(java.net.SocketTimeoutException("deadline")))
        assertTrue(AdbDiagnosticRetryPolicy.disconnected(IllegalStateException("ADB is not connected")))
        assertFalse(AdbDiagnosticRetryPolicy.disconnected(SecurityException("UID not verified")))
        assertFalse(AdbDiagnosticRetryPolicy.disconnected(IllegalStateException("deadline exceeded")))
    }
}

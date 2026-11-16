package dev.n2k02.adb.core

import java.io.IOException

/**
 * Safe replay policy for future consumers of the shared ADB core.
 * Only application-owned, fixed, read-only diagnostic command templates may
 * be retried once after a disconnected stream. This policy NEVER executes
 * or reconnects itself and does not authorize arbitrary shell input.
 */
object AdbDiagnosticRetryPolicy {
    private val safePrefixes = listOf(
        "dumpsys battery", "dumpsys batterystats", "dumpsys power",
        "dumpsys thermalservice", "dumpsys jobscheduler",
        "dumpsys audio", "dumpsys activity", "dumpsys gfxinfo",
        "dumpsys SurfaceFlinger --list", "dumpsys SurfaceFlinger --timestats -dump",
        "dumpsys -l", "top ", "cat /proc/", "logcat -d",
        "pm list packages", "pidof ", "head -c "
    )
    private val forbidden = listOf(
        "-clear", "-enable", "-disable", "--reset",
        "settings put", "pm grant", "pm revoke", "tcpip:", "usb:"
    )
    fun allowsReplay(command: String): Boolean =
        safePrefixes.any(command::startsWith) &&
            forbidden.none { command.contains(it, ignoreCase = true) }

    fun disconnected(failure: Exception): Boolean =
        failure is IOException ||
            (failure is IllegalStateException && listOf("ADB is not connected",
                "ADB transport disconnected", "stream closed").any {
                    failure.message?.contains(it, ignoreCase = true) == true
                })
}

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
        "pm list packages", "pidof ", "head -c ",
        "dumpsys meminfo", "dumpsys package ", "ps -A -o PID,UID,NAME",
        "cmd activity get-uid-state "
    )
    private val forbidden = listOf(
        "-clear", "-enable", "-disable", "--reset",
        "settings put", "pm grant", "pm revoke", "tcpip:", "usb:"
    )
    private const val MEMORY_SNAPSHOT =
        "cat /proc/meminfo; echo __MCP_PSI__; cat /proc/pressure/memory 2>&1"

    private fun simpleRead(command: String): Boolean {
        if (command.any { it == ';' || it == '\n' || it == '\r' ||
                    it == '`' || it == '>' || it == '<' }) return false
        if (listOf("$(", "&&", "||").any(command::contains)) return false
        return command.split("|").drop(1).all { pipe ->
            listOf("head ", "grep ", "tail ", "cut ").any {
                pipe.trimStart().startsWith(it)
            }
        }
    }

    fun allowsReplay(command: String): Boolean =
        command == MEMORY_SNAPSHOT || (
            safePrefixes.any(command::startsWith) &&
                forbidden.none { command.contains(it, ignoreCase = true) } &&
                simpleRead(command)
        )

    fun disconnected(failure: Exception): Boolean =
        (failure is IOException && failure !is java.net.SocketTimeoutException) ||
            (failure is IllegalStateException && listOf("ADB is not connected",
                "ADB transport disconnected", "stream closed").any {
                    failure.message?.contains(it, ignoreCase = true) == true
                })
}

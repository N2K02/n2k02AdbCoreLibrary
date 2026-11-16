package dev.n2k02.adb.core

import android.os.Binder
import android.os.Parcel
import android.os.Process
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku user-service endpoint. Instantiated by the Shizuku server as shell UID 2000,
 * never by an exported Android Service. No grant or bypass API is exposed.
 */
class ShizukuShellService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != TRANSACTION_EXEC) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        check(Process.myUid() == Process.SHELL_UID) { "Shizuku service must run as shell UID 2000" }
        val command = requireNotNull(data.readString())
        require(command.isNotEmpty() && command.length <= MAX_COMMAND_CHARS)
        val timeout = data.readLong().coerceIn(1_000L, 30_000L)
        val output = execute(command, timeout)
        reply?.writeNoException()
        reply?.writeString(output)
        return true
    }

    private fun execute(command: String, timeoutMs: Long): String {
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true).start()
        val out = ByteArrayOutputStream()
        // Consume output concurrently with waitFor to avoid a full pipe deadlock.
        val reader = Thread {
            runCatching {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val size = stream.read(buffer)
                        if (size < 0) break
                        synchronized(out) {
                            if (out.size() < MAX_OUTPUT_BYTES)
                                out.write(buffer, 0, minOf(size, MAX_OUTPUT_BYTES - out.size()))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            check(process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { "Shizuku command timed out" }
            reader.join(1_000L)
            return synchronized(out) { out.toString(Charsets.UTF_8.name()) }
        } finally {
            process.destroyForcibly()
            reader.interrupt()
        }
    }

    companion object {
        const val DESCRIPTOR = "dev.n2k02.adb.core.ShizukuShellService"
        const val TRANSACTION_EXEC = FIRST_CALL_TRANSACTION + 1
        const val MAX_COMMAND_CHARS = 8192
        const val MAX_OUTPUT_BYTES = 256 * 1024
    }
}

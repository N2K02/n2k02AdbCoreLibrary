package dev.n2k02.adb.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/** Shizuku is a separate, user-authorized Binder transport. No ADB socket is opened. */
interface ShizukuCapableWire {
    suspend fun connectShizuku(): Boolean
    val requiresShizuku: Boolean
}

/**
 * Prefers the already-running Shizuku server owned by LocalADB or another
 * compatible manager. It never requests permission silently or starts a server.
 * Direct ADB is an explicit opt-in fallback only.
 */
class ShizukuPreferredWire(
    context: Context,
    private val fallback: AdbWire,
    private val allowDirectAdbFallback: Boolean = false,
) : AdbWire, ShizukuCapableWire {
    private val app = context.applicationContext
    private val args = Shizuku.UserServiceArgs(
        ComponentName(app.packageName, ShizukuShellService::class.java.name)
    ).daemon(false).processNameSuffix("adbcore")
    @Volatile private var service: IBinder? = null
    @Volatile private var bound = false
    override val requiresShizuku: Boolean get() = !allowDirectAdbFallback
    override val connected: Boolean get() = service?.isBinderAlive == true ||
        (allowDirectAdbFallback && fallback.connected)
    override val mode: AdbTransportMode get() =
        if (service?.isBinderAlive == true) AdbTransportMode.SHIZUKU
        else if (allowDirectAdbFallback) fallback.mode else AdbTransportMode.NONE

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = binder
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    override suspend fun connectShizuku(): Boolean {
        if (!runCatching {
            Shizuku.pingBinder() &&
                Shizuku.getUid() == Process.SHELL_UID &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)) return false
        if (service?.isBinderAlive != true) {
            val result = withTimeoutOrNull(12_000L) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val temporary = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                            service = binder
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onServiceDisconnected(name: ComponentName) { service = null }
                    }
                    // This short-lived connection is retained for service lifetime; unbound on close.
                    try {
                        synchronized(this@ShizukuPreferredWire) {
                            if (!bound) {
                                Shizuku.bindUserService(args, connection)
                                bound = true
                            }
                        }
                        // A second connection callback also handles already bound services.
                        Shizuku.bindUserService(args, temporary)
                        continuation.invokeOnCancellation {
                            runCatching { Shizuku.unbindUserService(args, temporary, false) }
                        }
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } == true
            if (!result) return false
        }
        return runCatching { run("id -u", 3_000).trim() == "2000" }.getOrDefault(false)
    }

    override suspend fun run(command: String, timeoutMs: Long): String {
        val binder = service?.takeIf { it.isBinderAlive }
        if (binder == null) {
            check(allowDirectAdbFallback) { "Shizuku server not connected or permission not granted" }
            return fallback.run(command, timeoutMs)
        }
        require(command.length in 1..ShizukuShellService.MAX_COMMAND_CHARS)
        return withContext(Dispatchers.IO) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(ShizukuShellService.DESCRIPTOR)
                data.writeString(command)
                data.writeLong(timeoutMs)
                check(binder.transact(ShizukuShellService.TRANSACTION_EXEC, data, reply, 0)) {
                    "Shizuku shell Binder transaction failed"
                }
                reply.readException()
                requireNotNull(reply.readString())
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    override suspend fun pair(port: Int, code: String): Boolean =
        if (allowDirectAdbFallback) fallback.pair(port, code) else false
    override suspend fun connectTls(port: Int): Boolean =
        if (allowDirectAdbFallback) fallback.connectTls(port) else false
    override suspend fun connectTcp(): Boolean =
        if (allowDirectAdbFallback) fallback.connectTcp() else false
    override suspend fun switchToTcp(): Boolean =
        if (allowDirectAdbFallback) fallback.switchToTcp() else false
    override suspend fun switchToUsb(): Boolean =
        if (allowDirectAdbFallback) fallback.switchToUsb() else false

    override suspend fun close() {
        service = null
        if (bound) {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
            bound = false
        }
        fallback.close()
    }
}

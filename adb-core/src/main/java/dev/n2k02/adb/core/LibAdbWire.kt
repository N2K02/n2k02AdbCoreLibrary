package dev.n2k02.adb.core

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Concrete libadb implementation, shared by all apps rather than copied per repository. */
class LibAdbWire(
    private val context: Context,
    private val identitySource: AdbIdentitySource,
    private val deviceName: String,
) : AdbWire {
    private companion object {
        const val HOST = "127.0.0.1"
        const val TCP_PORT = 5555
        const val SHELL_SERVICE = "exec:sh"
    }
    private val mutex = Mutex()
    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var manager: AbsAdbConnectionManager? = null
    @Volatile private var shell: ShellSession? = null
    @Volatile private var currentPort: Int? = null
    @Volatile private var lastConnected: Boolean = false
    override val connected: Boolean get() = lastConnected
    override val mode: AdbTransportMode get() = if (!lastConnected) AdbTransportMode.NONE
        else if (currentPort == TCP_PORT) AdbTransportMode.LOCAL_TCP_5555
        else AdbTransportMode.WIRELESS_TLS

    private fun newManager(): AbsAdbConnectionManager {
        val identity = identitySource.load(context.applicationContext)
        // libadb can destroy its supplied private key on disconnect. Never expose the cached key.
        val keyBytes = requireNotNull(identity.privateKey.encoded) {
            "Persisted ADB private key must be exportable"
        }
        val sessionKey: PrivateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        return object : AbsAdbConnectionManager() {
            init {
                setApi(Build.VERSION.SDK_INT)
                setTimeout(10, TimeUnit.SECONDS)
            }
            override fun getPrivateKey(): PrivateKey = sessionKey
            override fun getCertificate(): Certificate = identity.certificate
            // Explicitly qualify the outer field. This anonymous manager itself
            // overrides getDeviceName(), and an unqualified "deviceName"
            // resolves to its own Java getter after R8 optimization,
            // causing StackOverflowError on signed release cold launch.
            override fun getDeviceName(): String = this@LibAdbWire.deviceName
        }
    }

    private fun retire(expected: AbsAdbConnectionManager?) {
        if (expected == null) return
        val oldShell: ShellSession?
        synchronized(this) {
            if (manager !== expected) return
            oldShell = shell
            shell = null
            manager = null
            currentPort = null
            lastConnected = false
        }
        runCatching { oldShell?.close() }
        // libadb.disconnect can wait on the peer's lock; do not block Quit/cancellation.
        detached.launch { runCatching { expected.disconnect() } }
    }

    private suspend fun open(port: Int, useTls: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(AdbVerification.validPort(port))
            if (!useTls) require(port == TCP_PORT)
            val existing = manager
            if (existing != null && currentPort == port && runCatching { existing.isConnected }.getOrDefault(false)) {
                lastConnected = true
                return@withLock true
            }
            if (existing != null) retire(existing)
            val fresh = newManager()
            synchronized(this@LibAdbWire) { manager = fresh; currentPort = port }
            try {
                val ok = fresh.connect(HOST, port)
                val healthy = ok || fresh.isConnected
                lastConnected = healthy
                if (!healthy) retire(fresh)
                healthy
            } catch (failure: Exception) {
                retire(fresh)
                false
            }
        }
    }

    override suspend fun connectTls(port: Int) = open(port, true)
    override suspend fun connectTcp() = open(TCP_PORT, false)

    override suspend fun pair(port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(AdbVerification.validPairing(port, code))
            val old = manager
            if (old != null) retire(old)
            val fresh = newManager()
            synchronized(this@LibAdbWire) { manager = fresh; currentPort = null }
            try {
                val paired = fresh.pair(HOST, port, code)
                if (!paired) retire(fresh)
                paired
            } catch (failure: Exception) {
                retire(fresh)
                false
            }
        }
    }

    /** A reusable, serialized shell with a per-session sentinel. Never interleave commands. */
    override suspend fun run(command: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        require(timeoutMs in 500L..180_000L)
        mutex.withLock {
            val current = manager ?: error("ADB transport disconnected")
            if (!runCatching { current.isConnected }.getOrDefault(false)) {
                retire(current)
                error("ADB transport disconnected")
            }
            val watchdog = detached.launch {
                delay(timeoutMs)
                retire(current) // Retire only this manager: never kill an already reconnected one.
            }
            // Opening a native stream can block as well: start the independent
            // deadline before opening it, and never run blocking I/O in the caller.
            val work = detached.async {
                val session = shell ?: ShellSession(current.openStream(SHELL_SERVICE)).also { shell = it }
                session.run(command)
            }
            try {
                // A cancelled caller must not wait for a native readLine(). Retire the stream instead.
                kotlinx.coroutines.withTimeout(timeoutMs + 1_000) { work.await() }
            } catch (cancelled: CancellationException) {
                retire(current)
                throw cancelled
            } catch (failure: Exception) {
                retire(current)
                throw failure
            } finally {
                watchdog.cancel()
            }
        }
    }

    override suspend fun switchToTcp(): Boolean = switchMode("tcpip:$TCP_PORT")
    override suspend fun switchToUsb(): Boolean = switchMode("usb:")

    private suspend fun switchMode(service: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = manager ?: return@withLock false
            if (!runCatching { current.isConnected }.getOrDefault(false)) return@withLock false
            try {
                current.openStream(service).let { stream ->
                    delay(if (service == "usb:") 400L else 500L)
                    runCatching { stream.close() }
                }
                retire(current)
                true
            } catch (failure: Exception) {
                retire(current)
                false
            }
        }
    }

    override suspend fun close() {
        val current = manager
        if (current != null) retire(current)
        else {
            shell?.close(); shell = null
            lastConnected = false
        }
    }

    /** libadb leaks one-shot streams; keep a single exec:sh stream and sentinel framing. */
    private class ShellSession(private val stream: AdbStream) {
        @Volatile private var active = false
        @Volatile private var closing = false
        private val marker = "__adbcore_" + UUID.randomUUID().toString().replace("-", "").take(12)
        private val output = stream.openOutputStream()
        private val input = stream.openInputStream().bufferedReader()

        init {
            // The daemon sometimes opens a PTY despite exec:sh; drain echo before first command.
            runCatching {
                output.write("stty -echo 2>/dev/null; PS1=''\n".toByteArray(Charsets.UTF_8))
                output.flush()
                val deadline = System.currentTimeMillis() + 400
                while (System.currentTimeMillis() < deadline) {
                    if (input.ready()) input.read() else Thread.sleep(20)
                }
            }
        }

        fun run(command: String): String {
            check(!closing) { "Shell closing" }
            active = true
            try {
                val start = "$marker-BEGIN"
                val end = "$marker-END"
                val oneLine = command.replace('\n', ';')
                output.write("echo $start; ( $oneLine ) ; echo $end\n".toByteArray(Charsets.UTF_8))
                output.flush()
                while (true) {
                    val line = input.readLine() ?: throw IOException("ADB stream closed before begin marker")
                    if (line.trimEnd() == start) break
                }
                val result = StringBuilder()
                while (true) {
                    val line = input.readLine() ?: throw IOException("ADB stream closed before end marker")
                    if (line.trimEnd() == end) break
                    result.appendLine(line)
                }
                return result.toString().trimEnd()
            } finally {
                active = false
                if (closing) runCatching { input.close() }
            }
        }

        fun close() {
            closing = true
            runCatching { if (!stream.isClosed) stream.close() }
            runCatching { output.close() }
            if (!active) runCatching { input.close() }
        }
    }
}

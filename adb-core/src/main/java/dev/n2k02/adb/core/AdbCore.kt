package dev.n2k02.adb.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket

/** App-specific privileged capabilities are callbacks; transport and recovery remain shared. */
interface AdbHostHooks {
    suspend fun onVerifiedShell() {}
    suspend fun onRestoreAfterRestartChanged(enabled: Boolean) {}
}

class AdbCore(
    private val wire: AdbWire,
    val preferences: AdbPreferenceStore,
    private val discovery: AdbDiscovery,
    private val hooks: AdbHostHooks = object : AdbHostHooks {},
    private val listenerAvailable: () -> Boolean = { localListenerAvailable() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _state = MutableStateFlow(AdbState(lastVerifiedAt = preferences.lastVerifiedAt()))
    val state: StateFlow<AdbState> = _state.asStateFlow()
    private var owners = 0
    private var serviceOwners = 0
    private var stopped = false
    private var watchdog: Job? = null
    private var retryJob: Job? = null

    init {
        scope.launch {
            discovery.events.collect { event ->
                if (stopped || preferences.manuallyStopped()) return@collect
                val previous = _state.value
                _state.value = when (event.service) {
                    AdbService.PAIRING -> previous.copy(pairingPort = event.port)
                    AdbService.CONNECTION -> previous.copy(connectionPort = event.port)
                }
                if (event.service == AdbService.CONNECTION && !previous.connected) {
                    retryJob?.cancel()
                    retryJob = scope.launch { connectTls(event.port) }
                }
            }
        }
    }

    /** Any shared page can safely share one instance; late callbacks are ignored on shutdown. */
    fun enterPage() {
        if (stopped) return
        owners++
        if (owners == 1) scope.launch { retry(auto = true) }
        configureWatchdog()
    }

    fun leavePage() {
        owners = (owners - 1).coerceAtLeast(0)
        if (owners == 0 && serviceOwners == 0) {
            stopDiscovery()
            // App background work is driven by its own service/worker, not a hidden Settings page.
            watchdog?.cancel()
            watchdog = null
        }
    }

    /**
     * Services retain recovery independently from visible pages.
     * Acquire at service start and release at service shutdown, including Quit.
     */
    fun acquireService() {
        if (stopped) return
        serviceOwners++
        if (serviceOwners == 1) scope.launch { retry(auto = true) }
        configureWatchdog()
    }

    fun releaseService() {
        serviceOwners = (serviceOwners - 1).coerceAtLeast(0)
        if (owners == 0 && serviceOwners == 0) {
            stopDiscovery()
            watchdog?.cancel()
            watchdog = null
        }
    }

    /** Called from the host boot receiver, never silently overriding deliberate Stop. */
    suspend fun startAfterBoot(): Boolean {
        if (stopped || preferences.manuallyStopped() ||
            !preferences.enabled(AdbOption.START_ON_BOOT) || !preferences.wasAuthorized()) return false
        return retry(auto = true)
    }

    /** Manual action clears saved Stop; automatic recovery never does. */
    suspend fun retry(auto: Boolean = false): Boolean = lock.withLock {
        if (stopped || (auto && preferences.manuallyStopped())) return@withLock false
        if (!auto) preferences.setManuallyStopped(false)
        if (wire.connected && wire.mode != AdbTransportMode.NONE &&
            verifyCurrent(wire.mode)) return@withLock true
        _state.value = _state.value.copy(phase = AdbPhase.CONNECTING, verifiedUid = null,
            transport = AdbTransportMode.NONE, message = "Checking authorized local ADB…")
        if (AdbVerification.mayConnectTcp(preferences.enabled(AdbOption.TCP_5555),
                preferences.manuallyStopped()) && listenerAvailable() &&
            runCatching { wire.connectTcp() }.getOrDefault(false) &&
            verifyCurrent(AdbTransportMode.LOCAL_TCP_5555)) return@withLock true
        val tlsPort = preferences.lastTlsPort()
        if (tlsPort != null && attachTls(tlsPort)) return@withLock true
        _state.value = _state.value.copy(phase = AdbPhase.NEEDS_SETUP,
            verifiedUid = null, message = "Pair or enable Wireless debugging on this phone")
        startDiscovery()
        false
    }

    suspend fun pair(port: Int, code: String): Boolean = lock.withLock {
        if (stopped || !AdbVerification.validPairing(port, code)) return@withLock false
        preferences.setManuallyStopped(false)
        _state.value = _state.value.copy(phase = AdbPhase.PAIRING, message = "Pairing with this phone…")
        val paired = runCatching { wire.pair(port, code) }.getOrDefault(false)
        _state.value = _state.value.copy(phase = if (paired) AdbPhase.SEARCHING else AdbPhase.NEEDS_SETUP,
            message = if (paired) "Paired. Looking for the Wireless debugging connection port…"
                else "Pairing failed; check the six-digit code")
        if (paired) startDiscovery()
        paired
    }

    suspend fun connectTls(port: Int): Boolean = lock.withLock {
        if (stopped || preferences.manuallyStopped() || !AdbVerification.validPort(port)) return@withLock false
        attachTls(port)
    }

    private suspend fun attachTls(port: Int): Boolean {
        _state.value = _state.value.copy(phase = AdbPhase.CONNECTING,
            message = "Connecting to authorized Wireless debugging…", verifiedUid = null)
        val connected = runCatching { wire.connectTls(port) }.getOrDefault(false)
        if (!connected || !verifyCurrent(AdbTransportMode.WIRELESS_TLS)) return false
        preferences.setLastTlsPort(port)
        if (!preferences.enabled(AdbOption.TCP_5555)) return true
        // Do not claim TLS success if the requested TCP promotion fails.
        _state.value = _state.value.copy(phase = AdbPhase.CONNECTING,
            message = "Switching authorized ADB to TCP/5555…", verifiedUid = null)
        if (!runCatching { wire.switchToTcp() }.getOrDefault(false)) {
            _state.value = _state.value.copy(phase = AdbPhase.NEEDS_SETUP,
                message = "TCP/5555 switch failed", verifiedUid = null)
            return false
        }
        repeat(4) {
            delay(1_100)
            if (runCatching { wire.connectTcp() }.getOrDefault(false) &&
                verifyCurrent(AdbTransportMode.LOCAL_TCP_5555)) return true
        }
        _state.value = _state.value.copy(phase = AdbPhase.NEEDS_SETUP,
            message = "TCP/5555 reconnect failed; enable Wireless debugging to recover",
            verifiedUid = null)
        return false
    }

    /** Only UID 2000 is accepted; cached UI badges are never proof of privilege. */
    private suspend fun verifyCurrent(mode: AdbTransportMode): Boolean {
        val uid = runCatching { AdbVerification.verifiedUid(wire.run("id -u", 3_000)) }.getOrNull()
        if (uid != 2000) {
            runCatching { wire.close() }
            _state.value = _state.value.copy(phase = AdbPhase.NEEDS_SETUP,
                verifiedUid = null, transport = AdbTransportMode.NONE,
                message = "ADB connection failed shell UID 2000 verification")
            return false
        }
        val now = System.currentTimeMillis()
        preferences.setAuthorized()
        preferences.setLastVerifiedAt(now)
        _state.value = _state.value.copy(phase = AdbPhase.VERIFIED, verifiedUid = uid,
            transport = mode, lastVerifiedAt = now, scanning = false,
            message = "ADB ready • shell UID 2000")
        stopDiscovery()
        hooks.onVerifiedShell()
        if (preferences.enabled(AdbOption.RESTORE_AFTER_RESTART)) {
            hooks.onRestoreAfterRestartChanged(true)
        }
        return true
    }

    suspend fun setOption(option: AdbOption, enabled: Boolean) {
        if (stopped) return
        preferences.setEnabled(option, enabled)
        when (option) {
            AdbOption.TCP_5555 -> if (!enabled && state.value.transport == AdbTransportMode.LOCAL_TCP_5555) {
                disableTcp()
            }
            AdbOption.WATCHDOG -> configureWatchdog()
            AdbOption.RESTORE_AFTER_RESTART ->
                if (state.value.connected) hooks.onRestoreAfterRestartChanged(enabled)
            else -> Unit
        }
    }

    /**
     * Privileged shell execution is guarded for EVERY command, not just when a
     * badge was last drawn. Identity proof and the command share the core lock,
     * while the wire serializes the actual shell stream.
     */
    suspend fun execVerified(command: String, timeoutMs: Long = 15_000): String = lock.withLock {
        check(!stopped && !preferences.manuallyStopped()) { "ADB stopped by user" }
        check(wire.connected && wire.mode != AdbTransportMode.NONE) { "ADB is not connected" }
        val uid = runCatching { AdbVerification.verifiedUid(wire.run("id -u", 3_000)) }.getOrNull()
        if (uid != 2000) {
            wire.close()
            _state.value = _state.value.copy(phase = AdbPhase.NEEDS_SETUP,
                verifiedUid = null, transport = AdbTransportMode.NONE,
                message = "Privileged command blocked: shell UID 2000 unavailable")
            throw SecurityException("ADB shell UID 2000 not verified")
        }
        wire.run(command, timeoutMs)
    }

    /** TCP disable is device-wide; it may affect other ADB clients. Always explicit. */
    suspend fun disableTcp(): Boolean = lock.withLock {
        preferences.setEnabled(AdbOption.TCP_5555, false)
        if (stopped || state.value.transport != AdbTransportMode.LOCAL_TCP_5555) return@withLock true
        val result = runCatching { wire.switchToUsb() }.getOrDefault(false)
        runCatching { wire.close() }
        _state.value = _state.value.copy(phase = AdbPhase.IDLE, verifiedUid = null,
            transport = AdbTransportMode.NONE, message = if (result)
                "TCP/5555 disabled. Reconnect through Wireless debugging."
                else "Could not confirm TCP/5555 shutdown; check Android settings.")
        result
    }

    /** Explicit manual disconnect persists a deliberate Stop across reboot. */
    suspend fun stop() {
        preferences.setManuallyStopped(true)
        stopDiscovery()
        watchdog?.cancel(); watchdog = null
        retryJob?.cancel(); retryJob = null
        owners = 0
        serviceOwners = 0
        // Interrupt a blocking native read BEFORE waiting for the connection lock.
        wire.close()
        lock.withLock {
            _state.value = AdbState(phase = AdbPhase.STOPPED, message = "Stopped by user")
        }
    }

    suspend fun quit() {
        stopped = true
        stop()
        scope.cancel()
    }

    private fun configureWatchdog() {
        watchdog?.cancel()
        watchdog = null
        if (stopped || owners + serviceOwners <= 0 || preferences.manuallyStopped() ||
            !preferences.enabled(AdbOption.WATCHDOG)) return
        watchdog = scope.launch {
            while (isActive && !stopped && !preferences.manuallyStopped()) {
                delay(45_000)
                if (!preferences.enabled(AdbOption.WATCHDOG)) break
                val healthy = runCatching {
                    wire.connected && AdbVerification.verifiedUid(wire.run("id -u", 3_000)) == 2000
                }.getOrDefault(false)
                if (!healthy) retry(auto = true)
            }
        }
    }

    private fun startDiscovery() {
        if (stopped || preferences.manuallyStopped() || owners + serviceOwners == 0) return
        _state.value = _state.value.copy(scanning = true)
        discovery.start()
    }

    private fun stopDiscovery() {
        discovery.stop()
        _state.value = _state.value.copy(scanning = false)
    }

    companion object {
        fun localListenerAvailable(): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 5555), 350) }
            true
        }.getOrDefault(false)
    }
}

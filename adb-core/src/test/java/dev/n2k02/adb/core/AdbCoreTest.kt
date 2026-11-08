package dev.n2k02.adb.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdbCoreTest {
    private class MemoryPrefs : AdbPreferenceStore {
        private val settings = AdbOption.entries.associateWith { it != AdbOption.RESTORE_AFTER_RESTART }
            .toMutableMap()
        var stopped = false
        var tlsPort: Int? = null
        var verifiedTime = 0L
        var authorized = false
        override fun enabled(option: AdbOption) = settings.getValue(option)
        override fun setEnabled(option: AdbOption, enabled: Boolean) { settings[option] = enabled }
        override fun manuallyStopped() = stopped
        override fun setManuallyStopped(stopped: Boolean) { this.stopped = stopped }
        override fun lastTlsPort() = tlsPort
        override fun setLastTlsPort(port: Int) { tlsPort = port }
        override fun lastVerifiedAt() = verifiedTime
        override fun setLastVerifiedAt(timeMs: Long) { verifiedTime = timeMs }
        override fun wasAuthorized() = authorized
        override fun setAuthorized() { authorized = true }
    }
    private class Discovery : AdbDiscovery {
        override val events: Flow<AdbDiscoveredPort> = MutableSharedFlow()
        var started = 0
        var stopped = 0
        var reason: String? = null
        override fun unavailableReason(): String? = reason
        override fun start() { started++ }
        override fun stop() { stopped++ }
    }
    private class Wire : AdbWire {
        override var connected = false
        override var mode = AdbTransportMode.NONE
        var uid = "2000"
        var actionCalls = 0
        var tcpAvailable = false
        var tcpSwitchAllowed = true
        var tlsAvailable = true
        var closeCount = 0
        var tcpStarts = 0
        var usbSwitches = 0
        override suspend fun pair(port: Int, code: String) = true
        override suspend fun connectTls(port: Int): Boolean {
            connected = tlsAvailable
            mode = if (connected) AdbTransportMode.WIRELESS_TLS else AdbTransportMode.NONE
            return connected
        }
        override suspend fun connectTcp(): Boolean {
            tcpStarts++
            connected = tcpAvailable
            mode = if (connected) AdbTransportMode.LOCAL_TCP_5555 else AdbTransportMode.NONE
            return connected
        }
        override suspend fun run(command: String, timeoutMs: Long): String {
            if (command == "id -u") return uid
            actionCalls++
            return "command output"
        }
        override suspend fun switchToTcp(): Boolean {
            if (!tcpSwitchAllowed) return false
            connected = false
            mode = AdbTransportMode.NONE
            tcpAvailable = true
            return true
        }
        override suspend fun switchToUsb(): Boolean {
            usbSwitches++
            connected = false
            mode = AdbTransportMode.NONE
            return true
        }
        override suspend fun close() {
            closeCount++; connected = false; mode = AdbTransportMode.NONE
        }
    }
    private fun create(wire: Wire, prefs: MemoryPrefs = MemoryPrefs(), discovery: Discovery = Discovery()) =
        AdbCore(wire, prefs, discovery, listenerAvailable = { false })

    @Test fun pairingConnectsPreviouslyDiscoveredTlsPortWithoutAnotherMdnsEvent() = runBlocking {
        val wire = Wire().apply { tlsAvailable = false }
        val prefs = MemoryPrefs().apply { setEnabled(AdbOption.TCP_5555, false) }
        val events = MutableSharedFlow<AdbDiscoveredPort>(extraBufferCapacity = 4)
        val discovery = object : AdbDiscovery {
            override val events: Flow<AdbDiscoveredPort> = events
            override fun start() = Unit
            override fun stop() = Unit
        }
        val core = AdbCore(wire, prefs, discovery, listenerAvailable = { false })
        core.enterPage()
        kotlinx.coroutines.withTimeout(5_000) {
            while (!core.state.value.scanning) kotlinx.coroutines.delay(10)
        }
        events.emit(AdbDiscoveredPort(AdbService.CONNECTION, 37000))
        kotlinx.coroutines.withTimeout(5_000) {
            while (core.state.value.connectionPort != 37000) kotlinx.coroutines.delay(10)
        }
        kotlinx.coroutines.delay(50)
        wire.tlsAvailable = true
        assertTrue(core.pair(37001, "123456"))
        assertTrue(core.state.value.connected)
        assertEquals(AdbTransportMode.WIRELESS_TLS, core.state.value.transport)
        core.quit()
    }

    @Test fun verifiedHookCanExecutePrivilegedCommandWithoutDeadlock() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val completed = kotlinx.coroutines.CompletableDeferred<String>()
        lateinit var core: AdbCore
        core = AdbCore(wire, prefs, Discovery(), hooks = object : AdbHostHooks {
            override suspend fun onVerifiedShell() {
                completed.complete(core.execVerified("echo from host hook"))
            }
        }, listenerAvailable = { false })
        assertTrue(kotlinx.coroutines.withTimeout(5_000) { core.retry() })
        assertEquals("command output",
            kotlinx.coroutines.withTimeout(5_000) { completed.await() })
        core.quit()
    }

    @Test fun quitCancelsPendingHostCallbacks() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val core = AdbCore(wire, prefs, Discovery(), hooks = object : AdbHostHooks {
            override suspend fun onVerifiedShell() {
                entered.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }, listenerAvailable = { false })
        assertTrue(core.retry())
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
        core.quit()
        kotlinx.coroutines.withTimeout(5_000) { cancelled.await() }
        assertTrue(prefs.manuallyStopped())
    }

    @Test fun serviceOwnerKeepsRecoveryAliveAfterSettingsPageCloses() = runBlocking {
        val wire = Wire().apply { tlsAvailable = false }
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val discovery = Discovery()
        val core = create(wire, prefs, discovery)
        core.acquireService()
        core.enterPage()
        kotlinx.coroutines.withTimeout(5_000) {
            while (discovery.started == 0) kotlinx.coroutines.delay(10)
        }
        core.leavePage()
        // A background service still owns the recovery lifecycle.
        assertEquals(0, discovery.stopped)
        core.releaseService()
        assertTrue(discovery.stopped > 0)
        core.quit()
    }

    @Test fun quitReleasesDiscoveryEvenWithActiveServiceAndPage() = runBlocking {
        val wire = Wire().apply { tlsAvailable = false }
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val discovery = Discovery()
        val core = create(wire, prefs, discovery)
        core.acquireService()
        core.enterPage()
        kotlinx.coroutines.withTimeout(5_000) {
            while (discovery.started == 0) kotlinx.coroutines.delay(10)
        }
        core.quit()
        assertTrue(discovery.stopped > 0)
        assertTrue(wire.closeCount > 0)
        assertTrue(prefs.manuallyStopped())
        assertEquals(AdbPhase.STOPPED, core.state.value.phase)
        assertFalse(core.retry(auto = true))
    }

    @Test fun healthProbeSerializesWithVerifiedCommand() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val wire = object : AdbWire {
            override var connected = false
            override var mode = AdbTransportMode.NONE
            var inFlight = 0
            var maxInFlight = 0
            override suspend fun pair(port: Int, code: String) = true
            override suspend fun connectTls(port: Int): Boolean {
                connected = true
                mode = AdbTransportMode.WIRELESS_TLS
                return true
            }
            override suspend fun connectTcp(): Boolean = false
            override suspend fun run(command: String, timeoutMs: Long): String {
                inFlight++
                maxInFlight = maxOf(maxInFlight, inFlight)
                try {
                    if (command == "echo hold") {
                        entered.complete(Unit)
                        release.await()
                    }
                    return if (command == "id -u") "2000" else "done"
                } finally {
                    inFlight--
                }
            }
            override suspend fun switchToTcp() = false
            override suspend fun switchToUsb() = false
            override suspend fun close() {
                connected = false
                mode = AdbTransportMode.NONE
            }
        }
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val core = AdbCore(wire, prefs, Discovery(), listenerAvailable = { false })
        assertTrue(core.retry())
        val command = async { core.execVerified("echo hold") }
        kotlinx.coroutines.withTimeout(5_000) { entered.await() }
        val probe = async { core.probeVerifiedShell() }
        kotlinx.coroutines.delay(50)
        assertFalse(probe.isCompleted)
        release.complete(Unit)
        assertEquals("done", kotlinx.coroutines.withTimeout(5_000) { command.await() })
        assertTrue(kotlinx.coroutines.withTimeout(5_000) { probe.await() })
        assertEquals(1, wire.maxInFlight)
        core.quit()
    }

    @Test fun existingTlsSessionPromotesToTcpWhenEnabled() = runBlocking {
        val wire = Wire().apply {
            connected = true
            mode = AdbTransportMode.WIRELESS_TLS
        }
        val prefs = MemoryPrefs()
        val hook = kotlinx.coroutines.CompletableDeferred<Unit>()
        val core = AdbCore(wire, prefs, Discovery(), hooks = object : AdbHostHooks {
            override suspend fun onVerifiedShell() { hook.complete(Unit) }
        }, listenerAvailable = { false })
        assertTrue(core.retry())
        assertEquals(AdbTransportMode.LOCAL_TCP_5555, core.state.value.transport)
        assertTrue(wire.tcpStarts > 0)
        kotlinx.coroutines.withTimeout(5_000) { hook.await() }
        core.quit()
    }

    @Test fun existingTlsSessionDoesNotClaimSuccessIfTcpPromotionFails() = runBlocking {
        val wire = Wire().apply {
            connected = true
            mode = AdbTransportMode.WIRELESS_TLS
            tcpSwitchAllowed = false
        }
        val prefs = MemoryPrefs()
        val core = create(wire, prefs)
        assertFalse(core.retry())
        assertFalse(core.state.value.connected)
        assertEquals(AdbPhase.NEEDS_SETUP, core.state.value.phase)
        assertEquals(0, wire.tcpStarts)
        core.quit()
    }

    @Test fun automaticPairingKeepsDiscoveryOwnedUntilReleased() = runBlocking {
        val wire = Wire().apply { tlsAvailable = false }
        val prefs = MemoryPrefs().apply {
            setEnabled(AdbOption.TCP_5555, false)
        }
        val discovery = Discovery()
        val core = create(wire, prefs, discovery)
        core.beginAutomaticPairingDiscovery()
        core.beginAutomaticPairingDiscovery() // Rapid Accessibility events share one lease.
        kotlinx.coroutines.withTimeout(5_000) {
            while (discovery.started == 0) kotlinx.coroutines.delay(10)
        }
        assertEquals(1, discovery.started)
        assertEquals(0, discovery.stopped)
        core.endAutomaticPairingDiscovery()
        kotlinx.coroutines.withTimeout(5_000) {
            while (discovery.stopped == 0) kotlinx.coroutines.delay(10)
        }
        core.quit()
    }

    @Test fun automaticPairingCannotUndoManualStopOrOptOut() = runBlocking {
        val prefs = MemoryPrefs().apply { stopped = true }
        val discovery = Discovery()
        val core = create(Wire(), prefs, discovery)
        core.beginAutomaticPairingDiscovery()
        assertEquals(0, discovery.started)
        prefs.stopped = false
        prefs.setEnabled(AdbOption.AUTO_PAIR, false)
        core.beginAutomaticPairingDiscovery()
        assertEquals(0, discovery.started)
        core.quit()
    }

    @Test fun missingLocalNetworkPermissionIsVisibleInsteadOfSilentScan() = runBlocking {
        val discovery = Discovery().apply {
            reason = "Nearby devices permission required for ADB discovery"
        }
        val prefs = MemoryPrefs().apply {
            setEnabled(AdbOption.TCP_5555, false)
        }
        val core = create(Wire(), prefs, discovery)
        core.enterPage()
        kotlinx.coroutines.withTimeout(5_000) {
            while (!core.state.value.message.contains("Nearby devices")) kotlinx.coroutines.delay(10)
        }
        assertFalse(core.state.value.scanning)
        assertEquals(0, discovery.started)
        core.quit()
    }

    @Test fun invalidUidFailsClosed() = runBlocking {
        val wire = Wire().apply { uid = "1000" }
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val core = create(wire, prefs)
        assertFalse(core.retry())
        assertFalse(core.state.value.connected)
        assertNull(core.state.value.verifiedUid)
        assertTrue(wire.closeCount > 0)
        core.quit()
    }

    @Test fun tlsSuccessPreservesAuthorizationAndOptionalTcpOff() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val core = create(wire, prefs)
        assertTrue(core.retry())
        assertTrue(core.state.value.connected)
        assertEquals(AdbTransportMode.WIRELESS_TLS, core.state.value.transport)
        assertTrue(prefs.wasAuthorized())
        assertEquals(0, wire.tcpStarts)
        core.quit()
    }

    @Test fun tcpShutdownRevokesPreferenceAndConnection() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs().apply { tlsPort = 37000 }
        val core = create(wire, prefs)
        assertTrue(core.retry())
        assertEquals(AdbTransportMode.LOCAL_TCP_5555, core.state.value.transport)
        assertTrue(core.disableTcp())
        assertFalse(prefs.enabled(AdbOption.TCP_5555))
        assertFalse(core.state.value.connected)
        assertEquals(1, wire.usbSwitches)
        core.quit()
    }

    @Test fun transientDisconnectPreservesPairingAndAllowsFutureAutoRecovery() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val core = create(wire, prefs)
        assertTrue(core.retry())
        assertTrue(prefs.wasAuthorized())
        val verifiedTime = prefs.lastVerifiedAt()
        core.disconnectIdle()
        assertFalse(wire.connected)
        assertFalse(core.state.value.connected)
        assertEquals(AdbPhase.IDLE, core.state.value.phase)
        assertEquals(AdbTransportMode.NONE, core.state.value.transport)
        assertFalse(prefs.manuallyStopped())
        assertEquals(37000, prefs.lastTlsPort())
        assertEquals(verifiedTime, prefs.lastVerifiedAt())
        assertTrue(core.retry(auto = true))
        assertTrue(core.state.value.connected)
        core.quit()
    }

    @Test fun transientDisconnectNeverClearsAnExplicitStop() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val core = create(wire, prefs)
        assertTrue(core.retry())
        core.stop()
        assertTrue(prefs.manuallyStopped())
        core.disconnectIdle()
        assertTrue(prefs.manuallyStopped())
        assertEquals(AdbPhase.STOPPED, core.state.value.phase)
        assertFalse(core.retry(auto = true))
        core.quit()
    }

    @Test fun manualStopBlocksBootAndAutoRecovery() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs().apply {
            stopped = true
            authorized = true
            tlsPort = 37000
        }
        val core = create(wire, prefs)
        assertFalse(core.startAfterBoot())
        assertFalse(core.retry(auto = true))
        assertEquals(0, wire.tcpStarts)
        assertTrue(core.retry()) // Explicit Retry is allowed to reverse manual Stop.
        core.stop()
        assertTrue(prefs.manuallyStopped())
        assertEquals(AdbPhase.STOPPED, core.state.value.phase)
        assertFalse(core.retry(auto = true))
        core.quit()
        assertFalse(core.retry())
    }

    @Test fun commandRequiresFreshUid2000() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs().apply {
            tlsPort = 37000
            setEnabled(AdbOption.TCP_5555, false)
        }
        val core = create(wire, prefs)
        assertTrue(core.retry())
        assertEquals("command output", core.execVerified("echo safe"))
        assertEquals(1, wire.actionCalls)
        wire.uid = "1000"
        try {
            core.execVerified("echo forbidden")
            fail("Non-shell command was executed")
        } catch (expected: SecurityException) {
            assertTrue(expected.message!!.contains("UID 2000"))
        }
        assertEquals(1, wire.actionCalls)
        assertFalse(core.state.value.connected)
        core.quit()
    }

    @Test fun cannotBootRecoverWithoutAuthorizationOrSetting() = runBlocking {
        val wire = Wire()
        val prefs = MemoryPrefs()
        val core = create(wire, prefs)
        assertFalse(core.startAfterBoot())
        prefs.authorized = true
        prefs.setEnabled(AdbOption.START_ON_BOOT, false)
        assertFalse(core.startAfterBoot())
        assertEquals(0, wire.tcpStarts)
        core.quit()
    }
    @Test fun duplicateMdnsAnnouncementsHaveBoundedRetryWithFreshPortImmediate() {
        var clock = 1_000L
        val gate = DiscoveryPortRetryGate(clockMillis = { clock }, cooldownMs = 15_000L)
        assertTrue(gate.mayAttempt(37123))
        assertFalse(gate.mayAttempt(37123))
        clock += 1_000L
        assertFalse(gate.mayAttempt(37123))
        assertTrue(gate.mayAttempt(37124))
        clock += 15_000L
        assertTrue(gate.mayAttempt(37124))
        gate.reset()
        assertTrue(gate.mayAttempt(37124))
    }

}

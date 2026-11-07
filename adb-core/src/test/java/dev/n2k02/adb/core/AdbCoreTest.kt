package dev.n2k02.adb.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
        override fun start() { started++ }
        override fun stop() { stopped++ }
    }
    private class Wire : AdbWire {
        override var connected = false
        override var mode = AdbTransportMode.NONE
        var uid = "2000"
        var actionCalls = 0
        var tcpAvailable = false
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
}

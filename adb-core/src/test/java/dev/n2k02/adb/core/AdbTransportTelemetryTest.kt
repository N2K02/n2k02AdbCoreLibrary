package dev.n2k02.adb.core

import org.junit.Assert.*
import org.junit.Test

class AdbTransportTelemetryTest {
    @Test fun boundedAndMonotonic() {
        val telemetry = AdbTransportTelemetry(2)
        telemetry.record(AdbTransportTelemetry.Stage.CONNECT_TLS, 11, true)
        telemetry.record(AdbTransportTelemetry.Stage.SHELL, -5, false)
        telemetry.record(AdbTransportTelemetry.Stage.SHELL, 50, true)
        assertEquals(2, telemetry.recent().size)
        assertEquals(0L, telemetry.recent().first().elapsedMs)
        assertEquals(1, telemetry.summaries().single().failures)
    }

    @Test fun percentilesAndReset() {
        val telemetry = AdbTransportTelemetry()
        (1L..20L).forEach { telemetry.record(AdbTransportTelemetry.Stage.CONNECT_TLS, it, true) }
        val summary = telemetry.summaries().single()
        assertEquals(10L, summary.p50Ms)
        assertEquals(19L, summary.p95Ms)
        telemetry.clear()
        assertTrue(telemetry.summaries().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedCapacity() { AdbTransportTelemetry(0) }
}

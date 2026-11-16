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


    @Test fun tracksFailuresSeparatelyPerStage() {
        val telemetry = AdbTransportTelemetry()
        telemetry.record(AdbTransportTelemetry.Stage.CONNECT_TLS, 100, false)
        telemetry.record(AdbTransportTelemetry.Stage.CONNECT_TLS, 200, true)
        telemetry.record(AdbTransportTelemetry.Stage.SHELL, 40, false)
        val summaries = telemetry.summaries().associateBy { it.stage }
        assertEquals(1, summaries.getValue(AdbTransportTelemetry.Stage.CONNECT_TLS).failures)
        assertEquals(1, summaries.getValue(AdbTransportTelemetry.Stage.SHELL).failures)
        assertEquals(2, summaries.getValue(AdbTransportTelemetry.Stage.CONNECT_TLS).count)
        assertEquals(100L, summaries.getValue(AdbTransportTelemetry.Stage.CONNECT_TLS).p50Ms)
        assertEquals(200L, summaries.getValue(AdbTransportTelemetry.Stage.CONNECT_TLS).p95Ms)
    }

    @Test fun evictionIsOldestFirstAndSnapshotsAreStable() {
        val telemetry = AdbTransportTelemetry(2)
        telemetry.record(AdbTransportTelemetry.Stage.PAIR, 9, false)
        val snapshot = telemetry.recent()
        telemetry.record(AdbTransportTelemetry.Stage.PAIR, 10, true)
        telemetry.record(AdbTransportTelemetry.Stage.PAIR, 11, true)
        assertEquals(9L, snapshot.single().elapsedMs)
        assertEquals(listOf(10L, 11L), telemetry.recent().map { it.elapsedMs })
        assertEquals(0, telemetry.summaries().single().failures)
    }

    @Test fun concurrentWritersStayWithinCapacity() {
        val telemetry = AdbTransportTelemetry(60)
        val workers = (1..8).map { worker ->
            Thread {
                repeat(500) { index ->
                    telemetry.record(AdbTransportTelemetry.Stage.SHELL,
                        (worker * 500 + index).toLong(), index % 2 == 0)
                    telemetry.summaries()
                }
            }.also { it.start() }
        }
        workers.forEach { it.join() }
        assertEquals(60, telemetry.recent().size)
        assertEquals(60, telemetry.summaries().single().count)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedCapacity() { AdbTransportTelemetry(0) }
}

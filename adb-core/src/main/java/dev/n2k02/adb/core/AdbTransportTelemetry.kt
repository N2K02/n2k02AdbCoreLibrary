package dev.n2k02.adb.core

import java.util.ArrayDeque

/** Bounded, secret-free connection diagnostics. No pairing code, host, command or output is stored. */
class AdbTransportTelemetry(private val capacity: Int = 60) {
    init { require(capacity in 1..600) }
    enum class Stage { PAIR, CONNECT_TLS, CONNECT_TCP, SHELL, DISCONNECT }
    data class Sample(val stage: Stage, val elapsedMs: Long, val success: Boolean)
    data class Summary(
        val stage: Stage,
        val count: Int,
        val failures: Int,
        val p50Ms: Long,
        val p95Ms: Long,
    )
    private val samples = ArrayDeque<Sample>()

    @Synchronized fun record(stage: Stage, elapsedMs: Long, success: Boolean) {
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(Sample(stage, elapsedMs.coerceAtLeast(0), success))
    }

    @Synchronized fun recent(): List<Sample> = samples.toList()

    @Synchronized fun summaries(): List<Summary> =
        Stage.entries.mapNotNull { stage ->
            val selected = samples.filter { it.stage == stage }
            if (selected.isEmpty()) return@mapNotNull null
            val sorted = selected.map { it.elapsedMs }.sorted()
            fun percentile(p: Int): Long = sorted[((sorted.size * p + 99) / 100 - 1).coerceIn(0, sorted.lastIndex)]
            Summary(stage, selected.size, selected.count { !it.success }, percentile(50), percentile(95))
        }

    @Synchronized fun clear() = samples.clear()
}

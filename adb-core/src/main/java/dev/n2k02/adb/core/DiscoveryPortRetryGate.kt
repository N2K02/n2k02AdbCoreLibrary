package dev.n2k02.adb.core

/** Prevent mDNS rediscovery storms from repeatedly opening native TLS handshakes.
 * A new port remains immediately eligible, while manual retries are unaffected.
 */
internal class DiscoveryPortRetryGate(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val cooldownMs: Long = 15_000L,
) {
    private var lastPort: Int? = null
    private var lastAttemptAt = 0L

    @Synchronized fun mayAttempt(port: Int): Boolean {
        if (!AdbVerification.validPort(port)) return false
        val now = clockMillis()
        if (lastPort == port && now >= lastAttemptAt && now - lastAttemptAt < cooldownMs)
            return false
        lastPort = port
        lastAttemptAt = now
        return true
    }

    @Synchronized fun reset() {
        lastPort = null
        lastAttemptAt = 0L
    }
}

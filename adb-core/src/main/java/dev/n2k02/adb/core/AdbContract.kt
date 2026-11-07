package dev.n2k02.adb.core

import android.content.Context
import java.security.PrivateKey
import java.security.cert.Certificate

/** Must wrap the host's already-authorized identity; NEVER replace persisted ADB keys. */
fun interface AdbIdentitySource {
    fun load(context: Context): AdbIdentity
}
data class AdbIdentity(val privateKey: PrivateKey, val certificate: Certificate)

enum class AdbOption { AUTO_PAIR, WATCHDOG, START_ON_BOOT, RESTORE_AFTER_RESTART, TCP_5555 }
enum class AdbTransportMode { NONE, WIRELESS_TLS, LOCAL_TCP_5555 }
enum class AdbPhase { IDLE, SEARCHING, PAIRING, CONNECTING, VERIFIED, NEEDS_SETUP, STOPPED }

data class AdbState(
    val phase: AdbPhase = AdbPhase.IDLE,
    val message: String = "Not connected",
    val verifiedUid: Int? = null,
    val transport: AdbTransportMode = AdbTransportMode.NONE,
    val pairingPort: Int? = null,
    val connectionPort: Int? = null,
    val scanning: Boolean = false,
    val lastVerifiedAt: Long = 0L,
) {
    val connected: Boolean get() = phase == AdbPhase.VERIFIED && verifiedUid == 2000
}

interface AdbPreferenceStore {
    fun enabled(option: AdbOption): Boolean
    fun setEnabled(option: AdbOption, enabled: Boolean)
    fun manuallyStopped(): Boolean
    fun setManuallyStopped(stopped: Boolean)
    fun lastTlsPort(): Int?
    fun setLastTlsPort(port: Int)
    fun lastVerifiedAt(): Long
    fun setLastVerifiedAt(timeMs: Long)
    fun wasAuthorized(): Boolean
    fun setAuthorized()
}

interface AdbWire {
    val connected: Boolean
    suspend fun pair(port: Int, code: String): Boolean
    suspend fun connectTls(port: Int): Boolean
    suspend fun connectTcp(): Boolean
    suspend fun run(command: String, timeoutMs: Long = 15_000): String
    suspend fun switchToTcp(): Boolean
    suspend fun switchToUsb(): Boolean
    suspend fun close()
}

internal object AdbVerification {
    fun verifiedUid(raw: String): Int? = raw.trim().lines().lastOrNull()?.trim()
        ?.takeIf { it == "2000" }?.toInt()
    fun mayConnectTcp(enabled: Boolean, stopped: Boolean): Boolean = enabled && !stopped
    fun shouldRecover(authorized: Boolean, stopped: Boolean): Boolean = authorized && !stopped
    fun validPairing(port: Int, code: String): Boolean = port in 1..65535 &&
        code.length == 6 && code.all { it in '0'..'9' }
    fun validPort(port: Int): Boolean = port in 1..65535
}

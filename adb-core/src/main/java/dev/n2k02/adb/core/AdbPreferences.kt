package dev.n2k02.adb.core

import android.content.Context

/** Standalone default; existing apps should wrap their legacy preference store. */
class SharedAdbPreferences(context: Context) : AdbPreferenceStore {
    private val prefs = context.applicationContext.getSharedPreferences("n2k02_adb_core", Context.MODE_PRIVATE)
    override fun enabled(option: AdbOption): Boolean =
        prefs.getBoolean("setting_${option.name.lowercase()}", option != AdbOption.RESTORE_AFTER_RESTART)
    override fun setEnabled(option: AdbOption, enabled: Boolean) {
        prefs.edit().putBoolean("setting_${option.name.lowercase()}", enabled).apply()
    }
    override fun manuallyStopped(): Boolean = prefs.getBoolean("manual_stop", false)
    override fun setManuallyStopped(stopped: Boolean) {
        prefs.edit().putBoolean("manual_stop", stopped).apply()
    }
    override fun lastTlsPort(): Int? = prefs.getInt("tls_port", -1).takeIf(AdbVerification::validPort)
    override fun setLastTlsPort(port: Int) {
        require(AdbVerification.validPort(port))
        prefs.edit().putInt("tls_port", port).apply()
    }
    override fun lastVerifiedAt(): Long = prefs.getLong("last_verified", 0L)
    override fun setLastVerifiedAt(timeMs: Long) {
        prefs.edit().putLong("last_verified", timeMs).apply()
    }
    override fun wasAuthorized(): Boolean = prefs.getBoolean("authorized", false)
    override fun setAuthorized() { prefs.edit().putBoolean("authorized", true).apply() }
}

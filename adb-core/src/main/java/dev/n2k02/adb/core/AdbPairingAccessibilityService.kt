package dev.n2k02.adb.core

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Only the OS Settings pairing dialog can supply an ephemeral six-digit code. */
object AdbPairingCodePolicy {
    fun isAndroidSettings(packageName: String?): Boolean = packageName in setOf(
        "com.android.settings", "com.google.android.settings",
        "com.samsung.android.settings", "com.android.tv.settings")
    fun isPairingDialog(labels: List<String>): Boolean = labels.any {
        it.contains("pairing code", ignoreCase = true) ||
            it.contains("pair device with", ignoreCase = true) ||
            it.contains("pair with device", ignoreCase = true)
    }
    fun extract(labels: List<String>): String? {
        if (!isPairingDialog(labels)) return null
        return labels.asSequence().map(String::trim)
            .firstOrNull { it.length == 6 && it.all { character -> character in '0'..'9' } }
            ?: labels.asSequence().filter { it.contains("pairing code", ignoreCase = true) }
                .mapNotNull { Regex("(?<![0-9])[0-9]{6}(?![0-9])").find(it)?.value }
                .firstOrNull()
    }
}

/**
 * Shared pairing Accessibility reader. Apps may subclass solely to bootstrap
 * their existing authorized identity through acquireCore().
 *
 * Android requires the user to enable this service and open the system Wireless
 * debugging pairing dialog for the initial trust grant. Never click, record,
 * persist, log or upload pairing codes.
 */
open class SharedAdbPairingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: SharedAdbPairingAccessibilityService? = null

        fun isEnabled(
            context: Context,
            serviceClass: Class<out AccessibilityService> = SharedAdbPairingAccessibilityService::class.java,
        ): Boolean {
            val services = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val name = ComponentName(context, serviceClass).flattenToString()
            return services.split(':').any { it.equals(name, ignoreCase = true) }
        }

        /** React when a pairing port appears *after* the Settings dialog. */
        fun notifyPairingPortDiscovered() { active?.scheduleRead() }
    }

    protected open fun acquireCore(): AdbCore? = AdbCoreRegistry.current

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastSignature: Int? = null
    private var remaining = 0
    private var attempts = 0
    private val read = object : Runnable {
        override fun run() {
            if (scan()) return
            if (remaining-- > 0) handler.postDelayed(this, 400L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        acquireCore()
        scheduleRead()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null ||
            !AdbPairingCodePolicy.isAndroidSettings(event.packageName?.toString())) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        scheduleRead()
    }

    private fun scheduleRead() {
        handler.removeCallbacks(read)
        remaining = 75 // Up to 30s for a pairing port discovered later via mDNS.
        handler.post(read)
    }

    private fun scan(): Boolean {
        val core = acquireCore() ?: return false
        if (!core.preferences.enabled(AdbOption.AUTO_PAIR)) return true
        val root = rootInActiveWindow ?: return false
        if (!AdbPairingCodePolicy.isAndroidSettings(root.packageName?.toString())) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val labels = mutableListOf<String>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 120) {
            val node = queue.removeFirst()
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            repeat(node.childCount.coerceAtMost(20)) { i -> node.getChild(i)?.let(queue::add) }
        }
        // Discovery is temporarily owned only when the real OS dialog is visible.
        if (!AdbPairingCodePolicy.isPairingDialog(labels)) return false
        core.beginAutomaticPairingDiscovery()
        val code = AdbPairingCodePolicy.extract(labels) ?: return false
        val port = core.state.value.pairingPort ?: return false
        val signature = (port.toString() + ":" + root.windowId + ":" + code).hashCode()
        if (lastSignature != signature && attempts < 3) {
            lastSignature = signature
            scope.launch {
                val success = try {
                    core.pair(port, code)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { false }
                if (success) attempts = 0
                else {
                    attempts++
                    lastSignature = null
                }
            }
        }
        return true
    }

    override fun onInterrupt() { handler.removeCallbacks(read) }
    override fun onDestroy() {
        handler.removeCallbacks(read)
        acquireCore()?.endAutomaticPairingDiscovery()
        if (active === this) active = null
        scope.cancel()
        super.onDestroy()
    }
}

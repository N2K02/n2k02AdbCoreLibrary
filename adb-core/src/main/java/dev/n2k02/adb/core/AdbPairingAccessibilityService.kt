package dev.n2k02.adb.core

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Parsing is pure so every app uses the same allowlist, validation and privacy policy. */
object AdbPairingCodePolicy {
    /**
     * Android's "Pair device with pairing code" dialog also displays the
     * phone's own "IP address & port". The port can be used when mDNS
     * discovery is blocked; the IP is NEVER used as a remote destination.
     * Require a visible pairing-dialog hint before examining endpoint text.
     */
    fun pairingPort(labels: List<String>): Int? {
        if (labels.none { it.contains("pairing code", true) ||
                it.contains("pair device with", true) ||
                it.contains("pair with device", true) }) return null
        val endpoint = Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}:([0-9]{2,5})(?![0-9])""")
        val explicit = Regex("""(?i)pairing\s+port\s*[:#]?\s*([0-9]{2,5})(?![0-9])""")
        return labels.asSequence().mapNotNull { label ->
            (endpoint.find(label)?.groupValues?.get(1) ?:
                explicit.find(label)?.groupValues?.get(1))?.toIntOrNull()
        }.firstOrNull { it in 1..65535 }
    }

    fun isAndroidSettings(packageName: String?): Boolean = packageName in setOf(
        "com.android.settings", "com.google.android.settings",
        "com.samsung.android.settings", "com.android.tv.settings")
    fun extract(labels: List<String>): String? {
        if (labels.none {
                it.contains("pairing code", ignoreCase = true) ||
                    it.contains("pair device with", ignoreCase = true) ||
                    it.contains("pair with device", ignoreCase = true)
            }) return null
        return labels.asSequence().map(String::trim)
            .firstOrNull { it.length == 6 && it.all { character -> character in '0'..'9' } }
            ?: labels.asSequence().filter { it.contains("pairing code", ignoreCase = true) }
                .mapNotNull { Regex("(?<![0-9])[0-9]{6}(?![0-9])").find(it)?.value }
                .firstOrNull()
    }
}

/**
 * Optional accessibility flow with no app-specific code. The host registers its
 * AdbCore in Application.onCreate through AdbCoreRegistry.install(core).
 * This service never performs clicks or records logs, codes, or screen contents.
 */
class SharedAdbPairingAccessibilityService : AccessibilityService() {
    companion object {
        fun isEnabled(context: Context): Boolean {
            val services = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val component = ComponentName(context, SharedAdbPairingAccessibilityService::class.java)
                .flattenToString()
            return services.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastSignature: Int? = null
    private var remaining = 0
    private val read = object : Runnable {
        override fun run() {
            if (scan()) return
            if (remaining-- > 0) handler.postDelayed(this, 400L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        schedule()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val core = AdbCoreRegistry.current ?: return
        if (event == null || !core.preferences.enabled(AdbOption.AUTO_PAIR) ||
            !AdbPairingCodePolicy.isAndroidSettings(event.packageName?.toString())) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(read)
        remaining = 15
        handler.post(read)
    }

    private fun scan(): Boolean {
        val core = AdbCoreRegistry.current ?: return true
        if (!core.preferences.enabled(AdbOption.AUTO_PAIR)) return false
        val root = rootInActiveWindow ?: return false
        if (!AdbPairingCodePolicy.isAndroidSettings(root.packageName?.toString())) return false
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        val labels = mutableListOf<String>()
        nodes.add(root)
        var visited = 0
        while (nodes.isNotEmpty() && visited++ < 100) {
            val node = nodes.removeFirst()
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            repeat(node.childCount.coerceAtMost(20)) { i -> node.getChild(i)?.let(nodes::add) }
        }
        val code = AdbPairingCodePolicy.extract(labels) ?: return false
        val port = core.state.value.pairingPort
            ?: AdbPairingCodePolicy.pairingPort(labels)
            ?: return false
        val signature = (port.toString() + ":" + root.windowId + ":" + code).hashCode()
        if (lastSignature != signature) {
            lastSignature = signature
            scope.launch { core.pair(port, code) }
        }
        return true
    }

    override fun onInterrupt() { handler.removeCallbacks(read) }
    override fun onDestroy() {
        handler.removeCallbacks(read)
        scope.cancel()
        super.onDestroy()
    }
}

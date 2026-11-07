package dev.n2k02.adb.core

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** One canonical Compose layout for every ADB-enabled N2K02 app. */
@Composable
fun AdbConnectionSettingsPage(
    adb: AdbCore,
    accessibilityEnabled: Boolean,
    onOpenAccessibility: (() -> Unit)? = null,
) {
    val state by adb.state.collectAsState()
    val scope = rememberCoroutineScope()
    var changeNote by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AdbSection("Pairing & recovery") {
            AdbOptionRow(adb, AdbOption.AUTO_PAIR, "Auto-detect pairing code",
                "Read only Android's pairing dialog through Accessibility.")
            if (adb.preferences.enabled(AdbOption.AUTO_PAIR) && !accessibilityEnabled) {
                Text("Accessibility is required for automatic pairing. Manual pairing is available on Home.",
                    style = MaterialTheme.typography.bodySmall)
                onOpenAccessibility?.let { action ->
                    OutlinedButton(onClick = action) { Text("Enable Accessibility") }
                }
            }
            HorizontalDivider()
            AdbOptionRow(adb, AdbOption.WATCHDOG, "Connection watchdog",
                "Check connection health and recover when authorized.")
            AdbOptionRow(adb, AdbOption.START_ON_BOOT, "Start on boot",
                "Try to restore the helper after restarting and unlocking.")
        }
        AdbSection("Connection & security") {
            AdbOptionRow(adb, AdbOption.RESTORE_AFTER_RESTART, "Restore after restart",
                "Requires Android permission; never overrides a manual off setting.")
            AdbOptionRow(adb, AdbOption.TCP_5555, "Keep ADB without Wi-Fi (TCP/5555)",
                "TCP/5555 may be reachable from your network. Disable it when not needed.")
            if (state.connected && state.transport == AdbTransportMode.LOCAL_TCP_5555) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val ok = adb.disableTcp()
                        changeNote = if (ok) "TCP/5555 shutdown requested" else
                            "Could not confirm shutdown. Verify in Android Settings."
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Disable TCP/5555") }
            }
            if (changeNote.isNotBlank()) Text(changeNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** First-time pairing belongs on Home, not inside the shared Settings cards. */
@Composable
fun AdbHomeSetupPage(adb: AdbCore) {
    val context = LocalContext.current
    val state by adb.state.collectAsState()
    val scope = rememberCoroutineScope()
    var pairingPort by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf("") }
    var connectionPort by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    DisposableEffect(adb) {
        adb.enterPage()
        onDispose { adb.leavePage() }
    }
    AdbSection("ADB setup") {
        Text(if (state.connected) "Connected • shell UID 2000 verified" else
            "ADB setup required • shell UID 2000 not verified",
            style = MaterialTheme.typography.titleSmall)
        Text(state.message, style = MaterialTheme.typography.bodySmall)
        if (!state.connected) {
            Text("Enable Wireless debugging and USB debugging in Developer options.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.fillMaxWidth()) { Text("Open Developer options") }
            OutlinedButton(onClick = { scope.launch { adb.retry() } },
                modifier = Modifier.fillMaxWidth()) { Text("Find / Retry") }
            Text("Pairing port: ${state.pairingPort ?: "Not detected"}",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { manual = !manual }, modifier = Modifier.fillMaxWidth()) {
                Text(if (manual) "Hide manual pairing" else "Manual pairing (fallback)")
            }
            if (manual) {
                OutlinedTextField(pairingPort,
                    { pairingPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Pairing port") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pairingCode,
                    { pairingCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Six-digit pairing code") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch { adb.pair(pairingPort.toIntOrNull() ?: -1, pairingCode) } },
                    enabled = pairingPort.toIntOrNull() in 1..65535 && pairingCode.length == 6,
                    modifier = Modifier.fillMaxWidth()) { Text("Pair") }
                OutlinedTextField(connectionPort,
                    { connectionPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Wireless debugging connection port") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch { adb.connectTls(connectionPort.toIntOrNull() ?: -1) } },
                    enabled = connectionPort.toIntOrNull() in 1..65535,
                    modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            }
        }
    }
}

@Composable
fun AdbDiagnosticsPage(adb: AdbCore) {
    val state by adb.state.collectAsState()
    val scope = rememberCoroutineScope()
    DisposableEffect(adb) {
        adb.enterPage()
        onDispose { adb.leavePage() }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AdbSection("ADB connection") {
            Text(if (state.connected) "Shell UID 2000 verified • Connected"
                else "ADB not verified", style = MaterialTheme.typography.titleSmall)
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            Text("Transport: ${state.transport}", style = MaterialTheme.typography.bodySmall)
            Text("Last verified: " + if (state.lastVerifiedAt > 0L)
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(state.lastVerifiedAt)) else "Never",
                style = MaterialTheme.typography.bodySmall)
            Text("Discovery: " + if (state.scanning) "Scanning" else "Idle",
                style = MaterialTheme.typography.bodySmall)
            Text("Pairing port: ${state.pairingPort ?: "Not detected"}",
                style = MaterialTheme.typography.bodySmall)
            Text("Connection port: ${state.connectionPort ?: "Not detected"}",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { adb.retry() } },
                    modifier = Modifier.weight(1f)) { Text("Retry") }
                OutlinedButton(onClick = { scope.launch { adb.stop() } },
                    modifier = Modifier.weight(1f)) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun AdbSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun AdbOptionRow(adb: AdbCore, option: AdbOption, title: String, detail: String) {
    val scope = rememberCoroutineScope()
    var checked by remember(option) { mutableStateOf(adb.preferences.enabled(option)) }
    Row(Modifier.fillMaxWidth().clickable {
        checked = !checked
        scope.launch { adb.setOption(option, checked) }
    }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = {
            checked = it
            scope.launch { adb.setOption(option, it) }
        })
    }
}

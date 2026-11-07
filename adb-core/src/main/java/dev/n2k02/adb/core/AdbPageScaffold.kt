package dev.n2k02.adb.core

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Canonical single-header layout. Use only when the host does not already render
 * mRadio's top bar; never nest two headers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbPageScaffold(
    pageTitle: String,
    pageIcon: ImageVector,
    appName: String,
    appIcon: @Composable () -> Unit,
    onNavigateBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(pageIcon, contentDescription = "Back to Settings")
                }
            },
            title = { Text(pageTitle, maxLines = 1, style = MaterialTheme.typography.titleMedium) },
            actions = {
                Row(modifier = Modifier.padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    appIcon()
                    Text(appName, style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            content()
        }
    }
}

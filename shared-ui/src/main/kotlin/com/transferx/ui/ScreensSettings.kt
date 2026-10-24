package com.transferx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.transferx.core.transfer.ConflictPolicy
import com.transferx.core.util.Log

@Composable
fun SettingsScreen(c: AppController) {
    val settings by c.node.settingsStore.settings.collectAsState()
    val trusted by c.node.trust.devices.collectAsState()
    var name by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var showLogs by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionCard("Device") {
            OutlinedTextField(name, { name = it.take(64) }, label = { Text("Device name shown to others") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { c.updateSettings { it.copy(deviceName = name) }; c.toast("Name saved") }, enabled = name.isNotBlank() && name != settings.deviceName) { Text("Save name") }
            LabeledValue("Fingerprint", c.node.identity.shortFingerprint, mono = true)
            Text("Compare this fingerprint with what the other device shows if you want to verify a pairing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Receiving") {
            ToggleRow("Accept incoming transfer requests", "Each request still needs your approval.", settings.receiving) { v -> c.updateSettings { it.copy(receiving = v) } }
            ToggleRow("Visible to nearby devices", "Announces this device's name on the local network.", settings.discoverable) { v -> c.updateSettings { it.copy(discoverable = v) } }
            LabeledValue("Save files to", settings.destination)
            OutlinedButton(onClick = { c.chooseDefaultDestination() }) { Text("Change folder") }
            Text("When a file with the same name exists", fontWeight = FontWeight.Medium)
            PolicyChooser(settings.conflictPolicy) { p -> c.updateSettings { it.copy(conflictPolicy = p) } }
        }

        SectionCard("Paired devices") {
            if (trusted.isEmpty()) Text("No paired devices yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            trusted.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Medium)
                        Text("${Format.platform(t.platform)} · paired ${Format.time(t.pairedAt)} · ${t.fingerprint.take(16).chunked(4).joinToString(" ").uppercase()}", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { c.node.trust.remove(t.fingerprint) }) { Text("Forget") }
                }
            }
        }

        SectionCard("Connection methods on this device") {
            c.platform.capabilities.forEach { cap ->
                Row {
                    Pill(
                        when (cap.status) {
                            CapabilityStatus.AVAILABLE -> "Available"
                            CapabilityStatus.LIMITED -> "Limited"
                            CapabilityStatus.UNAVAILABLE -> "Not available"
                        },
                        when (cap.status) {
                            CapabilityStatus.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer
                            CapabilityStatus.LIMITED -> MaterialTheme.colorScheme.tertiaryContainer
                            CapabilityStatus.UNAVAILABLE -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(cap.method, fontWeight = FontWeight.Medium)
                        Text(cap.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        SectionCard("Privacy and security", tone = Tone.INFO) {
            Text(
                "Files go directly between devices over the local network. Nothing is uploaded, and no account is needed. " +
                    "Connections are encrypted with TLS, and each device is identified by its certificate fingerprint. " +
                    "On shared or public Wi-Fi, other people on the network can see this device's name while it's visible, " +
                    "and could try to pair with it. Only enter codes from devices you can physically see, and turn off visibility when you don't need it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard("Diagnostics") {
            Text("TransferX ${c.node.appVersion} · data in ${c.node.dataDir}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showLogs = !showLogs }) { Text(if (showLogs) "Hide recent log" else "Show recent log") }
            if (showLogs) {
                // The log never contains file contents, pairing codes, or full fingerprints.
                val lines = remember { Log.recent().takeLast(80) }
                lines.forEach { e ->
                    Text("${e.level.name.first()} ${e.tag}: ${e.message}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PolicyChooser(current: ConflictPolicy, onChange: (ConflictPolicy) -> Unit) {
    val options = listOf(
        ConflictPolicy.RENAME to "Keep both (save as \"name (1)\")",
        ConflictPolicy.SKIP to "Skip the incoming file",
        ConflictPolicy.REPLACE to "Replace the existing file",
    )
    Column {
        options.forEach { (p, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = current == p, onClick = { onChange(p) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == p, onClick = null)
                Text(label)
            }
        }
    }
}

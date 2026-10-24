package com.transferx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.transferx.core.security.PairingCodes
import com.transferx.core.transfer.ConflictPolicy
import com.transferx.core.transfer.Prompt
import com.transferx.core.transfer.TransportKind
import kotlinx.coroutines.delay

/** Shows whichever engine prompt needs attention: incoming offer, pairing request, or code entry. */
@Composable
fun PromptHost(c: AppController) {
    val prompts by c.engine.prompts.collectAsState()
    val p = prompts.firstOrNull { it is Prompt.PairingCodeNeeded }
        ?: prompts.firstOrNull { it is Prompt.PairingRequested }
        ?: prompts.firstOrNull { it is Prompt.IncomingOffer }
    when (p) {
        is Prompt.PairingCodeNeeded -> PairingCodeDialog(c, p)
        is Prompt.PairingRequested -> PairingRequestedDialog(c, p)
        is Prompt.IncomingOffer -> IncomingOfferDialog(c, p)
        null -> {}
    }
}

@Composable
private fun PairingRequestedDialog(c: AppController, p: Prompt.PairingRequested) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pairing request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${p.peer.name} (${Format.platform(p.peer.platform)}) wants to pair with this device. To allow it, enter this code on ${p.peer.name}:")
                Text(p.code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Their fingerprint: ${p.peer.fingerprint.take(16).chunked(4).joinToString(" ").uppercase()}", style = MaterialTheme.typography.bodySmall)
                Text("Only continue if you can see that device. You'll still approve each transfer separately.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { c.engine.denyPairing(p.id) }) { Text("Deny") } },
    )
}

@Composable
private fun PairingCodeDialog(c: AppController, p: Prompt.PairingCodeNeeded) {
    var code by remember(p.id) { mutableStateOf("") }
    var invalid by remember(p.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pair with ${p.peer.name}") },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter the pairing code shown on ${p.peer.name}. You need to do this only once per device.")
                if (p.previousAttemptFailed) Text("That code was wrong. ${p.attemptsRemaining} attempt(s) left.", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    code, { code = it.uppercase().take(16); invalid = false },
                    label = { Text("Code (e.g. ABCD-EFGH-JKMN)") }, singleLine = true,
                    isError = invalid, modifier = Modifier.fillMaxWidth(),
                )
                if (invalid) Text("A code has 12 letters and digits.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(onClick = { if (!c.engine.submitPairingCode(p.id, code)) invalid = true }, enabled = PairingCodes.normalize(code) != null) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = { c.engine.submitPairingCode(p.id, null) }) { Text("Cancel") } },
    )
}

/**
 * The Incoming Transfer screen and the Storage Warning: sender identity, files, total
 * size, destination, and the space check, with Accept and Reject.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IncomingOfferDialog(c: AppController, p: Prompt.IncomingOffer) {
    val settings by c.node.settingsStore.settings.collectAsState()
    val pre = p.preflight
    // Default selection: everything that fits and isn't refused. Recomputed when the
    // preflight changes (re-check, or another destination).
    var selected by remember(p) { mutableStateOf(p.files.filter { it.fits && !it.tooLargeForFilesystem && !it.unsafe }.map { it.fileId }.toSet()) }
    var policy by remember(p.id) { mutableStateOf(settings.conflictPolicy) }
    var confirmReplace by remember(p.id) { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.id) {
        while (true) {
            now = System.currentTimeMillis(); delay(1_000)
        }
    }
    val chosen = p.files.filter { it.fileId in selected }
    val conflicts = chosen.count { it.conflictsWithExisting }
    val chosenBytes = chosen.sumOf { it.size }

    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 680.dp).padding(16.dp), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.heightIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Incoming transfer", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${p.peer.name} wants to send you ${p.files.size} file(s), ${Format.bytes(p.totalBytes)}.",
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(Format.platform(p.peer.platform))
                    Pill(if (p.transport == TransportKind.BLUETOOTH) "Bluetooth" else "Wi-Fi")
                    Pill("Paired device", MaterialTheme.colorScheme.primaryContainer)
                }
                Text("Fingerprint ${p.peer.fingerprint.take(16).chunked(4).joinToString(" ").uppercase()} · ${p.peer.address ?: ""}", style = MaterialTheme.typography.bodySmall)

                HorizontalDivider()
                LabeledValue("Save to", p.destination)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c.changeOfferDestination(p.id, p.destination) }) { Text("Choose another folder") }
                    TextButton(onClick = { c.recheckOffer(p.id, p.destination) }) { Text("Re-check space") }
                }
                when {
                    !pre.writable -> SectionCard(tone = Tone.ERROR) {
                        Text("TransferX can't write to this folder. Choose another one.", fontWeight = FontWeight.Medium)
                    }
                    pre.measurementUnknown -> SectionCard(tone = Tone.WARNING) {
                        Text("Free space couldn't be measured for this folder. The transfer will stop safely if it runs out of space.")
                    }
                    else -> {
                        LabeledValue("Space needed", Format.bytes(pre.requiredBytes) + " (all files, incl. safety margin)")
                        LabeledValue("Space available", Format.bytes(pre.availableBytes))
                    }
                }
                if (pre.writable && (pre.nonFittingFileIds.isNotEmpty() || pre.tooLargeForFilesystem.isNotEmpty())) {
                    SectionCard("Not enough space for everything", tone = Tone.WARNING) {
                        pre.shortfallBytes?.takeIf { it > 0 }?.let { Text("${Format.bytes(it)} more is needed to receive all files.") }
                        val nf = p.files.filter { it.fileId in pre.nonFittingFileIds }
                        if (nf.isNotEmpty()) Text("Won't fit: " + nf.take(6).joinToString { it.name } + if (nf.size > 6) " and ${nf.size - 6} more" else "")
                        val tl = p.files.filter { it.tooLargeForFilesystem }
                        if (tl.isNotEmpty()) Text("Too large for this drive's filesystem (FAT32, 4 GB limit): " + tl.joinToString { it.name })
                        Text("You can accept only the files that fit, free up space and re-check, or choose another folder. Space can still run out during the transfer; if it does, the transfer pauses safely.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider()
                Text("Files (${chosen.size} selected, ${Format.bytes(chosenBytes)})", fontWeight = FontWeight.Medium)
                p.files.take(300).forEach { f ->
                    val selectable = f.fits && !f.tooLargeForFilesystem && !f.unsafe
                    val note = when {
                        f.unsafe -> "refused: unsafe name"
                        f.tooLargeForFilesystem -> "too large for this drive"
                        !f.fits -> "doesn't fit"
                        f.conflictsWithExisting -> "a file with this name exists"
                        else -> null
                    }
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = f.fileId in selected, enabled = selectable, role = Role.Checkbox,
                            onValueChange = { on -> selected = if (on) selected + f.fileId else selected - f.fileId },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = f.fileId in selected, onCheckedChange = null, enabled = selectable)
                        FileLine(
                            f.name,
                            listOfNotNull(f.relativeDir.ifEmpty { null }, Format.bytes(f.size), note).joinToString(" · "),
                        )
                    }
                }
                if (p.files.size > 300) Text("…and ${p.files.size - 300} more files")

                if (conflicts > 0) {
                    HorizontalDivider()
                    Text("$conflicts selected file(s) have the same name as existing files", fontWeight = FontWeight.Medium)
                    PolicyChooser(policy) { policy = it; confirmReplace = false }
                    if (policy == ConflictPolicy.REPLACE) {
                        Row(
                            Modifier.toggleable(value = confirmReplace, role = Role.Checkbox, onValueChange = { confirmReplace = it }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = confirmReplace, onCheckedChange = null)
                            Text("Yes, overwrite $conflicts existing file(s). This can't be undone.")
                        }
                    }
                }

                val left = ((p.expiresAt - now) / 1000).coerceAtLeast(0)
                Text("This request expires in ${Format.duration(left)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    OutlinedButton(onClick = { c.respond(p.id, false, null, null, policy) }) { Text("Reject") }
                    Button(
                        onClick = { c.respond(p.id, true, selected, p.destination, policy) },
                        enabled = pre.writable && selected.isNotEmpty() && (conflicts == 0 || policy != ConflictPolicy.REPLACE || confirmReplace),
                    ) {
                        Text(if (selected.size == p.files.size) "Accept" else "Accept ${selected.size} of ${p.files.size}")
                    }
                }
            }
        }
    }
}

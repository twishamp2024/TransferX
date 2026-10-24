package com.transferx.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transferx.core.protocol.FileStatus
import com.transferx.core.transfer.SessionState
import io.nayuki.qrcodegen.QrCode

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NORMAL,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = when (tone) {
        Tone.NORMAL -> CardDefaults.cardColors()
        Tone.WARNING -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        Tone.ERROR -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        Tone.INFO -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    }
    Card(modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

enum class Tone { NORMAL, WARNING, ERROR, INFO }

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun StatePill(state: SessionState) {
    val c = MaterialTheme.colorScheme
    val color = when (state) {
        SessionState.COMPLETED -> c.primaryContainer
        SessionState.FAILED, SessionState.REJECTED -> c.errorContainer
        SessionState.BLOCKED, SessionState.INTERRUPTED, SessionState.PARTIAL -> c.tertiaryContainer
        else -> c.secondaryContainer
    }
    Pill(Format.state(state), color)
}

@Composable
fun FileStatusText(status: FileStatus) {
    val c = MaterialTheme.colorScheme
    val color = when (status) {
        FileStatus.COMPLETED -> c.primary
        FileStatus.FAILED -> c.error
        FileStatus.BLOCKED, FileStatus.INTERRUPTED -> c.tertiary
        else -> c.onSurfaceVariant
    }
    Text(Format.fileStatus(status), color = color, style = MaterialTheme.typography.labelMedium)
}

/** A progress bar that only moves with real byte counts; indeterminate when the total is unknown. */
@Composable
fun BytesProgress(done: Long, total: Long, modifier: Modifier = Modifier) {
    val fraction = if (total <= 0) 1f else (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "${(fraction * 100).toInt()} percent" },
    )
}

@Composable
fun LabeledValue(label: String, value: String, mono: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // weight(1f): the value wraps within the remaining width instead of being pushed out on phones.
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

@Composable
fun FileLine(name: String, sub: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FileBadge(name)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Small type badge (file extension) instead of a generic icon. */
@Composable
fun FileBadge(name: String) {
    val ext = name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" }
    val kind = kindOf(name)
    val color = when (kind) {
        "image" -> Color(0xFF2E7D6B)
        "video" -> Color(0xFF6A4C93)
        "audio" -> Color(0xFF1F6FA3)
        "document" -> Color(0xFFB45309)
        "archive" -> Color(0xFF5B6770)
        else -> Color(0xFF4B5563)
    }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(color).semantics { contentDescription = "$kind file" },
        contentAlignment = Alignment.Center,
    ) {
        Text(ext, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

fun kindOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "dng", "raw", "tif", "tiff" -> "image"
    "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp" -> "video"
    "mp3", "m4a", "flac", "wav", "ogg", "aac", "opus" -> "audio"
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "odt", "rtf", "csv" -> "document"
    "zip", "7z", "rar", "tar", "gz", "xz", "apk", "iso" -> "archive"
    else -> "other"
}

/** Renders [text] as a QR code (ECC level M, 4-module quiet zone). */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier, description: String = "QR code") {
    val qr = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier.aspectRatio(1f).background(Color.White).semantics { contentDescription = description }) {
        val quiet = 4
        val n = qr.size + quiet * 2
        val cell = size.minDimension / n
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) {
                    drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

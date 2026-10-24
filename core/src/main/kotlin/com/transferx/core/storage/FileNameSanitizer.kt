package com.transferx.core.storage

import java.nio.file.Path
import java.text.Normalizer

/**
 * Converts names chosen by a remote peer into names that are safe on every target
 * filesystem and can't escape the destination directory.
 */
object FileNameSanitizer {
    const val PARTIAL_DIR = ".transferx-partial"
    private const val MAX_NAME_BYTES = 200
    private const val MAX_DEPTH = 32
    private val reservedWindows = Regex("^(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³])(\\..*)?$", RegexOption.IGNORE_CASE)
    private const val ILLEGAL = "<>:\"/\\|?*"

    /** Returns a safe single path segment, or null if nothing usable remains. */
    fun sanitizeSegment(raw: String): String? {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFC)
        s = buildString(s.length) {
            for (c in s) {
                when {
                    c.code < 0x20 || c.code == 0x7f -> {} // control characters, including NUL
                    c in ILLEGAL -> append('_')
                    else -> append(c)
                }
            }
        }
        s = s.trim().trimEnd('.', ' ')
        if (s.isEmpty() || s == "." || s == "..") return null
        if (s.all { it == '.' }) return null
        if (reservedWindows.matches(s)) s = "_$s"
        if (s.equals(PARTIAL_DIR, ignoreCase = true)) s = "_$s"
        return truncateUtf8(s, MAX_NAME_BYTES)
    }

    /** Sanitizes a file name. Unusable names become "file". */
    fun sanitizeFileName(raw: String): String {
        // A peer may send a "name" containing separators: keep only the last segment.
        val last = raw.split('/', '\\').lastOrNull { it.isNotBlank() } ?: ""
        return sanitizeSegment(last) ?: "file"
    }

    /**
     * Sanitizes a '/'-separated relative folder path. Returns null if the path is
     * malicious (contains `..`, is absolute, has a drive letter, or is too deep) so the
     * caller can reject the file instead of guessing.
     */
    fun sanitizeRelativeDir(raw: String): List<String>? {
        if (raw.isEmpty()) return emptyList()
        if (raw.startsWith("/") || raw.startsWith("\\")) return null
        if (raw.length >= 2 && raw[1] == ':') return null // C:\…
        val parts = raw.split('/', '\\').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it.trim() == ".." }) return null
        if (parts.size > MAX_DEPTH) return null
        return parts.map { sanitizeSegment(it) ?: return null }
    }

    /**
     * Resolves [segments]/[name] under [base] and verifies the result is still inside
     * [base]. This is defence in depth: sanitized segments can't contain separators.
     */
    fun resolveInside(base: Path, segments: List<String>, name: String): Path {
        val root = base.toAbsolutePath().normalize()
        var p = root
        for (seg in segments) p = p.resolve(seg)
        p = p.resolve(name).normalize()
        require(p.startsWith(root) && p != root) { "Path escapes destination" }
        return p
    }

    /** "name (1).ext", "name (2).ext", … for conflict resolution by renaming. */
    fun numberedVariant(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        val hasExt = dot > 0 && dot >= name.length - 16
        val stem = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        return truncateUtf8("$stem ($n)", MAX_NAME_BYTES - ext.toByteArray().size) + ext
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 16) s.substring(dot) else ""
        val stem = if (ext.isEmpty()) s else s.substring(0, dot)
        val budget = maxBytes - ext.toByteArray(Charsets.UTF_8).size
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > budget) break
            sb.appendCodePoint(cp)
            used += bytes
            i += chars
        }
        return sb.toString().trimEnd('.', ' ') + ext
    }
}

package com.transferx.core.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Hashing {
    const val SHA256_LEN = 32

    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun sha256(data: ByteArray, off: Int = 0, len: Int = data.size): ByteArray =
        sha256().apply { update(data, off, len) }.digest()

    /** Constant-time comparison; never short-circuits on the first differing byte. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/**
 * TransferX's file integrity digest: SHA-256 over the concatenation of the SHA-256 of
 * every chunk (a two-level hash list). With the chunk size fixed per session and the file
 * size checked separately, it's as collision-resistant as SHA-256 of the file. It lets
 * the sender hash each byte once, and the same structure verifies resumed prefixes.
 * Note it isn't equal to `sha256sum` of the file.
 */
object FileDigest {
    fun of(input: java.io.InputStream, chunkSize: Int): String {
        val list = Hashing.sha256()
        val buf = ByteArray(chunkSize)
        while (true) {
            var n = 0
            while (n < chunkSize) {
                val r = input.read(buf, n, chunkSize - n)
                if (r < 0) break
                n += r
            }
            if (n == 0) break
            list.update(Hashing.sha256(buf, 0, n))
            if (n < chunkSize) break
        }
        return Hex.encode(list.digest())
    }
}

object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex" }
            ((hi shl 4) or lo).toByte()
        }
    }
}

object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
}

object Ids {
    private val random = SecureRandom()
    private val ID_PATTERN = Regex("^[A-Za-z0-9_-]{8,64}$")

    /** 128-bit random identifier, URL/filename safe. */
    fun newId(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        return Base64Url.encode(b)
    }

    /** Identifiers received from peers are used in file names, so they are strictly validated. */
    fun isValid(id: String): Boolean = ID_PATTERN.matches(id)
}

package com.transferx.core

import com.transferx.core.discovery.NetUtils
import com.transferx.core.discovery.RateLimiter
import com.transferx.core.protocol.ErrorCode
import com.transferx.core.protocol.FileMeta
import com.transferx.core.protocol.Frame
import com.transferx.core.protocol.FramedConnection
import com.transferx.core.protocol.Offer
import com.transferx.core.protocol.TransferException
import com.transferx.core.security.DeviceIdentity
import com.transferx.core.security.PairingCodeManager
import com.transferx.core.security.PairingCodes
import com.transferx.core.security.TlsContext
import com.transferx.core.security.TlsStream
import com.transferx.core.storage.FileNameSanitizer
import com.transferx.core.storage.IoErrorClassifier
import com.transferx.core.storage.NioStorageBackend
import com.transferx.core.storage.PlannedFile
import com.transferx.core.storage.StoragePreflight
import com.transferx.core.support.FaultyStorage
import com.transferx.core.transfer.PartialFile
import com.transferx.core.util.Hashing
import com.transferx.core.util.SpeedMeter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramingTest {
    @Test
    fun `control and data frames round-trip`() {
        val out = ByteArrayOutputStream()
        val w = FramedConnection(ByteArrayInputStream(ByteArray(0)), out)
        val offer = Offer("session-0001", 65536, listOf(FileMeta("file-00001", "a.txt", "", 3)))
        w.send(offer)
        val data = byteArrayOf(1, 2, 3)
        w.sendData(7, 65536L, Hashing.sha256(data), data, 3)
        val r = FramedConnection(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        assertEquals(offer, (r.readFrame() as Frame.Control).message)
        val d = r.readFrame() as Frame.Data
        assertEquals(7, d.fileIndex)
        assertEquals(65536L, d.offset)
        assertContentEquals(data, d.data.copyOf(d.length))
    }

    @Test
    fun `oversized frames are rejected before allocation`() {
        val bytes = ByteArrayOutputStream().apply {
            val d = java.io.DataOutputStream(this)
            d.writeInt(Int.MAX_VALUE); d.writeByte(1)
        }.toByteArray()
        val r = FramedConnection(ByteArrayInputStream(bytes), ByteArrayOutputStream(), maxControlBytes = 1024)
        val e = assertFailsWith<TransferException> { r.readFrame() }
        assertEquals(ErrorCode.PROTOCOL_ERROR, e.code)
    }

    @Test
    fun `malformed json is a protocol error`() {
        val bytes = ByteArrayOutputStream().apply {
            val d = java.io.DataOutputStream(this)
            val junk = "{not json".toByteArray()
            d.writeInt(1 + junk.size); d.writeByte(1); d.write(junk)
        }.toByteArray()
        val e = assertFailsWith<TransferException> { FramedConnection(ByteArrayInputStream(bytes), ByteArrayOutputStream()).readFrame() }
        assertEquals(ErrorCode.PROTOCOL_ERROR, e.code)
    }
}

class PairingTest {
    @Test
    fun `codes are 60-bit and normalize user input`() {
        val code = PairingCodes.generate()
        assertEquals(12, code.length)
        assertEquals(code, PairingCodes.normalize(PairingCodes.format(code).lowercase()))
        assertEquals("0111ABCDEFGH", PairingCodes.normalize("oIl1-abcd efgh"))
        assertNull(PairingCodes.normalize("too-short"))
        assertNull(PairingCodes.normalize("UUUU-UUUU-UUUU")) // U isn't in Crockford base32
    }

    @Test
    fun `proof binds both certificate fingerprints and the role`() {
        val a = "aa".repeat(32)
        val b = "bb".repeat(32)
        val p = PairingCodes.proof("ABCDEFGHJKMN", PairingCodes.Role.CLIENT, a, b)
        assertEquals(p, PairingCodes.proof("ABCDEFGHJKMN", PairingCodes.Role.CLIENT, a, b))
        assertNotEquals(p, PairingCodes.proof("ABCDEFGHJKMN", PairingCodes.Role.SERVER, a, b))
        assertNotEquals(p, PairingCodes.proof("ABCDEFGHJKMN", PairingCodes.Role.CLIENT, a, "cc".repeat(32)))
        assertNotEquals(p, PairingCodes.proof("ABCDEFGHJKMP", PairingCodes.Role.CLIENT, a, b))
    }

    @Test
    fun `codes expire, rotate after use, and lock after max attempts`() {
        var now = 1_000L
        val m = PairingCodeManager(ttlMs = 60_000, maxAttempts = 3) { now }
        val c1 = m.activeCode()
        assertEquals(c1, m.activeCode())
        now += 61_000
        val c2 = m.activeCode()
        assertNotEquals(c1.code, c2.code, "expired code must be replaced")
        assertEquals(2, m.verify { false }.attemptsRemaining)
        assertEquals(1, m.verify { false }.attemptsRemaining)
        assertEquals(0, m.verify { false }.attemptsRemaining)
        assertNotEquals(c2.code, m.current.value?.code, "code invalidated after max attempts")
        val c3 = m.activeCode()
        assertEquals(c3.code, m.verify { it == c3.code }.code)
        assertNotEquals(c3.code, m.current.value?.code, "code rotated after successful use")
    }
}

class SanitizerTest {
    @Test
    fun `path traversal and absolute paths are refused`() {
        assertNull(FileNameSanitizer.sanitizeRelativeDir("../outside"))
        assertNull(FileNameSanitizer.sanitizeRelativeDir("a/../../b"))
        assertNull(FileNameSanitizer.sanitizeRelativeDir("/etc"))
        assertNull(FileNameSanitizer.sanitizeRelativeDir("\\\\server\\share"))
        assertNull(FileNameSanitizer.sanitizeRelativeDir("C:\\Windows"))
        assertEquals(listOf("Photos", "2024"), FileNameSanitizer.sanitizeRelativeDir("Photos/./2024/"))
    }

    @Test
    fun `names are made safe on every filesystem`() {
        assertEquals("evil.txt", FileNameSanitizer.sanitizeFileName("../../evil.txt"))
        assertEquals("a_b__c_.txt", FileNameSanitizer.sanitizeFileName("a<b>:c?.txt"))
        assertEquals("_CON.txt", FileNameSanitizer.sanitizeFileName("CON.txt"))
        assertEquals("_nul", FileNameSanitizer.sanitizeFileName("nul"))
        assertEquals("file", FileNameSanitizer.sanitizeFileName(".."))
        assertEquals("trailing", FileNameSanitizer.sanitizeFileName("trailing. . "))
        assertEquals("ctrl.txt", FileNameSanitizer.sanitizeFileName("c\u0000t\u0001rl.txt"))
        assertEquals("_.transferx-partial", FileNameSanitizer.sanitizeFileName(".transferx-partial"))
        val long = "x".repeat(400) + ".jpeg"
        val s = FileNameSanitizer.sanitizeFileName(long)
        assertTrue(s.toByteArray().size <= 200 && s.endsWith(".jpeg"))
        assertEquals("photo (2).jpg", FileNameSanitizer.numberedVariant("photo.jpg", 2))
    }

    @Test
    fun `resolveInside never escapes the base`(@TempDir dir: Path) {
        assertFailsWith<IllegalArgumentException> { FileNameSanitizer.resolveInside(dir, listOf(".."), "x") }
        assertTrue(FileNameSanitizer.resolveInside(dir, listOf("a"), "b.txt").startsWith(dir))
    }
}

class StorageTest {
    @Test
    fun `preflight separates files that fit from files that don't`(@TempDir dir: Path) {
        val fs = FaultyStorage(dir).apply { quota.set(1_000_000) }
        val pre = StoragePreflight(fs, reserveBytes = 10_000, chunkSize = 64 * 1024)
            .check(dir, listOf(PlannedFile("small", 200_000), PlannedFile("big", 5_000_000), PlannedFile("medium", 500_000)))
        assertTrue(pre.writable)
        assertEquals(listOf("small", "medium"), pre.fittingFileIds)
        assertEquals(listOf("big"), pre.nonFittingFileIds)
        assertFalse(pre.sufficient)
        assertTrue(pre.requiredBytes > 5_700_000)
        assertTrue(pre.shortfallBytes!! > 0)
    }

    @Test
    fun `resumed bytes reduce the requirement`(@TempDir dir: Path) {
        val fs = FaultyStorage(dir).apply { quota.set(1_000_000) }
        val pre = StoragePreflight(fs, 10_000, 64 * 1024).check(dir, listOf(PlannedFile("f", 1_500_000, alreadyHave = 1_000_000)))
        assertEquals(listOf("f"), pre.fittingFileIds)
    }

    @Test
    fun `unwritable destination is reported`(@TempDir dir: Path) {
        val fs = FaultyStorage(dir).apply { writable.set(false) }
        val pre = StoragePreflight(fs, 0, 65536).check(dir, listOf(PlannedFile("a", 10)))
        assertFalse(pre.writable)
        assertTrue(pre.fittingFileIds.isEmpty())
    }

    @Test
    fun `FAT32 4 GiB limit is detected`() {
        assertEquals(4L * 1024 * 1024 * 1024 - 1, StoragePreflight.maxFileSizeFor("vfat"))
        assertEquals(Long.MAX_VALUE, StoragePreflight.maxFileSizeFor("exfat"))
        assertEquals(Long.MAX_VALUE, StoragePreflight.maxFileSizeFor(null))
    }

    @Test
    fun `io errors are classified per platform message`() {
        assertEquals(ErrorCode.STORAGE_FULL, IoErrorClassifier.classifyWrite(IOException("No space left on device")))
        assertEquals(ErrorCode.STORAGE_FULL, IoErrorClassifier.classifyWrite(IOException("There is not enough space on the disk")))
        assertEquals(ErrorCode.STORAGE_FULL, IoErrorClassifier.classifyWrite(IOException("write failed: EDQUOT (Disk quota exceeded)")))
        assertEquals(ErrorCode.PERMISSION_DENIED, IoErrorClassifier.classifyWrite(AccessDeniedException("/x")))
        assertEquals(ErrorCode.PERMISSION_DENIED, IoErrorClassifier.classifyWrite(IOException("Read-only file system")))
        assertEquals(ErrorCode.PERMISSION_DENIED, IoErrorClassifier.classifyWrite(SecurityException("revoked")))
        assertEquals(ErrorCode.STORAGE_FULL, IoErrorClassifier.classifyWrite(IOException("weird"), usableSpaceAfter = 10))
        assertEquals(ErrorCode.WRITE_FAILED, IoErrorClassifier.classifyWrite(IOException("weird"), usableSpaceAfter = 10_000_000))
    }
}

class PartialFileTest {
    private val chunk = 16 * 1024

    private fun writeChunks(pf: PartialFile, data: ByteArray, upTo: Int) {
        var off = 0
        while (off < upTo) {
            val n = minOf(chunk, data.size - off)
            val piece = data.copyOfRange(off, off + n)
            pf.writeChunk(off.toLong(), piece, n, Hashing.sha256(piece))
            off += n
        }
    }

    @Test
    fun `checkpoint survives reload and truncates the unverified tail`(@TempDir dir: Path) {
        val fs = NioStorageBackend()
        val data = ByteArray(100_000).also { Random(1).nextBytes(it) }
        val pf = PartialFile(fs, dir, "file-aaaaaaa", data.size.toLong(), chunk)
        pf.resetFiles()
        writeChunks(pf, data, 3 * chunk)
        pf.checkpoint()
        writeChunks2(pf, data, 3 * chunk, 5 * chunk) // written but never checkpointed
        pf.close()
        val reloaded = PartialFile(fs, dir, "file-aaaaaaa", data.size.toLong(), chunk)
        val v = reloaded.loadAndValidate()
        assertEquals(3L * chunk, v.verifiedOffset)
        assertEquals(3L * chunk, Files.size(reloaded.partPath))
        assertEquals(pf.prefixDigest(), v.prefixDigest)
    }

    private fun writeChunks2(pf: PartialFile, data: ByteArray, from: Int, to: Int) {
        var off = from
        while (off < to) {
            val piece = data.copyOfRange(off, off + chunk)
            pf.writeChunk(off.toLong(), piece, chunk, Hashing.sha256(piece))
            off += chunk
        }
    }

    @Test
    fun `corrupted chunk on disk is detected and truncated`(@TempDir dir: Path) {
        val fs = NioStorageBackend()
        val data = ByteArray(5 * chunk).also { Random(2).nextBytes(it) }
        val pf = PartialFile(fs, dir, "file-bbbbbbb", data.size.toLong(), chunk)
        pf.resetFiles()
        writeChunks(pf, data, data.size)
        pf.checkpoint()
        pf.close()
        // Flip one byte inside chunk 2 behind TransferX's back.
        java.io.RandomAccessFile(pf.partPath.toFile(), "rw").use { it.seek(2L * chunk + 5); it.write(0x55 xor data[2 * chunk + 5].toInt()) }
        val v = PartialFile(fs, dir, "file-bbbbbbb", data.size.toLong(), chunk).loadAndValidate()
        assertEquals(2L * chunk, v.verifiedOffset)
        assertNotNull(v.reason)
    }

    @Test
    fun `damaged checkpoint means restart from zero`(@TempDir dir: Path) {
        val fs = NioStorageBackend()
        val data = ByteArray(3 * chunk).also { Random(3).nextBytes(it) }
        val pf = PartialFile(fs, dir, "file-ccccccc", data.size.toLong(), chunk)
        pf.resetFiles(); writeChunks(pf, data, data.size); pf.checkpoint(); pf.close()
        Files.writeString(dir.resolve("file-ccccccc.ckpt"), "{garbage")
        val v = PartialFile(fs, dir, "file-ccccccc", data.size.toLong(), chunk).loadAndValidate()
        assertEquals(0, v.verifiedOffset)
        assertFalse(Files.exists(dir.resolve("file-ccccccc.part")))
    }
}

class NetTest {
    @Test
    fun `only local network addresses are accepted by default`() {
        fun a(s: String) = InetAddress.getByName(s)
        assertTrue(NetUtils.isLocalNetworkAddress(a("192.168.1.20")))
        assertTrue(NetUtils.isLocalNetworkAddress(a("10.1.2.3")))
        assertTrue(NetUtils.isLocalNetworkAddress(a("172.20.0.1")))
        assertTrue(NetUtils.isLocalNetworkAddress(a("169.254.10.10")))
        assertTrue(NetUtils.isLocalNetworkAddress(a("127.0.0.1")))
        assertTrue(NetUtils.isLocalNetworkAddress(a("fd12::1")))
        assertTrue(NetUtils.isLocalNetworkAddress(a("::ffff:192.168.0.5")))
        assertFalse(NetUtils.isLocalNetworkAddress(a("8.8.8.8")))
        assertFalse(NetUtils.isLocalNetworkAddress(a("2001:4860::8888")))
        assertFalse(NetUtils.isOnLocalSubnet("203.0.113.7")!!)
    }

    @Test
    fun `rate limiter bounds attempts per host`() {
        var now = 0L
        val rl = RateLimiter(3, 1_000) { now }
        repeat(3) { assertTrue(rl.tryAcquire("h")) }
        assertFalse(rl.tryAcquire("h"))
        assertTrue(rl.tryAcquire("other"))
        now += 1_001
        assertTrue(rl.tryAcquire("h"))
    }

    @Test
    fun `speed meter reports nothing until it has real samples`() {
        var t = 0L
        val m = SpeedMeter(nanoTime = { t })
        assertNull(m.bytesPerSecond())
        m.record(0); t += 1_000_000_000; m.record(10_000_000)
        assertEquals(10_000_000, m.bytesPerSecond())
        assertEquals(3, m.etaSeconds(25_000_000))
    }
}

class TlsStreamTest {
    /** The SSLEngine adapter used for Bluetooth, exercised over a plain TCP socket pair. */
    @Test
    fun `mutual TLS over an arbitrary stream`() {
        val serverId = DeviceIdentity.generate()
        val clientId = DeviceIdentity.generate()
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val payload = ByteArray(3_000_000).also { Random(9).nextBytes(it) }
        var serverSaw: String? = null
        var echoed: ByteArray? = null
        val server = thread {
            val s = ss.accept()
            val t = TlsStream(TlsContext(serverId).newEngine(false), s.getInputStream(), s.getOutputStream()) { s.close() }
            t.handshake()
            serverSaw = TlsContext.peerFingerprint(t.session)
            val buf = ByteArray(payload.size)
            var n = 0
            while (n < buf.size) {
                val r = t.input.read(buf, n, buf.size - n); if (r < 0) break; n += r
            }
            t.output.write(Hashing.sha256(buf, 0, n)); t.output.flush()
            echoed = buf
        }
        val c = Socket(InetAddress.getLoopbackAddress(), ss.localPort)
        val t = TlsStream(TlsContext(clientId).newEngine(true), c.getInputStream(), c.getOutputStream()) { c.close() }
        t.handshake()
        assertEquals(serverId.fingerprintHex, TlsContext.peerFingerprint(t.session))
        t.output.write(payload); t.output.flush()
        val digest = ByteArray(32)
        var n = 0
        while (n < 32) {
            val r = t.input.read(digest, n, 32 - n); if (r < 0) break; n += r
        }
        server.join(10_000)
        assertEquals(clientId.fingerprintHex, serverSaw)
        assertContentEquals(Hashing.sha256(payload), digest)
        assertContentEquals(payload, echoed)
        t.close(); ss.close()
    }
}

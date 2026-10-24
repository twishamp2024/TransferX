package com.transferx.core.security

import com.transferx.core.util.AtomicFiles
import com.transferx.core.util.Base64Url
import com.transferx.core.util.Hashing
import com.transferx.core.util.Hex
import com.transferx.core.util.Ids
import com.transferx.core.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * The device's long-term identity: an EC P-256 key pair and a self-signed certificate.
 * Peers identify this device by the SHA-256 fingerprint of the certificate.
 *
 * Generated on first launch and stored in the app's private data directory. No key
 * material is hardcoded or shared between installations.
 */
class DeviceIdentity(
    val deviceId: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val fingerprint: ByteArray = Hashing.sha256(certificate.encoded)
    val fingerprintHex: String = Hex.encode(fingerprint)

    /** Human-comparable short form, e.g. for "verify this device" displays. */
    val shortFingerprint: String get() = fingerprintHex.take(16).chunked(4).joinToString(" ").uppercase()

    @Serializable
    private data class Meta(val deviceId: String, val keystoreSecret: String, val createdAt: Long)

    companion object {
        private const val TAG = "Identity"
        private const val ALIAS = "transferx"
        private val json = Json { ignoreUnknownKeys = true }

        fun loadOrCreate(dir: Path): DeviceIdentity {
            Files.createDirectories(dir)
            val metaPath = dir.resolve("identity.json")
            val ksPath = dir.resolve("identity.p12")
            val existing = runCatching { load(metaPath, ksPath) }.onFailure {
                if (Files.exists(metaPath)) Log.w(TAG, "Stored identity unreadable; generating a new one", it)
            }.getOrNull()
            if (existing != null) return existing
            return create(metaPath, ksPath)
        }

        private fun load(metaPath: Path, ksPath: Path): DeviceIdentity? {
            val metaBytes = AtomicFiles.readOrNull(metaPath) ?: return null
            val meta = json.decodeFromString(Meta.serializer(), String(metaBytes, Charsets.UTF_8))
            val pw = meta.keystoreSecret.toCharArray()
            val ks = KeyStore.getInstance("PKCS12")
            ByteArrayInputStream(Files.readAllBytes(ksPath)).use { ks.load(it, pw) }
            val key = ks.getKey(ALIAS, pw) as PrivateKey
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            return DeviceIdentity(meta.deviceId, key, cert)
        }

        private fun create(metaPath: Path, ksPath: Path): DeviceIdentity {
            val random = SecureRandom()
            val deviceId = Ids.newId()
            val identity = generate(deviceId, random)
            val secretBytes = ByteArray(24).also(random::nextBytes)
            val secret = Base64Url.encode(secretBytes)
            val ks = KeyStore.getInstance("PKCS12")
            ks.load(null, null)
            ks.setKeyEntry(ALIAS, identity.privateKey, secret.toCharArray(), arrayOf(identity.certificate))
            val bos = ByteArrayOutputStream()
            ks.store(bos, secret.toCharArray())
            AtomicFiles.write(ksPath, bos.toByteArray())
            val meta = Meta(deviceId, secret, System.currentTimeMillis())
            AtomicFiles.write(metaPath, json.encodeToString(Meta.serializer(), meta).toByteArray(Charsets.UTF_8))
            restrictPermissions(ksPath)
            restrictPermissions(metaPath)
            Log.i(TAG, "Created device identity ${Log.shortFp(identity.fingerprintHex)}")
            return identity
        }

        /** Generates a fresh in-memory identity (also used by tests). */
        fun generate(deviceId: String = Ids.newId(), random: SecureRandom = SecureRandom()): DeviceIdentity {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), random)
            val kp = kpg.generateKeyPair()
            val subject = X500Name("CN=TransferX $deviceId")
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24L * 3600 * 1000)
            val notAfter = Date(now + 20L * 365 * 24 * 3600 * 1000)
            val serial = BigInteger(63, random).add(BigInteger.ONE)
            val builder = JcaX509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, kp.public)
            val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return DeviceIdentity(deviceId, kp.private, cert)
        }

        private fun restrictPermissions(path: Path) {
            // Owner-only on POSIX filesystems; on Windows the per-user profile ACL applies.
            runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        }
    }
}

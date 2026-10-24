package com.transferx.core.security

import com.transferx.core.util.Hashing
import com.transferx.core.util.Hex
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedTrustManager

/**
 * TLS configuration. Both sides present their self-signed device certificate (mutual
 * TLS). Certificate *trust* is decided by the engine right after the handshake, by
 * comparing the peer fingerprint against the trust store or the fingerprint pinned from
 * a QR code. That's why the trust manager here only checks that a certificate is present.
 */
class TlsContext(identity: DeviceIdentity) {
    val sslContext: SSLContext

    init {
        val pw = CharArray(0)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("id", identity.privateKey, pw, arrayOf(identity.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pw)
        sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, arrayOf(PinnedLaterTrustManager), SecureRandom())
    }

    fun wrapServer(raw: Socket): SSLSocket {
        val s = sslContext.socketFactory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        s.useClientMode = false
        s.needClientAuth = true
        configure(s.supportedProtocols) { s.enabledProtocols = it }
        return s
    }

    fun wrapClient(raw: Socket, host: String, port: Int): SSLSocket {
        val s = sslContext.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.useClientMode = true
        configure(s.supportedProtocols) { s.enabledProtocols = it }
        return s
    }

    fun newEngine(clientMode: Boolean): SSLEngine {
        val e = sslContext.createSSLEngine()
        e.useClientMode = clientMode
        if (!clientMode) e.needClientAuth = true
        configure(e.supportedProtocols) { e.enabledProtocols = it }
        return e
    }

    private inline fun configure(supported: Array<String>, apply: (Array<String>) -> Unit) {
        val wanted = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        require(wanted.isNotEmpty()) { "Platform supports neither TLS 1.3 nor 1.2" }
        apply(wanted.toTypedArray())
    }

    companion object {
        fun peerFingerprint(session: SSLSession): String {
            val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("Peer presented no X.509 certificate")
            return Hex.encode(Hashing.sha256(cert.encoded))
        }
    }
}

private object PinnedLaterTrustManager : X509ExtendedTrustManager() {
    private fun check(chain: Array<out X509Certificate>?) {
        if (chain.isNullOrEmpty()) throw CertificateException("No certificate presented")
        chain[0].checkValidity()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

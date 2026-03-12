package com.phonepclink.windows

import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Stores and retrieves the TLS certificate sent by the phone during pairing.
 *
 * After the user pairs once (QR or Auto-Discover), the phone's public cert is
 * saved to ~/.streambridge/trusted_cert.json.  Every subsequent connection uses
 * a cert-pinned SSLContext that ONLY trusts that exact certificate — no CA chain,
 * no wildcards.
 *
 * Security model (TOFU — Trust On First Use):
 *   • First connection before pairing → TLS with trust-all (still encrypted)
 *   • After pairing → TLS with cert pinning (encrypted + authenticated)
 *   • An attacker on the LAN sees only ciphertext even before pinning
 */
object CertStore {

    private val storeFile: File =
        File(System.getProperty("user.home"), ".streambridge/trusted_cert.json")

    // ── Persistence ─────────────────────────────────────────────────────────────

    /**
     * Saves the phone's certificate (base64 DER string, as sent during pairing).
     * Overwrites any previously stored certificate — i.e. re-pairing with a new
     * phone automatically replaces the old trusted cert.
     */
    fun saveCert(base64: String) {
        storeFile.parentFile?.mkdirs()
        val fingerprint = try {
            val bytes = Base64.getDecoder().decode(base64)
            val cert  = CertificateFactory.getInstance("X.509")
                .generateCertificate(bytes.inputStream()) as X509Certificate
            computeFingerprint(cert)
        } catch (_: Exception) { "unknown" }

        storeFile.writeText(
            JSONObject().apply {
                put("cert",        base64)
                put("fingerprint", fingerprint)   // human-readable, not used for trust
            }.toString(2)
        )
        println("CertStore: saved phone cert. Fingerprint: $fingerprint")
    }

    fun hasCert(): Boolean = storeFile.exists() && storeFile.length() > 0

    private fun loadBase64(): String? {
        if (!hasCert()) return null
        return try {
            JSONObject(storeFile.readText()).getString("cert")
        } catch (_: Exception) { null }
    }

    fun getStoredFingerprint(): String? {
        if (!hasCert()) return null
        return try {
            JSONObject(storeFile.readText()).optString("fingerprint", null)
        } catch (_: Exception) { null }
    }

    // ── SSLContext builders ──────────────────────────────────────────────────────

    /**
     * Builds an SSLContext that trusts ONLY the stored phone certificate.
     * Returns null if no cert has been saved yet (not yet paired).
     */
    fun buildPinnedSSLContext(): SSLContext? {
        val base64 = loadBase64() ?: return null
        return try {
            val certBytes = Base64.getDecoder().decode(base64)
            val cert      = CertificateFactory.getInstance("X.509")
                .generateCertificate(certBytes.inputStream()) as X509Certificate

            // Build a KeyStore containing only this one certificate
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).also {
                it.load(null, null)
                it.setCertificateEntry("phone_cert", cert)
            }

            val tmf = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .also { it.init(ks) }

            SSLContext.getInstance("TLS").also {
                it.init(null, tmf.trustManagers, SecureRandom())
            }
        } catch (e: Exception) {
            println("CertStore: failed to build pinned SSLContext — ${e.message}")
            null
        }
    }

    /**
     * Returns the X509TrustManager from a pinned SSLContext — needed by OkHttp
     * which requires the trust manager to be passed separately.
     */
    fun buildPinnedTrustManager(): X509TrustManager? {
        val base64 = loadBase64() ?: return null
        return try {
            val certBytes = Base64.getDecoder().decode(base64)
            val cert      = CertificateFactory.getInstance("X.509")
                .generateCertificate(certBytes.inputStream()) as X509Certificate

            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).also {
                it.load(null, null)
                it.setCertificateEntry("phone_cert", cert)
            }
            TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .also { it.init(ks) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
        } catch (e: Exception) {
            println("CertStore: failed to build trust manager — ${e.message}")
            null
        }
    }

    /**
     * An SSLContext that accepts ANY certificate — used only before the first
     * pairing, so the initial connection is still encrypted even without pinning.
     * After pairing the stored cert is used for all subsequent connections.
     */
    fun buildTrustAllSSLContext(): SSLContext {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").also { it.init(null, trustAll, SecureRandom()) }
    }

    fun buildTrustAllTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private fun computeFingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /**
     * An SSLSocketFactory wrapper that disables TLS hostname verification on every
     * socket it creates.  This is required because the phone's self-signed cert has
     * CN=StreamBridge but we connect via IP address — standard hostname verification
     * would reject it.  We achieve authentication through cert pinning instead.
     */
    class NoHostnameVerificationSocketFactory(
        private val delegate: SSLSocketFactory
    ) : SSLSocketFactory() {

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        // java-websocket calls createSocket() with NO arguments first to get an
        // unconnected socket, then connects it manually.  The base SocketFactory
        // implementation of this method throws "Unconnected sockets not implemented"
        // by default.  Without this override the WebSocket connection always fails
        // with exactly that error, even though the cert and SSLContext are correct.
        override fun createSocket(): java.net.Socket = patch(delegate.createSocket())

        override fun createSocket(s: java.net.Socket, h: String, port: Int, autoClose: Boolean) =
            patch(delegate.createSocket(s, h, port, autoClose))

        override fun createSocket(host: String, port: Int) =
            patch(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, local: InetAddress, lp: Int) =
            patch(delegate.createSocket(host, port, local, lp))

        override fun createSocket(host: InetAddress, port: Int) =
            patch(delegate.createSocket(host, port))

        override fun createSocket(addr: InetAddress, port: Int, local: InetAddress, lp: Int) =
            patch(delegate.createSocket(addr, port, local, lp))

        private fun patch(socket: java.net.Socket): java.net.Socket {
            (socket as? SSLSocket)?.apply {
                // Setting endpointIdentificationAlgorithm to null (or "") disables
                // hostname verification while keeping full TLS encryption and cert
                // validation (our custom TrustManager handles that part).
                sslParameters = sslParameters.also {
                    it.endpointIdentificationAlgorithm = ""
                }
            }
            return socket
        }
    }
}

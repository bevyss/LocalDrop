package org.localdrop.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** Private key remains in Android Keystore; only peer certificate hashes are stored in preferences. */
class LocalCredentials(context: Context, deviceId: String) {
    private val preferences = context.getSharedPreferences("trusted_peers", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val alias = "localdrop-tls-ec-v1"

    init {
        if (!keyStore.containsAlias(alias)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=LocalDrop-$deviceId"))
                .setCertificateSerialNumber(BigInteger(1, java.security.SecureRandom().generateSeed(16)))
                .setCertificateNotBefore(Date(now - 86_400_000))
                .setCertificateNotAfter(Date(now + 10L * 365 * 86_400_000))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(spec) }.generateKeyPair()
        }
        // Migrate development RSA identities. Rotation requires a new code comparison.
        if (keyStore.containsAlias("localdrop-tls-v1")) keyStore.deleteEntry("localdrop-tls-v1")
        if (keyStore.containsAlias("localdrop-tls-v2")) keyStore.deleteEntry("localdrop-tls-v2")
    }

    val certificate: X509Certificate get() = keyStore.getCertificate(alias) as X509Certificate

    fun context(): SSLContext {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, null) }
        // Provisional self-signed TLS: application verification and pinning follow before metadata.
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "Missing client certificate" }
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                require(chain.isNotEmpty()) { "Missing server certificate" }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(keys.keyManagers, arrayOf<TrustManager>(trust), null)
        }
    }

    fun isTrusted(id: String, hash: String): Boolean = preferences.getString(id, null) == hash

    fun trust(id: String, hash: String) {
        check(preferences.edit().putString(id, hash).commit()) { "Could not store trusted peer" }
    }
}

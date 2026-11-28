package uk.beertool.push

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun unb64url(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trim().trimEnd('='))

// Web Push keys travel as raw P-256 values: a 65-byte uncompressed point for a public key, a 32-byte scalar for a private one.
internal object P256 {
    private val random = SecureRandom()

    val params: ECParameterSpec = (newKeyPair().public as ECPublicKey).params

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun publicKey(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "A P-256 public key is 65 bytes starting with 0x04." }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
    }

    fun privateKey(raw: ByteArray): PrivateKey {
        require(raw.size == 32) { "A P-256 private key is 32 bytes." }
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, raw), params))
    }

    fun raw(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
    }

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    private fun fixed(n: BigInteger): ByteArray = n.toByteArray().let { b ->
        if (b.size == 32) b else if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
    }
}

class VapidKeys(val publicKey: ByteArray, privateKey: ByteArray) {
    internal val signingKey: PrivateKey = P256.privateKey(privateKey)
    val publicKeyText get() = b64url(publicKey)

    init {
        val check = "beertool".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(signingKey); update(check); sign() }
        val matches = Signature.getInstance("SHA256withECDSA").run { initVerify(P256.publicKey(publicKey)); update(check); verify(signature) }
        require(matches) { "The VAPID public key does not belong to the private key." }
    }

    companion object {
        fun parse(publicKey: String, privateKey: String) = VapidKeys(unb64url(publicKey), unb64url(privateKey))
    }
}

// RFC 8292: the push service checks this token against the public key the subscription was made with.
internal fun vapidJwt(keys: VapidKeys, audience: String, subject: String, expires: Instant): String {
    val header = b64url("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
    val claims = b64url("""{"aud":"$audience","exp":${expires.epochSecond},"sub":"$subject"}""".toByteArray())
    val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
        initSign(keys.signingKey)
        update("$header.$claims".toByteArray())
        sign()
    }
    return "$header.$claims.${b64url(signature)}"
}

private const val RECORD_SIZE = 4096

// RFC 8291 with the aes128gcm coding of RFC 8188, in a single record; the salt and key pair are fresh for every message.
internal fun encryptPush(
    payload: ByteArray,
    subscriberKey: ByteArray,
    authSecret: ByteArray,
    serverKeys: KeyPair = P256.newKeyPair(),
    salt: ByteArray = P256.randomBytes(16),
): ByteArray {
    val serverPublic = P256.raw(serverKeys.public)
    val shared = KeyAgreement.getInstance("ECDH").run {
        init(serverKeys.private)
        doPhase(P256.publicKey(subscriberKey), true)
        generateSecret()
    }
    val ikm = hkdf(authSecret, shared, "WebPush: info".toByteArray() + 0 + subscriberKey + serverPublic, 32)
    val key = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + 0, 16)
    val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + 0, 12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    }
    val sealed = cipher.doFinal(payload + 2)
    require(sealed.size <= RECORD_SIZE) { "A push message must fit one ${RECORD_SIZE}-byte record." }
    val header = ByteBuffer.allocate(16 + 4 + 1 + serverPublic.size)
        .put(salt).putInt(RECORD_SIZE).put(serverPublic.size.toByte()).put(serverPublic).array()
    return header + sealed
}

// A single-block HKDF, which is all Web Push needs as no output is longer than one SHA-256.
internal fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmac(salt, ikm)
    return hmac(prk, info + 1).copyOf(length)
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

private operator fun ByteArray.plus(b: Int): ByteArray = this + byteArrayOf(b.toByte())

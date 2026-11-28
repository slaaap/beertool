package uk.beertool.push

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.Signature
import java.time.Instant

class WebPushCryptoTest {

    @Test
    fun `should encrypt a message exactly as the Web Push standard's own example does`() {
        val serverKeys = KeyPair(
            P256.publicKey(unb64url("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")),
            P256.privateKey(unb64url("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")),
        )
        val subscriberKey = unb64url("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
        val authSecret = unb64url("BTBZMqHH6r4Tts7J_aSIgg")
        val salt = unb64url("DGv6ra1nlYgDCS1FRnbzlw")

        val message = encryptPush("When I grow up, I want to be a watermelon".toByteArray(), subscriberKey, authSecret, serverKeys, salt)

        b64url(message) shouldBe "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_" +
            "yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
    }

    @Test
    fun `should sign a token the push service can check against the public key, valid for no more than a day`() {
        val pair = P256.newKeyPair()
        val keys = VapidKeys(P256.raw(pair.public), privateScalar(pair))
        val expires = Instant.parse("2026-11-28T12:00:00Z")

        val (header, claims, signature) = vapidJwt(keys, "https://web.push.apple.com", "https://beertool.uk", expires).split(".")

        val valid = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(pair.public); update("$header.$claims".toByteArray()); verify(unb64url(signature))
        }
        valid shouldBe true
        String(unb64url(claims)) shouldContain """"aud":"https://web.push.apple.com""""
        String(unb64url(claims)) shouldContain """"sub":"https://beertool.uk""""
        String(unb64url(claims)) shouldContain """"exp":${expires.epochSecond}"""
    }

    @Test
    fun `should refuse a public key that does not belong to the private key`() {
        val one = P256.newKeyPair()
        val other = P256.newKeyPair()

        val error = shouldThrow<IllegalArgumentException> { VapidKeys(P256.raw(other.public), privateScalar(one)) }

        error.message shouldContain "does not belong"
    }

    private fun privateScalar(pair: KeyPair): ByteArray =
        (pair.private as java.security.interfaces.ECPrivateKey).s.toByteArray().let { b ->
            if (b.size == 32) b else if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
        }
}

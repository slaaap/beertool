package uk.beertool.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Secrets {
    private val random = SecureRandom()

    // URL-safe, so a token fits a Telegram start parameter or an HTTP header unchanged.
    fun newToken(): String = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)

    fun sha256(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).joinToString("") { "%02x".format(it) }
}

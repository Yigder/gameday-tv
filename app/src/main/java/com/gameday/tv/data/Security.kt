package com.gameday.tv.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 password hashes. Passwords themselves are never stored. Pure JVM, unit tested. */
object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val PREFIX = "pbkdf2-sha256"

    fun hash(password: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt, iterations)
        val b64 = Base64.getEncoder()
        return "$PREFIX$$iterations$${b64.encodeToString(salt)}$${b64.encodeToString(key)}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val dec = Base64.getDecoder()
        val salt = runCatching { dec.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { dec.decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, derive(password, salt, iterations))
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * Encrypts small secrets (IPTV logins) with a key held in the Android Keystore, so they aren't
 * readable from the app's storage. Falls back to plain text on devices with a broken keystore.
 */
object Vault {
    private const val ALIAS = "gameday_vault"
    private const val PLAIN = "plain:"
    private const val SEALED = "v1:"

    fun seal(text: String): String = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        SEALED + Base64.getEncoder().encodeToString(iv + ct)
    } catch (_: Exception) {
        PLAIN + text
    }

    fun open(sealed: String?): String? {
        if (sealed == null) return null
        if (sealed.startsWith(PLAIN)) return sealed.removePrefix(PLAIN)
        if (!sealed.startsWith(SEALED)) return null
        return try {
            val raw = Base64.getDecoder().decode(sealed.removePrefix(SEALED))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}

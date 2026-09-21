package com.philkes.notallyx.utils.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * E2E payload encryption for the self-hosted sync MVP (Phase 7 F1).
 *
 * The server (WebDAV/Nextcloud) only ever sees opaque encrypted blobs — it never receives the key
 * or the plaintext. The key is derived from the user's sync password with PBKDF2-HmacSHA256 (random
 * per-payload salt) and payloads are sealed with AES-256-GCM. This deliberately reuses the platform
 * JCE (same primitives the app already trusts for SQLCipher/zip AES crypto) instead of pulling in a
 * new dependency — FOSS/F-Droid constraint.
 *
 * Wire format (v1): `SXSYNC1` ASCII magic (7 bytes) | 16-byte salt | 12-byte IV | ciphertext+tag.
 */
object SyncCrypto {

    const val PAYLOAD_MAGIC = "SXSYNC1"

    private const val MAGIC_LENGTH = 7
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 120_000

    fun deriveKey(password: CharArray, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val key = factory.generateSecret(spec)
        spec.clearPassword()
        return SecretKeySpec(key.encoded, "AES")
    }

    fun encrypt(plainText: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(password.toCharArray(), salt),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        val cipherText = cipher.doFinal(plainText)
        val magic = PAYLOAD_MAGIC.toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(magic.size + salt.size + iv.size + cipherText.size)
        magic.copyInto(payload)
        salt.copyInto(payload, magic.size)
        iv.copyInto(payload, magic.size + salt.size)
        cipherText.copyInto(payload, magic.size + salt.size + iv.size)
        return payload
    }

    fun decrypt(payload: ByteArray, password: String): ByteArray {
        val magicLength = PAYLOAD_MAGIC.length
        require(payload.size > magicLength + SALT_SIZE + IV_SIZE) { "Sync payload too short" }
        val magic = String(payload, 0, magicLength, Charsets.US_ASCII)
        require(magic == PAYLOAD_MAGIC) { "Unknown sync payload format: $magic" }
        val salt = payload.copyOfRange(magicLength, magicLength + SALT_SIZE)
        val iv = payload.copyOfRange(magicLength + SALT_SIZE, magicLength + SALT_SIZE + IV_SIZE)
        val cipherText = payload.copyOfRange(magicLength + SALT_SIZE + IV_SIZE, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password.toCharArray(), salt),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(cipherText)
    }
}

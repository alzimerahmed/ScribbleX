package com.philkes.notallyx.utils.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** E2E payload encryption round-trip tests (pure JVM, no Android dependencies). */
class SyncCryptoTest {

    @Test
    fun `encrypt then decrypt round trips`() {
        val plain = "hello sync world".toByteArray(Charsets.UTF_8)
        val payload = SyncCrypto.encrypt(plain, "correct horse battery staple")
        assertThat(payload).isNotEqualTo(plain)
        val decrypted = SyncCrypto.decrypt(payload, "correct horse battery staple")
        assertThat(decrypted).isEqualTo(plain)
    }

    @Test
    fun `empty plaintext round trips`() {
        val plain = ByteArray(0)
        val decrypted = SyncCrypto.decrypt(SyncCrypto.encrypt(plain, "pw"), "pw")
        assertThat(decrypted).isEqualTo(plain)
    }

    @Test
    fun `wrong password fails authentication`() {
        val payload = SyncCrypto.encrypt("secret".toByteArray(), "right")
        assertThrows(Exception::class.java) { SyncCrypto.decrypt(payload, "wrong") }
    }

    @Test
    fun `tampered payload fails authentication`() {
        val payload = SyncCrypto.encrypt("secret".toByteArray(), "pw")
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { SyncCrypto.decrypt(payload, "pw") }
    }

    @Test
    fun `same plaintext encrypts to different ciphertexts (random IV and salt)`() {
        val plain = "determinism check".toByteArray()
        val first = SyncCrypto.encrypt(plain, "pw")
        val second = SyncCrypto.encrypt(plain, "pw")
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `payload carries version magic`() {
        val payload = SyncCrypto.encrypt("x".toByteArray(), "pw")
        val magic = String(payload, 0, SyncCrypto.PAYLOAD_MAGIC.length, Charsets.US_ASCII)
        assertThat(magic).isEqualTo(SyncCrypto.PAYLOAD_MAGIC)
    }
}

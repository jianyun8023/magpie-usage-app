package com.jianyun.x2app.vpn

import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class VpnCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun roundTripUsesFreshIvAndDoesNotStorePlaintext() {
        val secret = "private-test-configuration".toByteArray()
        val first = VpnCipher.encrypt(secret, key)
        val second = VpnCipher.encrypt(secret, key)
        assertArrayEquals(secret, VpnCipher.decrypt(first, key))
        assertFalse(first.contentEquals(second))
        assertFalse(String(first, Charsets.ISO_8859_1).contains(String(secret)))
    }

    @Test fun rejectsTamperingAndWrongKey() {
        val encrypted = VpnCipher.encrypt("secret".toByteArray(), key)
        val changed = encrypted.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { VpnCipher.decrypt(changed, key) }
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertThrows(AEADBadTagException::class.java) { VpnCipher.decrypt(encrypted, wrongKey) }
    }

    @Test fun rejectsTruncationAndUnknownVersion() {
        assertThrows(IllegalArgumentException::class.java) { VpnCipher.decrypt(ByteArray(20), key) }
        val encrypted = VpnCipher.encrypt("secret".toByteArray(), key).apply { this[0] = 2 }
        assertThrows(IllegalArgumentException::class.java) { VpnCipher.decrypt(encrypted, key) }
    }
}

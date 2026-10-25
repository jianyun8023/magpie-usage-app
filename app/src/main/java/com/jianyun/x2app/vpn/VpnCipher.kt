package com.jianyun.x2app.vpn

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Version byte, 96-bit random IV, then authenticated ciphertext (including tag). */
internal object VpnCipher {
    fun encrypt(bytes: ByteArray, key: SecretKey): ByteArray {
        require(bytes.size <= MAX_CONFIG_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(bytes)
    }

    fun decrypt(encrypted: ByteArray, key: SecretKey): ByteArray {
        require(encrypted.size in 29..(MAX_CONFIG_BYTES + 29))
        require(encrypted[0] == 1.toByte())
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 1, 12))
            doFinal(encrypted, 13, encrypted.size - 13)
        }
    }
}

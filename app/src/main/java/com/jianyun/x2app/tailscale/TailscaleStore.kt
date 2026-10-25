package com.jianyun.x2app.tailscale

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.*

/** Native state never enters backup, logs, or plaintext preferences. Failed decryption is fatal. */
internal class TailscaleStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "tailscale-state.enc"))
    private val alias = "x2.tailscale.state.v1"
    private var values: MutableMap<String, JsonElement>? = null

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Tailscale 会话密钥不可用，无法解密会话" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun load(): MutableMap<String, JsonElement> {
        values?.let { return it }
        val result = if (!file.baseFile.exists()) mutableMapOf() else {
            val bytes = file.readFully()
            require(bytes.size >= 29 && bytes[0] == 1.toByte()) { "Tailscale 会话存储已损坏" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(alias.toByteArray())
            Json.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).decodeToString()).jsonObject.toMutableMap()
        }
        values = result
        return result
    }

    @Synchronized fun read(key: String): String = (load()[key] as? JsonPrimitive)?.content.orEmpty()
    @Synchronized fun keys(): String = JsonArray(load().keys.filter { it.startsWith("statestore-") }.map { JsonPrimitive(it.removePrefix("statestore-")) }).toString()

    @Synchronized fun write(name: String, value: String) {
        val next = load().toMutableMap()
        if (value.isEmpty()) next.remove(name) else next[name] = JsonPrimitive(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(alias.toByteArray())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(JsonObject(next).toString().toByteArray())
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
            values = next
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }
}

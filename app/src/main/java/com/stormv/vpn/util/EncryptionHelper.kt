package com.stormv.vpn.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

/**
 * AES-256-GCM шифрование через Android Keystore.
 * Ключ хранится в аппаратном защищённом хранилище (TEE/SE),
 * недоступен вне приложения даже при root-доступе.
 */
object EncryptionHelper {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "stormv_server_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }
    }

    private fun getOrCreateKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGen.generateKey()
    }

    /** Шифрует строку, возвращает Base64(IV + ciphertext) */
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // Формат: [12 байт IV][ciphertext]
        val combined = iv + cipherText
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** Дешифрует строку из Base64(IV + ciphertext) */
    fun decrypt(cipherBase64: String): String {
        val combined = Base64.decode(cipherBase64, Base64.NO_WRAP)
        val iv = combined.sliceArray(0..11)
        val cipherText = combined.sliceArray(12 until combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    /** Маскирует IP/UUID в строке для безопасного отображения */
    fun maskSensitive(text: String): String {
        var result = text
        // IPv4: 185.199.108.153 → 185.***.***.*
        result = result.replace(
            Regex("""\b(\d{1,3})\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
        ) { mr -> "${mr.groupValues[1]}.***.***.*" }
        // IPv6
        result = result.replace(
            Regex("""\b([0-9a-fA-F]{1,4}):[0-9a-fA-F:]{3,}\b""")
        ) { mr -> "${mr.groupValues[1]}:****:****" }
        // UUID
        result = result.replace(
            Regex("""\b([0-9a-fA-F]{4})[0-9a-fA-F]{4}-[0-9a-fA-F-]{14,}\b""")
        ) { mr -> "${mr.groupValues[1]}****-****-****-****-************" }
        return result
    }
}

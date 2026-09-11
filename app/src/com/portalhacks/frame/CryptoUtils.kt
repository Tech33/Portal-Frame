package com.portalhacks.frame

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    const val DEFAULT_CHANNEL = "portal_broadcast"
    const val GLOBAL_FALLBACK_KEY = "PortalGlobal2026"

    fun deriveAesKey(channel: String?): String {
        val clean = channel?.trim() ?: ""
        if (clean.isEmpty() || clean.equals(DEFAULT_CHANNEL, ignoreCase = true)) {
            return GLOBAL_FALLBACK_KEY
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(("PortalFamilyKey_" + clean.lowercase()).toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 16)
    }

    fun decrypt(hexStr: String, keyStr: String): String {
        return try {
            if (hexStr.length < 34) return ""
            val data = ByteArray(hexStr.length / 2)
            for (i in data.indices) {
                data[i] = hexStr.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val iv = data.copyOfRange(0, 16)
            val ciphertext = data.copyOfRange(16, data.size)
            val keySpec = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
    }
}

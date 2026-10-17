package com.pg.sentinel

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM。金鑰＝SHA256("pg-key:"+配對碼)。輸出＝base64(iv[12]‖ct‖tag[16])，
 * 與家人端 console/pg_console.py 的 Python cryptography.AESGCM 位元相容。
 * topic 名＝SHA256("pg-topic:kind:"+配對碼) 前 20 hex，外人不可猜。
 */
object Crypto {
    private fun keyFrom(secret: String): SecretKeySpec {
        val k = MessageDigest.getInstance("SHA-256")
            .digest(("pg-key:$secret").toByteArray(Charsets.UTF_8))
        return SecretKeySpec(k, "AES")
    }

    fun encrypt(secret: String, plain: String): String {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyFrom(secret), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(secret: String, b64: String): String? {
        return try {
            val raw = Base64.decode(b64, Base64.NO_WRAP)
            if (raw.size < 13) return null
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, keyFrom(secret), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun topic(secret: String, kind: String): String {
        val h = MessageDigest.getInstance("SHA-256")
            .digest(("pg-topic:$kind:$secret").toByteArray(Charsets.UTF_8))
        val hex = h.joinToString("") { "%02x".format(it) }
        return "pg-$kind-" + hex.substring(0, 20)
    }
}

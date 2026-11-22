package com.musec.browser.data

import android.content.ContentValues
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM Encrypted Password Vault.
 * Mobile equivalent of MuSec Windows DPAPI + AES-256-GCM-AAD Vault.
 */
class EncryptedVault(private val db: MuSecDatabase) {

    companion object {
        private const val KEYSTORE_ALIAS = "musec_browser_vault_aes256_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    fun saveCredential(site: String, username: String, password: String) {
        val cleanSite = site.trim()
        val cleanUser = username.trim()
        if (cleanSite.isEmpty() || cleanUser.isEmpty() || password.isEmpty()) return

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD("$cleanSite\u0000$cleanUser".toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        val cv = ContentValues().apply {
            put("site", cleanSite)
            put("username", cleanUser)
            put("ciphertext_b64", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            put("iv_b64", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.insert("vault", null, cv)
    }

    fun listEntries(): List<VaultEntry> {
        val out = mutableListOf<VaultEntry>()
        db.readableDatabase.rawQuery(
            "SELECT id, site, username, updated_at FROM vault ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(VaultEntry(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3)))
            }
        }
        return out
    }

    fun decryptPassword(id: Long): String? {
        db.readableDatabase.rawQuery(
            "SELECT site, username, ciphertext_b64, iv_b64 FROM vault WHERE id = ?",
            arrayOf(id.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            val site = c.getString(0)
            val username = c.getString(1)
            val ciphertext = Base64.decode(c.getString(2), Base64.NO_WRAP)
            val iv = Base64.decode(c.getString(3), Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD("$site\u0000$username".toByteArray(Charsets.UTF_8))
            return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }
    }

    fun deleteEntry(id: Long) {
        db.writableDatabase.delete("vault", "id = ?", arrayOf(id.toString()))
    }

    fun generateStrongPassword(length: Int = 24): String {
        val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        val lower = "abcdefghijkmnopqrstuvwxyz"
        val digits = "23456789"
        val symbols = "!@#\$%^&*()-_=+[]{}?"
        val all = upper + lower + digits + symbols
        val rng = SecureRandom()
        val chars = mutableListOf(
            upper[rng.nextInt(upper.length)],
            lower[rng.nextInt(lower.length)],
            digits[rng.nextInt(digits.length)],
            symbols[rng.nextInt(symbols.length)]
        )
        repeat((length - 4).coerceAtLeast(8)) {
            chars.add(all[rng.nextInt(all.length)])
        }
        chars.shuffle(rng)
        return chars.joinToString("")
    }
}

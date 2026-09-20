package com.eeinspired.mantel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted credential storage with no third-party or AndroidX dependency
 * (Requirements Goal 4 / §8).
 *
 * A hardware-backed AES-256-GCM key lives in the AndroidKeyStore and never
 * leaves it; only the IV + ciphertext are written to a private SharedPreferences
 * file. `androidx.security:security-crypto` (EncryptedSharedPreferences) is
 * deliberately avoided — the requirements flag that its guidance has churned,
 * and doing the Keystore work directly removes the dependency entirely.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(creds: Credentials) {
        val plaintext = JSONObject()
            .put(FIELD_USER, creds.username)
            .put(FIELD_PASS, creds.appPassword)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.ENCRYPT_MODE, loadOrCreateKey()) }
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext)

        val blob = ByteArray(iv.size + body.size)
        iv.copyInto(blob)
        body.copyInto(blob, iv.size)

        prefs.edit { putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP)) }
        cached = creds
    }

    /**
     * Decrypting goes through the Keystore, which on real hardware (TEE/StrongBox)
     * costs hundreds of ms per call and serializes. The image loader asks for
     * credentials on every request, so the decrypted value is kept in memory.
     */
    fun load(): Credentials? = cached ?: decrypt()?.also { cached = it }

    private fun decrypt(): Credentials? = try {
        val stored = prefs.getString(KEY_BLOB, null) ?: return null
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val body = blob.copyOfRange(GCM_IV_BYTES, blob.size)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val json = JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))
        Credentials(json.getString(FIELD_USER), json.getString(FIELD_PASS))
    } catch (_: Exception) {
        // Key invalidated (e.g. device credentials changed), tampered blob, or
        // partial write — treat as logged out and clear the bad state.
        clear()
        null
    }

    fun clear() {
        cached = null
        prefs.edit { remove(KEY_BLOB) }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        // Prefer a StrongBox (dedicated security chip) key; fall back to TEE-backed
        // when the device has no StrongBox.
        //
        // NOTE: setUnlockedDeviceRequired(true) is deliberately NOT set. Background
        // upload workers (WorkManager) must decrypt the credential while the screen
        // is locked to keep a transfer going; requiring an unlocked device would
        // break that. The blob is still protected by the app sandbox, the
        // hardware-bound key, file-based encryption at rest, and allowBackup=false.
        return runCatching { generateKey(strongBox = true) }
            .getOrElse { generateKey(strongBox = false) }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        @Volatile
        var cached: Credentials? = null

        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mantel.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFS = "mantel_secure"
        const val KEY_BLOB = "credentials_blob"
        const val FIELD_USER = "u"
        const val FIELD_PASS = "p"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

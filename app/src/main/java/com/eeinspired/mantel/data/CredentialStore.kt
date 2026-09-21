package com.eeinspired.mantel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.eeinspired.mantel.telemetry.Telemetry
import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
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
            .put(FIELD_ID, creds.userId)
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

    private fun decrypt(): Credentials? {
        val stored = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val key = existingKey() ?: throw UnrecoverableKeyException("credential key missing")
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            require(blob.size > GCM_IV_BYTES) { "truncated credential blob" }
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val body = blob.copyOfRange(GCM_IV_BYTES, blob.size)

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            val json = JSONObject(String(cipher.doFinal(body), Charsets.UTF_8))
            val user = json.getString(FIELD_USER)
            // Blobs written before userId existed fall back to the login name.
            Credentials(user, json.getString(FIELD_PASS), json.optString(FIELD_ID).ifBlank { user })
        } catch (e: KeyStoreException) {
            // A Keystore hiccup, not proof the blob is bad, so it must not destroy it.
            transientFailure(e)
            null
        } catch (e: GeneralSecurityException) {
            // Wrong/invalidated key or tampered blob (AEADBadTag, InvalidKey, missing key): the
            // stored credentials can never be read again — treat as logged out.
            permanentFailure(e)
            null
        } catch (e: IllegalArgumentException) { // bad Base64 or truncated blob
            permanentFailure(e)
            null
        } catch (e: JSONException) {
            permanentFailure(e)
            null
        } catch (e: ProviderException) {
            transientFailure(e)
            null
        }
    }

    private fun permanentFailure(e: Exception) {
        Telemetry.setKey("credential_failure", "permanent:${e.javaClass.simpleName}")
        Telemetry.recordNonFatal(CredentialStoreException("credentials unreadable, cleared", e))
        clear()
    }

    /** Keystore hiccup (busy, StrongBox reset): report, but keep the blob so the next launch can retry. */
    private fun transientFailure(e: Exception) {
        Telemetry.setKey("credential_failure", "transient:${e.javaClass.simpleName}")
        Telemetry.recordNonFatal(CredentialStoreException("credential read failed, kept", e))
    }

    /** Names only the exception class; Keystore messages can carry key aliases/internal detail. */
    class CredentialStoreException(message: String, cause: Throwable) :
        RuntimeException("$message: ${cause.javaClass.simpleName}")

    fun clear() {
        cached = null
        // commit, not apply: a sign-out must not be lost if the process dies right after.
        prefs.edit(commit = true) { remove(KEY_BLOB) }
    }

    private fun existingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun loadOrCreateKey(): SecretKey {
        existingKey()?.let { return it }

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
        const val FIELD_ID = "i"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}

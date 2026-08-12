package dev.agentworkbench

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecretStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun put(secretId: String, value: String) {
        require(secretId.matches(SECRET_ID_PATTERN)) { "Secret id is invalid" }
        require(value.isNotBlank()) { "Secret value is required" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(secretId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = listOf(
            PAYLOAD_VERSION,
            encode(cipher.iv),
            encode(encrypted),
        ).joinToString(":")
        check(preferences.edit().putString(preferenceKey(secretId), payload).commit()) {
            "Encrypted secret could not be persisted"
        }
    }

    fun get(secretId: String): String? {
        require(secretId.matches(SECRET_ID_PATTERN)) { "Secret id is invalid" }
        val payload = preferences.getString(preferenceKey(secretId), null) ?: return null
        return try {
            val parts = payload.split(':')
            require(parts.size == 3 && parts[0] == PAYLOAD_VERSION)
            val iv = decode(parts[1])
            require(iv.size == GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            cipher.updateAAD(secretId.toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[2])).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            delete(secretId)
            null
        } catch (_: IllegalArgumentException) {
            delete(secretId)
            null
        }
    }

    fun contains(secretId: String): Boolean =
        secretId.matches(SECRET_ID_PATTERN) && preferences.contains(preferenceKey(secretId))

    fun delete(secretId: String) {
        require(secretId.matches(SECRET_ID_PATTERN)) { "Secret id is invalid" }
        check(preferences.edit().remove(preferenceKey(secretId)).commit()) {
            "Encrypted secret could not be removed"
        }
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun preferenceKey(secretId: String) = "secret.$secretId"

    private fun encode(value: ByteArray): String =
        Base64.encodeToString(value, Base64.NO_WRAP or Base64.URL_SAFE)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE)

    private companion object {
        const val PREFERENCES_NAME = "agent_workbench_encrypted_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.agentworkbench.provider-secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val PAYLOAD_VERSION = "v1"
        val SECRET_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,160}")
    }
}

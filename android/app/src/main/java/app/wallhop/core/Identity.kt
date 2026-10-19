package app.wallhop.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This phone's long-term X25519 key. The private key is stored encrypted with an AES key
 * that lives in the Android Keystore and never leaves it.
 */
class Identity private constructor(val privateKey: ByteArray) {
    val publicKey: ByteArray = Crypto.publicKey(privateKey)
    val deviceId: String get() = Crypto.deviceId(publicKey)

    companion object {
        private const val PREFS = "identity"
        private const val KEY_WRAPPED = "wrapped_private_key"
        private const val KEYSTORE_ALIAS = "wallhop_identity_wrap"

        @Volatile private var cached: Identity? = null

        fun get(context: Context): Identity = cached ?: synchronized(this) {
            cached ?: load(context).also { cached = it }
        }

        private fun load(context: Context): Identity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_WRAPPED, null)?.let { stored ->
                runCatching { return Identity(unwrap(Base64.decode(stored, Base64.NO_WRAP))) }
            }
            val privateKey = Crypto.generatePrivateKey()
            prefs.edit().putString(KEY_WRAPPED, Base64.encodeToString(wrap(privateKey), Base64.NO_WRAP)).apply()
            return Identity(privateKey)
        }

        private fun wrappingKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }

        private fun wrap(secret: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            return cipher.iv + cipher.doFinal(secret)
        }

        private fun unwrap(stored: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, stored, 0, 12))
            return cipher.doFinal(stored, 12, stored.size - 12)
        }
    }
}

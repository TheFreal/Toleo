package de.freal.toleo.tolino

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Everything needed to talk to the Tolino cloud on behalf of one account. */
@Serializable
data class Session(
    val partnerId: String,
    val resellerId: Int,
    val hardwareId: String,
    val accessToken: String,
    val refreshToken: String?,
    /** Epoch millis. */
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long?,
    val deviceRegistered: Boolean = false,
)

/**
 * Persists the [Session] encrypted with an AES-GCM key that never leaves Android Keystore.
 * The user's password is never seen by the app: the partner login runs in a WebView.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("toleo_session", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _session = MutableStateFlow(load())
    val session: StateFlow<Session?> = _session

    /** Stable per-install hardware ID, kept across logouts so we reuse our device slot. */
    val hardwareId: String
        get() = prefs.getString(KEY_HW, null) ?: generateHardwareId().also {
            prefs.edit { putString(KEY_HW, it) }
        }

    fun save(session: Session) {
        val plain = json.encodeToString(Session.serializer(), session).encodeToByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(plain)
        prefs.edit { putString(KEY_SESSION, Base64.encodeToString(blob, Base64.NO_WRAP)) }
        _session.value = session
    }

    fun clear() {
        prefs.edit { remove(KEY_SESSION) }
        _session.value = null
    }

    private fun load(): Session? = runCatching {
        val blob = Base64.decode(prefs.getString(KEY_SESSION, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_SIZE))
        val plain = cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
        json.decodeFromString(Session.serializer(), plain.decodeToString())
    }.getOrNull()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "toleo_session_key"
        const val KEY_SESSION = "session"
        const val KEY_HW = "hardware_id"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12

        /**
         * tolinocloud.py builds web reader IDs shaped like `1233X-44XXX-XXXXX-XXXXX-XXXXh`.
         * Whether the server validates the shape is unverified; we mimic it.
         */
        fun generateHardwareId(): String {
            val rnd = java.security.SecureRandom()
            val hex = "0123456789abcdef"
            fun r(n: Int) = (1..n).map { hex[rnd.nextInt(16)] }.joinToString("")
            return "1233${r(1)}-44${r(3)}-${r(5)}-${r(5)}-${r(4)}h"
        }
    }
}

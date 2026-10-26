package pro.tordownloader.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed AES/GCM for the values that must never be stored in
 * plaintext: the TorBox / Real-Debrid OAuth tokens and the TMDB API key.
 *
 * Why this exists: the Electron app stores those columns in a plaintext SQLite
 * file under %APPDATA% (see docs/source-analysis.md). That is not acceptable on
 * a phone — app-private storage is readable over ADB backup/root, and it is
 * trivially exfiltrated by malware with the same UID. Here the key material
 * never leaves the TEE/StrongBox-backed keystore and is non-exportable; the
 * stored value is `iv||ciphertext` Base64.
 *
 * Requires API 23+ (minSdk 26) — no fallback path exists on purpose: failing
 * closed is better than silently degrading to plaintext.
 */
object CryptoBox {

    private const val KEY_ALIAS = "td_secret_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** Encrypts [plaintext]; empty string means "no value" (blank input stays blank). */
    fun encrypt(plaintext: String?): String {
        if (plaintext.isNullOrEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
        } catch (t: Throwable) {
            ""
        }
    }

    /** Decrypts a value produced by [encrypt]; returns null when absent or corrupt. */
    fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_LEN) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN),
            )
            String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }

    /** Only used by diagnostics: proves the keystore key is usable without exposing it. */
    fun selfTest(): Boolean {
        val probe = "self-test-${System.currentTimeMillis()}"
        val enc = encrypt(probe)
        return decrypt(enc) == probe
    }
}

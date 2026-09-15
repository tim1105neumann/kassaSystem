package at.heuriger.kassa.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import at.heuriger.kassa.net.ApiException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pendant zu `App/Sources/Sync/Keychain.swift`: das Bearer-Token gehoert nicht
 * in die Preferences.
 *
 * Als Interface, damit Tests ohne AndroidKeyStore auskommen — den gibt es unter
 * Robolectric nicht.
 */
interface TokenStore {
    fun read(): String?

    /** `null` loescht das Token. */
    fun write(token: String?)
}

/**
 * Token verschluesselt im AndroidKeyStore.
 *
 * `androidx.security:security-crypto` (EncryptedSharedPreferences) ist
 * deprecated und ohne Nachfolger, deshalb hier von Hand: der AES-256-Schluessel
 * verlaesst den Keystore nie, in den Preferences liegt nur der Geheimtext.
 *
 * GCM braucht pro Verschluesselung einen frischen IV. Der wird dem Geheimtext
 * vorangestellt — ein wiederverwendeter IV wuerde GCM komplett brechen.
 */
class KeystoreTokenStore(context: Context) : TokenStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        val blob = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (blob.size <= IV_LENGTH) return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, blob, 0, IV_LENGTH),
            )
            String(cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH), Charsets.UTF_8)
        }.getOrNull()
        // Entschluesseln scheitert, wenn der Schluessel weg ist (App-Daten
        // geloescht, Geraet zurueckgesetzt). Dann gibt es kein Token — die App
        // meldet sich neu an, statt abzustuerzen.
    }

    /**
     * Schlaegt das Verschluesseln fehl (Keystore gesperrt, Schluessel durch ein
     * Systemupdate entwertet), bleibt das Token schlicht ungespeichert und der
     * Kellner meldet sich beim naechsten Start neu an.
     *
     * Bewusst kein Werfen: `SessionController.login` faengt nur [ApiException],
     * eine `KeyStoreException` wuerde also die gerade erfolgreiche Anmeldung
     * abstuerzen lassen — das waere schlechter als ein verlorenes Token. Swifts
     * `Keychain.set` ignoriert den `OSStatus` aus demselben Grund.
     */
    override fun write(token: String?) {
        if (token == null) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            cipher.iv + encrypted
        }.onSuccess { blob ->
            prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }.onFailure {
            prefs.edit().remove(KEY_TOKEN).apply()
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Das Geraet steht hinter der Schank und wird selten entsperrt —
                // eine Bindung an die Bildschirmsperre waere hier untauglich.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "at.heuriger.kassa.token"
        const val PREFS_NAME = "at.heuriger.kassa.secure"
        const val KEY_TOKEN = "bearerToken"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

/** Nur fuer Tests: haelt das Token im Speicher. */
class InMemoryTokenStore(initial: String? = null) : TokenStore {
    private var token: String? = initial

    override fun read(): String? = token

    override fun write(token: String?) {
        this.token = token
    }
}

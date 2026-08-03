package it.squarciagola.auth

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import it.squarciagola.BuildConfig
import it.squarciagola.net.Http
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 con PKCE contro gli endpoint di Spotify.
 *
 * ponytail: PKCE fatto a mano invece di AppAuth. Sono un code verifier, uno scambio e un
 * refresh, in tutto una novantina di righe, contro una dipendenza con la sua configurazione
 * nel manifest. Se un domani servissero più provider, AppAuth diventa la scelta giusta.
 *
 * I token stanno in EncryptedSharedPreferences: sono credenziali di accesso all'account.
 */
class SpotifyAuth(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "squarciagola_auth",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Preso dal build (local.properties) se c'è, altrimenti da quello scritto a mano
     * nell'app. Il valore nel build vince: è quello che fa sparire il campo dalla schermata.
     */
    var clientId: String
        get() = BuildConfig.SPOTIFY_CLIENT_ID.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_CLIENT_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()

    /** True quando il Client ID arriva dal build e non c'è nulla da chiedere all'utente. */
    val clientIdFromBuild: Boolean get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotEmpty()

    val isLoggedIn: Boolean get() = prefs.getString(KEY_REFRESH, null) != null

    // --- avvio del login ------------------------------------------------------------------

    /** Genera un verifier nuovo, lo salva e restituisce l'URL da aprire nel browser. */
    fun buildAuthorizeUrl(): String? {
        if (clientId.isEmpty()) return null
        val verifier = randomVerifier()
        prefs.edit().putString(KEY_VERIFIER, verifier).apply()
        val params = mapOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "code_challenge_method" to "S256",
            "code_challenge" to challengeOf(verifier),
            "scope" to SCOPE,
        )
        return "https://accounts.spotify.com/authorize?" + Http.encodeForm(params)
    }

    /** Scambia il codice ricevuto sul redirect. Da chiamare fuori dal main thread. */
    fun exchangeCode(code: String): Boolean {
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return false
        val body = Http.postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier,
            ),
        ) ?: return false
        return storeTokens(JSONObject(body))
    }

    // --- uso corrente ---------------------------------------------------------------------

    /**
     * Access token valido, rinnovandolo se sta per scadere. Null se non c'è sessione o se il
     * refresh fallisce: in quel caso l'utente deve rifare il login dal telefono.
     * Da chiamare fuori dal main thread.
     */
    @Synchronized
    fun accessToken(): String? {
        val token = prefs.getString(KEY_ACCESS, null)
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (token != null && System.currentTimeMillis() < expiresAt - REFRESH_MARGIN_MS) return token

        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        val body = Http.postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
            ),
        ) ?: return null
        return if (storeTokens(JSONObject(body))) prefs.getString(KEY_ACCESS, null) else null
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES_AT).remove(KEY_VERIFIER)
            .apply()
    }

    // --- interni --------------------------------------------------------------------------

    private fun storeTokens(json: JSONObject): Boolean {
        val access = json.optString("access_token").takeIf { it.isNotEmpty() } ?: return false
        val expiresIn = json.optLong("expires_in", 3600L)
        val editor = prefs.edit()
            .putString(KEY_ACCESS, access)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
        // Spotify non rimanda sempre il refresh token sul refresh: si conserva il precedente.
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { editor.putString(KEY_REFRESH, it) }
        editor.apply()
        return true
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        const val REDIRECT_URI = "it.squarciagola://auth"
        private const val SCOPE = "user-read-playback-state user-read-currently-playing"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val REFRESH_MARGIN_MS = 60_000L

        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_VERIFIER = "code_verifier"
    }
}

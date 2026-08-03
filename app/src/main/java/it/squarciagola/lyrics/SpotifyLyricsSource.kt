package it.squarciagola.lyrics

import it.squarciagola.auth.SpotifyAuth
import it.squarciagola.model.Lyrics
import it.squarciagola.model.LyricLine
import it.squarciagola.model.TrackMeta
import it.squarciagola.net.Http
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Testi dal client Spotify, tramite l'endpoint interno color-lyrics.
 *
 * Cosa comporta, messo per iscritto perche' non e' un dettaglio:
 * questo endpoint non fa parte della Web API pubblica e non e' coperto da alcuno scope OAuth.
 * Usarlo e' contro i Termini di Servizio di Spotify, richiede il cookie di sessione sp_dc
 * dell'account, e smette di funzionare a ogni rotazione del segreto TOTP del web player.
 *
 * ponytail: nessun tentativo di estrarre il segreto in automatico dal bundle JavaScript.
 * Sarebbe uno scraper da rincorrere a ogni rilascio di Spotify. Il segreto si incolla a mano
 * nelle impostazioni; quando smette di funzionare, la sorgente si tira indietro restituendo
 * null e il testo continua ad arrivare da LRCLIB. Percorso di aggiornamento: README.
 */
class SpotifyLyricsSource(private val auth: SpotifyAuth) : LyricsSource {

    override val name = "Spotify"

    private var cachedToken: String? = null
    private var cachedTokenExpiresAt = 0L

    override fun fetch(track: TrackMeta): Lyrics? {
        val token = webPlayerToken() ?: return null
        val body = Http.get(
            "$LYRICS_URL${track.id}?format=json&vocalRemoval=false&market=from_token",
            mapOf(
                "Authorization" to "Bearer $token",
                "App-Platform" to "WebPlayer",
                "Referer" to "https://open.spotify.com/",
            ),
        ) ?: return null

        return parse(body)
    }

    private fun parse(body: String): Lyrics? {
        val lyrics = JSONObject(body).optJSONObject("lyrics") ?: return null
        val array = lyrics.optJSONArray("lines") ?: return null
        val lines = ArrayList<LyricLine>(array.length())
        for (i in 0 until array.length()) {
            val line = array.optJSONObject(i) ?: continue
            lines.add(LyricLine(line.optLong("startTimeMs"), line.optString("words").trim()))
        }
        if (lines.isEmpty()) return null
        // syncType UNSYNCED significa che i timestamp sono tutti a zero: meglio dichiararlo
        // testo statico che fingere una sincronia che non c'e'.
        val unsynced = lyrics.optString("syncType") == "UNSYNCED" || lines.all { it.timeMs == 0L }
        return if (unsynced) {
            Lyrics.Plain(lines.joinToString("\n") { it.text }, name)
        } else {
            Lyrics.Synced(lines.sortedBy { it.timeMs }, name)
        }
    }

    /** Token del web player, diverso da quello OAuth. Vale un'ora, si tiene in memoria. */
    private fun webPlayerToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < cachedTokenExpiresAt - 60_000) return it }

        val spDc = auth.spDc.takeIf { it.isNotEmpty() } ?: return null
        val secret = auth.totpSecretHex.takeIf { it.isNotEmpty() } ?: return null
        val seconds = now / 1000
        val totp = totp(hexToBytes(secret) ?: return null, seconds)

        val query = Http.encodeForm(
            mapOf(
                "reason" to "init",
                "productType" to "web-player",
                "totp" to totp,
                "totpVer" to TOTP_VERSION,
                "ts" to seconds.toString(),
            )
        )
        val body = Http.get(
            "$TOKEN_URL?$query",
            mapOf(
                "Cookie" to "sp_dc=$spDc",
                "App-Platform" to "WebPlayer",
                "Referer" to "https://open.spotify.com/",
            ),
        ) ?: return null

        val json = JSONObject(body)
        val token = json.optString("accessToken").takeIf { it.isNotEmpty() } ?: return null
        cachedToken = token
        cachedTokenExpiresAt = json.optLong("accessTokenExpirationTimestampMs", now + 3_600_000)
        return token
    }

    /** TOTP secondo RFC 6238: HMAC-SHA1, passo 30 secondi, 6 cifre. */
    private fun totp(secret: ByteArray, epochSeconds: Long): String {
        val counter = epochSeconds / 30
        val message = ByteArray(8)
        for (i in 7 downTo 0) message[i] = ((counter shr ((7 - i) * 8)) and 0xFF).toByte()

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(message)

        val offset = (hash[hash.size - 1].toInt() and 0x0F)
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        return (binary % 1_000_000).toString().padStart(6, '0')
    }

    private fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim().removePrefix("0x")
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private companion object {
        const val TOKEN_URL = "https://open.spotify.com/api/token"
        const val LYRICS_URL = "https://spclient.wg.spotify.com/color-lyrics/v2/track/"

        /**
         * Versione del segreto TOTP incorporata nel web player. Va tenuta allineata al segreto
         * incollato nelle impostazioni: sono una coppia, se divergono il token viene rifiutato.
         */
        const val TOTP_VERSION = "61"
    }
}

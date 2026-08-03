package it.squarciagola.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Client HTTP minimo su HttpURLConnection.
 *
 * ponytail: nessuna libreria di rete. Le chiamate sono tre (token, stato riproduzione, testo),
 * tutte GET o POST con corpo breve. OkHttp o Retrofit qui sarebbero peso senza ritorno.
 */
object Http {

    const val USER_AGENT = "Squarciagola/0.1 (uso personale)"

    /** Corpo della risposta, oppure null per qualunque esito non riuscito. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String? =
        request("GET", url, headers, null, null)

    /** Come [get] ma per contenuti binari, cioè le copertine. */
    fun getBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode !in 200..299) null else conn.inputStream.use { it.readBytes() }
        } catch (e: IOException) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String? =
        request("POST", url, headers, encodeForm(form), "application/x-www-form-urlencoded")

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
        contentType: String?,
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                contentType?.let { setRequestProperty("Content-Type", it) }
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299) {
                // Il corpo delle risposte di errore contiene la spiegazione vera: buttarlo
                // significa ritrovarsi con un fallimento muto e nessun modo di capirlo.
                val dettaglio = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
                android.util.Log.w(
                    "Squarciagola",
                    "$method $url ha risposto ${conn.responseCode}: ${dettaglio.take(400)}",
                )
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: IOException) {
            android.util.Log.w("Squarciagola", "$method $url non riuscita: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    fun encodeForm(form: Map<String, String>): String =
        form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private const val TIMEOUT_MS = 8_000
}

package it.squarciagola.update

import it.squarciagola.net.Http
import org.json.JSONObject

data class Release(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
)

/**
 * Controlla se su GitHub c'è una versione più recente di quella installata.
 *
 * Convenzione, unica regola da ricordare: il tag della release è `v<versionCode>`, cioè
 * `v2`, `v3` e così via, e alla release è allegato un file .apk. Il numero nel tag è quello
 * che viene confrontato con il versionCode dell'app installata.
 *
 * ponytail: nessun server di aggiornamento, nessun file manifest da mantenere a mano.
 * Le release di GitHub espongono già tutto quello che serve, e pubblicare significa
 * caricare l'APK su una release nuova.
 */
object UpdateChecker {

    /** Proprietario e nome del repository. Si cambia qui, una volta sola. */
    const val REPOSITORY = "alexmoli97/squarciagola"

    /** Bloccante: va invocata su Dispatchers.IO. Null se non si riesce a sapere. */
    fun latest(): Release? {
        val body = Http.get(
            "https://api.github.com/repos/$REPOSITORY/releases/latest",
            mapOf("Accept" to "application/vnd.github+json"),
        ) ?: return null
        return parse(body)
    }

    /** Separata dalla rete per poterla verificare con un test. */
    fun parse(body: String): Release? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null

        val tag = json.optString("tag_name")
        val versionCode = tag.trimStart('v', 'V').takeWhile { it.isDigit() }
            .toIntOrNull() ?: return null

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url").takeIf { it.isNotEmpty() }
                if (apkUrl != null) break
            }
        }

        return Release(
            versionCode = versionCode,
            versionName = json.optString("name").takeIf { it.isNotEmpty() } ?: tag,
            apkUrl = apkUrl ?: return null,
            notes = json.optString("body"),
        )
    }
}

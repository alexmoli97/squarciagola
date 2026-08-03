package it.squarciagola.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings

/**
 * Scarica e avvia l'installazione di una nuova versione.
 *
 * ponytail: si appoggia a DownloadManager invece di scaricare a mano. E' un servizio di
 * sistema, gestisce da solo notifica di avanzamento, ripresa e rete che va e viene, e
 * restituisce un content URI gia' condivisibile con l'installer, il che evita di dover
 * configurare un FileProvider.
 *
 * L'installazione vera la fa Android e chiede conferma all'utente: un'app non puo'
 * sostituirsi da sola in silenzio, e va bene cosi'.
 */
object Updater {

    fun download(context: Context, release: Release): Long {
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Squarciagola ${release.versionName}")
            .setDescription("Download dell'aggiornamento")
            .setMimeType(APK_MIME)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "squarciagola-${release.versionCode}.apk",
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        return manager(context).enqueue(request)
    }

    /** Apre l'installer di sistema sull'APK scaricato. False se il file non e' disponibile. */
    fun install(context: Context, downloadId: Long): Boolean {
        val uri = manager(context).getUriForDownloadedFile(downloadId) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Da Android 8 il permesso di installare si concede per singola app. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)

    private const val APK_MIME = "application/vnd.android.package-archive"
}

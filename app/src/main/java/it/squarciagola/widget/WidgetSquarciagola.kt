package it.squarciagola.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import it.squarciagola.Engine
import it.squarciagola.MainActivity
import it.squarciagola.R

/**
 * Widget della schermata iniziale del telefono: brano in riproduzione, copertina e un tocco
 * che porta dritto al karaoke.
 *
 * Il receiver fa il meno possibile, e per una ragione precisa: qualunque eccezione qui dentro
 * si manifesta al'utente soltanto come "impossibile caricare il widget", senza una riga che
 * spieghi cosa sia successo. Quindi niente inizializzazioni pesanti, niente avvio di servizi
 * (da Android 12 farlo dal background lancia un'eccezione), e i dati del brano si leggono solo
 * se l'app e' gia' viva. Quando non lo e', il widget mostra il proprio nome e resta toccabile:
 * un widget che invita ad aprire l'app e' comunque meglio di un rettangolo grigio.
 *
 * ponytail: RemoteViews e non Glance. Tre testi e un'immagine non giustificano una dipendenza.
 */
class WidgetSquarciagola : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            runCatching { manager.updateAppWidget(id, viste(context)) }
                .onFailure {
                    android.util.Log.w("Squarciagola", "Widget non disegnato: ${it.message}", it)
                    // Ultima spiaggia: il layout cosi' com'e' nel file, senza dati.
                    runCatching {
                        manager.updateAppWidget(
                            id,
                            RemoteViews(context.packageName, R.layout.widget_squarciagola),
                        )
                    }
                }
        }
    }

    private fun viste(context: Context): RemoteViews {
        val viste = RemoteViews(context.packageName, R.layout.widget_squarciagola)

        // Solo se l'app e' gia' viva: inizializzare l'Engine da un receiver significherebbe
        // creare preferenze cifrate e coroutine per disegnare tre righe di testo.
        val frame = if (Engine.pronto) Engine.currentFrame() else null
        val inAscolto = frame != null && frame.title.isNotEmpty()

        if (inAscolto) {
            viste.setTextViewText(R.id.titolo, frame.title)
            viste.setTextViewText(R.id.artista, frame.artist)
            viste.setTextViewText(R.id.azione, context.getString(R.string.widget_canta))
            viste.setTextColor(R.id.azione, frame.accent)
            copertina()?.let { viste.setImageViewBitmap(R.id.copertina, it) }
        } else {
            viste.setTextViewText(R.id.titolo, context.getString(R.string.app_name))
            viste.setTextViewText(R.id.artista, "Tocca per aprire")
            viste.setTextViewText(R.id.azione, "")
            viste.setImageViewResource(R.id.copertina, R.drawable.ic_launcher)
        }

        val apri = Intent(context, MainActivity::class.java).apply {
            if (inAscolto) putExtra(MainActivity.EXTRA_APRI_KARAOKE, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        viste.setOnClickPendingIntent(
            R.id.radice,
            PendingIntent.getActivity(
                context,
                if (inAscolto) 1 else 0,
                apri,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return viste
    }

    /**
     * Copertina ridotta per il widget.
     *
     * Le immagini spedite a un widget viaggiano su una transazione Binder con un tetto di
     * memoria calcolato sulla sua area: quella da 320 pixel di lato pesa quattrocento
     * chilobyte e non e' una scommessa che vale la pena fare per un riquadro da 64.
     */
    private fun copertina(): Bitmap? {
        val piena = Engine.copertina.value ?: return null
        if (piena.isRecycled) return null
        return runCatching { Bitmap.createScaledBitmap(piena, LATO, LATO, true) }.getOrNull()
    }

    companion object {
        private const val LATO = 144

        /**
         * Ridisegna tutti i widget presenti. La chiama l'Engine al cambio di brano: il periodo
         * minimo che il sistema concede da solo e' mezz'ora, inutile per un widget che deve
         * dire cosa sta suonando adesso.
         */
        fun aggiorna(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, WidgetSquarciagola::class.java)
                )
                if (ids.isEmpty()) return
                context.sendBroadcast(
                    Intent(context, WidgetSquarciagola::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                )
            }
        }
    }
}

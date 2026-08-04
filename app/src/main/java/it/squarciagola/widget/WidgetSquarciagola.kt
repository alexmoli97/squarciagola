package it.squarciagola.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import it.squarciagola.Engine
import it.squarciagola.MainActivity
import it.squarciagola.PlaybackService
import it.squarciagola.R

/**
 * Widget della schermata iniziale del telefono: brano in riproduzione, copertina e punto del
 * pezzo, con un tocco che porta dritto al karaoke.
 *
 * Su Android Auto non esiste nulla di equivalente: la schermata iniziale mostra card
 * controllate da Google e non c'e' API per aggiungerne di terze parti. Li' l'app resta
 * un'icona nel launcher, e questo widget non c'entra.
 *
 * ponytail: RemoteViews e non Glance. Il contenuto e' fermo fra un aggiornamento e l'altro,
 * quattro elementi in croce, e Glance avrebbe portato una dipendenza per disegnarli.
 */
class WidgetSquarciagola : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Il widget e' anche il modo piu' rapido per far ripartire l'ascolto dopo che il
        // sistema ha ucciso il processo: se c'e' una sessione, si riaccende da solo.
        Engine.init(context)
        if (Engine.auth.isLoggedIn) PlaybackService.start(context)
        ids.forEach { disegna(context, manager, it) }
    }

    override fun onEnabled(context: Context) {
        Engine.init(context)
    }

    private fun disegna(context: Context, manager: AppWidgetManager, id: Int) {
        val frame = Engine.currentFrame()
        val viste = RemoteViews(context.packageName, R.layout.widget_squarciagola)

        val inAscolto = frame.title.isNotEmpty()
        viste.setTextViewText(R.id.titolo, if (inAscolto) frame.title else "Silenzio in cabina")
        viste.setTextViewText(
            R.id.artista,
            if (inAscolto) frame.artist else "Fai partire qualcosa su Spotify",
        )
        viste.setTextViewText(
            R.id.azione,
            if (inAscolto) context.getString(R.string.widget_canta) else "",
        )
        viste.setTextColor(R.id.azione, frame.accent)

        val avanzamento = if (frame.durationMs > 0) {
            ((frame.positionMs * 1000) / frame.durationMs).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        viste.setProgressBar(R.id.avanzamento, 1000, avanzamento, false)
        // Il colore della barra segue il brano, come ovunque nell'app. Prima di Android 12
        // RemoteViews non sa tingere una ProgressBar: resta quella di sistema.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            viste.setColorInt(R.id.avanzamento, "setProgressTintList", frame.accent, frame.accent)
        }

        val copertina = Engine.copertina.value
        if (copertina != null && !copertina.isRecycled) {
            viste.setImageViewBitmap(R.id.copertina, copertina)
        } else {
            viste.setImageViewResource(R.id.copertina, R.drawable.ic_launcher)
        }

        val apri = Intent(context, MainActivity::class.java).apply {
            // Se c'e' un brano si va dritti al karaoke: il widget lo si tocca per cantare,
            // non per aprire un menu.
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

        manager.updateAppWidget(id, viste)
    }

    companion object {
        /**
         * Ridisegna tutti i widget presenti. La chiama l'Engine al cambio di brano: il
         * periodo minimo che il sistema concede da solo e' mezz'ora, inutile per un widget
         * che deve dire cosa sta suonando adesso.
         */
        fun aggiorna(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, WidgetSquarciagola::class.java))
            if (ids.isEmpty()) return
            val intento = Intent(context, WidgetSquarciagola::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intento)
        }
    }
}

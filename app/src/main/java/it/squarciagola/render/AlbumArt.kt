package it.squarciagola.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import it.squarciagola.net.Http

/**
 * Copertina dell'album ridotta a una macchia di colore, da usare come sfondo del testo.
 *
 * ponytail: la sfocatura non si calcola. Si scarica l'immagine più piccola che Spotify
 * espone e la si riduce a una manciata di pixel: ridisegnandola a schermo intero,
 * l'interpolazione del filtro bilineare fa da sola tutto il lavoro. Nessuna RenderScript,
 * nessun RenderEffect, nessun limite di versione, e il costo è quello di un bitmap da
 * qualche centinaio di byte.
 *
 * Il limite di questa scelta: la sfocatura non è regolabile con continuità, si controlla
 * solo cambiando [DIMENSIONE]. Per uno sfondo dietro al testo va più che bene.
 */
object AlbumArt {

    private const val DIMENSIONE = 24

    private var urlInCache: String? = null
    private var bitmapInCache: Bitmap? = null

    /** Bloccante: va invocata su Dispatchers.IO. Null se non si riesce a scaricarla. */
    @Synchronized
    fun load(url: String): Bitmap? {
        if (url == urlInCache) return bitmapInCache

        val bytes = Http.getBytes(url) ?: return null
        val originale = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull() ?: return null
        val ridotta = runCatching {
            Bitmap.createScaledBitmap(originale, DIMENSIONE, DIMENSIONE, true)
        }.getOrNull()
        if (ridotta !== originale) originale.recycle()

        urlInCache = url
        bitmapInCache = ridotta
        return ridotta
    }

    @Synchronized
    fun clear() {
        urlInCache = null
        bitmapInCache = null
    }
}

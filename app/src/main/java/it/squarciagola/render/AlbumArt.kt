package it.squarciagola.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import it.squarciagola.net.Http
import it.squarciagola.ui.Accento

/**
 * Copertina dell'album sfocata, da usare come sfondo del testo.
 *
 * La sfocatura è calcolata davvero, con tre passate di media mobile su un'immagine ridotta.
 * L'alternativa piu' furba, ridurre a pochi pixel e lasciare che l'ingrandimento faccia da
 * sfumatura, produce un mosaico: si riconoscono i quadrati, non la foto. Qui la copertina
 * resta riconoscibile e morbida.
 *
 * ponytail: media mobile scritta a mano invece di RenderEffect o RenderScript. RenderEffect
 * vuole API 31 e un canvas accelerato, e in Android Auto si disegna su una Surface con
 * lockCanvas, che accelerata non è: sarebbero due strade diverse per lo stesso risultato.
 * Tre passate di box blur approssimano una gaussiana abbastanza bene, e il costo si paga una
 * volta per brano su un thread di I/O, non nel ciclo di disegno.
 */
/**
 * Tutto quello che la copertina ci da': la versione sfocata per lo sfondo, quella nitida da
 * mostrare in home, e il colore che il brano detta all'interfaccia.
 */
data class Sfondo(val immagine: Bitmap, val accento: Int, val nitida: Bitmap)

object AlbumArt {

    /** Lato dell'immagine su cui si sfoca. Piu' alto, piu' dettaglio e piu' lavoro. */
    private const val LATO = 160

    /** Lato della copertina mostrata a fuoco in home. */
    private const val LATO_NITIDA = 320

    /** Raggio della media mobile, in pixel dell'immagine ridotta. */
    private const val RAGGIO = 6

    private const val PASSATE = 3

    private var urlInCache: String? = null
    private var bitmapInCache: Sfondo? = null

    /** Bloccante: va invocata su Dispatchers.IO. Null se non si riesce a scaricarla. */
    @Synchronized
    fun load(url: String): Sfondo? {
        if (url == urlInCache) return bitmapInCache

        val bytes = Http.getBytes(url) ?: return null
        val originale = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            .getOrNull() ?: return null

        val nitida = runCatching { Bitmap.createScaledBitmap(originale, LATO_NITIDA, LATO_NITIDA, true) }
            .getOrNull() ?: return null
        val ridotta = runCatching { Bitmap.createScaledBitmap(originale, LATO, LATO, true) }
            .getOrNull()
        if (originale !== nitida && originale !== ridotta) originale.recycle()
        if (ridotta == null) return null

        val sfocata = runCatching { blur(ridotta) }.getOrNull() ?: ridotta
        if (sfocata !== ridotta) ridotta.recycle()

        urlInCache = url
        bitmapInCache = Sfondo(sfocata, accentoDi(sfocata), nitida)
        return bitmapInCache
    }

    /** Il colore che il brano detta all'interfaccia, ricavato dall'immagine gia' sfocata. */
    private fun accentoDi(immagine: Bitmap): Int {
        val pixels = IntArray(immagine.width * immagine.height)
        immagine.getPixels(pixels, 0, immagine.width, 0, 0, immagine.width, immagine.height)
        return Accento.dominante(pixels)
    }

    @Synchronized
    fun clear() {
        urlInCache = null
        bitmapInCache = null
    }

    /**
     * Tre passate di media mobile, ognuna orizzontale e poi verticale.
     *
     * Separare le due direzioni rende il costo proporzionale al raggio invece che al suo
     * quadrato: su 160 per 160 pixel sono poche decine di migliaia di operazioni per passata.
     */
    private fun blur(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val appoggio = IntArray(w * h)

        repeat(PASSATE) {
            mediaOrizzontale(pixels, appoggio, w, h)
            mediaVerticale(appoggio, pixels, w, h)
        }

        // Non si ricicla la sorgente: puo' non appartenerci. Lo fa chi l'ha creata.
        val risultato = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        risultato.setPixels(pixels, 0, w, 0, 0, w, h)
        return risultato
    }

    private fun mediaOrizzontale(src: IntArray, dst: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val riga = y * w
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                var i = x - RAGGIO
                val fine = x + RAGGIO
                while (i <= fine) {
                    val c = src[riga + i.coerceIn(0, w - 1)]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                    i++
                }
                dst[riga + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private fun mediaVerticale(src: IntArray, dst: IntArray, w: Int, h: Int) {
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0; var g = 0; var b = 0; var n = 0
                var i = y - RAGGIO
                val fine = y + RAGGIO
                while (i <= fine) {
                    val c = src[i.coerceIn(0, h - 1) * w + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                    i++
                }
                dst[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}

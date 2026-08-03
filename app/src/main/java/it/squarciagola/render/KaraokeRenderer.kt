package it.squarciagola.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import it.squarciagola.lyrics.LrcParser
import it.squarciagola.model.KaraokeFrame
import it.squarciagola.model.LyricLine
import it.squarciagola.model.Lyrics
import kotlin.math.exp

/**
 * Disegna un fotogramma del karaoke dentro un rettangolo.
 *
 * Non conosce né Android Auto né Compose: riceve un Canvas e un'area, e disegna. È la
 * ragione per cui lo stesso karaoke gira sullo schermo dell'auto e su quello del telefono
 * senza duplicare nulla.
 *
 * Lo scorrimento è una telecamera che insegue: a ogni fotogramma la posizione corrente si
 * avvicina a quella di destinazione di una frazione che dipende dal tempo trascorso. Non ci
 * sono salti perché non c'è nessun istante in cui qualcosa "scatta": il movimento è sempre in
 * corso, e cambiare riga sposta soltanto la destinazione.
 *
 * I Paint sono campi e non variabili locali: questo metodo viene invocato decine di volte al
 * secondo e allocare nel ciclo di disegno si vede.
 */
class KaraokeRenderer {

    private val background = Paint()
    private var gradientArea = Rect()
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artworkPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()

    private var layout: Layout? = null
    private var scroll = 0f
    private var lastFrameUptime = 0L

    fun draw(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        if (area.width() <= 0 || area.height() <= 0) return
        drawArtwork(canvas, area, frame)
        ensureBackground(area)
        canvas.drawRect(area, background)

        // La scala nasce dalla larghezza, che è il vincolo vero per il testo, e viene limitata
        // dall'altezza della sola fascia utile: intestazione e barra non partecipano, altrimenti
        // su uno schermo alto il testo resterebbe piccolo per far posto a spazio vuoto.
        val provvisoria = minOf(area.width() / REFERENCE_WIDTH, area.height() / REFERENCE_HEIGHT)
        val scale = minOf(
            area.width() / REFERENCE_WIDTH,
            contentHeight(area, provvisoria) / REFERENCE_CONTENT,
        )
        titlePaint.textSize = 19f * scale
        subtitlePaint.textSize = 14f * scale

        // Riga attiva e righe di contorno hanno la stessa dimensione di proposito. Cambiarla
        // fra le due significherebbe rifare il layout a ogni cambio riga, e il riflusso si
        // vedrebbe come uno scatto. L'evidenziazione la portano grassetto, colore e alone.
        activePaint.textSize = LYRIC_SIZE * scale
        idlePaint.textSize = LYRIC_SIZE * scale
        activePaint.setShadowLayer(activePaint.textSize * 0.5f, 0f, 0f, COLOR_GLOW)

        drawHeader(canvas, area, frame)
        drawBody(canvas, contentArea(area), frame)
        drawProgress(canvas, area, frame)
    }

    /** Altezza della fascia di testo, con i Paint dimensionati a una scala di prova. */
    private fun contentHeight(area: Rect, scaleDiProva: Float): Float =
        area.height() - (19f * 1.6f + 14f * 2.4f + 14f * 3.2f) * scaleDiProva

    /**
     * La fascia in cui vive il testo: quello che resta fra l'intestazione e la barra di
     * avanzamento. Il karaoke si centra qui dentro, non sullo schermo intero, altrimenti
     * resta un vuoto sotto il titolo e il testo si accalca sopra la barra.
     */
    private fun contentArea(area: Rect): Rect {
        val header = titlePaint.textSize * 1.6f + subtitlePaint.textSize * 2.4f
        val progress = subtitlePaint.textSize * 3.2f
        return Rect(
            area.left,
            (area.top + header).toInt(),
            area.right,
            (area.bottom - progress).toInt(),
        )
    }

    private fun drawHeader(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        if (frame.title.isEmpty()) return
        val left = area.left + area.width() * 0.05f
        // L'angolo in alto a destra non e' disponibile: sul telefono ci sta il comando di
        // chiusura, in auto la barra dei comandi dell'host. Titolo e artista si fermano prima
        // e vengono troncati, invece di finire sotto qualcosa e diventare illeggibili.
        val maxWidth = area.width() * HEADER_WIDTH_RATIO
        titlePaint.color = COLOR_TITLE
        subtitlePaint.color = COLOR_DIM
        canvas.drawText(
            ellipsize(frame.title, titlePaint, maxWidth),
            left,
            area.top + titlePaint.textSize * 1.6f,
            titlePaint,
        )
        canvas.drawText(
            ellipsize(frame.artist, subtitlePaint, maxWidth),
            left,
            area.top + titlePaint.textSize * 1.6f + subtitlePaint.textSize * 1.5f,
            subtitlePaint,
        )
    }

    private fun drawBody(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        when (val lyrics = frame.lyrics) {
            is Lyrics.Synced -> {
                val current = layoutFor(lyrics, lyrics.lines.map { it.text }, area)
                drawScroller(canvas, area, current, LrcParser.activeIndex(lyrics.lines, frame.positionMs))
            }

            is Lyrics.Plain -> {
                val righe = lyrics.text.lines().map { it.trim() }
                val current = layoutFor(lyrics, righe, area)
                // Senza timestamp si scorre in proporzione alla posizione nel brano. Non è
                // sincronia, è un compromesso per non lasciare fermo un muro di testo.
                val ratio =
                    if (frame.durationMs > 0) frame.positionMs.toFloat() / frame.durationMs else 0f
                val focus = (ratio * righe.size).toInt().coerceIn(0, (righe.size - 1).coerceAtLeast(0))
                drawScroller(canvas, area, current, focus)
            }

            else -> {
                layout = null
                idlePaint.color = COLOR_DIM
                val text = frame.message ?: if (frame.title.isEmpty()) "Silenzio in cabina"
                else "Di questo brano non si trova il testo"
                val rows = wrapFor(text, area.width() * 0.9f)
                val rowHeight = idlePaint.textSize * ROW_SPACING
                drawBlock(
                    canvas, area, rows,
                    area.exactCenterY() - rows.size * rowHeight / 2f,
                    rowHeight, idlePaint, COLOR_DIM, area.exactCenterX(),
                )
            }
        }
    }

    /**
     * Disegna il testo scorrevole con la telecamera che insegue il blocco attivo.
     *
     * Si scorrono tutte le righe a ogni fotogramma, ma senza rifare la mandata a capo: quella
     * sta nel layout in cache. Restano confronti su un centinaio di numeri, niente di
     * misurabile.
     */
    private fun drawScroller(canvas: Canvas, area: Rect, layout: Layout, active: Int) {
        if (layout.blocks.isEmpty()) return

        // Le pause strumentali sono righe vuote nel file LRC. Se la telecamera ci si centrasse
        // sopra resterebbe uno schermo vuoto e nessuna riga evidenziata proprio mentre lo
        // strumentale scorre: si resta invece sull'ultima riga cantata.
        var fuoco = active
        while (fuoco >= 0 && layout.blocks[fuoco].isEmpty()) fuoco--

        val destinazione = if (fuoco in layout.blocks.indices) {
            layout.tops[fuoco] + layout.heights[fuoco] / 2f
        } else {
            // Prima che il brano attacchi, le prime righe stanno sotto il centro: si vede che
            // sta per cominciare invece di trovarsi la prima riga già a metà schermo.
            -area.height() * 0.22f
        }

        avanzaTelecamera(destinazione)

        val originY = area.exactCenterY() - scroll
        val centerX = area.exactCenterX()
        for (indice in layout.blocks.indices) {
            val top = originY + layout.tops[indice]
            if (top + layout.heights[indice] < area.top) continue
            if (top > area.bottom) break
            val paint = if (indice == fuoco) activePaint else idlePaint
            drawBlock(
                canvas, area, layout.blocks[indice], top, layout.rowHeight, paint,
                colorePer(indice - fuoco), centerX,
            )
        }
    }

    /**
     * Smorzamento esponenziale verso la destinazione, indipendente dalla cadenza dei
     * fotogrammi: sul telefono si disegna a ogni vsync, in auto ogni 33 ms, e il movimento
     * deve durare uguale.
     */
    private fun avanzaTelecamera(destinazione: Float) {
        val adesso = SystemClock.uptimeMillis()
        val trascorso = if (lastFrameUptime == 0L) 0L else adesso - lastFrameUptime
        lastFrameUptime = adesso

        // Fotogramma perso, app tornata in primo piano, schermo riacceso: senza limite la
        // telecamera farebbe un balzo. Meglio un recupero rapido ma continuo.
        val dt = (trascorso / 1000f).coerceIn(0f, 0.12f)
        scroll += (destinazione - scroll) * (1f - exp(-dt / TAU))
    }

    private fun drawBlock(
        canvas: Canvas,
        area: Rect,
        rows: List<String>,
        top: Float,
        rowHeight: Float,
        paint: Paint,
        color: Int,
        centerX: Float,
    ) {
        paint.color = color
        rows.forEachIndexed { index, row ->
            if (row.isEmpty()) return@forEachIndexed
            val baseline = top + rowHeight * (index + 0.8f)
            if (baseline < area.top || baseline > area.bottom + rowHeight) return@forEachIndexed
            canvas.drawText(row, centerX, baseline, paint)
        }
    }

    /** Le righe sfumano allontanandosi da quella attiva, cosi' l'occhio trova subito il centro. */
    private fun colorePer(distanza: Int): Int = when (distanza) {
        0 -> COLOR_ACTIVE
        -1, 1 -> COLOR_NEAR
        -2, 2 -> COLOR_FAR
        else -> COLOR_FAINT
    }

    // --- layout ---------------------------------------------------------------------------

    /**
     * Posizioni verticali di tutte le righe, calcolate una volta sola.
     *
     * Mandare a capo l'intero testo a ogni fotogramma sarebbe lo spreco che rende scattoso
     * il disegno; qui si rifà solo quando cambia il brano o cambiano le dimensioni.
     */
    private class Layout(
        val identita: Any,
        val larghezza: Int,
        val rowHeight: Float,
        val blocks: List<List<String>>,
        val tops: FloatArray,
        val heights: FloatArray,
    )

    private fun layoutFor(identita: Any, testi: List<String>, area: Rect): Layout {
        val maxWidth = area.width() * 0.92f
        val rowHeight = idlePaint.textSize * ROW_SPACING
        val esistente = layout
        if (esistente != null &&
            esistente.identita === identita &&
            esistente.larghezza == area.width() &&
            esistente.rowHeight == rowHeight
        ) {
            return esistente
        }

        val gap = idlePaint.textSize * 0.5f
        // Una riga vuota non occupa una riga intera: resta il solo distacco fra i blocchi,
        // altrimenti ogni pausa strumentale aprirebbe una voragine in mezzo allo schermo.
        val blocks = testi.map { if (it.isBlank()) emptyList() else wrapFor(it, maxWidth) }
        val tops = FloatArray(blocks.size)
        val heights = FloatArray(blocks.size)
        var y = 0f
        blocks.forEachIndexed { indice, rows ->
            tops[indice] = y
            heights[indice] = rows.size * rowHeight
            y += heights[indice] + gap
        }

        val nuovo = Layout(identita, area.width(), rowHeight, blocks, tops, heights)
        layout = nuovo
        // Brano nuovo o schermo ruotato: la telecamera si posiziona di colpo invece di
        // arrivarci scorrendo da dove si trovava per il brano precedente.
        scroll = if (blocks.isEmpty()) 0f else tops[0] + heights[0] / 2f
        lastFrameUptime = 0L
        return nuovo
    }

    /**
     * Si misura sempre con il Paint in grassetto, anche per le righe normali: cosi' il layout
     * non cambia quando una riga diventa quella attiva e il testo in grassetto non trabocca.
     */
    private fun wrapFor(text: String, maxWidth: Float): List<String> =
        TextWrapper.wrap(text, maxWidth) { activePaint.measureText(it) }

    // --- cornice --------------------------------------------------------------------------

    private fun drawProgress(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        if (frame.durationMs <= 0) return
        val margin = area.width() * 0.05f
        val height = area.height() * 0.008f + 2f
        val top = area.bottom - subtitlePaint.textSize * 2.6f
        val width = area.width() - margin * 2

        barPaint.color = COLOR_BAR_BACKGROUND
        canvas.drawRoundRect(
            area.left + margin, top, area.left + margin + width, top + height, height, height, barPaint
        )
        val ratio = (frame.positionMs.toFloat() / frame.durationMs).coerceIn(0f, 1f)
        barPaint.color = if (frame.isPlaying) COLOR_ACTIVE else COLOR_DIM
        canvas.drawRoundRect(
            area.left + margin, top, area.left + margin + width * ratio, top + height, height, height, barPaint
        )

        val baseline = top + height + subtitlePaint.textSize * 1.3f
        subtitlePaint.color = COLOR_DIM
        canvas.drawText(
            "${clock(frame.positionMs)} / ${clock(frame.durationMs)}",
            area.left + margin,
            baseline,
            subtitlePaint,
        )

        // Da dove arriva il testo. Sta in basso e non in alto perché l'angolo in alto a destra
        // è occupato dal comando di chiusura sul telefono.
        if (frame.source.isNotEmpty()) {
            subtitlePaint.color = COLOR_FAR
            subtitlePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(frame.source, area.right - margin, baseline, subtitlePaint)
            subtitlePaint.textAlign = Paint.Align.LEFT
        }
    }

    /**
     * La copertina, ridotta a pochi pixel, ridisegnata a schermo intero: l'ingrandimento con
     * filtro bilineare produce la sfocatura senza calcolarla.
     *
     * Sopra ci va comunque il velo scuro: qui si legge un testo guidando, e una copertina
     * chiara sotto parole bianche renderebbe l'app inutile proprio quando serve. Il colore
     * dell'album si intuisce, non domina.
     */
    private fun drawArtwork(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        val bitmap = frame.artwork
        if (bitmap == null || bitmap.isRecycled) {
            background.alpha = 255
            return
        }
        artworkPaint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, null, area, artworkPaint)
        // Velo nero pieno, poi il gradiente sopra in trasparenza. Il nero garantisce un
        // pavimento di contrasto che non dipende da quanto e' chiara la copertina: il colore
        // dell'album si intuisce, il testo resta leggibile su qualunque immagine.
        scrimPaint.color = COLOR_SCRIM
        canvas.drawRect(area, scrimPaint)
        background.alpha = ARTWORK_SCRIM_ALPHA
    }

    /**
     * Sfondo con una velatura verde appena accennata in alto, ricalcolata solo quando l'area
     * cambia: costruire uno shader a ogni fotogramma sarebbe spreco.
     */
    private fun ensureBackground(area: Rect) {
        if (area == gradientArea) return
        background.shader = LinearGradient(
            area.exactCenterX(), area.top.toFloat(),
            area.exactCenterX(), area.bottom.toFloat(),
            intArrayOf(COLOR_BACKGROUND_TOP, COLOR_BACKGROUND, COLOR_BACKGROUND),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        gradientArea = Rect(area)
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return text
        val kept = paint.breakText(text, true, maxWidth - paint.measureText("..."), null)
        return text.take(kept.coerceAtLeast(0)).trimEnd() + "..."
    }

    private companion object {
        const val ROW_SPACING = 1.28f
        const val LYRIC_SIZE = 25f

        /** Quanta larghezza puo' occupare l'intestazione prima dell'angolo riservato. */
        const val HEADER_WIDTH_RATIO = 0.68f

        /** Costante di tempo della telecamera: piu' alta, piu' morbido e piu' lento. */
        const val TAU = 0.11f

        const val REFERENCE_WIDTH = 360f
        const val REFERENCE_HEIGHT = 340f
        const val REFERENCE_CONTENT = 235f

        /** Quanto resta del gradiente sopra la copertina velata. */
        const val ARTWORK_SCRIM_ALPHA = 150

        /** Velo nero sotto il gradiente: e' lui a garantire il contrasto minimo del testo. */
        const val COLOR_SCRIM = 0xBE000000.toInt()

        const val COLOR_BACKGROUND = 0xFF0B0B0F.toInt()
        const val COLOR_BACKGROUND_TOP = 0xFF111A15.toInt()
        const val COLOR_GLOW = 0x667BE3A3
        const val COLOR_TITLE = Color.WHITE
        const val COLOR_ACTIVE = 0xFF7BE3A3.toInt()
        const val COLOR_NEAR = 0xFFD8D8DE.toInt()
        const val COLOR_FAR = 0xFF7E7E8A.toInt()
        const val COLOR_FAINT = 0xFF55555F.toInt()
        const val COLOR_DIM = 0xFF9A9AA5.toInt()
        const val COLOR_BAR_BACKGROUND = 0xFF2A2A33.toInt()
    }
}

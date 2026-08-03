package it.squarciagola.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import it.squarciagola.lyrics.LrcParser
import it.squarciagola.model.KaraokeFrame
import it.squarciagola.model.LyricLine
import it.squarciagola.model.Lyrics

/**
 * Disegna un fotogramma del karaoke dentro un rettangolo.
 *
 * Non conosce ne' Android Auto ne' Compose: riceve un Canvas e un'area, e disegna. E' la
 * ragione per cui lo stesso karaoke gira sullo schermo dell'auto e su quello del telefono
 * senza duplicare nulla.
 *
 * Le due forme sono molto diverse: in auto lo spazio e' largo e basso, sul telefono in
 * verticale e' stretto e alto. Per questo la dimensione del testo tiene conto di entrambe le
 * misure e le righe lunghe vanno a capo invece di essere troncate.
 *
 * I Paint sono campi e non variabili locali: questo metodo viene invocato trenta volte al
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

    fun draw(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        if (area.width() <= 0 || area.height() <= 0) return
        ensureBackground(area)
        canvas.drawRect(area, background)

        // Il fattore piu' stretto tra larghezza e altezza: il testo resta leggibile sia sullo
        // schermo largo dell'auto sia in verticale sul telefono, senza traboccare da nessuna
        // delle due parti.
        val scale = minOf(area.width() / REFERENCE_WIDTH, area.height() / REFERENCE_HEIGHT)
        titlePaint.textSize = 19f * scale
        subtitlePaint.textSize = 14f * scale
        activePaint.textSize = 28f * scale
        idlePaint.textSize = 22f * scale

        // Alone attorno alla riga che si sta cantando: la stacca dalle altre anche con lo
        // sguardo di sbieco, che in macchina e' l'unico sguardo disponibile.
        // ponytail: shadow layer, non un blur vero. Costa poco su testo corto; se un giorno
        // dovesse pesare, si passa a un livello disegnato a parte.
        activePaint.setShadowLayer(activePaint.textSize * 0.55f, 0f, 0f, COLOR_GLOW)

        drawHeader(canvas, area, frame)
        drawBody(canvas, area, frame)
        drawProgress(canvas, area, frame)
    }

    private fun drawHeader(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        if (frame.title.isEmpty()) return
        val left = area.left + area.width() * 0.05f
        val maxWidth = area.width() * 0.9f
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

        // Da dove arriva il testo: senza questo non c'e' modo di accorgersi che una sorgente
        // ha smesso di rispondere e sta lavorando quella di riserva.
        if (frame.source.isNotEmpty()) {
            subtitlePaint.color = COLOR_FAR
            subtitlePaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                frame.source,
                area.right - area.width() * 0.05f,
                area.top + titlePaint.textSize * 1.6f,
                subtitlePaint,
            )
            subtitlePaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawBody(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        val centerX = area.exactCenterX()
        val centerY = area.exactCenterY() + area.height() * 0.03f

        when (val lyrics = frame.lyrics) {
            is Lyrics.Synced -> drawSynced(canvas, area, lyrics.lines, frame.positionMs, centerX, centerY)
            is Lyrics.Plain -> drawPlain(canvas, area, lyrics, frame, centerX, centerY)
            else -> {
                idlePaint.color = COLOR_DIM
                val text = frame.message ?: if (frame.title.isEmpty()) "Silenzio in cabina"
                else "Di questo brano non si trova il testo"
                drawBlock(
                    canvas, area,
                    wrapFor(text, idlePaint, area.width() * 0.9f),
                    centerY - idlePaint.textSize,
                    idlePaint.textSize * ROW_SPACING,
                    idlePaint, COLOR_DIM, centerX,
                )
            }
        }
    }

    private fun drawSynced(
        canvas: Canvas,
        area: Rect,
        lines: List<LyricLine>,
        positionMs: Long,
        centerX: Float,
        centerY: Float,
    ) {
        if (lines.isEmpty()) return
        val active = LrcParser.activeIndex(lines, positionMs)
        val maxWidth = area.width() * 0.92f
        val activeRowHeight = activePaint.textSize * ROW_SPACING
        val idleRowHeight = idlePaint.textSize * ROW_SPACING
        val gap = idlePaint.textSize * 0.5f

        val activeRows =
            if (active in lines.indices) wrapFor(lines[active].text, activePaint, maxWidth) else emptyList()
        val activeHeight = activeRows.size * activeRowHeight

        // Scorrimento continuo: nell'ultimo tratto prima della riga successiva il blocco si
        // sposta gradualmente verso l'alto, invece di saltare di colpo.
        val shift = scrollShift(lines, active, positionMs) * (activeHeight + gap)
        val activeTop = centerY - activeHeight / 2f - shift

        if (activeRows.isNotEmpty()) {
            drawBlock(canvas, area, activeRows, activeTop, activeRowHeight, activePaint, COLOR_ACTIVE, centerX)
        }

        var top = activeTop
        for (offset in 1..VISIBLE_ABOVE) {
            val index = active - offset
            if (index < 0) break
            val rows = wrapFor(lines[index].text, idlePaint, maxWidth)
            top -= gap + rows.size * idleRowHeight
            if (top + rows.size * idleRowHeight < area.top) break
            drawBlock(canvas, area, rows, top, idleRowHeight, idlePaint, colorFor(offset), centerX)
        }

        var bottom = activeTop + activeHeight
        for (offset in 1..VISIBLE_BELOW) {
            val index = active + offset
            if (index !in lines.indices) break
            val rows = wrapFor(lines[index].text, idlePaint, maxWidth)
            bottom += gap
            if (bottom > area.bottom) break
            drawBlock(canvas, area, rows, bottom, idleRowHeight, idlePaint, colorFor(offset), centerX)
            bottom += rows.size * idleRowHeight
        }
    }

    private fun drawPlain(
        canvas: Canvas,
        area: Rect,
        lyrics: Lyrics.Plain,
        frame: KaraokeFrame,
        centerX: Float,
        centerY: Float,
    ) {
        val lines = lyrics.text.lines().map { it.trim() }
        if (lines.isEmpty()) return
        val maxWidth = area.width() * 0.92f
        val rowHeight = idlePaint.textSize * ROW_SPACING
        val gap = idlePaint.textSize * 0.5f

        // Testo senza timestamp: si scorre in proporzione alla posizione nel brano. Non e'
        // sincronia, e' un compromesso onesto per non lasciare fermo un muro di testo.
        val ratio = if (frame.durationMs > 0) frame.positionMs.toFloat() / frame.durationMs else 0f
        val focus = (ratio * lines.size).toInt().coerceIn(0, lines.size - 1)

        val focusRows = wrapFor(lines[focus], idlePaint, maxWidth)
        val focusTop = centerY - focusRows.size * rowHeight / 2f
        drawBlock(canvas, area, focusRows, focusTop, rowHeight, idlePaint, COLOR_ACTIVE, centerX)

        var top = focusTop
        for (offset in 1..VISIBLE_ABOVE) {
            val index = focus - offset
            if (index < 0) break
            val rows = wrapFor(lines[index], idlePaint, maxWidth)
            top -= gap + rows.size * rowHeight
            if (top + rows.size * rowHeight < area.top) break
            drawBlock(canvas, area, rows, top, rowHeight, idlePaint, colorFor(offset), centerX)
        }

        var bottom = focusTop + focusRows.size * rowHeight
        for (offset in 1..VISIBLE_BELOW) {
            val index = focus + offset
            if (index >= lines.size) break
            val rows = wrapFor(lines[index], idlePaint, maxWidth)
            bottom += gap
            if (bottom > area.bottom) break
            drawBlock(canvas, area, rows, bottom, rowHeight, idlePaint, colorFor(offset), centerX)
            bottom += rows.size * rowHeight
        }
    }

    /** Disegna un blocco di righe gia' mandate a capo, saltando quelle fuori dall'area. */
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
            val baseline = top + rowHeight * (index + 0.82f)
            if (baseline < area.top + paint.textSize || baseline > area.bottom) return@forEachIndexed
            canvas.drawText(row, centerX, baseline, paint)
        }
    }

    private fun colorFor(offset: Int) = if (offset == 1) COLOR_NEAR else COLOR_FAR

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

    private fun wrapFor(text: String, paint: Paint, maxWidth: Float): List<String> =
        TextWrapper.wrap(text, maxWidth) { paint.measureText(it) }

    /** Frazione di riga gia' percorsa, usata per lo scorrimento continuo. */
    private fun scrollShift(lines: List<LyricLine>, active: Int, positionMs: Long): Float {
        if (active < 0 || active + 1 >= lines.size) return 0f
        val remaining = lines[active + 1].timeMs - positionMs
        if (remaining >= SCROLL_LEAD_MS || remaining < 0) return 0f
        return 1f - remaining / SCROLL_LEAD_MS.toFloat()
    }

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

        subtitlePaint.color = COLOR_DIM
        canvas.drawText(
            "${clock(frame.positionMs)} / ${clock(frame.durationMs)}",
            area.left + margin,
            top + height + subtitlePaint.textSize * 1.3f,
            subtitlePaint,
        )
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
        const val VISIBLE_ABOVE = 3
        const val VISIBLE_BELOW = 4
        const val ROW_SPACING = 1.28f
        const val SCROLL_LEAD_MS = 260L

        /** Misure di riferimento su cui e' tarata la scala del testo. */
        const val REFERENCE_WIDTH = 420f
        const val REFERENCE_HEIGHT = 340f

        const val COLOR_BACKGROUND = 0xFF0B0B0F.toInt()
        const val COLOR_BACKGROUND_TOP = 0xFF111A15.toInt()
        const val COLOR_GLOW = 0x667BE3A3
        const val COLOR_TITLE = Color.WHITE
        const val COLOR_ACTIVE = 0xFF7BE3A3.toInt()
        const val COLOR_NEAR = 0xFFD8D8DE.toInt()
        const val COLOR_FAR = 0xFF6A6A75.toInt()
        const val COLOR_DIM = 0xFF9A9AA5.toInt()
        const val COLOR_BAR_BACKGROUND = 0xFF2A2A33.toInt()
    }
}

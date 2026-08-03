package it.squarciagola.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import it.squarciagola.lyrics.LrcParser
import it.squarciagola.model.KaraokeFrame
import it.squarciagola.model.Lyrics

/**
 * Disegna un fotogramma del karaoke dentro un rettangolo.
 *
 * Non conosce ne' Android Auto ne' Compose: riceve un Canvas e un'area, e disegna. E' la
 * ragione per cui lo stesso karaoke gira sullo schermo dell'auto e su quello del telefono
 * senza duplicare nulla.
 *
 * I Paint sono campi e non variabili locali: questo metodo viene invocato trenta volte al
 * secondo e allocare nel ciclo di disegno si vede.
 */
class KaraokeRenderer {

    private val background = Paint()
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
        background.color = COLOR_BACKGROUND
        canvas.drawRect(area, background)

        val scale = area.height() / 400f
        titlePaint.textSize = 20f * scale
        subtitlePaint.textSize = 15f * scale
        activePaint.textSize = 30f * scale
        idlePaint.textSize = 24f * scale

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
    }

    private fun drawBody(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        val centerX = area.exactCenterX()
        val centerY = area.exactCenterY() + area.height() * 0.04f

        when (val lyrics = frame.lyrics) {
            is Lyrics.Synced -> drawSynced(canvas, area, lyrics, frame.positionMs, centerX, centerY)
            is Lyrics.Plain -> drawPlain(canvas, area, lyrics, frame, centerX, centerY)
            else -> {
                idlePaint.color = COLOR_DIM
                val text = frame.message ?: if (frame.title.isEmpty()) "In attesa di Spotify"
                else "Nessun testo per questo brano"
                canvas.drawText(ellipsize(text, idlePaint, area.width() * 0.9f), centerX, centerY, idlePaint)
            }
        }
    }

    private fun drawSynced(
        canvas: Canvas,
        area: Rect,
        lyrics: Lyrics.Synced,
        positionMs: Long,
        centerX: Float,
        centerY: Float,
    ) {
        val lines = lyrics.lines
        val active = LrcParser.activeIndex(lines, positionMs)
        val rowHeight = activePaint.textSize * 1.7f
        val maxWidth = area.width() * 0.92f

        // Scorrimento continuo: nell'ultimo tratto prima della riga successiva le righe si
        // spostano gradualmente, invece di saltare di colpo.
        val shift = scrollShift(lines, active, positionMs)

        for (offset in -VISIBLE_ABOVE..VISIBLE_BELOW) {
            val index = active + offset
            if (index < 0 || index >= lines.size) continue
            val text = lines[index].text
            if (text.isEmpty()) continue

            val y = centerY + (offset - shift) * rowHeight
            if (y < area.top + rowHeight * 0.5f || y > area.bottom - rowHeight * 0.5f) continue

            val paint = if (offset == 0) activePaint else idlePaint
            paint.color = when {
                offset == 0 -> COLOR_ACTIVE
                offset == -1 || offset == 1 -> COLOR_NEAR
                else -> COLOR_FAR
            }
            canvas.drawText(ellipsize(text, paint, maxWidth), centerX, y, paint)
        }
    }

    /** Frazione di riga gia' percorsa, usata per lo scorrimento continuo. */
    private fun scrollShift(lines: List<it.squarciagola.model.LyricLine>, active: Int, positionMs: Long): Float {
        if (active < 0 || active + 1 >= lines.size) return 0f
        val next = lines[active + 1].timeMs
        val remaining = next - positionMs
        if (remaining >= SCROLL_LEAD_MS || remaining < 0) return 0f
        return 1f - remaining / SCROLL_LEAD_MS.toFloat()
    }

    private fun drawPlain(
        canvas: Canvas,
        area: Rect,
        lyrics: Lyrics.Plain,
        frame: KaraokeFrame,
        centerX: Float,
        centerY: Float,
    ) {
        val lines = lyrics.text.lines()
        val rowHeight = idlePaint.textSize * 1.6f
        val maxWidth = area.width() * 0.92f
        // Testo senza timestamp: si scorre in proporzione alla posizione nel brano. Non e'
        // sincronia, e' un compromesso onesto per non lasciare fermo un muro di testo.
        val ratio = if (frame.durationMs > 0) frame.positionMs.toFloat() / frame.durationMs else 0f
        val focus = (ratio * lines.size).toInt().coerceIn(0, (lines.size - 1).coerceAtLeast(0))

        idlePaint.color = COLOR_NEAR
        for (offset in -VISIBLE_ABOVE..VISIBLE_BELOW) {
            val index = focus + offset
            if (index < 0 || index >= lines.size) continue
            val text = lines[index].trim()
            if (text.isEmpty()) continue
            val y = centerY + offset * rowHeight
            if (y < area.top + rowHeight || y > area.bottom - rowHeight) continue
            idlePaint.color = if (offset == 0) COLOR_ACTIVE else COLOR_FAR
            canvas.drawText(ellipsize(text, idlePaint, maxWidth), centerX, y, idlePaint)
        }
    }

    private fun drawProgress(canvas: Canvas, area: Rect, frame: KaraokeFrame) {
        if (frame.durationMs <= 0) return
        val margin = area.width() * 0.05f
        val height = area.height() * 0.012f
        val top = area.bottom - area.height() * 0.09f
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
            top + height + subtitlePaint.textSize * 1.4f,
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
        const val VISIBLE_ABOVE = 2
        const val VISIBLE_BELOW = 3
        const val SCROLL_LEAD_MS = 260L

        const val COLOR_BACKGROUND = 0xFF0B0B0F.toInt()
        const val COLOR_TITLE = Color.WHITE
        const val COLOR_ACTIVE = 0xFF7BE3A3.toInt()
        const val COLOR_NEAR = 0xFFD8D8DE.toInt()
        const val COLOR_FAR = 0xFF6A6A75.toInt()
        const val COLOR_DIM = 0xFF9A9AA5.toInt()
        const val COLOR_BAR_BACKGROUND = 0xFF2A2A33.toInt()
    }
}

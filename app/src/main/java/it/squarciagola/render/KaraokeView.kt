package it.squarciagola.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import it.squarciagola.Engine

/**
 * Il karaoke sullo schermo del telefono. Stesso renderer usato in auto, altro contenitore.
 *
 * Si ridisegna a ogni vsync solo mentre e' attaccata alla finestra: staccata, il
 * Choreographer viene sganciato e la vista smette di consumare.
 */
class KaraokeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val renderer = KaraokeRenderer()
    private val area = Rect()

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(tick)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        area.set(0, 0, width, height)
        renderer.draw(canvas, area, Engine.currentFrame())
    }
}

package it.squarciagola.car

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import it.squarciagola.Engine
import it.squarciagola.render.KaraokeRenderer

/**
 * Disegna il karaoke sulla Surface fornita dall'host di Android Auto.
 *
 * Il ciclo gira a circa 30 fotogrammi al secondo: basta per uno scorrimento fluido e non ha
 * senso spingersi oltre, visto che il contenuto cambia poche volte al secondo.
 *
 * Si disegna dentro l'area visibile comunicata dall'host, non sull'intera Surface: parte
 * dello schermo può essere coperta dai controlli di sistema, e quello che ci finisce sotto
 * non lo vede nessuno.
 */
class CarSurfaceRenderer(private val carContext: CarContext) : DefaultLifecycleObserver, SurfaceCallback {

    private val renderer = KaraokeRenderer()
    private val handler = Handler(Looper.getMainLooper())
    private val area = Rect()
    private var container: SurfaceContainer? = null

    private val frameLoop = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacks(frameLoop)
        container = null
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        if (area.isEmpty) {
            area.set(0, 0, surfaceContainer.width, surfaceContainer.height)
        }
        handler.removeCallbacks(frameLoop)
        handler.post(frameLoop)
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        if (!visibleArea.isEmpty) area.set(visibleArea)
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (area.isEmpty && !stableArea.isEmpty) area.set(stableArea)
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        handler.removeCallbacks(frameLoop)
        container = null
    }

    private fun render() {
        val surface = container?.surface ?: return
        if (!surface.isValid) return
        val canvas = try {
            surface.lockCanvas(null)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        try {
            renderer.draw(canvas, area, Engine.currentFrame())
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }

    private companion object {
        const val FRAME_INTERVAL_MS = 33L
    }
}

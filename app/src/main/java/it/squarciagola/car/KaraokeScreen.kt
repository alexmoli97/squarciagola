package it.squarciagola.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import it.squarciagola.Engine

/**
 * La schermata karaoke in auto.
 *
 * Il template serve solo a reggere la Surface e la barra dei comandi: tutto il disegno
 * avviene in [CarSurfaceRenderer]. I due pulsanti regolano la sincronia, che cambia da
 * impianto a impianto per via del ritardo del Bluetooth e va corretta sul posto.
 */
class KaraokeScreen(carContext: CarContext) : Screen(carContext) {

    private val surfaceRenderer = CarSurfaceRenderer(carContext)

    init {
        lifecycle.addObserver(surfaceRenderer)
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(offsetAction("-100 ms", -100))
            .addAction(offsetAction("+100 ms", +100))
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setBackgroundColor(CarColor.PRIMARY)
            .build()
    }

    private fun offsetAction(title: String, deltaMs: Long) = Action.Builder()
        .setTitle(title)
        .setOnClickListener {
            Engine.offsetMs += deltaMs
            invalidate()
        }
        .build()
}

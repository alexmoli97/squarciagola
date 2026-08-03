package it.squarciagola.playback

import it.squarciagola.model.PlaybackState

/**
 * Ricava la posizione corrente nel brano interpolando tra due campionamenti della Web API.
 *
 * Logica pura e senza dipendenze Android proprio per poterla testare: e' il punto in cui un
 * difetto non si vede, si sente soltanto come testo fuori sincrono.
 */
object PositionClock {

    /**
     * @param nowElapsedRealtime valore corrente di SystemClock.elapsedRealtime()
     * @param offsetMs calibrazione manuale, compensa la latenza audio del Bluetooth
     */
    fun positionMs(state: PlaybackState, nowElapsedRealtime: Long, offsetMs: Long): Long {
        val track = state.track ?: return 0L
        val base = if (state.isPlaying) {
            state.progressMs + (nowElapsedRealtime - state.sampledAtElapsedRealtime)
        } else {
            state.progressMs
        }
        return (base + offsetMs).coerceIn(0L, track.durationMs)
    }
}

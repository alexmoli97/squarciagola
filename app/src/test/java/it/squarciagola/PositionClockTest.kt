package it.squarciagola

import it.squarciagola.model.PlaybackState
import it.squarciagola.model.TrackMeta
import it.squarciagola.playback.PositionClock
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionClockTest {

    private val track = TrackMeta("id", "Titolo", "Artista", "Album", durationMs = 200_000)

    private fun state(progressMs: Long, playing: Boolean, sampledAt: Long = 1_000) =
        PlaybackState(track, progressMs, playing, sampledAt)

    @Test
    fun `in riproduzione interpola il tempo trascorso dal campionamento`() {
        val s = state(progressMs = 10_000, playing = true, sampledAt = 1_000)
        assertEquals(12_500, PositionClock.positionMs(s, nowElapsedRealtime = 3_500, offsetMs = 0))
    }

    @Test
    fun `in pausa la posizione resta ferma comunque passi il tempo`() {
        val s = state(progressMs = 10_000, playing = false, sampledAt = 1_000)
        assertEquals(10_000, PositionClock.positionMs(s, nowElapsedRealtime = 99_000, offsetMs = 0))
    }

    @Test
    fun `l offset sposta la posizione in entrambe le direzioni`() {
        val s = state(progressMs = 10_000, playing = false)
        assertEquals(10_400, PositionClock.positionMs(s, 1_000, offsetMs = 400))
        assertEquals(9_600, PositionClock.positionMs(s, 1_000, offsetMs = -400))
    }

    @Test
    fun `la posizione non esce mai dai limiti del brano`() {
        val s = state(progressMs = 199_000, playing = true, sampledAt = 0)
        assertEquals(200_000, PositionClock.positionMs(s, nowElapsedRealtime = 60_000, offsetMs = 0))

        val inizio = state(progressMs = 100, playing = false)
        assertEquals(0, PositionClock.positionMs(inizio, 1_000, offsetMs = -5_000))
    }

    @Test
    fun `senza traccia la posizione e zero`() {
        assertEquals(0, PositionClock.positionMs(PlaybackState.IDLE, 50_000, offsetMs = 250))
    }
}

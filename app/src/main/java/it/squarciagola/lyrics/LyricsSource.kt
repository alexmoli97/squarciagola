package it.squarciagola.lyrics

import it.squarciagola.model.Lyrics
import it.squarciagola.model.TrackMeta

/**
 * Una sorgente di testi. Le implementazioni vengono provate in ordine finche' una risponde.
 *
 * Restituire null significa "io non ce l'ho, prova la prossima", e vale sia per il brano
 * assente dal catalogo sia per la sorgente temporaneamente rotta. La distinzione non serve
 * a chi chiama: in entrambi i casi si passa oltre.
 */
interface LyricsSource {
    val name: String
    fun fetch(track: TrackMeta): Lyrics?
}

package it.squarciagola

import it.squarciagola.ui.Accento
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentoTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Il fondo del karaoke: qualunque accento deve staccarsi da qui. */
    private val fondo = rgb(11, 11, 15)

    private fun contrasto(colore: Int): Float {
        val a = Accento.luminanza(colore) + 0.05f
        val b = Accento.luminanza(fondo) + 0.05f
        return if (a > b) a / b else b / a
    }

    @Test
    fun `un colore quasi nero viene riportato a una luminosita visibile`() {
        val risultato = Accento.leggibile(rgb(20, 6, 4))
        assertTrue("contrasto ${contrasto(risultato)}", contrasto(risultato) >= 4.5f)
    }

    @Test
    fun `un colore accecante viene riportato in basso`() {
        val risultato = Accento.leggibile(rgb(255, 255, 40))
        assertTrue(Accento.luminanza(risultato) < Accento.luminanza(rgb(255, 255, 40)))
    }

    @Test
    fun `la tinta si conserva`() {
        // Un rosso scuro resta rosso: il canale rosso deve restare il dominante.
        val risultato = Accento.leggibile(rgb(90, 10, 10))
        val r = (risultato shr 16) and 0xFF
        val g = (risultato shr 8) and 0xFF
        val b = risultato and 0xFF
        assertTrue("$r $g $b", r > g && r > b)
    }

    @Test
    fun `un grigio non diventa un accento grigio`() {
        assertEquals(Accento.PREDEFINITO, Accento.leggibile(rgb(128, 128, 130)))
        assertEquals(Accento.PREDEFINITO, Accento.leggibile(rgb(0, 0, 0)))
    }

    @Test
    fun `il dominante segue il colore vivo e non quello piu diffuso`() {
        // Mille pixel di grigio spento contro cento di blu acceso: deve vincere il blu.
        val pixels = IntArray(1100) { if (it < 1000) rgb(90, 90, 92) else rgb(30, 80, 220) }
        val risultato = Accento.dominante(pixels)
        val r = (risultato shr 16) and 0xFF
        val b = risultato and 0xFF
        assertTrue("atteso blu, ottenuto ${Integer.toHexString(risultato)}", b > r)
    }

    @Test
    fun `una copertina senza colore ricade sul predefinito`() {
        val pixels = IntArray(500) { rgb(10, 10, 10) }
        assertEquals(Accento.PREDEFINITO, Accento.dominante(pixels))
        assertEquals(Accento.PREDEFINITO, Accento.dominante(IntArray(0)))
    }

    @Test
    fun `ogni accento prodotto e leggibile sul fondo scuro`() {
        val campioni = listOf(
            rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255),
            rgb(255, 200, 0), rgb(120, 0, 200), rgb(5, 40, 30),
        )
        for (colore in campioni) {
            val accento = Accento.leggibile(colore)
            assertTrue(
                "contrasto ${contrasto(accento)} per ${Integer.toHexString(colore)}",
                contrasto(accento) >= 4.5f,
            )
        }
    }
}

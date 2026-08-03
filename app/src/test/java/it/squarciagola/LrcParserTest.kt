package it.squarciagola

import it.squarciagola.lyrics.LrcParser
import it.squarciagola.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `legge minuti secondi e centesimi`() {
        val lines = LrcParser.parse("[00:12.34]prima\n[01:05.00]seconda")
        assertEquals(listOf(12_340L, 65_000L), lines.map { it.timeMs })
        assertEquals(listOf("prima", "seconda"), lines.map { it.text })
    }

    @Test
    fun `i millesimi non vengono scambiati per centesimi`() {
        assertEquals(1_005L, LrcParser.parse("[00:01.005]x").single().timeMs)
        assertEquals(1_050L, LrcParser.parse("[00:01.05]x").single().timeMs)
        assertEquals(1_500L, LrcParser.parse("[00:01.5]x").single().timeMs)
    }

    @Test
    fun `piu timestamp sulla stessa riga generano piu occorrenze`() {
        val lines = LrcParser.parse("[00:10.00][01:10.00]ritornello")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.text == "ritornello" })
        assertEquals(listOf(10_000L, 70_000L), lines.map { it.timeMs })
    }

    @Test
    fun `i tag di metadata vengono ignorati`() {
        val lines = LrcParser.parse("[ar:Artista]\n[ti:Titolo]\n[offset:+200]\n[00:01.00]testo")
        assertEquals(1, lines.size)
        assertEquals("testo", lines.single().text)
    }

    @Test
    fun `le righe vuote restano perche segnano le pause`() {
        val lines = LrcParser.parse("[00:01.00]testo\n[00:20.00]")
        assertEquals(2, lines.size)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `l ordine e sempre crescente anche se il file e disordinato`() {
        val lines = LrcParser.parse("[00:30.00]terza\n[00:10.00]prima\n[00:20.00]seconda")
        assertEquals(listOf("prima", "seconda", "terza"), lines.map { it.text })
    }

    @Test
    fun `i secondi fuori scala vengono scartati`() {
        assertTrue(LrcParser.parse("[00:75.00]sbagliata").isEmpty())
    }

    @Test
    fun `activeIndex trova la riga in corso`() {
        val lines = listOf(
            LyricLine(1_000, "a"),
            LyricLine(5_000, "b"),
            LyricLine(9_000, "c"),
        )
        assertEquals(-1, LrcParser.activeIndex(lines, 0))
        assertEquals(0, LrcParser.activeIndex(lines, 1_000))
        assertEquals(0, LrcParser.activeIndex(lines, 4_999))
        assertEquals(1, LrcParser.activeIndex(lines, 5_000))
        assertEquals(2, LrcParser.activeIndex(lines, 999_999))
    }

    @Test
    fun `activeIndex su lista vuota non esplode`() {
        assertEquals(-1, LrcParser.activeIndex(emptyList(), 1_000))
    }
}

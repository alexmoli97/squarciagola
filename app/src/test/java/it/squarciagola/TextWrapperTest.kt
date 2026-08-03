package it.squarciagola

import it.squarciagola.render.TextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWrapperTest {

    /** Un carattere vale un'unità: così le larghezze attese si leggono a occhio. */
    private val measure: (String) -> Float = { it.length.toFloat() }

    private fun wrap(text: String, maxWidth: Float) = TextWrapper.wrap(text, maxWidth, measure)

    @Test
    fun `una riga che ci sta resta intera`() {
        assertEquals(listOf("corta"), wrap("corta", 10f))
    }

    @Test
    fun `manda a capo sugli spazi senza superare la larghezza`() {
        val rows = wrap("uno due tre quattro", 8f)
        assertTrue(rows.all { it.length <= 8 })
        assertEquals("uno due tre quattro", rows.joinToString(" "))
    }

    @Test
    fun `una parola piu larga della riga viene spezzata`() {
        val rows = wrap("abcdefghij", 4f)
        assertTrue(rows.all { it.length <= 4 })
        assertEquals("abcdefghij", rows.joinToString(""))
    }

    @Test
    fun `parola lunghissima in mezzo ad altre non manda in loop`() {
        val rows = wrap("ok abcdefghijklmno fine", 5f)
        assertTrue(rows.all { it.length <= 5 })
        assertTrue(rows.isNotEmpty())
    }

    @Test
    fun `riga vuota resta una riga vuota e occupa spazio`() {
        assertEquals(listOf(""), wrap("", 10f))
    }

    @Test
    fun `larghezza non valida non spezza nulla`() {
        assertEquals(listOf("qualcosa"), wrap("qualcosa", 0f))
    }

    @Test
    fun `gli spazi doppi non producono righe vuote`() {
        val rows = wrap("uno  due", 3f)
        assertEquals(listOf("uno", "due"), rows)
    }
}

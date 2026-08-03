package it.squarciagola

import it.squarciagola.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    private fun release(tag: String, assetName: String = "squarciagola.apk") = """
        {
          "tag_name": "$tag",
          "name": "Squarciagola 1.2",
          "body": "Note di rilascio",
          "assets": [
            {"name": "sorgenti.zip", "browser_download_url": "https://esempio/sorgenti.zip"},
            {"name": "$assetName", "browser_download_url": "https://esempio/$assetName"}
          ]
        }
    """.trimIndent()

    @Test
    fun `ricava il versionCode dal tag`() {
        val parsed = UpdateChecker.parse(release("v7"))!!
        assertEquals(7, parsed.versionCode)
        assertEquals("Squarciagola 1.2", parsed.versionName)
        assertEquals("https://esempio/squarciagola.apk", parsed.apkUrl)
        assertEquals("Note di rilascio", parsed.notes)
    }

    @Test
    fun `il tag funziona anche senza la v iniziale`() {
        assertEquals(12, UpdateChecker.parse(release("12"))!!.versionCode)
    }

    @Test
    fun `sceglie l apk anche se non e il primo allegato`() {
        val parsed = UpdateChecker.parse(release("v3", "app-debug.apk"))!!
        assertEquals("https://esempio/app-debug.apk", parsed.apkUrl)
    }

    @Test
    fun `senza apk allegato non propone nulla`() {
        val senzaApk = """
            {"tag_name": "v4", "assets": [{"name": "note.txt", "browser_download_url": "https://esempio/note.txt"}]}
        """.trimIndent()
        assertNull(UpdateChecker.parse(senzaApk))
    }

    @Test
    fun `tag senza numero viene scartato`() {
        assertNull(UpdateChecker.parse(release("beta")))
    }

    @Test
    fun `risposta non valida non fa esplodere nulla`() {
        assertNull(UpdateChecker.parse("non sono json"))
        assertNull(UpdateChecker.parse("{}"))
    }
}

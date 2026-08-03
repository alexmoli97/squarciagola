package it.squarciagola.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * L'accento dell'interfaccia ricavato dalla copertina del brano.
 *
 * Non e' decorazione: la regola resta quella di sempre, un solo accento che marca la riga che
 * si sta cantando e l'azione principale. Cambia che quel colore lo detta la musica, quindi
 * ogni brano ha il suo e l'app cambia insieme a cio' che ascolti.
 *
 * Il vincolo che non si negozia e' la leggibilita': una copertina scura o slavata darebbe un
 * accento invisibile sul fondo nero, quindi qualunque colore viene riportato dentro una
 * finestra di saturazione e luminosita' prima di essere usato. Matematica su HSL scritta a
 * mano invece di android.graphics.Color, cosi' resta verificabile con un test normale.
 */
object Accento {

    /** Il verde menta di sempre: si usa quando non c'e' copertina o il colore non regge. */
    const val PREDEFINITO = 0xFF7BE3A3.toInt()

    private const val SATURAZIONE_MIN = 0.35f
    private const val SATURAZIONE_MAX = 0.85f
    private const val LUMINOSITA_MIN = 0.62f
    private const val LUMINOSITA_MAX = 0.78f

    /**
     * Colore dominante di un'immagine, scelto per quanto e' vivo e non per quanto e' esteso:
     * il grigio del fondo copertina occupa piu' pixel di qualunque cosa interessante.
     */
    fun dominante(pixels: IntArray): Int {
        if (pixels.isEmpty()) return PREDEFINITO

        val pesi = FloatArray(SETTORI)
        val sommaR = FloatArray(SETTORI)
        val sommaG = FloatArray(SETTORI)
        val sommaB = FloatArray(SETTORI)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val hsl = versoHsl(r, g, b)
            val saturazione = hsl[1]
            val luminosita = hsl[2]
            // I pixel spenti o quasi neri o quasi bianchi non dicono nulla sul colore del disco.
            if (saturazione < 0.2f || luminosita < 0.12f || luminosita > 0.92f) continue

            val settore = ((hsl[0] / 360f) * SETTORI).toInt().coerceIn(0, SETTORI - 1)
            val peso = saturazione * saturazione
            pesi[settore] += peso
            sommaR[settore] += r * peso
            sommaG[settore] += g * peso
            sommaB[settore] += b * peso
        }

        var migliore = -1
        for (i in 0 until SETTORI) if (migliore < 0 || pesi[i] > pesi[migliore]) migliore = i
        if (migliore < 0 || pesi[migliore] <= 0f) return PREDEFINITO

        val peso = pesi[migliore]
        return leggibile(
            componi(
                (sommaR[migliore] / peso).toInt(),
                (sommaG[migliore] / peso).toInt(),
                (sommaB[migliore] / peso).toInt(),
            )
        )
    }

    /**
     * Riporta un colore dentro la finestra in cui resta visibile sul fondo scuro e non
     * abbaglia. La tinta si conserva: cambiano solo quanto e' carico e quanto e' chiaro.
     */
    fun leggibile(colore: Int): Int {
        val r = (colore shr 16) and 0xFF
        val g = (colore shr 8) and 0xFF
        val b = colore and 0xFF
        val hsl = versoHsl(r, g, b)
        // Un grigio non ha tinta da conservare: meglio l'accento di sempre che un grigio chiaro.
        if (hsl[1] < 0.08f) return PREDEFINITO

        val tinta = hsl[0]
        val saturazione = hsl[1].coerceIn(SATURAZIONE_MIN, SATURAZIONE_MAX)
        var luminosita = hsl[2].coerceIn(LUMINOSITA_MIN, LUMINOSITA_MAX)

        // La luminosita' HSL non e' la luminanza percepita: un blu al 62 per cento resta
        // scuro perche' il canale blu pesa poco nell'occhio. Si alza finche' il contrasto sul
        // fondo e' davvero raggiunto, invece di fidarsi di una soglia nominale.
        var colore = daHsl(tinta, saturazione, luminosita)
        while (contrastoSulFondo(colore) < CONTRASTO_MINIMO && luminosita < 0.94f) {
            luminosita += 0.02f
            colore = daHsl(tinta, saturazione, luminosita)
        }
        return colore
    }

    /** Rapporto di contrasto rispetto al fondo del karaoke. */
    private fun contrastoSulFondo(colore: Int): Float {
        val a = luminanza(colore) + 0.05f
        val b = luminanza(FONDO) + 0.05f
        return if (a > b) a / b else b / a
    }

    /** Luminanza relativa, per verificare il contrasto sul fondo scuro. */
    fun luminanza(colore: Int): Float {
        fun canale(v: Int): Float {
            val s = v / 255f
            return if (s <= 0.03928f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
        return 0.2126f * canale((colore shr 16) and 0xFF) +
            0.7152f * canale((colore shr 8) and 0xFF) +
            0.0722f * canale(colore and 0xFF)
    }

    // --- conversioni ----------------------------------------------------------------------

    /** Restituisce tinta in gradi, saturazione e luminosita' fra 0 e 1. */
    private fun versoHsl(r: Int, g: Int, b: Int): FloatArray {
        val rn = r / 255f
        val gn = g / 255f
        val bn = b / 255f
        val massimo = max(rn, max(gn, bn))
        val minimo = min(rn, min(gn, bn))
        val delta = massimo - minimo
        val luminosita = (massimo + minimo) / 2f

        if (delta < 0.0001f) return floatArrayOf(0f, 0f, luminosita)

        val saturazione = delta / (1f - abs(2f * luminosita - 1f))
        val tinta = when (massimo) {
            rn -> 60f * (((gn - bn) / delta) % 6f)
            gn -> 60f * (((bn - rn) / delta) + 2f)
            else -> 60f * (((rn - gn) / delta) + 4f)
        }
        return floatArrayOf((tinta + 360f) % 360f, saturazione.coerceIn(0f, 1f), luminosita)
    }

    private fun daHsl(tinta: Float, saturazione: Float, luminosita: Float): Int {
        val c = (1f - abs(2f * luminosita - 1f)) * saturazione
        val x = c * (1f - abs(((tinta / 60f) % 2f) - 1f))
        val m = luminosita - c / 2f
        val (r, g, b) = when {
            tinta < 60f -> Triple(c, x, 0f)
            tinta < 120f -> Triple(x, c, 0f)
            tinta < 180f -> Triple(0f, c, x)
            tinta < 240f -> Triple(0f, x, c)
            tinta < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return componi(
            ((r + m) * 255f).toInt(),
            ((g + m) * 255f).toInt(),
            ((b + m) * 255f).toInt(),
        )
    }

    private fun componi(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    /** Numero di settori di tinta su cui si raggruppano i pixel. */
    private const val SETTORI = 24

    /** Il fondo su cui l'accento deve staccarsi, lo stesso del karaoke. */
    private const val FONDO = 0xFF0B0B0F.toInt()
    private const val CONTRASTO_MINIMO = 4.5f
}

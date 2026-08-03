package it.squarciagola.render

/**
 * Manda a capo una riga di testo entro una larghezza data.
 *
 * La misura arriva da fuori invece di usare Paint direttamente: cosi' la logica di
 * spezzatura resta verificabile con un test normale, senza dipendere dal framework grafico.
 */
object TextWrapper {

    fun wrap(text: String, maxWidth: Float, measure: (String) -> Float): List<String> {
        if (text.isEmpty()) return listOf("")
        if (maxWidth <= 0f || measure(text) <= maxWidth) return listOf(text)

        val rows = ArrayList<String>()
        var current = ""

        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (measure(candidate) <= maxWidth) {
                current = candidate
                continue
            }
            if (current.isNotEmpty()) {
                rows.add(current)
                current = ""
            }
            // Parola piu' larga della riga intera: si spezza a forza, altrimenti sparirebbe
            // oltre il bordo. Capita con i titoli piu' che con i testi, ma capita.
            var remaining = word
            while (measure(remaining) > maxWidth && remaining.length > 1) {
                var cut = remaining.length
                while (cut > 1 && measure(remaining.take(cut)) > maxWidth) cut--
                rows.add(remaining.take(cut))
                remaining = remaining.drop(cut)
            }
            current = remaining
        }

        if (current.isNotEmpty()) rows.add(current)
        return if (rows.isEmpty()) listOf(text) else rows
    }
}

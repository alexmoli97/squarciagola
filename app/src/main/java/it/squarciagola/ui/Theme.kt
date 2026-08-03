package it.squarciagola.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tema unico per la schermata di setup e per il karaoke.
 *
 * Solo scuro, e non per estetica: quest'app si guarda al buio, in macchina di sera o col
 * telefono nel supporto in abitacolo. Uno schermo chiaro in quelle condizioni acceca.
 *
 * Il verde menta non e' decorazione, e' l'unico accento: marca la riga che si sta cantando
 * nel karaoke e l'azione principale nella schermata. Tutto il resto vive di neutri.
 */
private val SquarciagolaColors = darkColorScheme(
    primary = Color(0xFF7BE3A3),
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF175034),
    onPrimaryContainer = Color(0xFF98FFBE),

    secondary = Color(0xFFB6CCBC),
    onSecondary = Color(0xFF21372A),
    secondaryContainer = Color(0xFF33493B),
    onSecondaryContainer = Color(0xFFD2E8D8),

    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFE6E6EC),
    surface = Color(0xFF0B0B0F),
    onSurface = Color(0xFFE6E6EC),
    surfaceVariant = Color(0xFF1C1C22),
    onSurfaceVariant = Color(0xFFC2C2CC),
    surfaceContainer = Color(0xFF16161C),
    surfaceContainerHigh = Color(0xFF1E1E25),
    surfaceContainerHighest = Color(0xFF262630),

    outline = Color(0xFF4A4A54),
    outlineVariant = Color(0xFF2C2C34),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val SquarciagolaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SquarciagolaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SquarciagolaColors,
        shapes = SquarciagolaShapes,
        content = content,
    )
}

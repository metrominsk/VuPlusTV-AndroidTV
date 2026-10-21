package tv.vuplus.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF090E16)
val Card = Color(0xFF131B27)
val CardFocused = Color(0xFF1F2C3F)
val CardSelected = Color(0xFF16233A)
val Panel = Color(0xF2101823)
val Line = Color(0xFF2A3748)
val Accent = Color(0xFF5AC8FA)
val TextPrimary = Color(0xFFEAF0F7)
val Muted = Color(0xFF93A3B8)
val Danger = Color(0xFFFF8A80)
val InfoYellow = Color(0xFFFFD54F)
val InfoGreen = Color(0xFF7BE08A)
val PiconBg = Color(0xFF1B2533)

@Composable
fun VuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Ink,
            surface = Card,
            onSurface = TextPrimary,
            onSurfaceVariant = Muted,
            outline = Line,
        ),
    ) {
        // Surface задаёт цвет текста по умолчанию. Без него Text в Material3 чёрный на тёмном фоне.
        Surface(modifier = Modifier.fillMaxSize(), color = Ink, contentColor = TextPrimary, content = content)
    }
}

package dev.ninecontroller.android.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same tokens as the desktop companion (hosts/app/ui/styles.css). */
object Nine {
    val bg = Color(0xFF0B0C0D)
    val s1 = Color(0xFF111315)
    val s2 = Color(0xFF171A1C)
    val text = Color(0xFFEEF0F1)
    val muted = text.copy(alpha = 0.62f)
    val dim = text.copy(alpha = 0.42f)
    val line = text.copy(alpha = 0.08f)
    val line2 = text.copy(alpha = 0.14f)
    val accent = Color(0xFFC6F432)
    val accentInk = Color(0xFF11140A)
    val warn = Color(0xFFF5B14C)
    val bad = Color(0xFFF2675A)
    val card = RoundedCornerShape(14.dp)
    val small = RoundedCornerShape(8.dp)
    val pill = RoundedCornerShape(999.dp)
    val mono = FontFamily.Monospace
}

private val scheme = darkColorScheme(
    primary = Nine.accent, onPrimary = Nine.accentInk, secondary = Nine.text, onSecondary = Nine.bg,
    background = Nine.bg, onBackground = Nine.text, surface = Nine.s1, onSurface = Nine.text,
    surfaceVariant = Nine.s2, onSurfaceVariant = Nine.muted, outline = Nine.line2, error = Nine.bad,
    surfaceContainer = Nine.s1, surfaceContainerHigh = Nine.s2, surfaceContainerHighest = Nine.s2,
)

@Composable
fun NineTheme(content: @Composable () -> Unit) {
    val t = MaterialTheme.typography
    MaterialTheme(
        colorScheme = scheme,
        typography = t.copy(
            headlineLarge = t.headlineLarge.copy(fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp),
            headlineSmall = t.headlineSmall.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp),
            titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
            bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        ),
        content = content,
    )
}

/** 11sp uppercase micro-label. */
@Composable
fun Micro(text: String, modifier: Modifier = Modifier, color: Color = Nine.muted) =
    Text(text.uppercase(), modifier, color = color, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.7.sp))

@Composable
fun primaryButtonColors() = ButtonDefaults.buttonColors(containerColor = Nine.accent, contentColor = Nine.accentInk)

val ButtonPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)

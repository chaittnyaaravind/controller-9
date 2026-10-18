package dev.ninecontroller.android.ui.theme

import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Controller-9 design tokens — "graphite precision". Same values as the desktop companion
 * (hosts/app/ui/styles.css) and docs/DESIGN_SYSTEM.md. Components use these, never raw values.
 *
 * Surfaces step up in lightness; secondary text is the text colour at lower alpha (never a grey);
 * hairlines are tinted text at 8 / 14 %; one accent (lime) for live / active / primary only.
 */
object Nine {
    val bg = Color(0xFF0B0C0D)
    val s1 = Color(0xFF111315)
    val s2 = Color(0xFF171A1C)
    val s3 = Color(0xFF1E2225)
    val text = Color(0xFFEEF0F1)
    val muted = text.copy(alpha = 0.66f)   // 7.9:1 on bg
    val dim = text.copy(alpha = 0.46f)     // 4.7:1 on bg (AA for body text)
    val faint = text.copy(alpha = 0.22f)   // decoration only, never text
    val line = text.copy(alpha = 0.08f)
    val line2 = text.copy(alpha = 0.14f)
    val accent = Color(0xFFC6F432)
    val accentInk = Color(0xFF11140A)
    val accentSoft = accent.copy(alpha = 0.12f)
    val warn = Color(0xFFF5B14C)
    val bad = Color(0xFFF2675A)
    val warnSoft = warn.copy(alpha = 0.10f)
    val badSoft = bad.copy(alpha = 0.10f)

    // Radius family: small (chips, inputs), card, pill.
    val small = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(999.dp)

    // Spacing (4/8 scale).
    val gutter = 20.dp
    val gap = 12.dp
    val section = 28.dp
    /** Minimum touch target (WCAG 2.5.8 / Android guidance). */
    val target = 48.dp

    val mono = FontFamily.Monospace
}

/** Type roles. Display is light-ish and tight; labels are small caps; metrics are monospaced. */
object NineType {
    val display = TextStyle(fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.6).sp)
    val title = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.7.sp)
    val metric = TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontFamily = FontFamily.Monospace, letterSpacing = (-0.4).sp)
    val mono = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
}

private val scheme = darkColorScheme(
    primary = Nine.accent, onPrimary = Nine.accentInk, secondary = Nine.text, onSecondary = Nine.bg,
    background = Nine.bg, onBackground = Nine.text, surface = Nine.s1, onSurface = Nine.text,
    surfaceVariant = Nine.s2, onSurfaceVariant = Nine.muted, outline = Nine.line2, outlineVariant = Nine.line, error = Nine.bad,
    surfaceContainer = Nine.s1, surfaceContainerHigh = Nine.s2, surfaceContainerHighest = Nine.s3, surfaceContainerLow = Nine.s1,
)

/** True when the user turned animations off (Settings → Accessibility → Remove animations). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun NineTheme(content: @Composable () -> Unit) {
    val t = MaterialTheme.typography
    val context = LocalContext.current
    val reduced = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = t.copy(
            headlineLarge = t.headlineLarge.merge(NineType.display),
            headlineSmall = t.headlineSmall.merge(NineType.title),
            titleLarge = t.titleLarge.merge(NineType.title),
            titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Medium),
            bodyLarge = t.bodyLarge.merge(NineType.body),
            bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        ),
    ) {
        // No Material Surface wraps our screens, so set the default text colour explicitly
        // (otherwise Text falls back to black on the dark background).
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Nine.text, LocalReducedMotion provides reduced, content = content)
    }
}

/** 11sp uppercase micro-label. */
@Composable
fun Micro(text: String, modifier: Modifier = Modifier, color: Color = Nine.muted) =
    Text(text.uppercase(), modifier, color = color, style = NineType.label)

@Composable
fun primaryButtonColors() = ButtonDefaults.buttonColors(containerColor = Nine.accent, contentColor = Nine.accentInk)

val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

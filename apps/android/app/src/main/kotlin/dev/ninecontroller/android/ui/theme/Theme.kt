package dev.ninecontroller.android.ui.theme

import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ninecontroller.android.R

/**
 * Controller-9 design tokens — "Lit Hardware" (docs/DESIGN_SYSTEM.md): a graphite instrument whose
 * light comes from one game colour. Components use these, never raw values.
 *
 * Surfaces step up in lightness; secondary text is the text colour at lower alpha (never a grey);
 * hairlines are tinted text at 8 / 14 %; one accent per screen, owned by the game being played.
 */
object Nine {
    val bg = Color(0xFF0A0B0D)
    val s1 = Color(0xFF121418)
    val s2 = Color(0xFF191C21)
    val s3 = Color(0xFF23272E)
    val text = Color(0xFFF1F3F5)
    val muted = text.copy(alpha = 0.66f)   // 7.9:1 on bg
    val dim = text.copy(alpha = 0.46f)     // 4.7:1 on bg (AA for body text)
    val faint = text.copy(alpha = 0.22f)   // decoration only, never text
    val line = text.copy(alpha = 0.08f)
    val line2 = text.copy(alpha = 0.14f)
    /** Default accent (no game chosen). Screens that belong to a game use [LocalAccent]. */
    val accent = Accent.VOLT.color
    val accentInk = Accent.VOLT.ink
    val accentSoft = accent.copy(alpha = 0.12f)
    val warn = Color(0xFFF5B14C)
    val bad = Color(0xFFF2675A)
    val warnSoft = warn.copy(alpha = 0.10f)
    val badSoft = bad.copy(alpha = 0.10f)

    // Radius family: small (chips, inputs), card, pill.
    val small = RoundedCornerShape(10.dp)
    val card = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(999.dp)

    // Spacing (4/8 scale).
    val gutter = 20.dp
    val gap = 12.dp
    val section = 32.dp
    /** Minimum touch target (WCAG 2.5.8 / Android guidance). */
    val target = 48.dp

    /** Geist does 90 % of the work; Geist Mono is for live numbers, codes and button letters. */
    val sans = FontFamily(
        Font(R.font.geist, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.geist, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(510))),
        Font(R.font.geist, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    )
    val mono = FontFamily(
        Font(R.font.geist_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
        Font(R.font.geist_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(520))),
    )
}

/**
 * A game's light. [ink] is the text colour on a solid accent fill (≥ 6:1). Each game profile owns
 * one; nothing else on its screens is coloured.
 */
@Immutable
enum class Accent(val label: String, val color: Color, val ink: Color) {
    VOLT("Volt", Color(0xFFC6F432), Color(0xFF11140A)),
    MAGENTA("Magenta", Color(0xFFFF2E88), Color(0xFF1C0410)),
    EMBER("Ember", Color(0xFFFF6A1A), Color(0xFF1C0A02)),
    CYAN("Cyan", Color(0xFF22D3EE), Color(0xFF03161A)),
    VIOLET("Violet", Color(0xFFA78BFA), Color(0xFF130B26)),
    GOLD("Gold", Color(0xFFFFC53D), Color(0xFF1C1302));

    val soft get() = color.copy(alpha = 0.12f)
    val glow get() = color.copy(alpha = 0.22f)
    /** The scene's black, pulled 4 % toward the game colour. */
    val bg get() = lerp(Nine.bg, color, 0.04f)
    val surface get() = lerp(Nine.s1, color, 0.05f)

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: VOLT }
}

val LocalAccent = staticCompositionLocalOf { Accent.VOLT }

/** Type roles. Display is light and tight; labels are small caps; metrics are monospaced. */
object NineType {
    val hero = TextStyle(fontFamily = Nine.sans, fontSize = 40.sp, lineHeight = 41.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.4).sp)
    val display = TextStyle(fontFamily = Nine.sans, fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.9).sp)
    val title = TextStyle(fontFamily = Nine.sans, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
    val body = TextStyle(fontFamily = Nine.sans, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp)
    val small = TextStyle(fontFamily = Nine.sans, fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = Nine.sans, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
    val metric = TextStyle(fontFamily = Nine.mono, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp)
    val mono = TextStyle(fontFamily = Nine.mono, fontSize = 12.sp, lineHeight = 16.sp)
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
    val context = LocalContext.current
    val reduced = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val base = Typography()
    fun TextStyle.geist() = copy(fontFamily = Nine.sans)
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            displayLarge = base.displayLarge.geist(), displayMedium = base.displayMedium.geist(), displaySmall = base.displaySmall.geist(),
            headlineLarge = base.headlineLarge.merge(NineType.display),
            headlineMedium = base.headlineMedium.geist(),
            headlineSmall = base.headlineSmall.merge(NineType.title),
            titleLarge = base.titleLarge.merge(NineType.title),
            titleMedium = base.titleMedium.geist().copy(fontWeight = FontWeight.Medium),
            titleSmall = base.titleSmall.geist(),
            bodyLarge = base.bodyLarge.merge(NineType.body),
            bodyMedium = base.bodyMedium.geist().copy(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = base.bodySmall.geist(),
            labelLarge = base.labelLarge.geist().copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
            labelMedium = base.labelMedium.geist(), labelSmall = base.labelSmall.geist(),
        ),
    ) {
        // No Material Surface wraps our screens, so set the default text colour and face explicitly
        // (otherwise Text falls back to black Roboto on the dark background).
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides Nine.text,
            androidx.compose.material3.LocalTextStyle provides NineType.body,
            LocalReducedMotion provides reduced,
            content = content,
        )
    }
}

/** 11sp uppercase micro-label. */
@Composable
fun Micro(text: String, modifier: Modifier = Modifier, color: Color = Nine.muted) =
    Text(text.uppercase(), modifier, color = color, style = NineType.label)

@Composable
fun primaryButtonColors() = ButtonDefaults.buttonColors(containerColor = Nine.accent, contentColor = Nine.accentInk)

val ButtonPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)

package dev.ninecontroller.android.ui.pad

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import dev.ninecontroller.android.ui.theme.Accent
import dev.ninecontroller.android.ui.theme.Nine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Liquid glass" controls over RGB light (docs/DESIGN_SYSTEM.md):
 *  - [drawAurora] paints slow-moving pools of light — the game's colour and its neighbours, or a
 *    full RGB cycle — that the glass sits on;
 *  - the controller screen draws the same aurora again, blurred and clipped to the controls
 *    ([glassPath]), so every button is frosted glass with the light moving through it;
 *  - [glassRound] / [glassPill] / [glassWell] add the body tint, the specular rim and the shine on top.
 */
enum class Rgb(val label: String) { GAME("Game colour"), RAINBOW("Rainbow"), OFF("Off");
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: GAME }
}

/** Colour at [hueShift] degrees from [base] (same lightness and saturation). */
fun shiftHue(base: Color, hueShift: Float): Color {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[0] = ((hsv[0] + hueShift) % 360f + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** The light the glass sits on. [t] is 0…1 over one slow loop (≈ 24 s). */
fun DrawScope.drawAurora(t: Float, accent: Accent, rgb: Rgb, base: Color = accent.bg, vivid: Float = 1f) {
    drawRect(base)
    if (rgb == Rgb.OFF) {
        for (fx in listOf(0.2f, 0.8f)) blob(Offset(size.width * fx, size.height * 0.62f), size.height * 0.75f, accent.color, 0.11f)
        return
    }
    val a = 2 * PI.toFloat() * t
    // Four pools drifting on slow Lissajous paths; colours from the game's hue family or the whole wheel.
    val colors = when (rgb) {
        Rgb.RAINBOW -> List(4) { i -> shiftHue(Color(0xFFFF2E88), 360f * t + i * 90f) }
        else -> listOf(accent.color, shiftHue(accent.color, 38f), shiftHue(accent.color, -42f), accent.color)
    }
    val w = size.width; val h = size.height
    val spots = listOf(
        Offset(w * (0.22f + 0.08f * sin(a)), h * (0.62f + 0.10f * cos(a * 1.3f))),
        Offset(w * (0.78f + 0.07f * cos(a * 0.9f)), h * (0.58f + 0.12f * sin(a * 1.1f))),
        Offset(w * (0.50f + 0.18f * sin(a * 0.7f)), h * (0.18f + 0.08f * cos(a))),
        Offset(w * (0.50f + 0.20f * cos(a * 1.2f)), h * (0.95f + 0.05f * sin(a * 0.8f))),
    )
    val radii = listOf(h * 0.85f, h * 0.85f, h * 0.55f, h * 0.6f)
    val strength = listOf(0.55f, 0.55f, 0.34f, 0.30f).map { it * vivid }
    for (i in 0..3) blob(spots[i], radii[i], colors[i], strength[i])
    // Darken the centre so the light reads as coming from behind the grips, not a wash.
    drawRect(Brush.radialGradient(listOf(base.copy(alpha = 0.6f), Color.Transparent), Offset(w / 2, h / 2), w * 0.30f))
}

private fun DrawScope.blob(c: Offset, r: Float, color: Color, alpha: Float) =
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent), c, r), r, c)

/** Union of the glass shapes (pixel rects; round = circle) — the frosted layer is clipped to it. */
fun glassPath(shapes: List<Pair<Rect, Boolean>>): Path = Path().apply {
    for ((r, round) in shapes) {
        if (round) addOval(Rect(r.center, minOf(r.width, r.height) / 2))
        else addRoundRect(RoundRect(r, CornerRadius(minOf(r.width, r.height) / 2)))
    }
}

/** A round glass cap. Frosting comes from the blurred layer below; this adds body, rim and shine. */
fun DrawScope.glassRound(a: Accent, c: Offset, rad: Float, pressed: Boolean, sw: Float, frosted: Boolean) {
    val r = if (pressed) rad * 0.96f else rad
    // Contact shadow, tinted with the scene.
    drawCircle(Brush.radialGradient(listOf(lerp(Color.Black, a.color, 0.08f).copy(alpha = 0.45f), Color.Transparent), c + Offset(0f, rad * 0.18f), rad * 1.25f), rad * 1.25f, c + Offset(0f, rad * 0.18f))
    if (pressed) drawCircle(Brush.radialGradient(listOf(a.color.copy(alpha = 0.55f), a.color.copy(alpha = 0.12f), Color.Transparent), c, rad * 2.1f), rad * 2.1f, c)
    // Body: clear glass (a whisper of white) or, pressed, the game colour poured in.
    val body = if (pressed) Brush.radialGradient(listOf(lerp(a.color, Color.White, 0.25f).copy(alpha = 0.95f), a.color.copy(alpha = 0.85f)), c - Offset(r * 0.3f, r * 0.35f), r * 1.4f)
        else Brush.verticalGradient(listOf(Color.White.copy(alpha = if (frosted) 0.10f else 0.13f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.06f)), c.y - r, c.y + r)
    drawCircle(body, r, c)
    // Inner shade at the bottom: glass is thicker at the edge, so it darkens there.
    drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (pressed) 0.10f else 0.18f)), c - Offset(0f, r * 0.2f), r * 1.15f), r, c)
    // Specular rim: bright where the light hits (top-left), almost gone at the bottom-right.
    drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.35f)),
        c - Offset(r, r), c + Offset(r, r)), r, c, style = Stroke(sw * 1.1f))
    // The curved shine across the top of the dome.
    drawOval(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (pressed) 0.45f else 0.32f), Color.Transparent), c.y - r * 0.9f, c.y - r * 0.15f),
        Offset(c.x - r * 0.62f, c.y - r * 0.88f), Size(r * 1.24f, r * 0.72f))
    // A tiny hot spot — what makes it read as liquid, not plastic.
    drawCircle(Color.White.copy(alpha = 0.55f), r * 0.07f, c + Offset(-r * 0.42f, -r * 0.5f))
}

/** A pill / rounded-rect glass cap (bumpers, triggers, centre buttons). [fill] 0…1 pours colour in from the left. */
fun DrawScope.glassPill(a: Accent, r: Rect, cr: CornerRadius, pressed: Boolean, sw: Float, frosted: Boolean, fill: Float = if (pressed) 1f else 0f) {
    val box = if (pressed) r.deflate(minOf(r.width, r.height) * 0.025f) else r
    drawRoundRect(Brush.verticalGradient(listOf(lerp(Color.Black, a.color, 0.08f).copy(alpha = 0.40f), Color.Transparent), box.top + box.height * 0.3f, box.bottom + box.height * 0.5f),
        Offset(box.left, box.top + box.height * 0.18f), box.size, cr)
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (frosted) 0.10f else 0.13f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.06f)), box.top, box.bottom), box.topLeft, box.size, cr)
    if (fill > 0f) {
        if (fill >= 1f) drawCircle(Brush.radialGradient(listOf(a.color.copy(alpha = 0.45f), Color.Transparent), box.center, maxOf(box.width, box.height) * 0.8f), maxOf(box.width, box.height) * 0.8f, box.center)
        drawRoundRect(Brush.horizontalGradient(listOf(a.color.copy(alpha = 0.9f), lerp(a.color, Color.White, 0.25f).copy(alpha = 0.95f)), box.left, box.left + box.width * fill),
            box.topLeft, Size(box.width * fill, box.height), cr)
    }
    drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.70f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.30f)),
        box.topLeft, Offset(box.right, box.bottom)), box.topLeft, box.size, cr, style = Stroke(sw * 1.1f))
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), box.top, box.top + box.height * 0.4f),
        Offset(box.left + box.height * 0.35f, box.top + box.height * 0.07f), Size(box.width - box.height * 0.7f, box.height * 0.28f), CornerRadius(box.height * 0.14f))
}

/** Glass ring for a stick well; [live] lights the gate in the game colour. */
fun DrawScope.glassWell(a: Accent, c: Offset, rad: Float, live: Boolean, sw: Float) {
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.10f), Color.White.copy(alpha = 0.05f)), c, rad), rad, c)
    drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.45f)), c - Offset(rad, rad), c + Offset(rad, rad)), rad, c, style = Stroke(sw))
    val gate = rad * 0.8f
    drawCircle(if (live) a.color.copy(alpha = 0.7f) else Nine.text.copy(alpha = 0.10f), gate, c, style = Stroke(sw * if (live) 1.6f else 1f))
    for (i in 0 until 8) {
        val ang = i * PI / 4
        val p0 = c + Offset((cos(ang) * gate * 1.07f).toFloat(), (sin(ang) * gate * 1.07f).toFloat())
        val p1 = c + Offset((cos(ang) * gate * 1.15f).toFloat(), (sin(ang) * gate * 1.15f).toFloat())
        drawLine(if (live) a.color.copy(alpha = 0.6f) else Nine.text.copy(alpha = 0.18f), p0, p1, sw, StrokeCap.Round)
    }
}

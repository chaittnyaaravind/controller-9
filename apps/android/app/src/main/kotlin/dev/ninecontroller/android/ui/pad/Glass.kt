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
import dev.ninecontroller.android.ui.theme.Accent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Black glass controls (docs/DESIGN_SYSTEM.md): smoked-black glass caps with a thin specular rim
 * and a soft shine, on a near-black scene lit only by the game's one premium tone.
 *  - [drawAurora] paints that scene: two soft pools of the accent behind the grips, slowly breathing;
 *  - the controller screen draws it again, blurred and clipped to the controls ([glassPath]), so the
 *    glass carries a faint reflection of the light;
 *  - pressed controls light their rim and letter in the accent, never a flat colour fill.
 */
enum class Rgb(val label: String) { GAME("Game light"), OFF("Off");
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: GAME }
}

private val Ink = Color(0xFF050506)        // the black of the scene
private val Smoke = Color(0xFF15161A)      // glass body, lit edge
private val SmokeDeep = Color(0xFF07080A)  // glass body, shadow side

/** Near-black scene, lit by the game's tone from behind the grips. [t] is 0…1 over a slow loop. */
fun DrawScope.drawAurora(t: Float, accent: Accent, rgb: Rgb, base: Color = Ink, vivid: Float = 1f) {
    drawRect(base)
    val w = size.width; val h = size.height
    val breathe = if (rgb == Rgb.OFF) 0f else 0.5f + 0.5f * sin(2 * PI.toFloat() * t)
    val a = (if (rgb == Rgb.OFF) 0.07f else 0.10f + 0.05f * breathe) * vivid
    for (fx in listOf(0.18f, 0.82f)) {
        val c = Offset(w * fx, h * 0.70f)
        drawCircle(Brush.radialGradient(listOf(accent.color.copy(alpha = a), accent.color.copy(alpha = a * 0.3f), Color.Transparent), c, h * 0.8f), h * 0.8f, c)
    }
    // A cool top light, as if from a studio softbox: keeps black from going flat.
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.035f * vivid), Color.Transparent), 0f, h * 0.45f))
}

/** Union of the glass shapes (pixel rects; round = circle) — the reflection layer is clipped to it. */
fun glassPath(shapes: List<Pair<Rect, Boolean>>): Path = Path().apply {
    for ((r, round) in shapes) {
        if (round) addOval(Rect(r.center, minOf(r.width, r.height) / 2))
        else addRoundRect(RoundRect(r, CornerRadius(minOf(r.width, r.height) / 2)))
    }
}

/** Specular rim: bright where the light hits (top-left), nearly gone bottom-right; the accent when lit. */
private fun rim(a: Accent, lit: Boolean, from: Offset, to: Offset) =
    if (lit) Brush.linearGradient(listOf(lerp(a.color, Color.White, 0.5f), a.color, a.color.copy(alpha = 0.55f)), from, to)
    else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.16f)), from, to)

/** A round black-glass cap. */
fun DrawScope.glassRound(a: Accent, c: Offset, rad: Float, pressed: Boolean, sw: Float, frosted: Boolean) {
    val r = if (pressed) rad * 0.965f else rad
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), c + Offset(0f, rad * 0.16f), rad * 1.22f), rad * 1.22f, c + Offset(0f, rad * 0.16f))
    if (pressed) drawCircle(Brush.radialGradient(listOf(a.color.copy(alpha = 0.30f), a.color.copy(alpha = 0.06f), Color.Transparent), c, rad * 1.9f), rad * 1.9f, c)
    // Smoked glass, lit from the top-left; a breath of the accent inside when pressed.
    drawCircle(Brush.radialGradient(listOf(if (pressed) lerp(Smoke, a.color, 0.22f) else Smoke, SmokeDeep), c - Offset(r * 0.35f, r * 0.45f), r * 1.6f), r, c)
    if (pressed) drawCircle(Brush.radialGradient(listOf(a.color.copy(alpha = 0.28f), Color.Transparent), c + Offset(0f, r * 0.25f), r * 0.9f), r, c)
    drawCircle(rim(a, pressed, c - Offset(r, r), c + Offset(r, r)), r, c, style = Stroke(sw * if (pressed) 1.4f else 1f))
    // Soft dome shine and a small hot spot: glass, not plastic.
    drawOval(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent), c.y - r * 0.9f, c.y - r * 0.2f),
        Offset(c.x - r * 0.6f, c.y - r * 0.86f), Size(r * 1.2f, r * 0.66f))
    drawCircle(Color.White.copy(alpha = 0.32f), r * 0.055f, c + Offset(-r * 0.42f, -r * 0.52f))
}

/** A pill / rounded-rect black-glass cap. [fill] 0…1 lights it from the left (analog triggers). */
fun DrawScope.glassPill(a: Accent, r: Rect, cr: CornerRadius, pressed: Boolean, sw: Float, frosted: Boolean, fill: Float = if (pressed) 1f else 0f) {
    val box = if (pressed) r.deflate(minOf(r.width, r.height) * 0.025f) else r
    val lit = pressed || fill > 0.5f
    drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), box.top + box.height * 0.3f, box.bottom + box.height * 0.5f),
        Offset(box.left, box.top + box.height * 0.16f), box.size, cr)
    drawRoundRect(Brush.verticalGradient(listOf(Smoke, SmokeDeep), box.top, box.bottom), box.topLeft, box.size, cr)
    if (fill > 0f) {
        drawRoundRect(Brush.horizontalGradient(listOf(a.color.copy(alpha = 0.10f), a.color.copy(alpha = 0.38f)), box.left, box.left + box.width * fill),
            box.topLeft, Size(box.width * fill, box.height), cr)
        if (lit) drawCircle(Brush.radialGradient(listOf(a.color.copy(alpha = 0.22f), Color.Transparent), box.center, maxOf(box.width, box.height) * 0.7f), maxOf(box.width, box.height) * 0.7f, box.center)
    }
    drawRoundRect(rim(a, lit, box.topLeft, Offset(box.right, box.bottom)), box.topLeft, box.size, cr, style = Stroke(sw * if (lit) 1.4f else 1f))
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent), box.top, box.top + box.height * 0.42f),
        Offset(box.left + box.height * 0.35f, box.top + box.height * 0.07f), Size(box.width - box.height * 0.7f, box.height * 0.3f), CornerRadius(box.height * 0.15f))
}

/** Black-glass stick well; [live] lights the gate ring in the accent. */
fun DrawScope.glassWell(a: Accent, c: Offset, rad: Float, live: Boolean, sw: Float) {
    drawCircle(Brush.radialGradient(listOf(SmokeDeep, Smoke.copy(alpha = 0.9f)), c, rad), rad, c)
    drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.22f)), c - Offset(rad, rad), c + Offset(rad, rad)), rad, c, style = Stroke(sw))
    val gate = rad * 0.8f
    drawCircle(if (live) a.color.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.07f), gate, c, style = Stroke(sw * if (live) 1.5f else 1f))
    for (i in 0 until 8) {
        val ang = i * PI / 4
        val p0 = c + Offset((cos(ang) * gate * 1.07f).toFloat(), (sin(ang) * gate * 1.07f).toFloat())
        val p1 = c + Offset((cos(ang) * gate * 1.15f).toFloat(), (sin(ang) * gate * 1.15f).toFloat())
        drawLine(if (live) a.color.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.14f), p0, p1, sw, StrokeCap.Round)
    }
}

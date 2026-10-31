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
import dev.ninecontroller.android.ui.theme.Accent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Matte-black hardware (docs/DESIGN_SYSTEM.md): the controller surface is a matte black shell and every
 * control is a moulded matte cap with a soft shadow under it and a fine machined highlight on its upper
 * edge. Pressing a cap moves it into the shell (shadow tightens, cap darkens, highlight dims) — no
 * coloured light, no blur. Function names are kept from the earlier glass material so callers don't change.
 */
enum class Rgb(val label: String) { GAME("Game light"), OFF("Off");
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: GAME }
}

private val Shell = Color(0xFF0B0B0C)       // the body
private val ShellTop = Color(0xFF131315)    // body, where the room light falls
private val CapTop = Color(0xFF2A2B2F)      // cap, lit upper face
private val CapBottom = Color(0xFF151619)   // cap, lower face
private val CapPressedTop = Color(0xFF1C1D20)
private val CapPressedBottom = Color(0xFF101113)
private val Well = Color(0xFF050506)        // recessed stick well

/** The shell. [t], [accent] and [rgb] are ignored: the surface carries no coloured light. */
@Suppress("UNUSED_PARAMETER")
fun DrawScope.drawAurora(t: Float, accent: Accent, rgb: Rgb, base: Color = Shell, vivid: Float = 1f) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(ShellTop, Shell, Color(0xFF070708)), 0f, h))
    // Soft studio light from above and a gentle vignette: depth without colour.
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.035f), Color.Transparent), Offset(w / 2, -h * 0.2f), h * 1.1f))
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), Offset(w / 2, h / 2), maxOf(w, h) * 0.75f))
}

/** Union of the cap shapes (pixel rects; round = circle). */
fun glassPath(shapes: List<Pair<Rect, Boolean>>): Path = Path().apply {
    for ((r, round) in shapes) {
        if (round) addOval(Rect(r.center, minOf(r.width, r.height) / 2))
        else addRoundRect(RoundRect(r, CornerRadius(minOf(r.width, r.height) / 2)))
    }
}

/** Machined edge: a fine highlight across the top that fades out down the sides. */
private fun edge(top: Float, bottom: Float, pressed: Boolean) = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = if (pressed) 0.10f else 0.22f), Color.White.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.5f)), top, bottom)

/** A round matte cap. Pressed: it sits 3 % lower, darker, with a tighter shadow. */
@Suppress("UNUSED_PARAMETER")
fun DrawScope.glassRound(a: Accent, c0: Offset, rad: Float, pressed: Boolean, sw: Float, frosted: Boolean) {
    val c = if (pressed) c0 + Offset(0f, rad * 0.03f) else c0
    val r = if (pressed) rad * 0.97f else rad
    // Contact shadow on the shell.
    val sh = c0 + Offset(0f, rad * (if (pressed) 0.06f else 0.16f))
    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = if (pressed) 0.55f else 0.75f), Color.Transparent), sh, rad * (if (pressed) 1.08f else 1.25f)), rad * 1.25f, sh)
    // Body: lit from above, matte.
    drawCircle(Brush.verticalGradient(listOf(if (pressed) CapPressedTop else CapTop, if (pressed) CapPressedBottom else CapBottom), c.y - r, c.y + r), r, c)
    // Slight dome: a broad, very soft sheen (no hot spot — matte, not glass).
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = if (pressed) 0.02f else 0.05f), Color.Transparent), c - Offset(0f, r * 0.35f), r * 0.9f), r, c)
    drawCircle(edge(c.y - r, c.y + r, pressed), r, c, style = Stroke(sw))
}

/** A pill / rounded-rect matte cap. [fill] 0…1 shows how far an analog trigger is pulled (a neutral bar). */
@Suppress("UNUSED_PARAMETER")
fun DrawScope.glassPill(a: Accent, r0: Rect, cr: CornerRadius, pressed: Boolean, sw: Float, frosted: Boolean, fill: Float = if (pressed) 1f else 0f) {
    val down = pressed || fill > 0.5f
    val r = if (down) r0.translate(0f, minOf(r0.width, r0.height) * 0.04f).deflate(minOf(r0.width, r0.height) * 0.02f) else r0
    val sh = Offset(0f, r0.height * (if (down) 0.06f else 0.14f))
    drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = if (down) 0.5f else 0.7f), Color.Transparent), r0.top + r0.height * 0.4f, r0.bottom + r0.height * 0.5f),
        r0.topLeft + sh, r0.size, cr)
    drawRoundRect(Brush.verticalGradient(listOf(if (down) CapPressedTop else CapTop, if (down) CapPressedBottom else CapBottom), r.top, r.bottom), r.topLeft, r.size, cr)
    if (fill > 0f) // how far it's pulled: a quiet light bar along the bottom edge
        drawRoundRect(Color.White.copy(alpha = 0.10f + 0.10f * fill), Offset(r.left + r.height * 0.3f, r.bottom - r.height * 0.18f),
            Size((r.width - r.height * 0.6f) * fill, r.height * 0.07f), CornerRadius(r.height * 0.035f))
    drawRoundRect(edge(r.top, r.bottom, down), r.topLeft, r.size, cr, style = Stroke(sw))
}

/** Recessed stick well with a fine gate ring and eight index marks; [live] brightens the gate slightly. */
@Suppress("UNUSED_PARAMETER")
fun DrawScope.glassWell(a: Accent, c: Offset, rad: Float, live: Boolean, sw: Float) {
    drawCircle(Brush.radialGradient(listOf(Well, Color(0xFF0D0D0F)), c, rad), rad, c)
    // Inner shadow at the top lip, light caught on the lower lip: it reads as a hollow.
    drawCircle(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), c.y - rad, c.y), rad, c)
    drawCircle(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.White.copy(alpha = 0.14f)), c.y - rad, c.y + rad), rad, c, style = Stroke(sw))
    val gate = rad * 0.8f
    drawCircle(Color.White.copy(alpha = if (live) 0.16f else 0.06f), gate, c, style = Stroke(sw))
    for (i in 0 until 8) {
        val ang = i * PI / 4
        val p0 = c + Offset((cos(ang) * gate * 1.07f).toFloat(), (sin(ang) * gate * 1.07f).toFloat())
        val p1 = c + Offset((cos(ang) * gate * 1.15f).toFloat(), (sin(ang) * gate * 1.15f).toFloat())
        drawLine(Color.White.copy(alpha = if (live) 0.28f else 0.12f), p0, p1, sw, StrokeCap.Round)
    }
}

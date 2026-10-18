package dev.ninecontroller.android.ui.pad

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.ninecontroller.android.pad.Action
import dev.ninecontroller.android.pad.Family
import dev.ninecontroller.android.pad.Glyph
import dev.ninecontroller.android.pad.PadElement
import dev.ninecontroller.android.pad.PadGeometry
import dev.ninecontroller.android.pad.PadMapping
import dev.ninecontroller.android.pad.Part
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.protocol.Button
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** What a control is doing right now (live surface) — previews use [Look.IDLE]. */
data class Look(val pressed: Boolean = false, val value: Float = 0f, val knobX: Float = 0f, val knobY: Float = 0f, val dpad: Int = 0) {
    companion object { val IDLE = Look() }
}

/**
 * Front-view diagram of a preset (fixed 2:1 canvas, letterboxed). Used by the Layouts cards,
 * the layout detail and the home screen. [highlight] outlines one control in the accent.
 */
@Composable
fun PadDiagram(mapping: PadMapping, modifier: Modifier = Modifier, highlight: String? = null, body: Boolean = true, labels: Boolean = true) {
    val tm = rememberTextMeasurer()
    val desc = "${mapping.preset.name} layout diagram: " + mapping.preset.elements.joinToString("; ") { e ->
        e.label + (mapping.actionOf(e)?.let { "" } ?: " (unavailable)")
    }
    Canvas(modifier.semantics { contentDescription = desc }) {
        val s = min(size.width / 200f, size.height / 100f)
        val ox = (size.width - 200f * s) / 2; val oy = (size.height - 100f * s) / 2
        if (body) drawBody(mapping.preset.family, ox, oy, s)
        for (e in mapping.preset.elements) {
            val b = PadGeometry.place(e, size.width, size.height, fit = true)
            drawElement(e, Rect(b.left, b.top, b.right, b.bottom), mapping.actionOf(e), Look.IDLE, tm, s, labels, highlight == e.id)
        }
    }
}

// ---------------------------------------------------------------- body silhouettes (preview only)

private class BodySpec(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float, val gx: Float, val gy: Float, val grx: Float, val gry: Float)

private fun spec(f: Family) = when (f) {
    // Grip shape is the main family difference: Xbox/Nintendo wide rounded grips, PlayStation
    // longer narrow grips, retro a flat wide body with short grips.
    Family.XBOX -> BodySpec(12f, 4f, 188f, 90f, 36f, 38f, 76f, 30f, 24f)
    Family.NINTENDO -> BodySpec(12f, 4f, 188f, 88f, 30f, 38f, 76f, 30f, 24f)
    Family.PLAYSTATION -> BodySpec(14f, 6f, 186f, 82f, 24f, 40f, 78f, 24f, 22f)
    Family.RETRO -> BodySpec(6f, 8f, 194f, 92f, 38f, 34f, 84f, 28f, 16f)
}

private fun DrawScope.drawBody(f: Family, ox: Float, oy: Float, s: Float) {
    val b = spec(f)
    fun ov(cx: Float) = Path().apply { addOval(Rect(ox + (cx - b.grx) * s, oy + (b.gy - b.gry) * s, ox + (cx + b.grx) * s, oy + (b.gy + b.gry) * s)) }
    var p = Path().apply { addRoundRect(RoundRect(Rect(ox + b.l * s, oy + b.t * s, ox + b.r * s, oy + b.b * s), CornerRadius(b.radius * s))) }
    for (cx in listOf(b.gx, 200f - b.gx)) p = Path.combine(PathOperation.Union, p, ov(cx))
    drawPath(p, Nine.s1.copy(alpha = 0.9f))
    drawPath(p, Nine.line2, style = Stroke(1.2f * density))
}

// ---------------------------------------------------------------- one control

private val labelStyle = TextStyle(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)

/**
 * Draws one control into [r] (px). [u] = px per canvas unit, used for strokes and glyph size.
 * Unavailable controls (no action) are dashed and dim; pressed ones fill with the accent.
 */
fun DrawScope.drawElement(e: PadElement, r: Rect, action: Action?, look: Look, tm: TextMeasurer, u: Float, labels: Boolean = true, highlight: Boolean = false) {
    val off = action == null
    val sw = (0.55f * u).coerceIn(1f, 2.2f * density)
    val stroke = if (off) Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f * u, 1.1f * u))) else Stroke(sw)
    val edge = when { highlight -> Nine.accent; off -> Nine.faint; else -> Nine.line2 }
    val fill = when { off -> Color.Transparent; look.pressed -> Nine.accent; else -> Nine.s2 }
    val ink = when { off -> Nine.faint; look.pressed -> Nine.accentInk; else -> Nine.muted }
    val c = r.center
    when (e.part) {
        Part.STICK -> {
            val rad = min(r.width, r.height) / 2
            drawCircle(Nine.s1, rad, c); drawCircle(edge, rad, c, style = stroke)
            drawCircle(Nine.line, rad * 0.55f, c, style = Stroke(sw))
            val k = Offset(c.x + look.knobX * rad * 0.58f, c.y - look.knobY * rad * 0.58f)
            drawCircle(if (look.pressed) Nine.text else Nine.s3, rad * 0.38f, k)
            drawCircle(Nine.line2, rad * 0.38f, k, style = Stroke(sw))
        }
        Part.DPAD -> {
            val sz = min(r.width, r.height); val arm = sz / 3f
            val o = Offset(c.x - sz / 2, c.y - sz / 2)
            val cr = CornerRadius(arm * 0.28f)
            val arms = listOf(Button.UP to Offset(arm, 0f), Button.LEFT to Offset(0f, arm), Button.RIGHT to Offset(2 * arm, arm), Button.DOWN to Offset(arm, 2 * arm))
            val solid = Path.combine(PathOperation.Union,
                Path().apply { addRoundRect(RoundRect(Rect(o.x + arm, o.y, o.x + 2 * arm, o.y + sz), cr)) },
                Path().apply { addRoundRect(RoundRect(Rect(o.x, o.y + arm, o.x + sz, o.y + 2 * arm), cr)) })
            drawPath(solid, Nine.s2)
            for ((b, at) in arms) if (look.dpad and b.mask != 0) drawRoundRect(Nine.accent, o + at, Size(arm, arm), cr)
            drawPath(solid, edge, style = stroke)
            for ((b, at) in arms) drawCircle(if (look.dpad and b.mask != 0) Nine.accentInk else Nine.dim, arm * 0.09f, o + at + Offset(arm / 2, arm / 2))
        }
        Part.TRIGGER -> {
            val cr = CornerRadius(r.height * 0.42f)
            drawRoundRect(Nine.s1, r.topLeft, r.size, cr)
            if (look.value > 0f) drawRoundRect(Nine.accent.copy(alpha = 0.9f), r.topLeft, Size(r.width * look.value, r.height), cr)
            drawRoundRect(edge, r.topLeft, r.size, cr, style = stroke)
            if (labels) text(tm, e.short, Offset(r.left + r.height * 0.6f, c.y), u, if (look.value > 0.45f) Nine.accentInk else ink, start = true)
        }
        Part.BUMPER, Part.PADDLE, Part.TOUCHPAD -> {
            val cr = CornerRadius(if (e.part == Part.TOUCHPAD) 3.5f * u else r.height / 2)
            if (fill != Color.Transparent) drawRoundRect(fill, r.topLeft, r.size, cr)
            drawRoundRect(edge, r.topLeft, r.size, cr, style = stroke)
            if (labels) text(tm, e.short, c, u, ink)
        }
        else -> { // FACE, STICK_PRESS, CENTER
            if (e.round) {
                val rad = min(r.width, r.height) / 2
                if (fill != Color.Transparent) drawCircle(fill, rad, c)
                drawCircle(edge, rad, c, style = stroke)
            } else {
                val cr = CornerRadius(min(r.width, r.height) / 2)
                if (fill != Color.Transparent) drawRoundRect(fill, r.topLeft, r.size, cr)
                drawRoundRect(edge, r.topLeft, r.size, cr, style = stroke)
            }
            if (e.glyph == Glyph.TEXT) { if (labels) text(tm, e.short, c, u, ink, big = e.part == Part.FACE) }
            else glyph(e.glyph, c, min(r.width, r.height) * 0.26f, ink, sw)
        }
    }
}

private fun DrawScope.text(tm: TextMeasurer, s: String, at: Offset, u: Float, color: Color, big: Boolean = false, start: Boolean = false) {
    val px = (if (big) 5.6f else if (s.length > 3) 3.4f else 4.2f) * u
    val style = labelStyle.copy(fontSize = (px / density / fontScale).sp, color = color)
    val m = tm.measure(s, style)
    val x = if (start) at.x else at.x - m.size.width / 2f
    drawText(m, topLeft = Offset(x, at.y - m.size.height / 2f))
}

/** Generic glyphs only — no vendor logos. */
private fun DrawScope.glyph(g: Glyph, c: Offset, r: Float, color: Color, sw: Float) {
    val st = Stroke(sw * 1.15f, cap = StrokeCap.Round)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(color, Offset(c.x + x0 * r, c.y + y0 * r), Offset(c.x + x1 * r, c.y + y1 * r), sw * 1.15f, StrokeCap.Round)
    when (g) {
        Glyph.CROSS -> { line(-0.85f, -0.85f, 0.85f, 0.85f); line(-0.85f, 0.85f, 0.85f, -0.85f) }
        Glyph.CIRCLE -> drawCircle(color, r * 0.9f, c, style = st)
        Glyph.SQUARE -> drawRect(color, Offset(c.x - r * 0.8f, c.y - r * 0.8f), Size(r * 1.6f, r * 1.6f), style = st)
        Glyph.TRIANGLE -> drawPath(Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r * 0.95f, c.y + r * 0.7f); lineTo(c.x - r * 0.95f, c.y + r * 0.7f); close() }, color, style = st)
        Glyph.HOME -> drawPath(Path().apply {
            moveTo(c.x - r * 0.85f, c.y - r * 0.05f); lineTo(c.x, c.y - r * 0.85f); lineTo(c.x + r * 0.85f, c.y - r * 0.05f)
            moveTo(c.x - r * 0.6f, c.y - r * 0.25f); lineTo(c.x - r * 0.6f, c.y + r * 0.8f); lineTo(c.x + r * 0.6f, c.y + r * 0.8f); lineTo(c.x + r * 0.6f, c.y - r * 0.25f)
        }, color, style = st)
        Glyph.PLUS -> { line(-0.8f, 0f, 0.8f, 0f); line(0f, -0.8f, 0f, 0.8f) }
        Glyph.MINUS -> line(-0.8f, 0f, 0.8f, 0f)
        Glyph.MENU -> { line(-0.75f, -0.5f, 0.75f, -0.5f); line(-0.75f, 0f, 0.75f, 0f); line(-0.75f, 0.5f, 0.75f, 0.5f) }
        Glyph.VIEW -> {
            drawRect(color, Offset(c.x - r * 0.8f, c.y - r * 0.7f), Size(r * 1.0f, r * 0.9f), style = st)
            drawRect(color, Offset(c.x - r * 0.2f, c.y - r * 0.2f), Size(r * 1.0f, r * 0.9f), style = st)
        }
        Glyph.SHARE -> { line(0f, -0.9f, 0f, 0.3f); line(-0.4f, -0.5f, 0f, -0.9f); line(0.4f, -0.5f, 0f, -0.9f); line(-0.7f, 0.1f, -0.7f, 0.8f); line(-0.7f, 0.8f, 0.7f, 0.8f); line(0.7f, 0.8f, 0.7f, 0.1f) }
        Glyph.CREATE -> { line(-0.5f, 0.8f, -0.2f, -0.8f); line(0f, 0.8f, 0.3f, -0.8f); line(0.5f, 0.8f, 0.8f, -0.8f) }
        Glyph.CAPTURE -> { drawRoundRect(color, Offset(c.x - r * 0.85f, c.y - r * 0.85f), Size(r * 1.7f, r * 1.7f), CornerRadius(r * 0.3f), style = st); drawCircle(color, r * 0.38f, c) }
        Glyph.MUTE -> line(-0.6f, 0f, 0.6f, 0f)
        Glyph.STAR -> drawPath(Path().apply {
            for (i in 0 until 10) {
                val a = Math.toRadians(-90.0 + i * 36.0); val rr = if (i % 2 == 0) r else r * 0.45f
                val p = Offset(c.x + (rr * cos(a)).toFloat(), c.y + (rr * sin(a)).toFloat())
                if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
            }; close()
        }, color, style = st)
        Glyph.HEART -> drawPath(Path().apply {
            moveTo(c.x, c.y + r * 0.8f)
            cubicTo(c.x - r * 1.2f, c.y, c.x - r * 0.6f, c.y - r, c.x, c.y - r * 0.35f)
            cubicTo(c.x + r * 0.6f, c.y - r, c.x + r * 1.2f, c.y, c.x, c.y + r * 0.8f)
        }, color, style = st)
        Glyph.TEXT -> Unit
    }
}

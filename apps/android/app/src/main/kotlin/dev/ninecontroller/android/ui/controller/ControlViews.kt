package dev.ninecontroller.android.ui.controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ninecontroller.android.ui.theme.Nine
import dev.ninecontroller.protocol.Button as Btn
import dev.ninecontroller.protocol.ControlDef
import dev.ninecontroller.protocol.ControlKind
import kotlin.math.min

/** Draws one control. Visually distinct per type; pressed state uses the game's accent. */
@Composable
fun ControlVisual(c: ControlDef, v: Visual, steering: Double, accent: dev.ninecontroller.android.ui.theme.Accent = dev.ninecontroller.android.ui.theme.Accent.PLATINUM) {
    Box(Modifier.fillMaxSize().alpha(c.opacity.toFloat()), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val stroke = Stroke(width = 1.5f * density)
            when (c.kind) {
                ControlKind.BUTTON -> {
                    val r = min(w, h) / 2f
                    val round = min(w, h) < 140 * density || kotlin.math.abs(w - h) < 0.2f * w
                    if (round) {
                        drawCircle(if (v.pressed) accent.color else Nine.s2, r * 0.94f)
                        drawCircle(Nine.line2, r * 0.94f, style = stroke)
                    } else {
                        drawRoundRect(if (v.pressed) accent.color else Nine.s2, cornerRadius = CornerRadius(18 * density))
                        drawRoundRect(Nine.line2, cornerRadius = CornerRadius(18 * density), style = stroke)
                    }
                }
                ControlKind.STICK -> {
                    val r = min(w, h) / 2f
                    val c0 = Offset(w / 2, h / 2)
                    drawCircle(Nine.s1, r, c0)
                    drawCircle(Nine.line2, r, c0, style = stroke)
                    drawCircle(Nine.line, r * 0.5f, c0, style = stroke)
                    val knob = Offset(c0.x + (v.x * r * 0.62).toFloat(), c0.y - (v.y * r * 0.62).toFloat())
                    drawCircle(if (v.pressed) Nine.text else Nine.s2, r * 0.36f, knob)
                    drawCircle(Nine.line2, r * 0.36f, knob, style = stroke)
                }
                ControlKind.DPAD -> {
                    val s = min(w, h); val arm = s / 3f
                    val o = Offset((w - s) / 2, (h - s) / 2)
                    val arms = listOf(Btn.UP to Offset(arm, 0f), Btn.LEFT to Offset(0f, arm), Btn.RIGHT to Offset(2 * arm, arm), Btn.DOWN to Offset(arm, 2 * arm))
                    for ((b, at) in arms) {
                        val on = v.mask and b.mask != 0
                        drawRoundRect(if (on) accent.color else Nine.s2, o + at, Size(arm, arm), CornerRadius(8 * density))
                        drawRoundRect(Nine.line2, o + at, Size(arm, arm), CornerRadius(8 * density), style = stroke)
                    }
                    drawRect(Nine.s2, o + Offset(arm, arm), Size(arm, arm))
                }
                ControlKind.TRIGGER, ControlKind.PEDAL -> {
                    val cr = CornerRadius((if (c.kind == ControlKind.PEDAL) 22 else 14) * density)
                    drawRoundRect(Nine.s1, cornerRadius = cr)
                    val fill = (v.value * h).toFloat()
                    if (fill > 0) drawRoundRect(accent.color.copy(alpha = 0.85f), Offset(0f, 0f), Size(w, fill), cr)
                    drawRoundRect(Nine.line2, cornerRadius = cr, style = stroke)
                    if (c.kind == ControlKind.PEDAL) for (i in 1..5) {
                        val y = h * i / 6f
                        drawLine(Nine.line, Offset(w * 0.25f, y), Offset(w * 0.75f, y), 1.5f * density, StrokeCap.Round)
                    }
                }
                ControlKind.STEERING -> {
                    val cy = h * 1.6f; val r = h * 1.45f
                    val arc = Path().apply { addArc(androidx.compose.ui.geometry.Rect(Offset(w / 2, cy), r), 235f, 70f) }
                    drawPath(arc, Nine.line2, style = Stroke(3 * density, cap = StrokeCap.Round))
                    val a = Math.toRadians(270 + 35 * steering)
                    val tip = Offset(w / 2 + (r * kotlin.math.cos(a)).toFloat(), cy + (r * kotlin.math.sin(a)).toFloat())
                    drawCircle(Nine.text, 6 * density, tip)
                    drawLine(Nine.dim, Offset(w / 2, cy - r - 8 * density), Offset(w / 2, cy - r + 8 * density), 1.5f * density)
                }
            }
        }
        val label = c.label ?: when (c.kind) { ControlKind.BUTTON -> c.bind.uppercase(); ControlKind.TRIGGER, ControlKind.PEDAL -> c.bind.uppercase(); else -> null }
        if (label != null && c.kind != ControlKind.STICK && c.kind != ControlKind.DPAD) {
            val pressed = v.pressed && c.kind == ControlKind.BUTTON
            Text(label, color = if (pressed) accent.ink else Nine.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = if (c.kind == ControlKind.PEDAL || c.kind == ControlKind.TRIGGER) Modifier.align(Alignment.BottomCenter) else Modifier)
        }
    }
}

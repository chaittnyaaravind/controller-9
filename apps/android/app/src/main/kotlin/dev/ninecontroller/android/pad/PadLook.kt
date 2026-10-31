package dev.ninecontroller.android.pad

import dev.ninecontroller.protocol.ControlDef
import dev.ninecontroller.protocol.ControlKind
import kotlin.math.abs

/**
 * How a custom layout's control is drawn: as the same black-glass part a controller layout uses, so a
 * customised layout looks like the layout it came from (glyphs, D-pad chevrons, stick wells), not a
 * flat stand-in. Mirror of apps/ios/Core/.../PadLook.swift; tested in PadLookTest.
 */
object PadLook {
    /**
     * The part to draw for [c] in a box of [w] x [h] (px), and the action it sends; null for controls
     * no controller has (pedals, steering), which keep their own drawing.
     *  - A control customised from a layout keeps that layout's part and glyph (same id and label; the
     *    layout named by the start of [profileId], "arcade-custom", wins when several layouts match).
     *  - Otherwise: near-square buttons are round caps, long ones capsules.
     *  - A round cap the player stretched into a long box becomes a capsule, glyph kept.
     */
    fun element(c: ControlDef, w: Float, h: Float, profileId: String? = null): Pair<PadElement, Action>? {
        val kind = when (c.kind) {
            ControlKind.BUTTON -> Action.Kind.BUTTON; ControlKind.STICK -> Action.Kind.STICK
            ControlKind.DPAD -> Action.Kind.DPAD; ControlKind.TRIGGER -> Action.Kind.TRIGGER
            else -> return null
        }
        val action = Action.entries.firstOrNull { it.kind == kind && it.bind == c.bind } ?: return null
        val label = c.label ?: c.bind.uppercase()
        val square = abs(w - h) < 0.25f * maxOf(w, h)
        val known = PadPresets.all.sortedByDescending { profileId?.startsWith(it.id + "-") == true }.asSequence()
            .flatMap { it.elements }.firstOrNull { it.id == c.id && it.short == label && it.default?.kind == kind }
        val part = known?.part ?: when (kind) {
            Action.Kind.BUTTON -> if (square) Part.FACE else Part.BUMPER
            Action.Kind.STICK -> Part.STICK; Action.Kind.DPAD -> Part.DPAD; Action.Kind.TRIGGER -> Part.TRIGGER
        }
        val base = known?.copy(default = action) ?: PadElement(c.id, part, label, 0.0, 0.0, 1.0, 1.0, action)
        val e = if (base.round && base.part != Part.STICK && !square) base.copy(part = Part.CENTER, w = 2.0, h = 1.0) else base
        return e to action
    }

    /**
     * Width of the clear gap, centred on a screen [w] wide, between the controls that reach into the top
     * [band] (px), less [margin] on each side: the in-game bar fits there, never over a trigger. Controls
     * spanning the centre are ignored (the bar has to sit somewhere). Null when nothing is up there.
     */
    fun topGap(boxes: Collection<PadGeometry.Box>, w: Float, band: Float, margin: Float): Float? {
        val mid = w / 2
        val up = boxes.filter { it.top < band && !(it.left < mid && it.right > mid) }
        if (up.isEmpty()) return null
        val left = up.filter { it.cx < mid }.maxOfOrNull { it.right } ?: 0f
        val right = up.filter { it.cx >= mid }.minOfOrNull { it.left } ?: w
        return (2 * minOf(mid - left, right - mid) - 2 * margin).coerceAtLeast(0f)
    }
}

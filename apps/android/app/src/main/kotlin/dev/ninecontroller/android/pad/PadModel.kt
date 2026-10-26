package dev.ninecontroller.android.pad

import dev.ninecontroller.protocol.Button

/**
 * Normalized logical gamepad inputs — exactly what protocol v1 can carry (SPEC §6.3). Visual
 * presets never talk to the wire directly: every element resolves to one of these, and each
 * of these has exactly one wire encoding. Face buttons are positional (south/east/west/north),
 * so a PlayStation Cross, an Xbox A and a Nintendo B — all at the bottom — send the same bit.
 */
enum class Action(val id: String, val title: String, val kind: Kind, val button: Button? = null, val wire: String) {
    SOUTH("south", "Face button — bottom", Kind.BUTTON, Button.A, "button A (bit 0)"),
    EAST("east", "Face button — right", Kind.BUTTON, Button.B, "button B (bit 1)"),
    WEST("west", "Face button — left", Kind.BUTTON, Button.X, "button X (bit 2)"),
    NORTH("north", "Face button — top", Kind.BUTTON, Button.Y, "button Y (bit 3)"),
    BUMPER_L("bumper_l", "Left bumper", Kind.BUTTON, Button.LB, "LB (bit 4)"),
    BUMPER_R("bumper_r", "Right bumper", Kind.BUTTON, Button.RB, "RB (bit 5)"),
    BACK("back", "Back / View / Select", Kind.BUTTON, Button.VIEW, "View (bit 6)"),
    START("start", "Start / Menu / Options", Kind.BUTTON, Button.MENU, "Menu (bit 7)"),
    STICK_L_PRESS("stick_l_press", "Left stick press", Kind.BUTTON, Button.LS, "LS (bit 8)"),
    STICK_R_PRESS("stick_r_press", "Right stick press", Kind.BUTTON, Button.RS, "RS (bit 9)"),
    GUIDE("guide", "Guide / Home", Kind.BUTTON, Button.GUIDE, "Guide (bit 14, host may ignore)"),
    DPAD("dpad", "Directional pad", Kind.DPAD, wire = "D-pad (bits 10–13)"),
    STICK_L("stick_l", "Left stick", Kind.STICK, wire = "lx / ly"),
    STICK_R("stick_r", "Right stick", Kind.STICK, wire = "rx / ry"),
    TRIGGER_L("trigger_l", "Left trigger", Kind.TRIGGER, wire = "lt 0–65535"),
    TRIGGER_R("trigger_r", "Right trigger", Kind.TRIGGER, wire = "rt 0–65535");

    enum class Kind { BUTTON, DPAD, STICK, TRIGGER }

    /** Bind string understood by the profile model and the touch engine. */
    val bind: String get() = when (this) {
        DPAD -> "dpad"; STICK_L -> "left"; STICK_R -> "right"; TRIGGER_L -> "lt"; TRIGGER_R -> "rt"
        else -> button!!.id
    }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): Action? = byId[id]
        val buttons: List<Action> get() = entries.filter { it.kind == Kind.BUTTON }
    }
}

/** Physical part of a controller face; decides drawing, touch behaviour and what may be remapped. */
enum class Part { FACE, BUMPER, TRIGGER, STICK, STICK_PRESS, DPAD, CENTER, TOUCHPAD, PADDLE }

/** Drawn symbol. Only generic shapes — never a vendor logo. */
enum class Glyph { TEXT, CROSS, CIRCLE, SQUARE, TRIANGLE, HOME, PLUS, MINUS, MENU, VIEW, SHARE, CREATE, CAPTURE, MUTE, STAR, HEART }

/**
 * One control on a preset, in the 200 x 100 preset canvas (landscape front view). Sizes are
 * in the same units and are scaled uniformly, so circles stay circles on any screen.
 */
data class PadElement(
    val id: String,
    val part: Part,
    /** Physical label printed on the real controller ("A", "Cross", "ZL", "Options"). */
    val label: String,
    val cx: Double, val cy: Double, val w: Double, val h: Double,
    /** Logical action out of the box, or null when protocol v1 cannot carry this control. */
    val default: Action?,
    val glyph: Glyph = Glyph.TEXT,
    /** Why the control does nothing, when [default] is null and nothing is mapped. */
    val unavailable: String? = null,
    /** Short label drawn on the control (defaults to [label]). */
    val short: String = label,
) {
    val round: Boolean get() = part == Part.FACE || part == Part.STICK || part == Part.STICK_PRESS || (part == Part.CENTER && w == h)
    /** Only plain buttons may be remapped, and only to other buttons. Sticks, D-pad and analog triggers keep their kind. */
    val remappable: Boolean get() = part == Part.PADDLE || (default?.kind == Action.Kind.BUTTON && part in setOf(Part.FACE, Part.BUMPER, Part.CENTER, Part.STICK_PRESS))
}

enum class Family { XBOX, PLAYSTATION, NINTENDO, RETRO, ARCADE }

/** A visual preset. Interface layout only — not hardware emulation (docs/LAYOUTS.md). */
data class PadPreset(
    val id: String,
    val name: String,
    val tagline: String,
    val family: Family,
    val symmetric: Boolean,
    val elements: List<PadElement>,
    /** Facts the user should know about this preset on protocol v1. */
    val notes: List<String>,
) {
    fun element(id: String) = elements.first { it.id == id }
}

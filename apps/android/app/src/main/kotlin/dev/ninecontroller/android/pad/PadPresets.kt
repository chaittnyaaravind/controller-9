package dev.ninecontroller.android.pad

import dev.ninecontroller.android.pad.Action.BACK
import dev.ninecontroller.android.pad.Action.BUMPER_L
import dev.ninecontroller.android.pad.Action.BUMPER_R
import dev.ninecontroller.android.pad.Action.DPAD
import dev.ninecontroller.android.pad.Action.EAST
import dev.ninecontroller.android.pad.Action.GUIDE
import dev.ninecontroller.android.pad.Action.NORTH
import dev.ninecontroller.android.pad.Action.SOUTH
import dev.ninecontroller.android.pad.Action.START
import dev.ninecontroller.android.pad.Action.STICK_L
import dev.ninecontroller.android.pad.Action.STICK_L_PRESS
import dev.ninecontroller.android.pad.Action.STICK_R
import dev.ninecontroller.android.pad.Action.STICK_R_PRESS
import dev.ninecontroller.android.pad.Action.TRIGGER_L
import dev.ninecontroller.android.pad.Action.TRIGGER_R
import dev.ninecontroller.android.pad.Action.WEST

/**
 * The five layout presets as data. Canvas: 200 x 100 units, landscape front view, origin top-left.
 * Triggers sit on the top edge, bumpers just below; the two halves mirror around x = 100.
 *
 *   Asymmetric (Xbox, Switch Pro): left stick high, D-pad low, right stick low, face buttons high.
 *   Symmetric (DualSense, DualShock 4, 8BitDo Pro 2): D-pad and face buttons high, both sticks low.
 */
object PadPresets {
    private const val NO_SEND = "Protocol v1 has no input for this control, so it sends nothing."
    private const val NO_TOUCHPAD = "Protocol v1 has no touchpad input (neither touch nor click), so it sends nothing."

    // ---------------------------------------------------------------- shared geometry
    private fun shoulders(lt: String, rt: String, lb: String, rb: String) = listOf(
        PadElement("trigger_l", Part.TRIGGER, lt, 30.0, 9.0, 44.0, 14.0, TRIGGER_L),
        PadElement("trigger_r", Part.TRIGGER, rt, 170.0, 9.0, 44.0, 14.0, TRIGGER_R),
        PadElement("bumper_l", Part.BUMPER, lb, 30.0, 24.5, 44.0, 12.0, BUMPER_L),
        PadElement("bumper_r", Part.BUMPER, rb, 170.0, 24.5, 44.0, 12.0, BUMPER_R),
    )

    /** Face diamond. Labels/glyphs are given as (top, right, bottom, left); actions are always positional. */
    private fun face(cx: Double, cy: Double, top: Pair<String, Glyph>, right: Pair<String, Glyph>, bottom: Pair<String, Glyph>, left: Pair<String, Glyph>): List<PadElement> {
        val o = 13.5; val d = 14.0
        return listOf(
            PadElement("face_n", Part.FACE, top.first, cx, cy - o, d, d, NORTH, top.second),
            PadElement("face_e", Part.FACE, right.first, cx + o, cy, d, d, EAST, right.second),
            PadElement("face_s", Part.FACE, bottom.first, cx, cy + o, d, d, SOUTH, bottom.second),
            PadElement("face_w", Part.FACE, left.first, cx - o, cy, d, d, WEST, left.second),
        )
    }

    private fun asymmetricBody(l3: String, r3: String) = listOf(
        PadElement("stick_l", Part.STICK, "Left stick", 36.0, 52.0, 34.0, 34.0, STICK_L),
        PadElement("dpad", Part.DPAD, "D-pad", 70.0, 80.0, 30.0, 30.0, DPAD),
        PadElement("stick_r", Part.STICK, "Right stick", 130.0, 80.0, 30.0, 30.0, STICK_R),
        PadElement("stick_l_press", Part.STICK_PRESS, "$l3 (stick press)", 14.0, 84.0, 12.0, 12.0, STICK_L_PRESS, short = l3),
        PadElement("stick_r_press", Part.STICK_PRESS, "$r3 (stick press)", 186.0, 84.0, 12.0, 12.0, STICK_R_PRESS, short = r3),
    )

    private fun symmetricBody(l3: String, r3: String) = listOf(
        PadElement("dpad", Part.DPAD, "D-pad", 36.0, 52.0, 32.0, 32.0, DPAD),
        PadElement("stick_l", Part.STICK, "Left stick", 72.0, 80.0, 30.0, 30.0, STICK_L),
        PadElement("stick_r", Part.STICK, "Right stick", 128.0, 80.0, 30.0, 30.0, STICK_R),
        PadElement("stick_l_press", Part.STICK_PRESS, "$l3 (stick press)", 46.0, 88.0, 12.0, 12.0, STICK_L_PRESS, short = l3),
        PadElement("stick_r_press", Part.STICK_PRESS, "$r3 (stick press)", 154.0, 88.0, 12.0, 12.0, STICK_R_PRESS, short = r3),
    )

    // ---------------------------------------------------------------- presets
    val XBOX = PadPreset(
        id = "xbox", name = "Xbox-style", tagline = "Asymmetric sticks · A B X Y", family = Family.XBOX, symmetric = false,
        elements = shoulders("LT", "RT", "LB", "RB") +
            face(164.0, 52.0, "Y" to Glyph.TEXT, "B" to Glyph.TEXT, "A" to Glyph.TEXT, "X" to Glyph.TEXT) +
            asymmetricBody("LS", "RS") + listOf(
                PadElement("view", Part.CENTER, "View", 86.0, 50.0, 11.0, 11.0, BACK, Glyph.VIEW),
                PadElement("guide", Part.CENTER, "Guide", 100.0, 38.0, 14.0, 14.0, GUIDE, Glyph.HOME),
                PadElement("menu", Part.CENTER, "Menu", 114.0, 50.0, 11.0, 11.0, START, Glyph.MENU),
                PadElement("share", Part.CENTER, "Share", 100.0, 58.0, 10.0, 7.0, null, Glyph.SHARE, NO_SEND),
            ),
        notes = listOf("A, B, X and Y send the same bits as an Xbox pad's positions.", "Guide is sent; some hosts ignore it.", "Share has no protocol input."),
    )

    val DUALSENSE = PadPreset(
        id = "dualsense", name = "DualSense-style", tagline = "Symmetric sticks · touchpad · Create", family = Family.PLAYSTATION, symmetric = true,
        elements = shoulders("L2", "R2", "L1", "R1") +
            face(164.0, 52.0, "Triangle" to Glyph.TRIANGLE, "Circle" to Glyph.CIRCLE, "Cross" to Glyph.CROSS, "Square" to Glyph.SQUARE) +
            symmetricBody("L3", "R3") + listOf(
                PadElement("touchpad", Part.TOUCHPAD, "Touchpad", 100.0, 38.0, 52.0, 24.0, null, unavailable = NO_TOUCHPAD),
                PadElement("create", Part.CENTER, "Create", 64.0, 32.0, 8.0, 13.0, BACK, Glyph.CREATE),
                PadElement("options", Part.CENTER, "Options", 136.0, 32.0, 8.0, 13.0, START, Glyph.MENU),
                PadElement("ps", Part.CENTER, "PS button", 100.0, 60.0, 11.0, 11.0, GUIDE, Glyph.HOME, short = "PS"),
                PadElement("mute", Part.CENTER, "Mute", 100.0, 72.0, 10.0, 5.0, null, Glyph.MUTE, NO_SEND),
            ),
        notes = listOf("Cross is the bottom button and sends the same bit as Xbox A.", "Create sends Back/View; Options sends Start/Menu.", "Touchpad, Mute, adaptive triggers and haptics are not in protocol v1."),
    )

    val DUALSHOCK4 = PadPreset(
        id = "dualshock4", name = "DualShock 4-style", tagline = "Symmetric sticks · touchpad · Share", family = Family.PLAYSTATION, symmetric = true,
        elements = shoulders("L2", "R2", "L1", "R1") +
            face(164.0, 52.0, "Triangle" to Glyph.TRIANGLE, "Circle" to Glyph.CIRCLE, "Cross" to Glyph.CROSS, "Square" to Glyph.SQUARE) +
            symmetricBody("L3", "R3") + listOf(
                PadElement("touchpad", Part.TOUCHPAD, "Touchpad", 100.0, 36.0, 46.0, 22.0, null, unavailable = NO_TOUCHPAD),
                PadElement("share", Part.CENTER, "Share", 70.0, 30.0, 8.0, 12.0, BACK, Glyph.SHARE),
                PadElement("options", Part.CENTER, "Options", 130.0, 30.0, 8.0, 12.0, START, Glyph.MENU),
                PadElement("ps", Part.CENTER, "PS button", 100.0, 58.0, 11.0, 11.0, GUIDE, Glyph.HOME, short = "PS"),
            ),
        notes = listOf("Share sends Back/View; Options sends Start/Menu.", "Touchpad and light bar are not in protocol v1."),
    )

    val SWITCH_PRO = PadPreset(
        id = "switchpro", name = "Switch Pro-style", tagline = "Asymmetric sticks · Nintendo A B X Y", family = Family.NINTENDO, symmetric = false,
        elements = shoulders("ZL", "ZR", "L", "R") +
            face(164.0, 52.0, "X" to Glyph.TEXT, "A" to Glyph.TEXT, "B" to Glyph.TEXT, "Y" to Glyph.TEXT) +
            asymmetricBody("LS", "RS") + listOf(
                PadElement("minus", Part.CENTER, "−", 80.0, 36.0, 10.0, 10.0, BACK, Glyph.MINUS),
                PadElement("plus", Part.CENTER, "+", 120.0, 36.0, 10.0, 10.0, START, Glyph.PLUS),
                PadElement("capture", Part.CENTER, "Capture", 88.0, 50.0, 9.0, 9.5, null, Glyph.CAPTURE, NO_SEND),
                PadElement("home", Part.CENTER, "Home", 112.0, 50.0, 11.0, 11.0, GUIDE, Glyph.HOME),
            ),
        notes = listOf("Positional: Nintendo B (bottom) sends Xbox A's bit, and Nintendo A (right) sends Xbox B's bit — the same as Steam/SDL's default for a Pro Controller.",
            "Remap A/B in the layout if a game expects Nintendo label order.", "ZL/ZR are analog here (the real ones are digital). Capture has no protocol input."),
    )

    val EIGHTBITDO_PRO2 = PadPreset(
        id = "8bitdo-pro2", name = "8BitDo Pro 2-style", tagline = "Symmetric retro · rear paddles", family = Family.RETRO, symmetric = true,
        elements = shoulders("L2", "R2", "L", "R") +
            face(164.0, 52.0, "X" to Glyph.TEXT, "A" to Glyph.TEXT, "B" to Glyph.TEXT, "Y" to Glyph.TEXT) +
            symmetricBody("L3", "R3") + listOf(
                PadElement("select", Part.CENTER, "Select", 84.0, 44.0, 14.0, 7.0, BACK, short = "SEL"),
                PadElement("start", Part.CENTER, "Start", 116.0, 44.0, 14.0, 7.0, START, short = "START"),
                PadElement("star", Part.CENTER, "Star", 100.0, 34.0, 9.0, 9.0, null, Glyph.STAR, NO_SEND),
                PadElement("home", Part.CENTER, "Home", 100.0, 58.0, 11.0, 11.0, GUIDE, Glyph.HOME),
                PadElement("paddle_l", Part.PADDLE, "P1 (rear paddle)", 22.0, 92.0, 20.0, 10.0, null, unavailable = "Unassigned. Map it to a button in Layouts to mirror that button.", short = "P1"),
                PadElement("paddle_r", Part.PADDLE, "P2 (rear paddle)", 178.0, 92.0, 20.0, 10.0, null, unavailable = "Unassigned. Map it to a button in Layouts to mirror that button.", short = "P2"),
            ),
        notes = listOf("Nintendo-style labels, positional mapping (B bottom sends Xbox A's bit).", "Rear paddles P1/P2 start unassigned; they can mirror any button.", "Star has no protocol input."),
    )

    val all: List<PadPreset> = listOf(XBOX, DUALSENSE, DUALSHOCK4, SWITCH_PRO, EIGHTBITDO_PRO2)

    fun byId(id: String): PadPreset? = all.find { it.id == id }

    /** Settings key for "use a pad preset": `pad:<presetId>`. Anything else is a custom profile id. */
    const val PREFIX = "pad:"
    fun fromSelection(selection: String): PadPreset? = if (selection.startsWith(PREFIX)) byId(selection.removePrefix(PREFIX)) else null
    fun selection(p: PadPreset) = PREFIX + p.id
}

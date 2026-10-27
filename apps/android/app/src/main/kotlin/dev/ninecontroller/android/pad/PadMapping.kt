package dev.ninecontroller.android.pad

import dev.ninecontroller.protocol.ControlDef
import dev.ninecontroller.protocol.ControlKind
import dev.ninecontroller.protocol.MotionSettings
import dev.ninecontroller.protocol.Orientation
import dev.ninecontroller.protocol.Profile
import dev.ninecontroller.protocol.StickSettings

class MappingException(message: String) : Exception(message)

/**
 * The single mapping layer: preset element → logical [Action] → wire value.
 *
 * Rules (tested in PadMappingTest):
 *  - Sticks, D-pad and triggers are fixed to their kind and are never remapped.
 *  - Primary buttons (face, bumpers, centre, stick press) can only be permuted: assigning an action
 *    that another primary button holds swaps the two, so no action is ever lost or duplicated.
 *  - Paddles are mirrors: unassigned, or a copy of a button that some primary button already sends.
 *  - Controls with no protocol input stay unavailable and cannot be mapped.
 */
class PadMapping private constructor(val preset: PadPreset, private val overrides: Map<String, Action?>) {

    fun actionOf(e: PadElement): Action? = if (overrides.containsKey(e.id)) overrides[e.id] else e.default
    fun actionOf(elementId: String): Action? = actionOf(preset.element(elementId))

    val isDefault: Boolean get() = overrides.isEmpty()

    /** Elements that currently send something. */
    val active: List<Pair<PadElement, Action>> get() = preset.elements.mapNotNull { e -> actionOf(e)?.let { e to it } }

    /** Actions a remappable element may take, for the picker. */
    fun choices(e: PadElement): List<Action?> = when {
        !e.remappable -> emptyList()
        e.part == Part.PADDLE -> listOf<Action?>(null) + primaries().mapNotNull { actionOf(it) }.distinct().sortedBy { it.ordinal }
        else -> primaries().mapNotNull { actionOf(it) }.sortedBy { it.ordinal }
    }

    fun assign(elementId: String, action: Action?): PadMapping {
        val e = preset.elements.find { it.id == elementId } ?: throw MappingException("Unknown control '$elementId'")
        if (!e.remappable) throw MappingException("${e.label} can't be remapped")
        val next = current().toMutableMap()
        if (e.part == Part.PADDLE) {
            if (action != null && (action.kind != Action.Kind.BUTTON || primaries().none { actionOf(it) == action }))
                throw MappingException("A paddle can only mirror a button that is on this layout")
            next[e.id] = action
        } else {
            if (action == null || action.kind != Action.Kind.BUTTON) throw MappingException("${e.label} must send a button")
            val holder = primaries().find { it.id != e.id && actionOf(it) == action }
                ?: if (actionOf(e) == action) return this else throw MappingException("${action.title} isn't on this layout")
            next[holder.id] = actionOf(e)
            next[e.id] = action
        }
        return from(preset, next)
    }

    fun reset(): PadMapping = PadMapping(preset, emptyMap())

    /** Empty when the mapping is safe to use. */
    fun problems(): List<String> = validate(preset, overrides)

    /** Stable text form for settings: only differences from the preset defaults. */
    fun encode(): String = overrides.entries.sortedBy { it.key }.joinToString(";") { (k, v) -> "$k=${v?.id ?: "-"}" }

    /** Touch-surface controls (protocol profile model), positions from the preset canvas. */
    fun controls(): List<ControlDef> = active.map { (e, a) ->
        val kind = when (a.kind) {
            Action.Kind.STICK -> ControlKind.STICK; Action.Kind.DPAD -> ControlKind.DPAD
            Action.Kind.TRIGGER -> ControlKind.TRIGGER; Action.Kind.BUTTON -> ControlKind.BUTTON
        }
        ControlDef(e.id, kind, a.bind, (e.cx - e.w / 2) / 200, (e.cy - e.h / 2) / 100, e.w / 200, e.h / 100, label = e.short.take(12))
    }

    /** The same controls as a protocol Profile, so the existing validator checks every bind. */
    fun toProfile(motion: MotionSettings = MotionSettings(), stickDeadzone: Double = 0.08) = Profile(
        id = "pad-${preset.id}", name = preset.name, orientation = Orientation.LANDSCAPE,
        controls = controls(), motion = motion, sticks = StickSettings(stickDeadzone),
    )

    /**
     * The layout as an editable profile for a screen of [w] x [h] (any unit): each control where the
     * controller screen draws it (PadGeometry.place), so round controls stay round and the user can then
     * move and resize every button on its own (the layout editor).
     */
    fun toProfile(w: Float, h: Float, id: String, name: String, motion: MotionSettings = MotionSettings(), stickDeadzone: Double = 0.08): Profile {
        val controls = active.map { (e, a) ->
            val b = PadGeometry.place(e, w, h)
            val kind = when (a.kind) {
                Action.Kind.STICK -> ControlKind.STICK; Action.Kind.DPAD -> ControlKind.DPAD
                Action.Kind.TRIGGER -> ControlKind.TRIGGER; Action.Kind.BUTTON -> ControlKind.BUTTON
            }
            val x = (b.left / w).toDouble().coerceIn(0.0, 0.97); val y = (b.top / h).toDouble().coerceIn(0.0, 0.97)
            ControlDef(e.id, kind, a.bind, x, y, (b.width / w).toDouble().coerceIn(0.03, 1 - x), (b.height / h).toDouble().coerceIn(0.03, 1 - y), label = e.short.take(12))
        }
        return Profile(id = id, name = name.take(48), orientation = Orientation.LANDSCAPE, controls = controls, motion = motion, sticks = StickSettings(stickDeadzone))
    }

    private fun primaries() = preset.elements.filter { it.remappable && it.part != Part.PADDLE }
    private fun current(): Map<String, Action?> = preset.elements.filter { it.remappable }.associate { it.id to actionOf(it) }

    companion object {
        fun default(preset: PadPreset) = PadMapping(preset, emptyMap())

        /** Keep only real differences from defaults. Throws if the result breaks a rule. */
        private fun from(preset: PadPreset, full: Map<String, Action?>): PadMapping {
            val diff = full.filter { (id, a) -> preset.element(id).default != a }
            validate(preset, diff).firstOrNull()?.let { throw MappingException(it) }
            return PadMapping(preset, diff)
        }

        fun validate(preset: PadPreset, overrides: Map<String, Action?>): List<String> {
            val out = ArrayList<String>()
            val byId = preset.elements.associateBy { it.id }
            for ((id, a) in overrides) {
                val e = byId[id] ?: run { out += "Unknown control '$id'"; null } ?: continue
                if (!e.remappable) out += "${e.label} can't be remapped"
                if (a != null && a.kind != Action.Kind.BUTTON) out += "${e.label} can only send a button"
                if (a == null && e.part != Part.PADDLE) out += "${e.label} must send a button"
            }
            val act = { e: PadElement -> if (overrides.containsKey(e.id)) overrides[e.id] else e.default }
            val prim = preset.elements.filter { it.remappable && it.part != Part.PADDLE }
            val sent = prim.mapNotNull(act)
            sent.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { out += "${it.title} is on more than one button" }
            if (sent.toSet() != prim.mapNotNull { it.default }.toSet()) out += "Every button of the preset must still be reachable"
            preset.elements.filter { it.part == Part.PADDLE }.forEach { p ->
                val a = act(p)
                if (a != null && a !in sent) out += "${p.label} mirrors ${a.title}, which no button sends"
            }
            return out.distinct()
        }

        /** Parse stored text. Anything invalid falls back to the preset defaults, with the reason. */
        fun decode(preset: PadPreset, text: String?): Pair<PadMapping, String?> {
            if (text.isNullOrBlank()) return default(preset) to null
            val map = LinkedHashMap<String, Action?>()
            for (pair in text.split(';')) {
                val (k, v) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return default(preset) to "Saved mapping was unreadable; defaults restored"
                map[k] = if (v == "-") null else Action.fromId(v) ?: return default(preset) to "Saved mapping named an unknown action; defaults restored"
            }
            val problems = validate(preset, map)
            return if (problems.isEmpty()) PadMapping(preset, map.filter { (id, a) -> preset.element(id).default != a }) to null
            else default(preset) to "Saved mapping was invalid (${problems.first()}); defaults restored"
        }
    }
}

/** Uniform placement of the 200 x 100 preset canvas into a W x H area (any unit). */
object PadGeometry {
    /** A control's screen area. [round] controls (face buttons, sticks) are the ellipse inside the box. */
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float, val round: Boolean = false) {
        val width get() = right - left; val height get() = bottom - top
        val cx get() = (left + right) / 2; val cy get() = (top + bottom) / 2
        fun contains(x: Float, y: Float): Boolean {
            if (!round) return x in left..right && y in top..bottom
            val nx = (x - cx) / (width / 2).coerceAtLeast(1e-6f); val ny = (y - cy) / (height / 2).coerceAtLeast(1e-6f)
            return nx * nx + ny * ny <= 1f
        }
        fun inflateTo(min: Float): Box {
            val dx = maxOf(0f, (min - width) / 2); val dy = maxOf(0f, (min - height) / 2)
            return Box(left - dx, top - dy, right + dx, bottom + dy, round)
        }
    }

    /**
     * Positions stretch with the area (controls move toward the grips on wide phones); sizes use one
     * scale so round controls stay round. With [fit] the canvas keeps its 2:1 shape (previews).
     */
    fun place(e: PadElement, w: Float, h: Float, fit: Boolean = false): Box {
        val s = minOf(w / 200f, h / 100f)
        val (sx, sy) = if (fit) s to s else w / 200f to h / 100f
        val ox = if (fit) (w - 200f * s) / 2 else 0f
        val oy = if (fit) (h - 100f * s) / 2 else 0f
        val cx = ox + e.cx.toFloat() * sx; val cy = oy + e.cy.toFloat() * sy
        val hw = e.w.toFloat() * s / 2; val hh = e.h.toFloat() * s / 2
        return Box(cx - hw, cy - hh, cx + hw, cy + hh, e.round)
    }

    /** Distance from (x, y) to the control's edge (circle for round controls); 0 inside. */
    private fun Box.distance(x: Float, y: Float): Float {
        if (round) return maxOf(0f, kotlin.math.hypot(x - cx, y - cy) - minOf(width, height) / 2)
        val dx = maxOf(left - x, 0f, x - right); val dy = maxOf(top - y, 0f, y - bottom)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Touch hit test. A control the finger is actually on always wins (if drawn boxes overlap, the one
     * whose centre is closest). Only a finger that misses every control falls back to the targets grown
     * to [minTarget] (48dp), and then to the control whose *edge* is nearest — a near-miss goes to the
     * control it nearly touched, never to a bigger neighbour whose centre happens to be closer.
     */
    fun hit(boxes: Map<String, Box>, x: Float, y: Float, minTarget: Float): String? {
        boxes.entries.filter { it.value.contains(x, y) }
            .minByOrNull { val b = it.value; (b.cx - x) * (b.cx - x) + (b.cy - y) * (b.cy - y) }
            ?.let { return it.key }
        return boxes.entries.filter { it.value.inflateTo(minTarget).contains(x, y) }
            .minByOrNull { it.value.distance(x, y) }?.key
    }
}

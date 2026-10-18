package dev.ninecontroller.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Profile schema 1 — mirrors protocol/rust/src/profile.rs and protocol/schema/profile.v1.schema.json. */
const val PROFILE_SCHEMA = 1
const val MAX_CONTROLS = 48

@Serializable enum class Orientation { @SerialName("landscape") LANDSCAPE, @SerialName("portrait") PORTRAIT }

@Serializable enum class ControlKind {
    @SerialName("button") BUTTON, @SerialName("stick") STICK, @SerialName("dpad") DPAD,
    @SerialName("trigger") TRIGGER, @SerialName("pedal") PEDAL, @SerialName("steering") STEERING,
}

@Serializable
data class ControlDef(
    val id: String,
    @SerialName("type") val kind: ControlKind,
    val bind: String,
    val x: Double, val y: Double, val w: Double, val h: Double,
    val label: String? = null,
    val opacity: Double = 1.0,
    val toggle: Boolean = false,
    val haptic: Boolean = true,
)

@Serializable
data class MotionSettings(
    val enabled: Boolean = false,
    val axis: String = "lx",
    val lockDeg: Double = 45.0,
    val deadzone: Double = 0.02,
    val sensitivity: Double = 1.0,
    val curve: Double = 1.0,
    val invert: Boolean = false,
    val minCutoff: Double = 1.5,
    val beta: Double = 0.03,
)

@Serializable data class StickSettings(val deadzone: Double = 0.08)
@Serializable data class HapticSettings(val enabled: Boolean = true, val strength: Double = 0.7)

@Serializable
data class Profile(
    val schema: Int = PROFILE_SCHEMA,
    val id: String,
    val name: String,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val controls: List<ControlDef>,
    val motion: MotionSettings = MotionSettings(),
    val sticks: StickSettings = StickSettings(),
    val haptics: HapticSettings = HapticSettings(),
) {
    fun validate() {
        fun bad(m: String): Nothing = throw ProfileException(m)
        fun range(n: String, v: Double, lo: Double, hi: Double) { if (!v.isFinite() || v < lo || v > hi) bad("$n must be in $lo..$hi") }
        if (schema != PROFILE_SCHEMA) bad("schema must be 1")
        if (id.isEmpty() || id.length > 64 || !id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) bad("id must be 1-64 chars of a-z 0-9 - _")
        if (name.isBlank() || name.codePointCount(0, name.length) > 48) bad("name must be 1-48 characters")
        if (controls.size > MAX_CONTROLS) bad("at most $MAX_CONTROLS controls")
        val seen = HashSet<String>()
        for (c in controls) {
            if (c.id.isEmpty() || c.id.length > 32 || !seen.add(c.id)) bad("control id '${c.id}' empty, too long or duplicated")
            range("${c.id}.x", c.x, 0.0, 1.0); range("${c.id}.y", c.y, 0.0, 1.0)
            range("${c.id}.w", c.w, 0.03, 1.0); range("${c.id}.h", c.h, 0.03, 1.0)
            if (c.x + c.w > 1.0 + 1e-6 || c.y + c.h > 1.0 + 1e-6) bad("${c.id} extends past the screen edge")
            range("${c.id}.opacity", c.opacity, 0.1, 1.0)
            if ((c.label?.codePointCount(0, c.label.length) ?: 0) > 12) bad("${c.id} label longer than 12")
            val ok = when (c.kind) {
                ControlKind.BUTTON -> Button.fromId(c.bind) != null
                ControlKind.STICK -> c.bind == "left" || c.bind == "right"
                ControlKind.DPAD -> c.bind == "dpad"
                ControlKind.TRIGGER, ControlKind.PEDAL -> c.bind == "lt" || c.bind == "rt"
                ControlKind.STEERING -> c.bind == "motion"
            }
            if (!ok) bad("${c.id} has invalid bind '${c.bind}' for its type")
        }
        if (motion.axis != "lx" && motion.axis != "rx") bad("motion.axis must be lx or rx")
        range("motion.lockDeg", motion.lockDeg, 10.0, 180.0)
        range("motion.deadzone", motion.deadzone, 0.0, 0.5)
        range("motion.sensitivity", motion.sensitivity, 0.25, 4.0)
        range("motion.curve", motion.curve, 0.5, 3.0)
        range("motion.minCutoff", motion.minCutoff, 0.05, 20.0)
        range("motion.beta", motion.beta, 0.0, 1.0)
        range("sticks.deadzone", sticks.deadzone, 0.0, 0.5)
        range("haptics.strength", haptics.strength, 0.0, 1.0)
    }

    fun toJson(): String = pretty.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
        private val pretty = Json { prettyPrint = true; encodeDefaults = true }

        /** Parse any supported schema, migrate to v1, validate. */
        fun fromJson(s: String): Profile {
            val v = try { json.parseToJsonElement(s).jsonObject } catch (e: Exception) { throw ProfileException("invalid JSON: ${e.message}") }
            val schema = (v["schema"] ?: v["version"])?.jsonPrimitive?.intOrNull ?: 0
            val migrated = when {
                schema == 0 -> migrateV0(v)
                schema == 1 -> v
                else -> throw ProfileTooNewException(schema)
            }
            val p = try { json.decodeFromJsonElement(serializer(), migrated) } catch (e: Exception) { throw ProfileException("invalid profile: ${e.message}") }
            p.validate()
            return p
        }

        fun migrateV0(v: JsonObject): JsonObject {
            val name = v["name"]?.jsonPrimitive?.content ?: "Imported"
            val id = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("").take(64).ifEmpty { "imported" }
            val steer = v["steer"]?.jsonObject
            val range = steer?.get("range")?.jsonPrimitive?.doubleOrNull ?: 90.0
            return buildJsonObject {
                put("schema", 1); put("id", id); put("name", name); put("orientation", "landscape")
                put("controls", buildJsonArray {
                    (v["layout"]?.jsonArray ?: emptyList<JsonElement>()).forEachIndexed { i, el ->
                        val c = el.jsonObject
                        val kind = when (val k = c["kind"]?.jsonPrimitive?.content ?: "") {
                            "pad" -> "dpad"; "slider" -> "trigger"; "button", "stick", "pedal" -> k
                            else -> throw ProfileException("v0 control kind '$k' cannot be migrated")
                        }
                        val r = c["rect"]?.jsonArray?.takeIf { it.size == 4 } ?: throw ProfileException("v0 control $i has no rect")
                        val f = { j: Int -> r[j].jsonPrimitive.doubleOrNull ?: 0.0 }
                        add(buildJsonObject {
                            put("id", "c$i"); put("type", kind)
                            put("bind", c["action"]?.jsonPrimitive?.content ?: if (kind == "dpad") "dpad" else "")
                            put("x", f(0)); put("y", f(1)); put("w", f(2)); put("h", f(3))
                        })
                    }
                })
                put("motion", buildJsonObject {
                    put("enabled", steer?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: false)
                    put("lockDeg", range / 2.0)
                    put("deadzone", steer?.get("deadzone")?.jsonPrimitive?.doubleOrNull ?: 0.02)
                })
            }
        }
    }
}

open class ProfileException(message: String) : Exception(message)
class ProfileTooNewException(val schema: Int) : ProfileException("This profile uses schema $schema; update the app to open it")

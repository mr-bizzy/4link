// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON Schema SUBSET a function's input and output may use (§3): an
 * object of named properties, each a string, number, boolean or (one level
 * down) another object. Anything else is outside the subset and the function
 * carrying it is dropped on reading.
 */
sealed class Schema {
    /** For the model: what this value means. Truncated on reading. */
    abstract val description: String?

    data class Obj(
        val properties: Map<String, Schema>,
        val required: Set<String> = emptySet(),
        override val description: String? = null,
    ) : Schema()

    data class Str(
        val maxLength: Int? = null,
        val enum: List<String>? = null,
        override val description: String? = null,
        /**
         * The caller may fill this with the user's own words (§3a): the model
         * sends a [Said] placeholder instead of retyping a long text.
         */
        val fromSpeech: Boolean = false,
    ) : Schema()

    data class Num(override val description: String? = null) : Schema()

    data class Bool(override val description: String? = null) : Schema()

    fun toJson(): JSONObject = JSONObject().also { o ->
        when (this) {
            is Obj -> {
                o.put("type", "object")
                o.put("properties", JSONObject().also { p -> properties.forEach { (k, v) -> p.put(k, v.toJson()) } })
                if (required.isNotEmpty()) o.put("required", JSONArray(required.toList()))
            }
            is Str -> {
                o.put("type", "string")
                maxLength?.let { o.put("maxLength", it) }
                enum?.let { o.put("enum", JSONArray(it)) }
                if (fromSpeech) o.put(Said.WIRE, true)
            }
            is Num -> o.put("type", "number")
            is Bool -> o.put("type", "boolean")
        }
        description?.let { o.put("description", it) }
    }

    companion object {
        /** An empty object: a function that takes or returns nothing. */
        val NOTHING = Obj(emptyMap())

        /**
         * Reads one schema node, or throws [IllegalArgumentException] naming
         * what fell outside the subset. [depth] stops nesting below one object
         * inside the top one.
         */
        fun parse(o: JSONObject, depth: Int = 0): Schema {
            val description = o.optString("description").takeIf { it.isNotBlank() }
                ?.take(FourLink.PROPERTY_DESCRIPTION_MAX)
            return when (val type = o.optString("type")) {
                "object" -> {
                    require(depth <= 1) { "objects may nest only one level" }
                    val props = o.optJSONObject("properties") ?: JSONObject()
                    val properties = linkedMapOf<String, Schema>()
                    for (key in props.keys()) {
                        properties[key] = parse(props.getJSONObject(key), depth + 1)
                    }
                    val required = o.optJSONArray("required")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }.toSet()
                    } ?: emptySet()
                    require(properties.keys.containsAll(required)) { "required names a property that does not exist" }
                    Obj(properties, required, description)
                }
                "string" -> Str(
                    maxLength = if (o.has("maxLength")) o.getInt("maxLength") else null,
                    enum = o.optJSONArray("enum")?.let { a -> (0 until a.length()).map { a.getString(it) } },
                    description = description,
                    fromSpeech = o.optBoolean(Said.WIRE, false),
                )
                "number", "integer" -> Num(description)
                "boolean" -> Bool(description)
                else -> throw IllegalArgumentException("type \"$type\" is outside the 4Link schema subset")
            }
        }
    }
}

/** One published function (§3). */
data class FunctionSpec(
    val id: String,
    val version: String,
    val title: String,
    val description: String,
    val effect: Effect,
    val input: Schema.Obj = Schema.NOTHING,
    val output: Schema.Obj = Schema.NOTHING,
    /**
     * Always confirmed by the caller, even when its user switched on "add/change
     * without asking" for this app (§P3). For calls a misheard word must never
     * run unseen: switching a rack's power, moving a desk. Wire: "confirm":"always".
     */
    val confirmAlways: Boolean = false,
    /**
     * §3c — a successful reply carries a frame (§4a) beside its json. A caller that cannot consume a
     * frame MUST NOT list this function to a model or to a user. Wire: "frame":true.
     */
    val frame: Boolean = false,
    /**
     * §11b — the reasons this function MAY answer with, for a reader that wants to know them in
     * advance. Optional, and never exhaustive by promise: a caller still handles a reason not listed.
     * Wire: "reasons": ["4zones.grant_off", …].
     */
    val reasons: List<String> = emptyList(),
) {
    /** The major of [version]; 0 when it cannot be read. */
    val major: Int get() = version.substringBefore('.').toIntOrNull() ?: 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("version", version)
        put("title", title)
        put("description", description)
        put("effect", effect.wire)
        put("input", input.toJson())
        put("output", output.toJson())
        if (confirmAlways) put("confirm", "always")
        if (frame) put("frame", true)
        if (reasons.isNotEmpty()) put("reasons", org.json.JSONArray(reasons))
    }

    companion object {
        private val ID = Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")

        /** Reads one function, applying the limits; throws when it is not one. */
        fun parse(o: JSONObject): FunctionSpec {
            val id = o.getString("id")
            require(ID.matches(id)) { "id \"$id\" is not <area>.<verb>" }
            val version = o.optString("version", "1.0")
            require(Regex("\\d+\\.\\d+").matches(version)) { "version \"$version\" is not major.minor" }
            val effect = requireNotNull(Effect.fromWireOrChange(o.optString("effect"))) { "effect is missing (read, create, change or delete)" }
            val input = o.optJSONObject("input")?.let { Schema.parse(it) } ?: Schema.NOTHING
            val output = o.optJSONObject("output")?.let { Schema.parse(it) } ?: Schema.NOTHING
            require(input is Schema.Obj) { "input must be an object" }
            require(output is Schema.Obj) { "output must be an object" }
            return FunctionSpec(
                id = id,
                version = version,
                // Enforced by the reader, never trusted from the writer (§3).
                title = o.optString("title").trim().take(FourLink.TITLE_MAX),
                description = o.optString("description").trim().take(FourLink.DESCRIPTION_MAX),
                effect = effect,
                input = input,
                output = output,
                confirmAlways = o.optString("confirm") == "always",
                frame = o.optBoolean("frame", false),
                reasons = o.optJSONArray("reasons")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).takeIf(FourLink::isReason) }
                }.orEmpty(),
            )
        }
    }
}

/** What one app publishes (§3). */
data class Catalogue(
    val app: String,
    val functions: List<FunctionSpec>,
    val version: String = FourLink.VERSION,
) {
    val major: Int get() = version.substringBefore('.').toIntOrNull() ?: 0

    fun find(id: String): FunctionSpec? = functions.firstOrNull { it.id == id }

    fun toJson(): String = JSONObject().apply {
        put(FourLink.KEY_VERSION, version)
        put(FourLink.KEY_APP, app)
        put("functions", JSONArray().also { a -> functions.forEach { a.put(it.toJson()) } })
    }.toString()

    /** A catalogue read from JSON, and the functions that had to be dropped, with why. */
    data class Parsed(val catalogue: Catalogue, val dropped: List<Pair<String, String>>)

    companion object {
        /**
         * Reads a catalogue. Null when the text is not a catalogue at all or
         * carries a protocol major this library does not know (§10). A
         * function outside the subset is dropped, not fatal: the rest of the
         * app's functions still work.
         */
        fun parse(json: String): Parsed? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            val version = o.optString(FourLink.KEY_VERSION, "")
            if (version.substringBefore('.').toIntOrNull() != FourLink.MAJOR) return null
            val app = o.optString(FourLink.KEY_APP).trim().take(FourLink.TITLE_MAX)
            val functions = mutableListOf<FunctionSpec>()
            val dropped = mutableListOf<Pair<String, String>>()
            val seen = mutableSetOf<String>()
            val array = o.optJSONArray("functions") ?: JSONArray()
            for (i in 0 until array.length()) {
                val f = array.optJSONObject(i) ?: continue
                val id = f.optString("id", "#$i")
                runCatching { FunctionSpec.parse(f) }
                    .onSuccess { if (seen.add(it.id)) functions += it else dropped += it.id to "duplicate id" }
                    .onFailure { dropped += id to (it.message ?: "unreadable") }
            }
            return Parsed(Catalogue(app, functions, version), dropped)
        }
    }
}

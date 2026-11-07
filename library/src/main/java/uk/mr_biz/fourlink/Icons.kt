// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject

/**
 * `icons.get` (§15b): one SVG path per id on a fixed 24 x 24 view box, and nothing else (no colour,
 * no stroke, no bitmap, no XML). Both sides use this object, so the provider and the caller cannot
 * drift. The reader enforces every limit; an entry over a limit or outside the path grammar is
 * dropped with a log line and the rest are kept. A missing id is not an error.
 */
object Icons {
    const val FUNCTION = "icons.get"
    const val VERSION = "1.0"

    /** At most this many ids in one call. */
    const val MAX_IDS = 32
    /** At most this many characters in one `d`. */
    const val D_MAX = 4096

    const val KEY_IDS = "ids"
    const val KEY_ICONS = "icons"

    /** One picture: the path, and the fill rule (even-odd when true, non-zero when false). */
    data class Icon(val d: String, val evenodd: Boolean = false)

    private const val COMMANDS = "MmLlHhVvCcSsQqTtAaZz"

    /**
     * Whether [d] keeps to the path grammar of §15b: the commands `MmLlHhVvCcSsQqTtAaZz`, numbers
     * (an optional sign, digits with an optional point, an optional exponent), spaces and commas,
     * nothing else, and it starts with a move. One pass, no regular expression, so a hostile 4096
     * characters cost a linear scan. Argument counts per command are not checked: nothing here can
     * carry anything but a path.
     */
    fun isPath(d: String): Boolean {
        if (d.isBlank() || d.length > D_MAX) return false
        var i = 0
        var first = true
        while (i < d.length) {
            val c = d[i]
            when {
                c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r' -> i++
                c in COMMANDS -> {
                    if (first && c != 'M' && c != 'm') return false
                    first = false; i++
                }
                c == '+' || c == '-' || c == '.' || c.isAsciiDigit() -> {
                    if (first) return false
                    i = number(d, i) ?: return false
                }
                else -> return false
            }
        }
        return !first
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** The index after the number starting at [start], or null when there is none there. */
    private fun number(d: String, start: Int): Int? {
        var i = start
        if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
        var digits = 0
        while (i < d.length && d[i].isAsciiDigit()) { i++; digits++ }
        if (i < d.length && d[i] == '.') {
            i++
            while (i < d.length && d[i].isAsciiDigit()) { i++; digits++ }
        }
        if (digits == 0) return null
        if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
            var j = i + 1
            if (j < d.length && (d[j] == '+' || d[j] == '-')) j++
            var exp = 0
            while (j < d.length && d[j].isAsciiDigit()) { j++; exp++ }
            if (exp == 0) return null
            i = j
        }
        return i
    }

    /** Why [icon] breaks §15b, or null when it is fine. */
    fun problem(icon: Icon): String? = when {
        icon.d.length > D_MAX -> "d over $D_MAX characters"
        !isPath(icon.d) -> "d is not an SVG path"
        else -> null
    }

    /** The `ids` input as text. More than [MAX_IDS] is not sent: the caller splits its keys. */
    fun idsToText(ids: List<String>): String {
        require(ids.size <= MAX_IDS) { "at most $MAX_IDS ids a call" }
        return JSONArray(ids).toString()
    }

    /**
     * The provider's reading of `ids`: the ids, or null when the text is not an array of strings
     * or holds more than [MAX_IDS] (answered `bad_arguments`). Duplicates are kept once.
     */
    fun parseIds(text: String?): List<String>? {
        val a = runCatching { JSONArray(text ?: return null) }.getOrNull() ?: return null
        if (a.length() > MAX_IDS) return null
        val out = linkedSetOf<String>()
        for (i in 0 until a.length()) out += (a.opt(i) as? String ?: return null)
        return out.toList()
    }

    /**
     * The `icons` output as the provider sends it: only the ids it has a picture for, by the same
     * rules as [parse], so nothing written here is dropped there.
     */
    fun toText(icons: Map<String, Icon>, onDrop: (String) -> Unit = {}): String {
        val o = JSONObject()
        for ((id, icon) in icons) {
            val why = problem(icon)
            if (why != null) { onDrop("icon ${id.take(40)} left out: $why"); continue }
            o.put(id, JSONObject().put("d", icon.d).also { if (icon.evenodd) it.put("evenodd", true) })
        }
        return o.toString()
    }

    /** The pictures in [text]. A bad entry is dropped and [onDrop] told why; text that is not an object gives none. */
    fun parse(text: String?, onDrop: (String) -> Unit = {}): Map<String, Icon> {
        val o = runCatching { JSONObject(text ?: return emptyMap()) }.getOrElse { onDrop("not a JSON object"); return emptyMap() }
        val out = linkedMapOf<String, Icon>()
        for (id in o.keys()) {
            val e = o.optJSONObject(id)
            if (e == null) { onDrop("icon ${id.take(40)} dropped: not an object"); continue }
            val d = e.opt("d") as? String
            if (d == null) { onDrop("icon ${id.take(40)} dropped: no d"); continue }
            val evenodd = when (val v = e.opt("evenodd")) {
                null -> false
                is Boolean -> v
                else -> { onDrop("icon ${id.take(40)} dropped: evenodd is not true or false"); continue }
            }
            val icon = Icon(d, evenodd)
            val why = problem(icon)
            if (why != null) { onDrop("icon ${id.take(40)} dropped: $why"); continue }
            out[id] = icon
        }
        return out
    }

    /** The catalogue entry a provider lists (§15b). */
    val SPEC = FunctionSpec(
        FUNCTION, VERSION, "Pictures for keys",
        "Pictures for a control surface's keys. Not for a person's request.",
        Effect.READ,
        Schema.Obj(mapOf(KEY_IDS to Schema.Str(description = "JSON array of ids, at most 32")), setOf(KEY_IDS)),
        Schema.Obj(mapOf(KEY_ICONS to Schema.Str(description = "JSON object: id to {d, evenodd}"))),
    )

    /**
     * The provider's handler: [pictures] gives the pictures it has for the ids asked (leaving out
     * the rest). Bad input is `bad_arguments`; the answer goes through [toText]'s limits.
     */
    fun handle(arguments: JSONObject, pictures: (List<String>) -> Map<String, Icon>, onDrop: (String) -> Unit = {}): Outcome {
        val ids = parseIds(arguments.opt(KEY_IDS) as? String)
            ?: return Outcome.BadArguments("ids must be a JSON array of at most $MAX_IDS ids.")
        val wanted = ids.toSet()
        val icons = pictures(ids).filterKeys { it in wanted }
        return Outcome.Ok(JSONObject().put(KEY_ICONS, toText(icons, onDrop)).toString())
    }
}

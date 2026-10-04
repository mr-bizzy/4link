// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject

/**
 * The user's own words in a string argument (spec §3a). A long text, such as
 * the body of a dictated note, is not retyped by the model: it sends a
 * placeholder in a field marked [Schema.Str.fromSpeech], and the caller puts
 * in what the user said, exactly as the caller has it (tidied, if the caller
 * tidies). Retyping was slow, could change words, and a long note could be
 * cut off by the model's own output limit.
 *
 * The placeholder is either `{{said}}` (everything the user said) or
 * `{{said from: <the first words of the part to keep>}}`, which drops a
 * spoken lead-in such as "add this to my notes".
 */
object Said {
    /** The schema keyword marking a fillable string. */
    const val WIRE = "x-from-speech"

    private val PLACEHOLDER = Regex("""^\s*\{\{\s*said(?:\s+from\s*:\s*(.*?))?\s*\}\}\s*$""", RegexOption.IGNORE_CASE)

    /** True when [value] is a placeholder rather than text. */
    fun isPlaceholder(value: String): Boolean = PLACEHOLDER.matches(value)

    /** The rule for the model; only given when the caller has the words to put in. */
    const val RULE =
        "A string field with \"$WIRE\": true can take the user's own words instead of your retyping them: " +
            "send exactly \"{{said}}\" for everything the user said, or \"{{said from: <the first five or so words of the part to keep>}}\" " +
            "to leave out a lead-in like \"add this to my notes\". Use it for the body of a note or message; never use it in other fields."

    /**
     * [arguments] with every placeholder in a fillable field replaced by
     * [said], or null when a placeholder stands where it may not (a field not
     * marked, or no words to put in). Unchanged when there is none.
     */
    fun fill(input: Schema.Obj, arguments: JSONObject, said: String?): JSONObject? {
        val out = JSONObject(arguments.toString())
        for (key in arguments.keys()) {
            val value = arguments.opt(key) as? String ?: continue
            val m = PLACEHOLDER.matchEntire(value) ?: continue
            val field = input.properties[key] as? Schema.Str
            if (field?.fromSpeech != true || said.isNullOrBlank()) return null
            out.put(key, from(said, m.groupValues[1]))
        }
        return out
    }

    /**
     * [said] from the first place [start] occurs, compared word by word,
     * ignoring case and punctuation; all of [said] when [start] is blank or
     * not found, so nothing the user said is lost to a model's misquote.
     */
    internal fun from(said: String, start: String): String {
        val want = words(start).map { it.second }
        if (want.isEmpty()) return said.trim()
        val have = words(said)
        for (i in 0..have.size - want.size) {
            if ((want.indices).all { have[i + it].second == want[it] }) return said.substring(have[i].first).trim()
        }
        return said.trim()
    }

    /** (start offset, lower-case letters and digits) of each word. */
    private fun words(s: String): List<Pair<Int, String>> =
        Regex("""[\p{L}\p{N}']+""").findAll(s).map { it.range.first to it.value.lowercase().replace("'", "") }.toList()
}

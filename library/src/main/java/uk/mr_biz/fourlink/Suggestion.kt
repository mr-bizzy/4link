// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject

/**
 * A provider's "did you mean" after a `bad_arguments` answer (§11a). NOTHING was done;
 * if the user agrees, the caller invokes [function] with [arguments]. The provider never
 * acts on it by itself.
 */
data class Suggestion(val question: String, val function: String, val arguments: JSONObject) {

    fun toJson(): String = JSONObject()
        .put("question", question.take(QUESTION_MAX))
        .put("function", function)
        .put("arguments", arguments)
        .toString()

    companion object {
        /** One sentence for the user. */
        const val QUESTION_MAX = 200

        private val FUNCTION_ID = Regex("^[a-z0-9]+(\\.[a-z0-9]+)+$")

        /**
         * Reads a suggestion from a provider, or null when it is anything but a well-formed one.
         * The question is cut to [QUESTION_MAX]; it is the provider's TEXT, not the caller's
         * instruction (§8). Whether the function is one the user approved, and whether the
         * arguments fit its schema, is the CALLER's check against the catalogue it read.
         */
        fun parse(json: String?): Suggestion? {
            val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
            val question = (o.opt("question") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val function = (o.opt("function") as? String)?.trim()?.takeIf { FUNCTION_ID.matches(it) } ?: return null
            val arguments = o.optJSONObject("arguments") ?: return null
            return Suggestion(question.take(QUESTION_MAX), function, arguments)
        }
    }
}

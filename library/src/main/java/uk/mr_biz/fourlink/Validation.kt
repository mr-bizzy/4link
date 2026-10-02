package uk.mr_biz.fourlink

import org.json.JSONObject

/**
 * Arguments against a function's input schema (§3, §11 `bad_arguments`).
 * Returns the first problem as a sentence naming the field, or null when
 * the arguments fit. Unknown fields are ignored (§10).
 */
object Validation {

    fun problem(schema: Schema, value: Any?, path: String = "arguments"): String? = when (schema) {
        is Schema.Obj -> {
            if (value !is JSONObject) "$path must be an object" else {
                schema.required.firstOrNull { !value.has(it) || value.isNull(it) }?.let { return "$path.$it is required" }
                schema.properties.entries.asSequence()
                    .filter { value.has(it.key) && !value.isNull(it.key) }
                    .mapNotNull { (k, s) -> problem(s, value.get(k), "$path.$k") }
                    .firstOrNull()
            }
        }
        is Schema.Str -> when {
            value !is String -> "$path must be text"
            schema.maxLength != null && value.length > schema.maxLength -> "$path is longer than ${schema.maxLength} characters"
            schema.enum != null && value !in schema.enum -> "$path must be one of ${schema.enum.joinToString(", ")}"
            else -> null
        }
        is Schema.Num -> if (value is Number) null else "$path must be a number"
        is Schema.Bool -> if (value is Boolean) null else "$path must be true or false"
    }

    /** The arguments as text: a parse failure is a problem like any other. */
    fun problem(function: FunctionSpec, argumentsJson: String?): String? {
        val o = runCatching { JSONObject(argumentsJson ?: "{}") }.getOrNull()
            ?: return "arguments are not a JSON object"
        return problem(function.input, o)
    }
}

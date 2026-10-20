// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject

/**
 * The element format of `links.list` (§14a): a family app's links, each a ready-made 4Link call
 * (function, version, arguments) that the caller runs with `invoke` on that app, so identity,
 * family, pairing, grants and `confirm` apply as for any call. Both sides use this, so the
 * provider and the caller cannot drift (§14c). The array travels as text, since the schema
 * subset has no arrays; its order is the order. No url, no icon.
 */
object Links {
    const val FUNCTION = "links.list"
    const val VERSION = "1.0"

    const val ID_MAX = 200
    const val NAME_MAX = 60
    const val GROUP_MAX = 60

    private val MAJOR_MINOR = Regex("\\d+\\.\\d+")

    /** [id] is fixed (made from what the link does, never from a name); [name] is for people. */
    data class Link(
        val id: String,
        val name: String,
        val function: String,
        val version: String,
        val arguments: JSONObject,
        val group: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
            .also { o -> group?.let { o.put("group", it) } }
            .put("function", function).put("version", version).put("arguments", JSONObject(arguments.toString()))

        // JSONObject has no value equality; compare the arguments as text.
        override fun equals(other: Any?): Boolean = other is Link && id == other.id && name == other.name &&
            function == other.function && version == other.version && group == other.group &&
            canonical(arguments) == canonical(other.arguments)

        override fun hashCode(): Int = listOf(id, name, function, version, group, canonical(arguments)).hashCode()
    }

    /** [value] as text with every object's keys sorted, so two equal argument objects compare equal. */
    internal fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null -> "null"
        else -> value.toString()
    }

    /** Why [link] breaks §14a, or null when it is fine (uniqueness apart). */
    fun problem(link: Link): String? = when {
        link.id.isBlank() -> "no id"
        link.id.length > ID_MAX -> "id over $ID_MAX"
        link.name.isBlank() -> "no name"
        link.name.length > NAME_MAX -> "name over $NAME_MAX"
        link.group != null && link.group.length > GROUP_MAX -> "group over $GROUP_MAX"
        !FunctionSpec.ID.matches(link.function) -> "function is not a 4Link id"
        !MAJOR_MINOR.matches(link.version) -> "version is not major.minor"
        else -> null
    }

    /**
     * The links in [text]. An element over a limit, with a missing or ill-formed field, or with an
     * id already seen is dropped, and [onDrop] is told why (the caller's log line); the rest are
     * kept, in the array's order. With the provider's [catalogue], a link whose function is not
     * in it is dropped too (§14a: the function must be in the provider's own catalogue). Text
     * that is not a JSON array gives none.
     */
    fun parse(text: String?, catalogue: Catalogue? = null, onDrop: (String) -> Unit = {}): List<Link> {
        val a = runCatching { JSONArray(text ?: return emptyList()) }.getOrElse { onDrop("not a JSON array"); return emptyList() }
        val out = mutableListOf<Link>()
        val seen = mutableSetOf<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)
            if (o == null) { onDrop("element $i is not an object"); continue }
            fun str(key: String): String? = (o.opt(key) as? String)?.trim()
            val arguments = o.opt("arguments") as? JSONObject
            if (arguments == null) { onDrop("element $i dropped: arguments is not an object"); continue }
            val link = Link(
                str("id").orEmpty(), str("name").orEmpty(), str("function").orEmpty(), str("version").orEmpty(),
                arguments, str("group")?.ifEmpty { null },
            )
            val why = problem(link) ?: when {
                link.id in seen -> "duplicate id"
                catalogue != null && catalogue.find(link.function) == null -> "function not in the provider's catalogue"
                else -> null
            }
            if (why != null) { onDrop("element $i dropped: $why"); continue }
            seen += link.id
            out += link
        }
        return out
    }

    /** [links] as `links.list` returns them; the same rules as [parse], so nothing it writes is dropped there. */
    fun toText(links: List<Link>, onDrop: (String) -> Unit = {}): String {
        val a = JSONArray()
        val seen = mutableSetOf<String>()
        for (l in links) {
            val why = problem(l) ?: if (l.id in seen) "duplicate id" else null
            if (why != null) { onDrop("link ${l.id.take(40)} left out: $why"); continue }
            seen += l.id
            a.put(l.toJson())
        }
        return a.toString()
    }
}

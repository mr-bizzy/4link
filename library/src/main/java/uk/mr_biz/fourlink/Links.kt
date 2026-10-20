// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject

/**
 * The element format of `links.list` (§14a): a family app's links, each a URL in its own
 * scheme that the caller opens as ACTION_VIEW. Both sides use this, so the provider and the
 * caller cannot drift (§14c). The array travels as text, since the schema subset has no arrays.
 */
object Links {
    const val FUNCTION = "links.list"
    const val VERSION = "1.0"

    const val ID_MAX = 200
    const val NAME_MAX = 60
    const val URL_MAX = 500
    const val GROUP_MAX = 60

    /** [id] is fixed (made from what the link does, never from a name); [name] is for people. */
    data class Link(val id: String, val name: String, val url: String, val group: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url)
            .also { o -> group?.let { o.put("group", it) } }
    }

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):.+")

    /** Why [link] breaks §14a, or null when it is fine (uniqueness apart). */
    fun problem(link: Link): String? = when {
        link.id.isBlank() -> "no id"
        link.id.length > ID_MAX -> "id over $ID_MAX"
        link.name.isBlank() -> "no name"
        link.name.length > NAME_MAX -> "name over $NAME_MAX"
        link.url.isBlank() -> "no url"
        link.url.length > URL_MAX -> "url over $URL_MAX"
        else -> {
            val scheme = SCHEME.find(link.url)?.groupValues?.get(1)?.lowercase()
            when {
                scheme == null -> "url has no scheme"
                scheme == "http" || scheme == "https" -> "url is http(s)"
                link.group != null && link.group.length > GROUP_MAX -> "group over $GROUP_MAX"
                else -> null
            }
        }
    }

    /**
     * The links in [text]. An element over a limit, with a missing field or with an id already
     * seen is dropped, and [onDrop] is told why (the caller's log line); the rest are kept.
     * Text that is not a JSON array gives none.
     */
    fun parse(text: String?, onDrop: (String) -> Unit = {}): List<Link> {
        val a = runCatching { JSONArray(text ?: return emptyList()) }.getOrElse { onDrop("not a JSON array"); return emptyList() }
        val out = mutableListOf<Link>()
        val seen = mutableSetOf<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: run { onDrop("element $i is not an object"); null } ?: continue
            fun str(key: String): String? = (o.opt(key) as? String)?.trim()
            val group = str("group")?.ifEmpty { null }
            val link = Link(str("id").orEmpty(), str("name").orEmpty(), str("url").orEmpty(), group)
            val why = problem(link) ?: if (link.id in seen) "duplicate id" else null
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

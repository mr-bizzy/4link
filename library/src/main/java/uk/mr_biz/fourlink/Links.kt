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
    /** §15f: a key-cap label. */
    const val SHORT_MAX = 16
    /** §14a: the package name a link opens. */
    const val APP_MAX = 100

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** §15f: a link's `icon`, an id as long as a link id may be. */
    fun isIconId(s: String): Boolean = s.isNotBlank() && s.length <= ID_MAX

    /** §14a: a package name, Java grammar (two segments at least), at most [APP_MAX] characters. */
    fun isApp(s: String): Boolean = s.length <= APP_MAX && PACKAGE.matches(s)

    private val MAJOR_MINOR = Regex("\\d+\\.\\d+")

    /** [id] is fixed (made from what the link does, never from a name); [name] is for people. */
    data class Link(
        val id: String,
        val name: String,
        val function: String,
        val version: String,
        val arguments: JSONObject,
        val group: String? = null,
        /** §15f: a key-cap label, at most [SHORT_MAX]. */
        val short: String? = null,
        /** §15f: the one number argument of the call a slider or dial fills, 0 to 100. Without it, a key. */
        val level: String? = null,
        /** §15f: the provider's state key that shows this control. */
        val state: String? = null,
        /**
         * §14a: the package this link opens or brings forward, so a surface can draw that app's own
         * launcher icon. A name, not a picture; the caller never launches it itself. Family only:
         * [forCaller] strips it for anyone else.
         */
        val app: String? = null,
        /**
         * §15f: false when the provider knows a press now would do nothing useful. Null (absent) or
         * true is offered; a hint for drawing, never a gate. Fails open: see [parse].
         */
        val offered: Boolean? = null,
        /**
         * §15f: the id, in this provider's own `icons.get`, of the picture for the link as it
         * stands now (a toggle's Stop picture while it records). Null: the link's own id. Fails
         * open: see [parse].
         */
        val icon: String? = null,
    ) {
        /** §15b/§15f: the id to ask `icons.get` for. */
        val iconId: String get() = icon ?: id

        /** Absent means offered (§15f): a link is greyed only on the provider's own `false`. */
        val isOffered: Boolean get() = offered != false

        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
            .also { o -> group?.let { o.put("group", it) } }
            .put("function", function).put("version", version).put("arguments", JSONObject(arguments.toString()))
            .also { o -> short?.let { o.put("short", it) }; level?.let { o.put("level", it) }; state?.let { o.put("state", it) } }
            .also { o -> app?.let { o.put("app", it) }; offered?.let { o.put("offered", it) }; icon?.let { o.put("icon", it) } }

        // JSONObject has no value equality; compare the arguments as text.
        override fun equals(other: Any?): Boolean = other is Link && id == other.id && name == other.name &&
            function == other.function && version == other.version && group == other.group &&
            short == other.short && level == other.level && state == other.state && app == other.app &&
            offered == other.offered && icon == other.icon &&
            canonical(arguments) == canonical(other.arguments)

        override fun hashCode(): Int = listOf(id, name, function, version, group, short, level, state, app, offered, icon, canonical(arguments)).hashCode()
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
        link.short != null && (link.short.isBlank() || link.short.length > SHORT_MAX) -> "short is empty or over $SHORT_MAX"
        link.level != null && link.level.isBlank() -> "level names no argument"
        link.state != null && !State.isName(link.state) -> "state is not a state key"
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
                short = str("short"), level = str("level"), state = str("state"),
                // §14a: an ill-formed app is dropped, and the link KEPT (unlike every other limit here).
                app = o.opt("app")?.let { raw ->
                    (raw as? String)?.takeIf(::isApp).also { if (it == null) onDrop("element $i: app dropped, link kept") }
                },
                // §15f: only a JSON boolean counts; anything else is dropped and the link kept, offered.
                offered = o.opt("offered")?.let { raw ->
                    (raw as? Boolean).also { if (it == null) onDrop("element $i: offered dropped, link kept") }
                },
                // §15f: an ill-formed icon is dropped and the link kept, with its own id's picture.
                icon = o.opt("icon")?.let { raw ->
                    (raw as? String)?.trim()?.takeIf(::isIconId).also { if (it == null) onDrop("element $i: icon dropped, link kept") }
                },
            )
            // A field of the wrong type is a malformed element, not a missing one.
            if (listOf("short", "level", "state").any { o.has(it) && o.opt(it) !is String }) {
                onDrop("element $i dropped: short, level and state must be text"); continue
            }
            val why = problem(link) ?: when {
                link.id in seen -> "duplicate id"
                catalogue != null && catalogue.find(link.function) == null -> "function not in the provider's catalogue"
                // §15f: with the catalogue, the level must be a number argument of that function.
                catalogue != null && link.level != null &&
                    catalogue.find(link.function)!!.input.properties[link.level] !is Schema.Num -> "level is not a number argument of ${link.function}"
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
            // As on reading: an ill-formed app goes, the link stays.
            var kept = if (l.app != null && !isApp(l.app)) l.copy(app = null).also { onDrop("link ${l.id.take(40)}: app left out") } else l
            if (kept.icon != null && !isIconId(kept.icon!!)) kept = kept.copy(icon = null).also { onDrop("link ${l.id.take(40)}: icon left out") }
            a.put(kept.toJson())
        }
        return a.toString()
    }

    /**
     * §14a, the provider's list for one caller: `app` goes to FAMILY only. For anyone else it is
     * stripped from every link, so another developer's app never learns which apps the user put on a
     * Links page.
     */
    fun forCaller(links: List<Link>, standing: Standing): List<Link> =
        if (standing == Standing.FAMILY) links else links.map { if (it.app == null) it else it.copy(app = null) }

    /** [forCaller], written as `links.list` returns it. */
    fun toTextFor(links: List<Link>, standing: Standing, onDrop: (String) -> Unit = {}): String =
        toText(forCaller(links, standing), onDrop)
}

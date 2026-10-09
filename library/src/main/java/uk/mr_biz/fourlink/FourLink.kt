// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

/**
 * The 4Link wire vocabulary: docs/4LINK-SPEC.md §4, §6, §11. Frozen once
 * shipped; adding a key is fine, changing one is a new major version.
 */
object FourLink {
    /** The protocol version a catalogue carries, and the major a caller must know. */
    const val VERSION = "1.0"
    const val MAJOR = 1

    /** Every member's provider authority is its package name plus this. */
    const val AUTHORITY_SUFFIX = ".4link"
    fun authorityOf(packageName: String): String = packageName + AUTHORITY_SUFFIX

    const val METHOD_HELLO = "hello"
    const val METHOD_CATALOGUE = "catalogue"
    const val METHOD_INVOKE = "invoke"

    /** Bundle keys. */
    const val KEY_APP = "app"
    const val KEY_VERSION = "4link"
    const val KEY_CALLER = "caller"
    const val KEY_JSON = "json"
    /** §4a — an invoke reply's one frame, a read-only `SharedMemory`, described by its [KEY_JSON]. */
    const val KEY_FRAME = "frame"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_MESSAGE = "message"
    /** Optional, on a `bad_arguments` answer only (§11a): a JSON [Suggestion]. */
    const val KEY_SUGGESTION = "suggestion"
    /** On an invoke: the function's major version the caller read from the catalogue. */
    const val KEY_FUNCTION_VERSION = "version"

    /** The pairing request (§6): an explicit intent to the provider app. */
    const val ACTION_PAIR = "uk.mr_biz.4link.action.PAIR"
    /** String array extra: function ids, or the effect names "read" / "create" / "change" / "delete". */
    const val EXTRA_FUNCTIONS = "uk.mr_biz.4link.extra.FUNCTIONS"

    /**
     * 4Dictate's release digest (§5a), the one 4Zones pins for its B75 door.
     * The family is a LIST of per-app release keys: the `fourlink_family_digests`
     * resource (the release certificates only in src/main; plus the debug
     * certificate in src/debug). These constants exist for tests and for
     * naming a key in code.
     */
    const val FAMILY_RELEASE_DIGEST = "7ffc5b0df6b4ffb420d8965db8e041fa4398b534608142d110885b9e67cfd8d9"

    /** 4Tasks' own release digest (owner's decision 2026-10-03: one key per app). */
    const val FOURTASKS_RELEASE_DIGEST = "92c51b99e38eb2f498375cd912cea900203ce6a19069d33ce8384203a1c2ad06"

    /** 4Home's own release digest (owner, 2026-10-06: Home Assistant by voice, its own key). */
    const val FOURHOME_RELEASE_DIGEST = "d511f22254f19fbb0a1c7d71576a0b8242e2796ee6f9c05adb5ad549c6198a8e"

    const val RATE_LIMIT_PER_MINUTE = 30
    const val AUDIT_LIMIT = 500

    const val TITLE_MAX = 60
    const val DESCRIPTION_MAX = 300
    /** A property's own description inside a schema, if it has one. */
    const val PROPERTY_DESCRIPTION_MAX = 120
}

/** What the provider knows about who is calling (§4 `hello`). */
enum class Standing(val wire: String) {
    FAMILY("family"), PAIRED("paired"), UNKNOWN("unknown");

    companion object {
        fun fromWire(s: String?): Standing = entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

/** Whether a function reads, changes or deletes anything (§3). */
enum class Effect(val wire: String) {
    READ("read"), CREATE("create"), CHANGE("change"), DELETE("delete");

    /**
     * Needs the user's confirmation before a model-chosen call (P3). The library default is "yes" for everything but READ,
     * CREATE included; a calling app may let the user switch confirmation off for CREATE and/or CHANGE (never DELETE) and then
     * must tell the user what was done.
     */
    val needsConfirmation: Boolean get() = this != READ

    companion object {
        fun fromWire(s: String?): Effect? = entries.firstOrNull { it.wire == s }

        /**
         * Forward-compatible reading of a catalogue's effect: a word this version does not know is CHANGE, so a caller built before an
         * effect existed still asks before running it. A missing or empty value is not a function (null).
         */
        fun fromWireOrChange(s: String?): Effect? = if (s.isNullOrBlank()) null else fromWire(s) ?: CHANGE
    }
}

/** The error codes of §11, as they travel. */
enum class ErrorCode(val wire: String) {
    UNKNOWN_FUNCTION("unknown_function"),
    BAD_ARGUMENTS("bad_arguments"),
    NOT_PAIRED("not_paired"),
    NOT_GRANTED("not_granted"),
    RATE_LIMITED("rate_limited"),
    REFUSED("refused"),
    FAILED("failed");

    companion object {
        fun fromWire(s: String?): ErrorCode? = entries.firstOrNull { it.wire == s }
    }
}

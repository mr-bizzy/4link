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
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_MESSAGE = "message"
    /** On an invoke: the function's major version the caller read from the catalogue. */
    const val KEY_FUNCTION_VERSION = "version"

    /** The pairing request (§6): an explicit intent to the provider app. */
    const val ACTION_PAIR = "uk.mr_biz.4link.action.PAIR"
    /** String array extra: function ids, or the effect names "read" / "change" / "delete". */
    const val EXTRA_FUNCTIONS = "uk.mr_biz.4link.extra.FUNCTIONS"

    /**
     * The release family digest (§5a): 4Dictate's release certificate, the
     * one 4Zones pins for its B75 door. The LIST a build trusts is the
     * `fourlink_family_digests` resource (release only in src/main; plus the
     * debug certificate in src/debug); this constant exists for tests and
     * for naming it in code.
     */
    const val FAMILY_RELEASE_DIGEST = "7ffc5b0df6b4ffb420d8965db8e041fa4398b534608142d110885b9e67cfd8d9"

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
    READ("read"), CHANGE("change"), DELETE("delete");

    /** Needs the user's confirmation before a model-chosen call (P3). */
    val needsConfirmation: Boolean get() = this != READ

    companion object {
        fun fromWire(s: String?): Effect? = entries.firstOrNull { it.wire == s }
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

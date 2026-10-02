// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject

/**
 * One line per call (§9): when, who, what, and how it ended. There is no
 * field for arguments or results, so none can ever be written.
 */
data class AuditEntry(
    val timeMs: Long,
    val callerPackage: String,
    /** The method ("hello", "catalogue") or the function id. */
    val what: String,
    /** "ok", an [ErrorCode] wire value, or "pairing_void" / "unidentified". */
    val result: String,
) {
    fun toJson(): String = JSONObject().put("t", timeMs).put("who", callerPackage).put("what", what).put("result", result).toString()

    companion object {
        fun fromJson(line: String): AuditEntry? = runCatching {
            val o = JSONObject(line)
            AuditEntry(o.getLong("t"), o.getString("who"), o.getString("what"), o.getString("result"))
        }.getOrNull()
    }
}

/** The last [limit] calls, newest last, kept as JSON lines. */
class AuditLog(private val storage: TextStorage, private val limit: Int = FourLink.AUDIT_LIMIT) {

    private val entries = ArrayDeque<AuditEntry>()

    init {
        storage.read()?.lineSequence()?.mapNotNull(AuditEntry::fromJson)?.forEach { entries.addLast(it) }
        while (entries.size > limit) entries.removeFirst()
    }

    @Synchronized fun record(entry: AuditEntry) {
        entries.addLast(entry)
        while (entries.size > limit) entries.removeFirst()
        storage.write(entries.joinToString("\n") { it.toJson() })
    }

    @Synchronized fun all(): List<AuditEntry> = entries.toList()

    @Synchronized fun clear() {
        entries.clear()
        storage.write("")
    }
}

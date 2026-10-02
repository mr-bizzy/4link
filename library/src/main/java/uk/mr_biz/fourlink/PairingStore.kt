package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject

/** Where a store keeps its text: a file on Android, a string in tests. */
interface TextStorage {
    fun read(): String?
    fun write(text: String)
}

class MemoryStorage(private var text: String? = null) : TextStorage {
    override fun read(): String? = text
    override fun write(text: String) { this.text = text }
}

/**
 * One approved relationship (§6 on the provider side, §7 on the caller
 * side — the same record, read in opposite directions): the app, the
 * certificate it had when approved, what it may do, and when it last did.
 */
data class Pairing(
    val packageName: String,
    val certDigest: String,
    val granted: Set<String>,
    val grantedAtMs: Long,
    val fourLinkVersion: String = FourLink.VERSION,
    /** Function id to the last time it was called through this pairing. */
    val lastUsed: Map<String, Long> = emptyMap(),
) {
    fun allows(functionId: String): Boolean = functionId in granted

    fun toJson(): JSONObject = JSONObject().apply {
        put("package", packageName)
        put("cert", certDigest)
        put("granted", JSONArray(granted.toList()))
        put("grantedAtMs", grantedAtMs)
        put("4link", fourLinkVersion)
        put("lastUsed", JSONObject().also { o -> lastUsed.forEach { (k, v) -> o.put(k, v) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Pairing? = runCatching {
            val granted = o.optJSONArray("granted")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val used = o.optJSONObject("lastUsed")?.let { u -> u.keys().asSequence().associateWith { u.getLong(it) } }.orEmpty()
            Pairing(
                packageName = o.getString("package"),
                certDigest = o.getString("cert").lowercase(),
                granted = granted.toSet(),
                grantedAtMs = o.optLong("grantedAtMs"),
                fourLinkVersion = o.optString("4link", FourLink.VERSION),
                lastUsed = used,
            )
        }.getOrNull()
    }
}

/**
 * The pairings an app holds (§6), or the providers a caller may use (§7):
 * one file, one record per package. Every write goes to storage at once, so
 * a revocation is in force before the Remove button is released.
 */
class PairingStore(private val storage: TextStorage) {

    private val byPackage = linkedMapOf<String, Pairing>()

    init {
        runCatching { JSONArray(storage.read() ?: "[]") }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) {
                a.optJSONObject(i)?.let(Pairing::fromJson)?.let { byPackage[it.packageName] = it }
            }
        }
    }

    @Synchronized fun all(): List<Pairing> = byPackage.values.toList()

    @Synchronized fun get(packageName: String): Pairing? = byPackage[packageName]

    @Synchronized fun put(pairing: Pairing) {
        byPackage[pairing.packageName] = pairing.copy(certDigest = pairing.certDigest.lowercase())
        save()
    }

    @Synchronized fun remove(packageName: String): Boolean {
        val removed = byPackage.remove(packageName) != null
        if (removed) save()
        return removed
    }

    /** Records a use (§6 "when it last did it"). */
    @Synchronized fun touch(packageName: String, functionId: String, nowMs: Long) {
        val p = byPackage[packageName] ?: return
        byPackage[packageName] = p.copy(lastUsed = p.lastUsed + (functionId to nowMs))
        save()
    }

    /**
     * The pairing for a caller, only if the caller is still the app that was
     * approved (§6: a changed certificate voids the pairing, which is removed
     * here so the list never shows a grant that no longer holds).
     */
    @Synchronized fun validFor(caller: Caller): Pairing? {
        val p = byPackage[caller.packageName] ?: return null
        if (p.certDigest != caller.certDigest.lowercase()) {
            byPackage.remove(caller.packageName)
            save()
            return null
        }
        return p
    }

    private fun save() {
        storage.write(JSONArray().also { a -> byPackage.values.forEach { a.put(it.toJson()) } }.toString())
    }
}

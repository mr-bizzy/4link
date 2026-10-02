// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject

/** The platform as the tests need it: which uid is which package, signed how. */
class FakeSigners : SignerLookup {
    private val uidToPackages = hashMapOf<Int, MutableList<String>>()
    private val signers = hashMapOf<String, List<String>>()

    fun install(uid: Int, packageName: String, vararg digests: String): FakeSigners {
        uidToPackages.getOrPut(uid) { mutableListOf() }.add(packageName)
        signers[packageName] = digests.toList()
        return this
    }

    fun uninstall(packageName: String) {
        signers.remove(packageName)
        uidToPackages.values.forEach { it.remove(packageName) }
    }

    override fun packagesOf(uid: Int): List<String> = uidToPackages[uid].orEmpty()
    override fun signerDigests(packageName: String): List<String>? = signers[packageName]
}

val OUR_DIGEST = "aa".repeat(32)
val OTHER_DIGEST = "bb".repeat(32)
val SPOOF_DIGEST = "cc".repeat(32)

val ECHO = Catalogue(
    app = "4Link Echo",
    functions = listOf(
        FunctionSpec(
            "echo.say", "1.0", "Say it back", "Returns the text it was given.", Effect.READ,
            Schema.Obj(mapOf("text" to Schema.Str(maxLength = 500)), setOf("text")),
            Schema.Obj(mapOf("said" to Schema.Str())),
        ),
        FunctionSpec(
            "echo.note", "1.0", "Save a note", "Keeps a short note the user dictated.", Effect.CHANGE,
            Schema.Obj(mapOf("text" to Schema.Str(maxLength = 500), "pinned" to Schema.Bool()), setOf("text")),
            Schema.Obj(mapOf("count" to Schema.Num())),
        ),
        FunctionSpec(
            "echo.wipe", "2.0", "Delete every note", "Deletes all notes.", Effect.DELETE,
        ),
    ),
)

fun family() = Family(listOf(OUR_DIGEST))

fun args(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

class FixedClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke(): Long = now
}

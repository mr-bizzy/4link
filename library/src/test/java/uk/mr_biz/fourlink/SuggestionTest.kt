// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §11a: "did you mean" after a bad_arguments answer. */
class SuggestionTest {
    private val sample = Suggestion(
        "Did you mean “Get back to Sandra Elaine about her reservation”?",
        "tasks.complete",
        JSONObject().put("id", 4),
    )

    @Test fun `a suggestion survives its own JSON`() {
        val back = Suggestion.parse(sample.toJson())!!
        assertEquals(sample.question, back.question)
        assertEquals("tasks.complete", back.function)
        assertEquals(4, back.arguments.getInt("id"))
    }

    @Test fun `the question is cut to 200 characters on both sides`() {
        val long = Suggestion("q".repeat(500), "tasks.complete", JSONObject())
        assertEquals(200, JSONObject(long.toJson()).getString("question").length)
        val read = Suggestion.parse(JSONObject().put("question", "q".repeat(500)).put("function", "a.b").put("arguments", JSONObject()).toString())!!
        assertEquals(200, read.question.length)
    }

    @Test fun `anything malformed is no suggestion`() {
        assertNull(Suggestion.parse(null))
        assertNull(Suggestion.parse(""))
        assertNull(Suggestion.parse("not json"))
        assertNull(Suggestion.parse("""{"function":"a.b","arguments":{}}"""))                       // no question
        assertNull(Suggestion.parse("""{"question":"  ","function":"a.b","arguments":{}}"""))       // blank question
        assertNull(Suggestion.parse("""{"question":"q","arguments":{}}"""))                          // no function
        assertNull(Suggestion.parse("""{"question":"q","function":"Tasks.Complete","arguments":{}}""")) // not an id
        assertNull(Suggestion.parse("""{"question":"q","function":"nodots","arguments":{}}"""))
        assertNull(Suggestion.parse("""{"question":"q","function":"a.b; rm -rf","arguments":{}}"""))
        assertNull(Suggestion.parse("""{"question":"q","function":"a.b"}"""))                       // no arguments
        assertNull(Suggestion.parse("""{"question":"q","function":"a.b","arguments":"x"}"""))       // arguments not an object
        assertNull(Suggestion.parse("""{"question":5,"function":"a.b","arguments":{}}"""))          // question not text
    }

    @Test fun `the caller reads a suggestion only on bad_arguments`() {
        val values = mapOf("ok" to false, "error" to "bad_arguments", "message" to "No open task contains “sounder”.", "suggestion" to sample.toJson())
        val r = ClientCore.invoke(values) as InvokeResult.Error
        assertEquals(ErrorCode.BAD_ARGUMENTS, r.code)
        assertEquals("No open task contains “sounder”.", r.message)
        assertEquals("tasks.complete", r.suggestion!!.function)

        for (other in listOf("refused", "failed", "not_granted")) {
            val o = ClientCore.invoke(values + ("error" to other)) as InvokeResult.Error
            assertNull("$other must not carry a suggestion", o.suggestion)
        }
    }

    @Test fun `a malformed suggestion leaves the plain message, and a missing one is null`() {
        val bad = ClientCore.invoke(mapOf("ok" to false, "error" to "bad_arguments", "message" to "m", "suggestion" to "{oops")) as InvokeResult.Error
        assertEquals("m", bad.message); assertNull(bad.suggestion)
        val none = ClientCore.invoke(mapOf("ok" to false, "error" to "bad_arguments", "message" to "m")) as InvokeResult.Error
        assertNull(none.suggestion)
    }

    @Test fun `the provider passes a suggestion through, audited as bad_arguments`() {
        val signers = FakeSigners().install(10001, "uk.mr_biz.fourdictate", OUR_DIGEST)
        val audit = AuditLog(MemoryStorage())
        val clock = FixedClock()
        val gate = ProviderGate(family(), PairingStore(MemoryStorage()), signers, RateLimiter(now = clock), audit, clock)
        val core = ProviderCore("4Link Echo", { ECHO }, gate) { _, _, _ -> Outcome.BadArguments("No note says that.", sample) }
        val us = CallerIdentity.identify(10001, signers)
        val r = core.invoke(us, "echo.note", """{"text":"x"}""", "1") as Reply.Error
        assertEquals(ErrorCode.BAD_ARGUMENTS, r.code)
        assertEquals("No note says that.", r.message)
        assertEquals(sample, r.suggestion)
        assertEquals(listOf("echo.note" to "bad_arguments"), audit.all().map { it.what to it.result })
        assertTrue(audit.all().none { it.toJson().contains("Sandra") })  // the suggestion's content is never logged
    }
}

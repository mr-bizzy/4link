// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** §4c `origin`: a family caller's declaration passes through; anything else is a model's choice. */
class OriginTest {

    private val signers = FakeSigners()
        .install(10001, "uk.mr_biz.fourdictate", OUR_DIGEST)
        .install(10002, "com.example.stranger", OTHER_DIGEST)
    private val pairings = PairingStore(MemoryStorage()).apply {
        put(Pairing("com.example.stranger", OTHER_DIGEST, setOf("echo.say", "echo.note"), 0))
    }
    private val clock = FixedClock()
    private val gate = ProviderGate(family(), pairings, signers, RateLimiter(now = clock), AuditLog(MemoryStorage()), clock)
    private val seen = mutableListOf<Origin>()
    /** echo.note refuses a model; echo.say only records what it saw. */
    private val core = ProviderCore("4Link Echo", { ECHO }, gate) { f, _, c ->
        seen += c.origin
        if (f.id == "echo.note") Origin.refuseUnlessUser(c) ?: Outcome.Ok("""{"count":1}""") else Outcome.Ok("""{"said":"x"}""")
    }
    private val us = CallerIdentity.identify(10001, signers)
    private val stranger = CallerIdentity.identify(10002, signers)
    private val say = """{"text":"x"}"""

    @Test fun `the wire names`() {
        assertEquals("origin", FourLink.EXTRA_ORIGIN)
        assertEquals("user", FourLink.ORIGIN_USER)
        assertEquals("model", FourLink.ORIGIN_MODEL)
        assertEquals("not_for_model", FourLink.REASON_NOT_FOR_MODEL)
        assertEquals(listOf("user", "model"), Origin.entries.map { it.wire })
    }

    @Test fun `a family caller's user and model pass through to the function`() {
        core.invoke(us, "echo.say", say, "1", "user")
        core.invoke(us, "echo.say", say, "1", "model")
        assertEquals(listOf(Origin.USER, Origin.MODEL), seen)
    }

    @Test fun `missing or unknown is a model`() {
        core.invoke(us, "echo.say", say, "1", null)
        core.invoke(us, "echo.say", say, "1", "USER")
        core.invoke(us, "echo.say", say, "1", "person")
        core.invoke(us, "echo.say", say, "1", "")
        assertEquals(List(4) { Origin.MODEL }, seen)
    }

    @Test fun `a paired caller claiming user is a model`() {
        core.invoke(stranger, "echo.say", say, "1", "user")
        assertEquals(listOf(Origin.MODEL), seen)
        val r = core.invoke(stranger, "echo.note", say, "1", "user") as Reply.Error
        assertEquals(FourLink.REASON_NOT_FOR_MODEL, r.reason)
    }

    @Test fun `the helper refuses a model with refused, not_for_model, not fixable, and lets the user through`() {
        val refused = core.invoke(us, "echo.note", say, "1", "model") as Reply.Error
        assertEquals(ErrorCode.REFUSED, refused.code)
        assertEquals(FourLink.REASON_NOT_FOR_MODEL, refused.reason)
        assertEquals(false, refused.fixable)
        assertEquals(Origin.NOT_FOR_MODEL_SENTENCE, refused.message)
        assertTrue(core.invoke(us, "echo.note", say, "1", "user") is Reply.Json)
        val own = Origin.refuseUnlessUser(Caller(1, "p", OUR_DIGEST), "Back is a key, not something to ask for.")!!
        assertEquals("Back is a key, not something to ask for.", own.message)
        assertNull(Origin.refuseUnlessUser(Caller(1, "p", OUR_DIGEST, Origin.USER)))
    }

    @Test fun `the reason travels, a token a provider may declare and the caller reads`() {
        assertTrue(FourLink.mayDeclareReason(FourLink.REASON_NOT_FOR_MODEL))
        val values = mapOf<String, Any?>(FourLink.KEY_OK to false, FourLink.KEY_ERROR to "refused",
            FourLink.KEY_MESSAGE to "no", FourLink.KEY_REASON to "not_for_model", FourLink.KEY_FIXABLE to false)
        val e = ClientCore.invoke(values) as InvokeResult.Error
        assertEquals("not_for_model", e.reason)
        assertEquals(false, e.fixable)
    }

    @Test fun `a caller is a model outside an invoke`() {
        assertEquals(Origin.MODEL, CallerIdentity.identify(10001, signers)!!.origin)
        assertEquals(Origin.MODEL, Origin.read("user", Standing.PAIRED))
        assertEquals(Origin.MODEL, Origin.read("user", Standing.UNKNOWN))
        assertEquals(Origin.USER, Origin.read("user", Standing.FAMILY))
    }

    @Test fun `the client sends origin on every invoke and takes it with no default`() {
        val client = File("src/main/java/uk/mr_biz/fourlink/android/FourLinkClient.kt").readText()
        assertTrue(client.contains("putString(FourLink.EXTRA_ORIGIN, origin.wire)"))
        assertEquals("both public forms and the private one", 3, Regex("fun invoke\\([^)]*origin: Origin\\)").findAll(client).count())
        assertTrue("no default", !client.contains("origin: Origin ="))
        val provider = File("src/main/java/uk/mr_biz/fourlink/android/FourLinkProvider.kt").readText()
        assertTrue(provider.contains("extras?.getString(FourLink.EXTRA_ORIGIN)"))
    }
}

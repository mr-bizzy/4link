// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §11b — a refusal may carry a stable token and `fixable` beside its sentence, so no caller matches the sentence. */
class ReasonTest {

    private val signers = FakeSigners().install(10001, "uk.mr_biz.fourscreenshots", OUR_DIGEST)
    private val audit = AuditLog(MemoryStorage())
    private val gate = ProviderGate(family(), PairingStore(MemoryStorage()), signers, RateLimiter(now = FixedClock()), audit, FixedClock())
    private val us = CallerIdentity.identify(10001, signers)

    private fun answer(outcome: Outcome) = ProviderCore("4Zones", { ECHO }, gate) { _, _, _ -> outcome }
        .invoke(us, "echo.say", """{"text":"x"}""", "1") as Reply.Error

    @Test fun `a refusal carries its prefixed token and fixable beside its sentence`() {
        val r = answer(Outcome.Refused("Screen capture is off.", "4zones.grant_off", fixable = true))
        assertEquals(ErrorCode.REFUSED, r.code)
        assertEquals("Screen capture is off.", r.message)
        assertEquals("4zones.grant_off", r.reason)
        assertEquals(true, r.fixable)
    }

    @Test fun `a core token goes unprefixed, a failure may carry one too, and none is none`() {
        assertEquals("unavailable", answer(Outcome.Failed("Too slow.", "unavailable", fixable = false)).reason)
        val bare = answer(Outcome.Refused("No."))
        assertNull(bare.reason)
        assertNull(bare.fixable)
    }

    @Test fun `a provider may not invent an unprefixed token, or send a malformed one, and the sentence still goes`() {
        for (bad in listOf("grant_off", "Grant Off", "grant-off", "4zones.", ".grant_off", "", "4zones.x.y", "off; rm")) {
            val r = answer(Outcome.Refused("Off.", bad, fixable = true))
            assertNull(bad, r.reason)
            assertEquals("Off.", r.message)
            assertEquals("fixable still goes", true, r.fixable)
        }
    }

    @Test fun `the client reads token and fixable on refused and failed, any well-formed token, and nothing elsewhere`() {
        val refused = ClientCore.invoke(mapOf("ok" to false, "error" to "refused", "message" to "Off.", "reason" to "4zones.grant_off", "fixable" to true)) as InvokeResult.Error
        assertEquals("4zones.grant_off", refused.reason)
        assertEquals(true, refused.fixable)
        val later = ClientCore.invoke(mapOf("ok" to false, "error" to "failed", "message" to "x", "reason" to "some_core_token_added_later")) as InvokeResult.Error
        assertEquals("a core token this reader does not know still reads", "some_core_token_added_later", later.reason)
        val other = ClientCore.invoke(mapOf("ok" to false, "error" to "not_paired", "message" to "No.", "reason" to "not_found", "fixable" to true)) as InvokeResult.Error
        assertNull("only refused and failed carry a reason", other.reason)
        assertNull(other.fixable)
        val old = ClientCore.invoke(mapOf("ok" to false, "error" to "refused", "message" to "Off.")) as InvokeResult.Error
        assertNull("an old provider sends neither, and that is not an error", old.reason)
        assertNull(old.fixable)
    }

    @Test fun `the catalogue may list a function's reasons, and a malformed one is dropped on reading`() {
        val f = FunctionSpec("screen.capture", "1.0", "Capture", "One picture.", Effect.READ, reasons = listOf("4zones.grant_off", "not_found"))
        val read = Catalogue.parse(Catalogue("4Zones", listOf(f)).toJson())!!.catalogue.find("screen.capture")!!
        assertEquals(listOf("4zones.grant_off", "not_found"), read.reasons)
        assertTrue("absent by default", Catalogue.parse(ECHO.toJson())!!.catalogue.functions.all { it.reasons.isEmpty() })
        val junk = org.json.JSONObject(f.toJson().toString()).put("reasons", org.json.JSONArray(listOf("Bad Token", "not_found")))
        assertEquals(listOf("not_found"), FunctionSpec.parse(junk).reasons)
    }

    @Test fun `the audit log records the code, not the token`() {
        answer(Outcome.Refused("Off.", "4zones.grant_off", fixable = true))
        assertEquals(listOf("echo.say" to "refused"), audit.all().map { it.what to it.result })
    }

    @Test fun `the core vocabulary is the agreed eight`() {
        assertEquals(
            setOf("not_set_up", "permission_off", "not_found", "busy", "unavailable", "rate_limited", "too_large", "user_declined"),
            FourLink.CORE_REASONS,
        )
        FourLink.CORE_REASONS.forEach { assertTrue(it, FourLink.mayDeclareReason(it)) }
    }
}

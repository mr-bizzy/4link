// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3c and §4a — a reply may carry one frame, only from a function that declares it. */
class FrameTest {

    private val capture = FunctionSpec(
        "screen.capture", "1.0", "Capture a screen", "One picture of one screen.", Effect.READ,
        Schema.Obj(mapOf("display" to Schema.Num()), setOf("display")),
        Schema.Obj(mapOf("width" to Schema.Num(), "height" to Schema.Num())),
        frame = true,
    )
    private val catalogue = Catalogue(app = "4Zones", functions = ECHO.functions + capture)

    /** Stands in for a SharedMemory: the core never looks inside. */
    private val picture = Any()

    private val signers = FakeSigners().install(10001, "uk.mr_biz.fourscreenshots", OUR_DIGEST)
    private val gate = ProviderGate(family(), PairingStore(MemoryStorage()), signers, RateLimiter(now = FixedClock()), AuditLog(MemoryStorage()), FixedClock())
    private val us = CallerIdentity.identify(10001, signers)

    private fun core(answer: (FunctionSpec) -> Outcome) = ProviderCore("4Zones", { catalogue }, gate) { f, _, _ -> answer(f) }

    @Test fun `the mark survives a round trip and is absent by default`() {
        val read = Catalogue.parse(catalogue.toJson())!!.catalogue
        assertTrue(read.find("screen.capture")!!.frame)
        assertFalse(read.find("echo.say")!!.frame)
        assertFalse(JSONObject(ECHO.functions.first().toJson().toString()).has("frame"))
    }

    @Test fun `a declared function's frame reaches the reply beside its json`() {
        val r = core { Outcome.Ok("""{"width":4,"height":2}""", picture) }.invoke(us, "screen.capture", """{"display":0}""", "1") as Reply.Json
        assertEquals("""{"width":4,"height":2}""", r.json)
        assertSame(picture, r.frame)
    }

    @Test fun `a frame from a function that does not declare one is a failure, not a reply`() {
        val r = core { Outcome.Ok("""{"said":"x"}""", picture) }.invoke(us, "echo.say", """{"text":"x"}""", "1")
        assertEquals(ErrorCode.FAILED, (r as Reply.Error).code)
    }

    @Test fun `a declared function that answers without its frame is a failure`() {
        val r = core { Outcome.Ok("""{"width":4,"height":2}""") }.invoke(us, "screen.capture", """{"display":0}""", "1")
        assertEquals(ErrorCode.FAILED, (r as Reply.Error).code)
    }

    @Test fun `the client reads the frame by its key, and its absence as none`() {
        val ok = ClientCore.invoke(mapOf("ok" to true, "json" to "{}", "frame" to picture)) as InvokeResult.Ok
        assertSame(picture, ok.frame)
        assertNull((ClientCore.invoke(mapOf("ok" to true, "json" to "{}")) as InvokeResult.Ok).frame)
    }

    @Test fun `the audit log records a frame call as any other, and never the frame`() {
        val audit = AuditLog(MemoryStorage())
        val logged = ProviderGate(family(), PairingStore(MemoryStorage()), signers, RateLimiter(now = FixedClock()), audit, FixedClock())
        ProviderCore("4Zones", { catalogue }, logged) { _, _, _ -> Outcome.Ok("{}", picture) }
            .invoke(us, "screen.capture", """{"display":0}""", "1")
        assertEquals(listOf("screen.capture" to "ok"), audit.all().map { it.what to it.result })
    }
}

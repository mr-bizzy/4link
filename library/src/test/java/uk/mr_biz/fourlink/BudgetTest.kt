// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State v2 §8 (the press reserve) and §9 (refusals counted, readable by the caller about itself).
 * Measured on the owner's S25, 21 Nov 2026: background traffic used up 4Zones' 120 and five of his
 * own desk presses were refused.
 */
class BudgetTest {

    private val signers = FakeSigners()
        .install(10001, "uk.mr_biz.fourzones", OUR_DIGEST)
        .install(10004, "uk.mr_biz.fourkeys", OUR_DIGEST)
        .install(10002, "com.example.stranger", OTHER_DIGEST)
    private val clock = FixedClock()
    private val pairings = PairingStore(MemoryStorage())
    private val gate = ProviderGate(family(), pairings, signers, RateLimiter(now = clock), AuditLog(MemoryStorage()), clock)
    private fun caller(uid: Int) = CallerIdentity.identify(uid, signers)!!

    private fun background(c: Caller, n: Int) = (1..n).count { gate.gateInvoke(c, "echo.say") == Decision.Allowed }

    @Test fun `background stops at 100, the last 20 are for the user's own calls`() {
        val z = caller(10001)
        assertEquals(100, background(z, 150))
        assertEquals(ErrorCode.RATE_LIMITED, (gate.gateCatalogue(z) as Decision.Refused).code)
        val presses = (1..30).count { gate.gateInvoke(z, "home.cover.act", Origin.USER) == Decision.Allowed }
        assertEquals(FourLink.RESERVED_FOR_USER_PER_MINUTE, presses)
    }

    @Test fun `the user's calls may use all 120, and then background has none`() {
        val z = caller(10001)
        assertEquals(120, (1..130).count { gate.gateInvoke(z, "echo.say", Origin.USER) == Decision.Allowed })
        assertEquals(0, background(z, 5))
    }

    @Test fun `a caller that marks everything user starves only itself`() {
        val liar = caller(10001)
        repeat(200) { gate.gateInvoke(liar, "echo.say", Origin.USER) }
        val keys = caller(10004)
        assertEquals(100, background(keys, 150))
        assertEquals(20, (1..30).count { gate.gateInvoke(keys, "echo.say", Origin.USER) == Decision.Allowed })
    }

    @Test fun `state get keeps its own limit and never touches the press budget`() {
        val z = caller(10001)
        repeat(300) { gate.gateInvoke(z, State.GET) }
        assertEquals(100, background(z, 150))
    }

    @Test fun `a paired caller keeps 30 with no split, since it can never be user`() {
        val s = caller(10002)
        pairings.put(Pairing(s.packageName, s.certDigest, setOf("echo.say"), clock.now))
        assertEquals(30, (1..40).count { gate.gateInvoke(s, "echo.say", Origin.USER) == Decision.Allowed })
    }

    @Test fun `the window slides, so the reserve comes back`() {
        val z = caller(10001)
        background(z, 100)
        clock.now += 60_000
        assertEquals(100, background(z, 150))
    }

    @Test fun `refusals are counted per caller and reason, for the minute and since start`() {
        val z = caller(10001)
        repeat(103) { (gate.gateInvoke(z, "echo.say") as? Decision.Refused)?.let { r -> gate.record(z.packageName, "echo.say", r.code.wire) } }
        gate.record(z.packageName, "echo.say", ErrorCode.BAD_ARGUMENTS.wire)
        gate.gateCatalogue(caller(10002))
        val counts = gate.refusals(z.packageName)
        assertEquals(RefusalCount(minute = 3, total = 3), counts["rate_limited"])
        assertEquals(RefusalCount(minute = 1, total = 1), counts["bad_arguments"])
        assertEquals(setOf("rate_limited", "bad_arguments"), counts.keys)
        clock.now += 60_000
        assertEquals(RefusalCount(minute = 0, total = 3), gate.refusals(z.packageName)["rate_limited"])
        assertEquals(RefusalCount(minute = 0, total = 1), gate.refusals("com.example.stranger")["not_paired"])
    }

    @Test fun `a family caller reads its own refusals and nobody else's`() {
        val catalogue = Catalogue("Echo", listOf(FunctionSpec("echo.say", "1.0", "Say", "Says it.", Effect.READ)))
        val core = ProviderCore("Echo", { catalogue }, gate) { _, _, _ -> Outcome.Ok() }
        val z = caller(10001)
        val shown = (core.catalogue(z) as Reply.Json).json
        assertTrue("never listed: a surface offering catalogue functions as actions must not offer it", !shown.contains(FourLink.REFUSALS))
        repeat(100) { core.invoke(z, "echo.say", "{}", "1") } // the catalogue above took the 1st place
        val r = core.invoke(z, FourLink.REFUSALS, "{}", "1", FourLink.ORIGIN_USER).let { it as? Reply.Json ?: error("got $it") }
        val o = JSONObject(JSONObject(r.json).getString("refusals"))
        assertEquals(1, o.getJSONObject("rate_limited").getInt("minute"))
        assertEquals(setOf("rate_limited"), o.keys().asSequence().toSet())
        val other = core.invoke(caller(10004), FourLink.REFUSALS, "{}", "1") as Reply.Json
        assertEquals(0, JSONObject(JSONObject(other.json).getString("refusals")).length())
    }

    @Test fun `a refused user press is counted as refused and not retried by the gate`() {
        val z = caller(10001)
        repeat(120) { gate.gateInvoke(z, "echo.say", Origin.USER) }
        assertEquals(ErrorCode.RATE_LIMITED, (gate.gateInvoke(z, "home.cover.act", Origin.USER) as Decision.Refused).code)
    }
}

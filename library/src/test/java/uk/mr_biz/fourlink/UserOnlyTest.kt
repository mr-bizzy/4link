// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** §3d: a function marked "origin": "user" is never offered to a model and is refused by the provider unless the user triggered it. */
class UserOnlyTest {

    private val press = FunctionSpec(
        "keys.press", "1.0", "Press keys", "Presses a key combination.", Effect.CHANGE,
        Schema.Obj(mapOf("keys" to Schema.Str(maxLength = 40)), setOf("keys")), userOnly = true,
    )
    private val catalogue = ECHO.copy(functions = ECHO.functions + press)

    @Test fun `the flag round-trips as origin user, and is absent otherwise`() {
        val o = press.toJson()
        assertEquals("user", o.getString("origin"))
        assertTrue(FunctionSpec.parse(o).userOnly)
        assertEquals(press, FunctionSpec.parse(o))
        assertFalse(ECHO.functions[0].toJson().has("origin"))
        val back = Catalogue.parse(catalogue.toJson())!!
        assertTrue(back.catalogue.find("keys.press")!!.userOnly)
    }

    @Test fun `an unknown value is ignored`() {
        listOf<Any>("model", "USER", "anyone", true, 1).forEach { v ->
            assertFalse("$v", FunctionSpec.parse(press.toJson().put("origin", v)).userOnly)
        }
    }

    @Test fun `a user-only function is absent from the prompt, and refused if the model names it`() {
        val sources = listOf(SkillPrompt.Source("uk.mr_biz.fourzones", catalogue, family = true))
        val prompt = SkillPrompt.instructions(sources, ZonedDateTime.of(2026, 11, 7, 12, 0, 0, 0, ZoneId.of("Europe/London")))
        assertFalse(prompt.contains("keys.press"))
        assertTrue(prompt.contains("echo.say"))
        val d = SkillPrompt.parse("""{"function":"keys.press","arguments":{"keys":"ctrl+w"}}""", sources)
        assertTrue(d is SkillPrompt.Decision.None)
        assertTrue((d as SkillPrompt.Decision.None).reason.contains("keys.press"))
        assertEquals(listOf("echo.say", "echo.note", "echo.wipe"), Plumbing.forModel(catalogue).functions.map { it.id })
        assertTrue(Plumbing.hiddenFromModel(press))
        assertNull(Plumbing.refusalForModel(ECHO.functions[0]))
    }

    // ---- the provider ----

    private val signers = FakeSigners()
        .install(10001, "uk.mr_biz.fourstreamdeck", OUR_DIGEST)
        .install(10002, "com.example.stranger", OTHER_DIGEST)
    private val pairings = PairingStore(MemoryStorage()).apply {
        put(Pairing("com.example.stranger", OTHER_DIGEST, setOf("keys.press"), 0))
    }
    private val clock = FixedClock()
    private val gate = ProviderGate(family(), pairings, signers, RateLimiter(now = clock), AuditLog(MemoryStorage()), clock)
    private val ran = mutableListOf<Origin>()
    private val core = ProviderCore("4Zones", { catalogue }, gate) { _, _, c -> ran += c.origin; Outcome.Ok() }
    private val deck = CallerIdentity.identify(10001, signers)
    private val stranger = CallerIdentity.identify(10002, signers)
    private val keys = """{"keys":"ctrl+w"}"""

    private fun assertNotForModel(r: Reply) {
        r as Reply.Error
        assertEquals(ErrorCode.REFUSED, r.code)
        assertEquals(FourLink.REASON_NOT_FOR_MODEL, r.reason)
        assertEquals(false, r.fixable)
    }

    @Test fun `refused for a missing origin, a model origin, and a paired caller claiming user`() {
        assertNotForModel(core.invoke(deck, "keys.press", keys, "1", null))
        assertNotForModel(core.invoke(deck, "keys.press", keys, "1", "model"))
        assertNotForModel(core.invoke(deck, "keys.press", keys, "1", "nonsense"))
        assertNotForModel(core.invoke(stranger, "keys.press", keys, "1", "user"))
        assertTrue("the function never ran", ran.isEmpty())
    }

    @Test fun `it runs for a family caller's user call`() {
        assertTrue(core.invoke(deck, "keys.press", keys, "1", "user") is Reply.Json)
        assertEquals(listOf(Origin.USER), ran)
    }

    @Test fun `functions without the flag are not touched`() {
        assertTrue(core.invoke(deck, "echo.say", """{"text":"x"}""", "1", null) is Reply.Json)
        assertEquals(listOf(Origin.MODEL), ran)
    }

    @Test fun `the refusal is logged with its code`() {
        val audit = AuditLog(MemoryStorage())
        val g = ProviderGate(family(), pairings, signers, RateLimiter(now = clock), audit, clock)
        ProviderCore("4Zones", { catalogue }, g) { _, _, _ -> Outcome.Ok() }.invoke(deck, "keys.press", keys, "1", "model")
        assertEquals("refused", audit.all().last().result)
    }
}

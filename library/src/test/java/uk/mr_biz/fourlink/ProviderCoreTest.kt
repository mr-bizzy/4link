package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4 the three methods, §10 the major check, §11 the error codes, end to end without Android. */
class ProviderCoreTest {

    private val signers = FakeSigners()
        .install(10001, "uk.mr_biz.fourdictate", OUR_DIGEST)
        .install(10002, "com.example.stranger", OTHER_DIGEST)
    private val pairings = PairingStore(MemoryStorage())
    private val audit = AuditLog(MemoryStorage())
    private val clock = FixedClock()
    private val gate = ProviderGate(family(), pairings, signers, RateLimiter(now = clock), audit, clock)
    private val performed = mutableListOf<Pair<String, JSONObject>>()
    private val core = ProviderCore("4Link Echo", { ECHO }, gate) { f, a, _ ->
        performed += f.id to a
        when (f.id) {
            "echo.say" -> Outcome.Ok(JSONObject().put("said", a.getString("text")).toString())
            "echo.note" -> Outcome.Ok("""{"count":1}""")
            else -> Outcome.Refused("Wiping is switched off.")
        }
    }
    private val us = CallerIdentity.identify(10001, signers)
    private val stranger = CallerIdentity.identify(10002, signers)

    @Test fun `hello is always answered and names no function`() {
        assertEquals(Reply.Hello("4Link Echo", "1.0", Standing.FAMILY), core.hello(us))
        assertEquals(Reply.Hello("4Link Echo", "1.0", Standing.UNKNOWN), core.hello(stranger))
        assertEquals(Reply.Hello("4Link Echo", "1.0", Standing.UNKNOWN), core.hello(null))
        assertTrue(audit.all().isEmpty())
    }

    @Test fun `catalogue for family is the whole catalogue, for a stranger not_paired`() {
        assertEquals(Reply.Json(ECHO.toJson()), core.catalogue(us))
        assertEquals(ErrorCode.NOT_PAIRED, (core.catalogue(stranger) as Reply.Error).code)
        assertEquals(ErrorCode.NOT_PAIRED, (core.catalogue(null) as Reply.Error).code)
    }

    @Test fun `invoke runs a known function with valid arguments and answers its json`() {
        val r = core.invoke(us, "echo.say", """{"text":"hi"}""", "1")
        assertEquals(Reply.Json("""{"said":"hi"}"""), r)
        assertEquals("echo.say", performed.single().first)
        assertEquals(listOf("echo.say" to "ok"), audit.all().map { it.what to it.result })
    }

    @Test fun `unknown_function, also for a major the caller did not read`() {
        assertEquals(ErrorCode.UNKNOWN_FUNCTION, (core.invoke(us, "echo.shout", "{}", "1") as Reply.Error).code)
        assertEquals(ErrorCode.UNKNOWN_FUNCTION, (core.invoke(us, "echo.wipe", "{}", "1.0") as Reply.Error).code)
        assertEquals(ErrorCode.UNKNOWN_FUNCTION, (core.invoke(us, null, "{}", "1") as Reply.Error).code)
        assertTrue(performed.isEmpty())
        assertEquals("unknown_function", audit.all().first().result)
    }

    @Test fun `bad_arguments names the field and runs nothing`() {
        val r = core.invoke(us, "echo.note", """{"pinned":true}""", "1") as Reply.Error
        assertEquals(ErrorCode.BAD_ARGUMENTS, r.code)
        assertTrue(r.message, r.message.contains("text is required"))
        assertTrue(performed.isEmpty())
    }

    @Test fun `a paired stranger - granted runs, not granted refused, unpaired refused`() {
        assertEquals(ErrorCode.NOT_PAIRED, (core.invoke(stranger, "echo.say", """{"text":"x"}""", "1") as Reply.Error).code)
        pairings.put(Pairing("com.example.stranger", OTHER_DIGEST, setOf("echo.say"), clock.now))
        assertEquals(Reply.Json("""{"said":"x"}"""), core.invoke(stranger, "echo.say", """{"text":"x"}""", "1"))
        assertEquals(ErrorCode.NOT_GRANTED, (core.invoke(stranger, "echo.note", """{"text":"x"}""", "1") as Reply.Error).code)
        assertEquals(1, performed.size)
        assertEquals(
            listOf("not_paired", "ok", "not_granted"),
            audit.all().map { it.result },
        )
    }

    @Test fun `refused and failed carry the provider's sentence`() {
        val refused = core.invoke(us, "echo.wipe", "{}", "2") as Reply.Error
        assertEquals(ErrorCode.REFUSED, refused.code)
        assertEquals("Wiping is switched off.", refused.message)
        val failing = ProviderCore("x", { ECHO }, gate) { _, _, _ -> throw IllegalStateException("disk full") }
        val failed = failing.invoke(us, "echo.say", """{"text":"x"}""", "1") as Reply.Error
        assertEquals(ErrorCode.FAILED, failed.code)
        assertEquals("disk full", failed.message)
    }

    @Test fun `the audit log never holds argument values`() {
        core.invoke(us, "echo.say", """{"text":"SECRET-WORDS"}""", "1")
        core.invoke(us, "echo.note", """{"pinned":"SECRET-WORDS"}""", "1")
        val everything = audit.all().joinToString { it.toJson() }
        assertTrue(!everything.contains("SECRET"))
    }
}

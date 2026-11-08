// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §15g triggers: touch bumps seq and pings with the state unchanged; read names the function that gives the answer. */
class TriggerTest {

    private val start = 1_700_000_000_000L

    private class Rig(start: Long) {
        val clock = FixedClock(start)
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val subs = Subscriptions(MemoryStorage(), clock)
        val sent = mutableListOf<Triple<String, List<String>, Long>>()
        val pinger = Pinger(subs, clock, { d, t -> tasks += (clock.now + d) to t }) { s, k, q -> sent += Triple(s, k, q) }
        val store = StateStore("4zones", start) { k, q -> pinger.changed(k, q) }
        fun advance(ms: Long) {
            val end = clock.now + ms
            while (true) {
                val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
                tasks.remove(next); clock.now = maxOf(clock.now, next.first); next.second()
            }
            clock.now = end
        }
    }

    @Test fun `touch pings with a rising seq and the token idle`() {
        val r = Rig(start)
        r.store.touch("4zones.focus", read = "focus.read")
        r.subs.subscribe("deck", listOf("4zones.focus"), r.store.keys())
        r.advance(200); r.store.touch("4zones.focus")
        r.advance(200); r.store.touch("4zones.focus")
        assertEquals(listOf(start + 2, start + 3), r.sent.map { it.third })
        val v = r.store.get()["4zones.focus"]!!
        assertEquals(State.Value(State.Token.IDLE, seq = start + 3, read = "focus.read"), v)
    }

    @Test fun `repeated touches within 100 ms coalesce into one ping of the latest`() {
        val r = Rig(start)
        r.store.touch("4zones.focus")
        r.subs.subscribe("deck", listOf("4zones.focus"), r.store.keys())
        r.advance(200)
        repeat(20) { r.store.touch("4zones.focus"); r.advance(4) }
        r.advance(200)
        assertEquals("the first at once, one trailing with the latest", listOf(start + 2, start + 21), r.sent.map { it.third })
    }

    @Test fun `set with an unchanged value stays silent, touch never does`() {
        val r = Rig(start)
        r.store.set("4zones.access", State.Token.IDLE)
        r.subs.subscribe("deck", listOf("4zones.access"), r.store.keys())
        r.store.set("4zones.access", State.Token.IDLE)
        r.advance(500)
        assertTrue(r.sent.isEmpty())
        r.store.touch("4zones.access")
        assertEquals(1, r.sent.size)
        assertEquals(State.Token.IDLE, r.store.get()["4zones.access"]!!.token)
    }

    @Test fun `touch keeps the rest of the state and its read`() {
        val store = StateStore("4zones", start)
        store.set("4zones.x", State.Token.ATTENTION, extra = "4zones.down", read = "focus.read")
        store.touch("4zones.x")
        assertEquals(State.Value(State.Token.ATTENTION, "4zones.down", start + 2, read = "focus.read"), store.get()["4zones.x"])
    }

    @Test fun `touch follows the namespace rules, and read must be a function id`() {
        val store = StateStore("4zones", start)
        listOf("4dictate.mic", "focus", "4zones.Focus").forEach { assertTrue(it, runCatching { store.touch(it) }.isFailure) }
        listOf("Focus.Read", "focus", "focus.read!").forEach {
            assertTrue(it, runCatching { store.touch("4zones.focus", read = it) }.isFailure)
            assertTrue(it, runCatching { store.set("4zones.f", State.Token.IDLE, read = it) }.isFailure)
        }
    }

    @Test fun `read round-trips, and a bad one is dropped with the rest kept`() {
        val store = StateStore("4zones", start).apply { touch("4zones.focus", read = "focus.read") }
        val back = State.parseStates(State.statesToText(store.get()))
        assertEquals("focus.read", back["4zones.focus"]!!.read)
        val text = JSONObject()
            .put("4zones.a", JSONObject().put("token", "idle").put("seq", 1).put("read", "NOT AN ID"))
            .put("4zones.b", JSONObject().put("token", "idle").put("seq", 2).put("read", 7))
            .toString()
        val drops = mutableListOf<String>()
        val s = State.parseStates(text, drops::add)
        assertEquals(State.Value(State.Token.IDLE, seq = 1), s["4zones.a"])
        assertEquals(State.Value(State.Token.IDLE, seq = 2), s["4zones.b"])
        assertEquals(2, drops.size)
    }

    @Test fun `the subscriber's onChanged gets the read, and it resolves to the provider's read function`() {
        val focusRead = FunctionSpec("focus.read", "1.0", "Focused app", "Which app has focus.", Effect.READ)
        val focusSet = FunctionSpec("focus.set", "1.0", "Focus an app", "Focuses an app.", Effect.CHANGE)
        val zones = Catalogue("4Zones", listOf(focusRead, focusSet))
        val seen = mutableListOf<Map<String, State.Value>>()
        var seq = start
        val sub = StateSubscriber(
            tracked = { setOf("uk.mr_biz.fourzones") },
            read = { _, _ -> mapOf("4zones.focus" to State.Value(State.Token.IDLE, seq = ++seq, read = "focus.read")) },
            run = { it() },
            schedule = { _, _ -> },
            onChanged = { _, c -> seen += c },
        )
        sub.onPing("uk.mr_biz.fourzones", listOf("4zones.focus"))
        sub.onPing("uk.mr_biz.fourzones", listOf("4zones.focus"))
        assertEquals(2, seen.size)
        assertEquals("focus.read", seen.last()["4zones.focus"]!!.read)
        assertEquals(mapOf("4zones.focus" to focusRead), State.triggerReads(seen.last(), zones))
        val notARead = mapOf("4zones.x" to State.Value(State.Token.IDLE, seq = 1, read = "focus.set"))
        val missing = mapOf("4zones.y" to State.Value(State.Token.IDLE, seq = 1, read = "focus.gone"))
        assertTrue(State.triggerReads(notARead + missing, zones).isEmpty())
        assertNull(State.triggerReads(mapOf("k.a" to State.Value(State.Token.IDLE, seq = 1)), zones)["k.a"])
    }

    /** §15g: a trigger's read takes no arguments, so the helper always sends {} and offers no way to send more. */
    @Test fun `the trigger read sends an empty object and takes no arguments`() {
        assertEquals("{}", State.TRIGGER_READ_ARGUMENTS)
        assertEquals(0, JSONObject(State.TRIGGER_READ_ARGUMENTS).length())
        val src = java.io.File("src/main/java/uk/mr_biz/fourlink/android/FourLinkState.kt").readText()
        assertTrue(src.contains("fun triggerRead(client: FourLinkClient, provider: String, function: FunctionSpec): InvokeResult"))
        assertTrue(src.contains("client.invoke(provider, function, State.TRIGGER_READ_ARGUMENTS, Origin.MODEL)"))
        assertTrue("no arguments parameter", !src.substringAfter("fun triggerRead(").substringBefore(")").contains("argumentsJson"))
    }
}

// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** State v2, the provider's side: §3 seq floor, §4a snapshot, §5 per-kind gaps, §6 ended. */
class StateV2ProviderTest {

    private class Timer(val clock: FixedClock) {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, task: () -> Unit) { tasks += (clock.now + delay) to task }
        fun advance(ms: Long) {
            val end = clock.now + ms
            while (true) {
                val next = tasks.filter { it.first <= end }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                clock.now = maxOf(clock.now, next.first)
                next.second()
            }
            clock.now = end
        }
    }

    private class CountingStorage : TextStorage {
        var text: String? = null
        var writes = 0
        override fun read(): String? = text
        override fun write(text: String) { this.text = text; writes++ }
    }

    // ---- §3: seq never goes back, across restarts and reboots, whatever the clock says ----

    @Test fun `seq starts above the stored floor even when the clock went back`() {
        val disk = CountingStorage()
        val first = SeqFloor(disk, startMs = 5_000_000L)
        val seen = (1..10).map { first.next() }
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
        // A reboot with the clock a day behind.
        val second = SeqFloor(disk, startMs = 5_000_000L - 86_400_000L)
        assertTrue("after a restart with an earlier clock, seq still rises", second.next() > seen.last())
    }

    @Test fun `the floor is written at start and only again as the counter nears it, not per change`() {
        val disk = CountingStorage()
        val f = SeqFloor(disk, startMs = 1_000L, margin = 1_000L)
        assertEquals(1, disk.writes)
        repeat(800) { f.next() }
        assertEquals("well inside the margin: no writes", 1, disk.writes)
        repeat(1_000) { f.next() }
        assertTrue("near the floor, it moves", disk.writes in 2..4)
        val again = SeqFloor(disk, startMs = 0L)
        assertTrue(again.next() > 1_000L + 1_800L)
    }

    @Test fun `a corrupt floor is ignored, never a crash`() {
        val disk = CountingStorage().apply { text = "nonsense" }
        assertTrue(SeqFloor(disk, startMs = 7L).next() > 7L)
    }

    @Test fun `a store with a floor uses it for every key`() {
        val disk = CountingStorage()
        val a = StateStore("4home", 9_000_000L, seqs = SeqFloor(disk, 9_000_000L))
        a.set("4home.desk", State.Token.IDLE, level = 10.0)
        val before = a.get()["4home.desk"]!!.seq
        val b = StateStore("4home", 1L, seqs = SeqFloor(disk, 1L))
        b.set("4home.desk", State.Token.IDLE, level = 10.0)
        assertTrue(b.get()["4home.desk"]!!.seq > before)
    }

    // ---- §5: level-only changes wait 1000 ms, anything else 100 ms; the latest always goes ----

    private class Rig {
        val clock = FixedClock(1_000_000L)
        val timer = Timer(clock)
        val subs = Subscriptions(MemoryStorage(), clock)
        val sent = mutableListOf<Pair<Long, List<String>>>()
        lateinit var store: StateStore
        val pinger = Pinger(subs, clock, timer::schedule) { _, k, _ -> sent += clock.now to k }
        init {
            store = StateStore("4home", clock.now) { k, q -> pinger.changed(k, q, store.levelOnly(k, q)) }
            subs.subscribe("keys", listOf("4home.desk", "4home.mic"), setOf("4home.desk", "4home.mic"))
        }
    }

    @Test fun `a moving desk pings once a second, first at once, last always`() {
        val r = Rig()
        r.store.set("4home.desk", State.Token.IDLE, level = 0.0)
        val t0 = r.clock.now
        for (i in 1..12) { r.timer.advance(200); r.store.set("4home.desk", State.Token.IDLE, level = i * 5.0) }
        r.timer.advance(2_000)
        val times = r.sent.map { it.first - t0 }
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), times)
        assertEquals(60.0, r.store.get()["4home.desk"]!!.level!!, 0.0)
    }

    @Test fun `a token change is not held behind a level gap`() {
        val r = Rig()
        r.store.set("4home.desk", State.Token.IDLE, level = 0.0)
        val t0 = r.clock.now
        r.timer.advance(50); r.store.set("4home.desk", State.Token.IDLE, level = 5.0)
        r.timer.advance(10); r.store.set("4home.desk", State.Token.ATTENTION, level = 5.0)
        r.timer.advance(2_000)
        assertEquals(listOf(0L, 100L), r.sent.map { it.first - t0 })
    }

    @Test fun `the first value of a key is never level-only`() {
        val s = StateStore("4home", 1L)
        s.set("4home.desk", State.Token.IDLE, level = 3.0)
        assertFalse(s.levelOnly("4home.desk", s.get()["4home.desk"]!!.seq))
        s.set("4home.desk", State.Token.IDLE, level = 4.0)
        assertTrue(s.levelOnly("4home.desk", s.get()["4home.desk"]!!.seq))
        s.set("4home.desk", State.Token.IDLE, level = 4.0, on = true)
        assertFalse(s.levelOnly("4home.desk", s.get()["4home.desk"]!!.seq))
    }

    // ---- §4a: the subscribe reply carries the values (the retained message) ----

    @Test fun `subscribe answers with the current values of the keys it accepted`() {
        val store = StateStore("4home", 1L).apply {
            set("4home.desk", State.Token.IDLE, level = 12.0)
            set("4home.hall", State.Token.IDLE, on = true)
        }
        val provider = StateProvider(store, Subscriptions(MemoryStorage(), FixedClock(1L)))
        val out = provider.subscribe(JSONObject().put("keys", """["4home.desk","x.y"]"""), Caller(10, "keys", OUR_DIGEST)) as Outcome.Ok
        val states = State.parseStates(JSONObject(out.json).getString(State.KEY_STATES))
        assertEquals(setOf("4home.desk"), states.keys)
        assertEquals(12.0, states["4home.desk"]!!.level!!, 0.0)
    }

    // ---- §4: the payload, under 8 KB or not at all ----

    @Test fun `values are the states encoding, and left out over 8 KB`() {
        val store = StateStore("4home", 1L).apply { set("4home.desk", State.Token.IDLE, level = 12.0) }
        val text = State.valuesFor(store.get(listOf("4home.desk")))!!
        assertEquals(12.0, State.parseStates(text)["4home.desk"]!!.level!!, 0.0)
        val big = (1..400).associate { "4home.k$it" to State.Value(State.Token.IDLE, seq = it.toLong(), level = 50.0) }
        assertEquals(null, State.valuesFor(big))
    }

    // ---- §6: a subscription that runs out is announced ----

    @Test fun `a subscription that expires is reported once`() {
        val clock = FixedClock(0L)
        val subs = Subscriptions(MemoryStorage(), clock)
        val ended = mutableListOf<Subscription>()
        subs.onEnded = { ended += it }
        subs.subscribe("keys", listOf("4home.desk"), setOf("4home.desk"))
        clock.now += State.MAX_SECONDS * 1000
        subs.live(); subs.live()
        assertEquals(listOf("keys"), ended.map { it.packageName })
    }

    @Test fun `the subscriber's own empty subscribe is not announced`() {
        val subs = Subscriptions(MemoryStorage(), FixedClock(0L))
        val ended = mutableListOf<Subscription>()
        subs.onEnded = { ended += it }
        subs.subscribe("keys", listOf("4home.desk"), setOf("4home.desk"))
        subs.subscribe("keys", emptyList(), setOf("4home.desk"))
        assertTrue(ended.isEmpty())
    }
}

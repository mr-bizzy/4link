// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** State v2, the subscriber's side: §4 proven values, §4a snapshot, §6 birth and ended, §7 retry floor. */
class StateV2SubscriberTest {

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

    private class Rig {
        val clock = FixedClock(0)
        val timer = Timer(clock)
        val reads = mutableListOf<Pair<Long, List<String>>>()
        val changes = mutableListOf<Map<String, State.Value>>()
        val births = mutableListOf<String>()
        val ended = mutableListOf<Pair<String, List<String>>>()
        var answer: (List<String>) -> StateRead = { StateRead.Failed }
        val sub = StateSubscriber(
            tracked = { setOf("p") },
            read = { _, _ -> error("v2 rig reads through fetch") },
            run = { it() },
            schedule = timer::schedule,
            onChanged = { _, c -> changes += c },
            fetch = { _, keys -> reads += clock.now to keys; answer(keys) },
            birthHeard = { births += it },
            endedHeard = { p, k -> ended += p to k },
            now = clock,
        )
    }

    private fun v(seq: Long, level: Double? = null) = State.Value(State.Token.IDLE, null, seq, level)

    @Test fun `proven values are applied with no read`() {
        val r = Rig()
        r.sub.onValues("p", listOf("k.desk"), mapOf("k.desk" to v(5, 40.0)))
        assertEquals(0, r.reads.size)
        assertEquals(listOf(mapOf("k.desk" to v(5, 40.0))), r.changes)
    }

    @Test fun `an older value never undoes a newer one, whichever path it came by`() {
        val r = Rig()
        r.sub.onValues("p", listOf("k.desk"), mapOf("k.desk" to v(7, 50.0)))
        r.sub.onValues("p", listOf("k.desk"), mapOf("k.desk" to v(6, 45.0)))
        assertEquals(v(7, 50.0), r.sub.held("p")["k.desk"])
        assertEquals(1, r.changes.size)
    }

    @Test fun `a key named but missing from the values is read, as v1`() {
        val r = Rig()
        r.answer = { StateRead.Got(mapOf("k.mic" to v(3))) }
        r.sub.onValues("p", listOf("k.desk", "k.mic"), mapOf("k.desk" to v(5)))
        assertEquals(listOf(0L to listOf("k.mic")), r.reads)
    }

    @Test fun `values from a provider not tracked are ignored`() {
        val r = Rig()
        r.sub.onValues("stranger", listOf("k.desk"), mapOf("k.desk" to v(5)))
        assertTrue(r.changes.isEmpty())
    }

    @Test fun `a birth resets the watermark, applies as given, and asks for a re-subscribe`() {
        val r = Rig()
        r.sub.onValues("p", listOf("k.desk"), mapOf("k.desk" to v(1_000, 90.0)))
        // The provider lost its store: its seq came back lower.
        r.sub.onBirth("p", mapOf("k.desk" to v(10, 12.0)))
        assertEquals(v(10, 12.0), r.sub.held("p")["k.desk"])
        assertEquals(listOf("p"), r.births)
        // ...and from then on its seq rules again.
        r.sub.onValues("p", listOf("k.desk"), mapOf("k.desk" to v(11, 13.0)))
        assertEquals(v(11, 13.0), r.sub.held("p")["k.desk"])
    }

    @Test fun `ended is passed on so the subscriber subscribes again`() {
        val r = Rig()
        r.sub.onEnded("p", listOf("k.desk"))
        assertEquals(listOf("p" to listOf("k.desk")), r.ended)
    }

    @Test fun `rate_limited waits at least 10 s, and one retry at a time per provider`() {
        val r = Rig()
        r.answer = { StateRead.RateLimited }
        r.sub.onPing("p", listOf("k.desk"))
        r.sub.onPing("p", listOf("k.mic"))
        r.timer.advance(9_999)
        assertEquals("the second ping superseded the wait, so two reads; no retry yet", 2, r.reads.size)
        r.timer.advance(1)
        assertEquals(3, r.reads.size)
        assertEquals(setOf("k.desk", "k.mic"), r.reads.last().second.toSet())
    }

    @Test fun `retries to one provider stay within 6 a minute`() {
        val r = Rig()
        r.answer = { StateRead.Failed }
        r.sub.onPing("p", listOf("k.desk"))
        r.timer.advance(60_000)
        val retries = r.reads.drop(1).count { it.first < 60_000 }
        assertTrue("retries in the first minute: $retries", retries <= 6)
    }

    @Test fun `the old read signature still works for v1 subscribers`() {
        val changes = mutableListOf<Map<String, State.Value>>()
        val sub = StateSubscriber({ setOf("p") }, { _, _ -> mapOf("k.a" to v(2)) }, { it() }, { _, _ -> }, { _, c -> changes += c })
        sub.onPing("p", listOf("k.a"))
        assertEquals(listOf(mapOf("k.a" to v(2))), changes)
    }
}

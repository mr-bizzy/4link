// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** §15c state: tokens, seq, subscriptions on disk, the ping's coalescing, the subscriber's guard, and the gate. */
class StateTest {

    private val start = 1_700_000_000_000L

    /** A fake scheduler on a fake clock: tasks run when [advance] passes their time. */
    private class FakeTimer(val clock: FixedClock) {
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

    // ---- tokens, keys, the store ----

    @Test fun `an unknown token reads as attention, the four read as themselves`() {
        assertEquals(State.Token.ATTENTION, State.Token.read("glowing"))
        assertEquals(State.Token.ATTENTION, State.Token.read(null))
        State.Token.entries.forEach { assertEquals(it, State.Token.read(it.wire)) }
        assertNull("a provider may set only the four", State.Token.ofWire("glowing"))
    }

    @Test fun `seq starts at the process start and rises by one with each change of a key`() {
        val store = StateStore("4dictate", start)
        store.set("4dictate.mic", State.Token.IDLE)
        assertEquals(start + 1, store.get()["4dictate.mic"]!!.seq)
        store.set("4dictate.mic", State.Token.LISTENING, "4dictate.command")
        assertEquals(start + 2, store.get()["4dictate.mic"]!!.seq)
        store.set("4dictate.other", State.Token.WORKING)
        assertEquals("seq is per key", start + 1, store.get()["4dictate.other"]!!.seq)
    }

    @Test fun `a restart starts above anything the last process sent`() {
        val first = StateStore("4dictate", start)
        repeat(500) { first.set("4dictate.mic", if (it % 2 == 0) State.Token.LISTENING else State.Token.IDLE) }
        val restarted = StateStore("4dictate", start + 60_000)
        restarted.set("4dictate.mic", State.Token.IDLE)
        assertTrue(restarted.get()["4dictate.mic"]!!.seq > first.get()["4dictate.mic"]!!.seq)
    }

    @Test fun `the same value again is no change, no seq, no ping`() {
        val heard = mutableListOf<Pair<String, Long>>()
        val store = StateStore("4dictate", start) { k, s -> heard += k to s }
        store.set("4dictate.mic", State.Token.LISTENING)
        store.set("4dictate.mic", State.Token.LISTENING)
        store.set("4dictate.mic", State.Token.LISTENING, "4dictate.command")
        assertEquals(listOf("4dictate.mic" to start + 1, "4dictate.mic" to start + 2), heard)
    }

    @Test fun `keys and extras must be the provider's own namespace, within 40 characters`() {
        val store = StateStore("4dictate", start)
        listOf("4zones.access", "mic", "4dictate.", "4dictate.Mic", "4dictate.mic.x", "4dictate." + "a".repeat(32)).forEach {
            assertTrue(it, runCatching { store.set(it, State.Token.IDLE) }.isFailure)
        }
        assertTrue(runCatching { store.set("4dictate.mic", State.Token.IDLE, "4zones.command") }.isFailure)
        assertTrue(runCatching { store.set("4dictate.mic", State.Token.IDLE, "command") }.isFailure)
        store.set("4dictate." + "a".repeat(31), State.Token.IDLE) // exactly 40
        assertTrue(store.keys().isEmpty().not())
    }

    @Test fun `the reader drops a bad extra, keeps the token, and drops an entry without seq`() {
        val text = JSONObject()
            .put("4dictate.mic", JSONObject().put("token", "listening").put("extra", "NOT VALID").put("seq", 41))
            .put("4dictate.other", JSONObject().put("token", "sparkly").put("extra", "4dictate.command").put("seq", 3))
            .put("4dictate.noseq", JSONObject().put("token", "idle"))
            .put("Bad Key", JSONObject().put("token", "idle").put("seq", 1))
            .toString()
        val drops = mutableListOf<String>()
        val states = State.parseStates(text, drops::add)
        assertEquals(State.Value(State.Token.LISTENING, null, 41), states["4dictate.mic"])
        assertEquals(State.Value(State.Token.ATTENTION, "4dictate.command", 3), states["4dictate.other"])
        assertEquals(setOf("4dictate.mic", "4dictate.other"), states.keys)
        assertEquals(3, drops.size)
    }

    @Test fun `what the provider writes the caller reads`() {
        val store = StateStore("4dictate", start)
        store.set("4dictate.mic", State.Token.LISTENING, "4dictate.command")
        assertEquals(store.get(), State.parseStates(State.statesToText(store.get())))
    }

    // ---- subscriptions ----

    @Test fun `a subscription keeps the keys the provider has, lasts at most a day, and survives a restart`() {
        val clock = FixedClock(start)
        val storage = MemoryStorage()
        val subs = Subscriptions(storage, clock, seconds = 999_999)
        assertEquals(State.MAX_SECONDS, subs.seconds)
        assertEquals(listOf("4dictate.mic"), subs.subscribe("deck", listOf("4dictate.mic", "4dictate.nope"), setOf("4dictate.mic")))
        val again = Subscriptions(storage, clock)
        assertEquals(listOf(Subscription("deck", setOf("4dictate.mic"), start + 86_400_000)), again.live())
    }

    @Test fun `it ends when it runs out`() {
        val clock = FixedClock(start)
        val subs = Subscriptions(MemoryStorage(), clock, seconds = 60)
        subs.subscribe("deck", listOf("4dictate.mic"), setOf("4dictate.mic"))
        clock.now += 59_999
        assertEquals(1, subs.live().size)
        clock.now += 1
        assertTrue(subs.live().isEmpty())
        assertTrue(subs.watchers(listOf("4dictate.mic")).isEmpty())
    }

    @Test fun `a renewal restarts the clock`() {
        val clock = FixedClock(start)
        val subs = Subscriptions(MemoryStorage(), clock, seconds = 60)
        subs.subscribe("deck", listOf("4dictate.mic"), setOf("4dictate.mic"))
        clock.now += 50_000
        subs.subscribe("deck", listOf("4dictate.mic"), setOf("4dictate.mic"))
        clock.now += 50_000
        assertEquals(1, subs.live().size)
    }

    @Test fun `an empty list ends it, a list of only unknown keys leaves it alone`() {
        val clock = FixedClock(start)
        val subs = Subscriptions(MemoryStorage(), clock)
        subs.subscribe("deck", listOf("4dictate.mic"), setOf("4dictate.mic"))
        assertEquals(emptyList<String>(), subs.subscribe("deck", listOf("4dictate.nope"), setOf("4dictate.mic")))
        assertEquals(1, subs.live().size)
        subs.subscribe("deck", emptyList(), setOf("4dictate.mic"))
        assertTrue(subs.live().isEmpty())
    }

    @Test fun `removing the subscriber's package removes the subscription, on disk too`() {
        val storage = MemoryStorage()
        val subs = Subscriptions(storage, FixedClock(start))
        subs.subscribe("deck", listOf("4dictate.mic"), setOf("4dictate.mic"))
        assertTrue(subs.remove("deck"))
        assertFalse(subs.remove("deck"))
        assertTrue(Subscriptions(storage, FixedClock(start)).live().isEmpty())
    }

    @Test fun `a stored expiry is never trusted beyond a day from now`() {
        val storage = MemoryStorage("""[{"package":"deck","keys":["4dictate.mic"],"expires":${Long.MAX_VALUE}}]""")
        val clock = FixedClock(start)
        val subs = Subscriptions(storage, clock)
        clock.now += 86_400_000
        assertTrue(subs.live().isEmpty())
    }

    @Test fun `the provider's handlers answer keys, seconds and states`() {
        val store = StateStore("4dictate", start).apply { set("4dictate.mic", State.Token.IDLE); set("4dictate.err", State.Token.ATTENTION) }
        val provider = StateProvider(store, Subscriptions(MemoryStorage(), FixedClock(start)))
        val deck = Caller(10, "deck", OUR_DIGEST)
        val sub = provider.subscribe(JSONObject().put("keys", """["4dictate.mic","x.y"]"""), deck) as Outcome.Ok
        assertEquals(listOf("4dictate.mic"), State.parseKeys(JSONObject(sub.json).getString("keys")))
        assertEquals(86_400, JSONObject(sub.json).getInt("seconds"))
        val all = provider.get(JSONObject()) as Outcome.Ok
        assertEquals(setOf("4dictate.mic", "4dictate.err"), State.parseStates(JSONObject(all.json).getString("states")).keys)
        val one = provider.get(JSONObject().put("keys", """["4dictate.err","x.unknown"]""")) as Outcome.Ok
        assertEquals(setOf("4dictate.err"), State.parseStates(JSONObject(one.json).getString("states")).keys)
        assertTrue(provider.get(JSONObject().put("keys", "nonsense")) is Outcome.BadArguments)
        assertTrue(provider.subscribe(JSONObject(), deck) is Outcome.BadArguments)
    }

    // ---- the ping ----

    private class Rig(start: Long) {
        val clock = FixedClock(start)
        val timer = FakeTimer(clock)
        val subs = Subscriptions(MemoryStorage(), clock)
        val sent = mutableListOf<Triple<String, List<String>, Long>>()
        val pinger = Pinger(subs, clock, timer::schedule) { s, k, q -> sent += Triple(s, k, q) }
        val store = StateStore("4dictate", start) { k, q -> pinger.changed(k, q) }
    }

    @Test fun `the first change pings at once, explicitly per subscriber, with no token`() {
        val r = Rig(start)
        r.subs.subscribe("deck", listOf("4dictate.mic"), setOf("4dictate.mic"))
        r.store.set("4dictate.mic", State.Token.LISTENING)
        assertEquals(listOf(Triple("deck", listOf("4dictate.mic"), start + 1)), r.sent)
    }

    @Test fun `changes inside 100 ms coalesce into one trailing ping carrying the latest`() {
        val r = Rig(start)
        r.subs.subscribe("deck", listOf("4dictate.mic"), setOf("4dictate.mic"))
        r.store.set("4dictate.mic", State.Token.LISTENING)
        r.timer.advance(10); r.store.set("4dictate.mic", State.Token.WORKING)
        r.timer.advance(10); r.store.set("4dictate.mic", State.Token.IDLE)
        r.timer.advance(10); r.store.set("4dictate.mic", State.Token.ATTENTION)
        assertEquals("nothing more inside the gap", 1, r.sent.size)
        r.timer.advance(69)
        assertEquals(1, r.sent.size)
        r.timer.advance(1)
        assertEquals(2, r.sent.size)
        assertEquals(Triple("deck", listOf("4dictate.mic"), start + 4), r.sent[1])
        assertEquals("at most one per key every 100 ms", start + 100, r.clock.now)
        r.timer.advance(1_000)
        assertEquals("and nothing after", 2, r.sent.size)
    }

    @Test fun `each key has its own gap, and a ping names only the keys its subscriber watches`() {
        val r = Rig(start)
        val keys = setOf("4dictate.mic", "4dictate.err")
        r.subs.subscribe("deck", keys.toList(), keys)
        r.subs.subscribe("watch", listOf("4dictate.err"), keys)
        r.store.set("4dictate.mic", State.Token.LISTENING)
        r.timer.advance(50)
        r.store.set("4dictate.err", State.Token.ATTENTION)
        assertEquals(
            listOf(Triple("deck", listOf("4dictate.mic"), start + 1), Triple("deck", listOf("4dictate.err"), start + 1), Triple("watch", listOf("4dictate.err"), start + 1)),
            r.sent,
        )
        r.store.set("4dictate.mic", State.Token.IDLE); r.store.set("4dictate.err", State.Token.IDLE)
        r.timer.advance(100)
        // mic was due at 100, err at 150: two trailing pings.
        assertEquals(Triple("deck", listOf("4dictate.mic"), start + 2), r.sent[3])
        assertEquals(setOf(Triple("deck", listOf("4dictate.err"), start + 2), Triple("watch", listOf("4dictate.err"), start + 2)), r.sent.drop(4).toSet())
    }

    @Test fun `no subscriber, or an expired one, gets no ping`() {
        val r = Rig(start)
        r.store.set("4dictate.mic", State.Token.LISTENING)
        r.subs.subscribe("deck", listOf("4dictate.mic"), setOf("4dictate.mic"))
        r.clock.now += 86_400_000
        r.store.set("4dictate.mic", State.Token.IDLE)
        assertTrue(r.sent.isEmpty())
    }

    // ---- the subscriber ----

    /** Runs nothing until told: a read is "in flight" from [run] until [runAll] executes it. */
    private class FakeExecutor {
        val queue = ArrayDeque<() -> Unit>()
        fun run(task: () -> Unit) { queue.addLast(task) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst()() }
    }

    private class SubRig(tracked: Set<String> = setOf("p")) {
        val clock = FixedClock(0)
        val timer = FakeTimer(clock)
        val exec = FakeExecutor()
        val reads = mutableListOf<Pair<Long, List<String>>>()
        val changes = mutableListOf<Map<String, State.Value>>()
        var answer: (List<String>) -> Map<String, State.Value>? = { null }
        val sub = StateSubscriber(
            tracked = { tracked },
            read = { _, keys -> reads += clock.now to keys; answer(keys) },
            run = exec::run,
            schedule = timer::schedule,
            onChanged = { _, c -> changes += c },
        )
        fun ping(vararg keys: String) { sub.onPing("p", keys.toList()); }
    }

    private fun v(seq: Long, token: State.Token = State.Token.IDLE) = State.Value(token, null, seq)

    @Test fun `a forged ping costs one gated read and shows nothing false`() {
        val r = SubRig()
        val truth = mapOf("k.a" to v(5))
        r.answer = { truth }
        r.ping("k.a"); r.exec.runAll()
        assertEquals(listOf(truth), r.changes)
        // Forged: claims a change, but the gated read says what is true, so nothing changes.
        r.ping("k.a"); r.exec.runAll()
        assertEquals(2, r.reads.size)
        assertEquals(1, r.changes.size)
        assertEquals(truth, r.sub.held("p"))
    }

    @Test fun `a ping naming a provider not tracked, or no key, reads nothing`() {
        val r = SubRig()
        r.sub.onPing("com.example.stranger", listOf("k.a"))
        r.sub.onPing(null, listOf("k.a"))
        r.sub.onPing("p", null)
        r.sub.onPing("p", listOf("<bad key>"))
        r.exec.runAll()
        assertEquals(0, r.reads.size)
    }

    @Test fun `a reply older than one held is dropped, a newer one kept`() {
        val r = SubRig()
        assertEquals(mapOf("k.a" to v(41)), r.sub.accept("p", mapOf("k.a" to v(41))))
        assertTrue("older", r.sub.accept("p", mapOf("k.a" to v(40))).isEmpty())
        assertTrue("the same", r.sub.accept("p", mapOf("k.a" to v(41))).isEmpty())
        assertEquals(mapOf("k.a" to v(42)), r.sub.accept("p", mapOf("k.a" to v(42))))
        assertEquals(mapOf("k.a" to v(42)), r.sub.held("p"))
        r.sub.forget("p")
        assertTrue(r.sub.held("p").isEmpty())
    }

    @Test fun `a burst of 50 pings costs at most 2 reads and ends on the latest state`() {
        val r = SubRig()
        var truth = 1L
        var first = true
        r.answer = { _ ->
            val snapshot = mapOf("k.a" to v(truth))
            if (first) {
                first = false
                // The other 49 pings arrive while the first read is in flight, and the state moves on.
                repeat(49) { truth++; r.ping("k.a") }
            }
            snapshot
        }
        r.ping("k.a")
        r.exec.runAll()
        assertEquals(2, r.reads.size)
        assertEquals(mapOf("k.a" to v(50)), r.sub.held("p"))
        assertEquals(listOf(mapOf("k.a" to v(1)), mapOf("k.a" to v(50))), r.changes)
    }

    @Test fun `pings during a read are one more read with every key asked for`() {
        val r = SubRig()
        r.answer = { keys -> keys.associateWith { v(1) } }
        r.ping("k.a")
        r.ping("k.b"); r.ping("k.c"); r.ping("k.b")
        r.exec.runAll()
        assertEquals(listOf(listOf("k.a"), listOf("k.b", "k.c")), r.reads.map { it.second })
    }

    @Test fun `a rate-limited read retries after 1 s, then 2 s, and lands`() {
        val r = SubRig()
        var fails = 2
        r.answer = { if (fails-- > 0) null else mapOf("k.a" to v(7, State.Token.LISTENING)) }
        r.ping("k.a"); r.exec.runAll()
        r.timer.advance(999); r.exec.runAll()
        assertEquals("no retry before 1 s", 1, r.reads.size)
        r.timer.advance(1); r.exec.runAll()
        assertEquals(2, r.reads.size)
        r.timer.advance(1_999); r.exec.runAll()
        assertEquals(2, r.reads.size)
        r.timer.advance(1); r.exec.runAll()
        assertEquals(listOf(0L, 1_000L, 3_000L), r.reads.map { it.first })
        assertEquals(mapOf("k.a" to v(7, State.Token.LISTENING)), r.sub.held("p"))
        r.timer.advance(120_000); r.exec.runAll()
        assertEquals("nothing after it landed", 3, r.reads.size)
    }

    @Test fun `the backoff doubles to a 30 s cap and never gives up`() {
        val r = SubRig()
        r.ping("k.a"); r.exec.runAll()
        repeat(150) { r.timer.advance(1_000); r.exec.runAll() }
        val gaps = r.reads.map { it.first }.zipWithNext { x, y -> y - x }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), gaps.take(7))
        assertTrue(r.timer.tasks.isNotEmpty())
    }

    @Test fun `a ping during a backoff supersedes it, reads at once with both sets of keys`() {
        val r = SubRig()
        var fail = true
        r.answer = { keys -> if (fail) null else keys.associateWith { v(3) } }
        r.ping("k.a"); r.exec.runAll()          // fails, retry waits 1 s
        r.timer.advance(1_000); r.exec.runAll() // fails again, retry waits 2 s
        fail = false
        r.timer.advance(500)
        r.ping("k.b"); r.exec.runAll()          // supersedes the wait
        assertEquals(listOf(listOf("k.a"), listOf("k.a"), listOf("k.a", "k.b")), r.reads.map { it.second })
        assertEquals(1_500L, r.reads.last().first)
        r.timer.advance(10_000); r.exec.runAll()
        assertEquals("the superseded retry does nothing", 3, r.reads.size)
        assertEquals(setOf("k.a", "k.b"), r.sub.held("p").keys)
    }

    @Test fun `a successful read resets the backoff`() {
        val r = SubRig()
        var fail = true
        r.answer = { if (fail) null else mapOf("k.a" to v(r.reads.size.toLong())) }
        r.ping("k.a"); r.exec.runAll()
        r.timer.advance(1_000); r.exec.runAll()
        fail = false
        r.timer.advance(2_000); r.exec.runAll()
        fail = true
        r.ping("k.a"); r.exec.runAll()
        r.timer.advance(1_000); r.exec.runAll()
        assertEquals("1 s again, not 4 s", 5, r.reads.size)
    }

    @Test fun `forgetting a provider voids its waiting retry`() {
        val r = SubRig()
        r.ping("k.a"); r.exec.runAll()
        r.sub.forget("p")
        r.timer.advance(60_000); r.exec.runAll()
        assertEquals(1, r.reads.size)
    }

    // ---- the gate ----

    private val signers = FakeSigners()
        .install(10001, "uk.mr_biz.fourstreamdeck", OUR_DIGEST)
        .install(10002, "com.example.stranger", OTHER_DIGEST)
    private val pairings = PairingStore(MemoryStorage())
    private val clock = FixedClock()
    private val gate = ProviderGate(family(), pairings, signers, RateLimiter(now = clock), AuditLog(MemoryStorage()), clock)
    private fun caller(uid: Int) = CallerIdentity.identify(uid, signers)!!

    @Test fun `a paired caller is not_granted for state, whatever its grant`() {
        val c = caller(10002)
        pairings.put(Pairing(c.packageName, c.certDigest, setOf(State.GET, State.SUBSCRIBE, Icons.FUNCTION), clock.now))
        assertEquals(ErrorCode.NOT_GRANTED, (gate.gateInvoke(c, State.GET) as Decision.Refused).code)
        assertEquals(ErrorCode.NOT_GRANTED, (gate.gateInvoke(c, State.SUBSCRIBE) as Decision.Refused).code)
        assertEquals("pictures follow the grant", Decision.Allowed, gate.gateInvoke(c, Icons.FUNCTION))
    }

    @Test fun `an unpaired caller is not_paired for state`() {
        assertEquals(ErrorCode.NOT_PAIRED, (gate.gateInvoke(caller(10002), State.GET) as Decision.Refused).code)
    }

    @Test fun `state get has its own 120 a minute and does not use up the family's 120`() {
        val c = caller(10001)
        repeat(120) { assertEquals(Decision.Allowed, gate.gateInvoke(c, State.GET)) }
        assertEquals(ErrorCode.RATE_LIMITED, (gate.gateInvoke(c, State.GET) as Decision.Refused).code)
        repeat(120) { assertEquals("the family's 120 are untouched", Decision.Allowed, gate.gateInvoke(c, "echo.say")) }
        assertEquals(ErrorCode.RATE_LIMITED, (gate.gateInvoke(c, "echo.say") as Decision.Refused).code)
        clock.now += 60_000
        assertEquals(Decision.Allowed, gate.gateInvoke(c, State.GET))
    }

    @Test fun `using up the family's 120 leaves state get alone, and subscribe counts in the 120`() {
        val c = caller(10001)
        repeat(119) { gate.gateInvoke(c, "echo.say") }
        assertEquals(Decision.Allowed, gate.gateInvoke(c, State.SUBSCRIBE))
        assertEquals(ErrorCode.RATE_LIMITED, (gate.gateInvoke(c, State.SUBSCRIBE) as Decision.Refused).code)
        assertEquals(Decision.Allowed, gate.gateInvoke(c, State.GET))
    }

    @Test fun `through the door, family reads state and a paired app cannot`() {
        val store = StateStore("4dictate", start).apply { set("4dictate.mic", State.Token.LISTENING) }
        val provider = StateProvider(store, Subscriptions(MemoryStorage(), clock))
        val catalogue = Catalogue("4Dictate", listOf(State.GET_SPEC, State.SUBSCRIBE_SPEC, Icons.SPEC))
        val core = ProviderCore("4Dictate", { catalogue }, gate) { f, a, c -> provider.perform(f, a, c) ?: Outcome.Failed("no") }
        val family = core.invoke(caller(10001), State.GET, "{}", "1") as Reply.Json
        assertEquals(State.Token.LISTENING, State.parseStates(JSONObject(family.json).getString("states"))["4dictate.mic"]!!.token)
        val stranger = caller(10002)
        pairings.put(Pairing(stranger.packageName, stranger.certDigest, setOf(State.GET), clock.now))
        assertEquals(ErrorCode.NOT_GRANTED, (core.invoke(stranger, State.GET, "{}", "1") as Reply.Error).code)
        assertEquals(ErrorCode.NOT_GRANTED, (core.invoke(stranger, State.SUBSCRIBE, """{"keys":"[]"}""", "1") as Reply.Error).code)
    }

    @Test fun `the catalogue entries are reads in the schema subset`() {
        val back = Catalogue.parse(Catalogue("x", listOf(State.GET_SPEC, State.SUBSCRIBE_SPEC)).toJson())!!
        assertEquals(listOf(State.GET, State.SUBSCRIBE), back.catalogue.functions.map { it.id })
        assertTrue(back.catalogue.functions.all { it.effect == Effect.READ })
        assertEquals(State.keysToText(listOf("a.b")), JSONArray(listOf("a.b")).toString())
    }

    // ---- §15a ----

    @Test fun `a model is never offered pictures or state, and cannot call them by name`() {
        val withPlumbing = ECHO.copy(functions = ECHO.functions + listOf(Icons.SPEC, State.GET_SPEC, State.SUBSCRIBE_SPEC))
        assertEquals(ECHO.functions.map { it.id }, Plumbing.forModel(withPlumbing).functions.map { it.id })
        val sources = listOf(SkillPrompt.Source("uk.mr_biz.fourlink.echo", withPlumbing, family = true))
        val prompt = SkillPrompt.instructions(sources, ZonedDateTime.of(2026, 11, 7, 12, 0, 0, 0, ZoneId.of("Europe/London")))
        listOf("icons.get", "state.get", "state.subscribe").forEach { assertFalse(it, it in prompt) }
        assertTrue("echo.say" in prompt)
        val d = SkillPrompt.parse("""{"function":"state.get","arguments":{}}""", sources)
        assertTrue(d is SkillPrompt.Decision.None)
        assertTrue(Plumbing.isPlumbing("icons.get") && Plumbing.isPlumbing("state.anything"))
        assertFalse(Plumbing.isPlumbing("iconsx.get") || Plumbing.isPlumbing("echo.state"))
        assertNull(Plumbing.refusalForModel("echo.say"))
    }
}

// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject

/**
 * State for control surfaces (§15c): a provider names a few STATE KEYS, each with a [Token] that says
 * what the user should notice, never how it looks. `state.get` reads them, `state.subscribe` asks for
 * a ping when they change. Family callers only in this version ([ProviderGate]).
 */
object State {
    const val GET = "state.get"
    const val SUBSCRIBE = "state.subscribe"
    const val VERSION = "1.0"

    const val KEY_KEYS = "keys"
    const val KEY_STATES = "states"
    const val KEY_SECONDS = "seconds"

    /** The longest a subscription may last, and the provider's default. */
    const val MAX_SECONDS = 86_400L

    /** At most this many characters in a key or an extra. */
    const val NAME_MAX = 40

    /** The ping (§15c): an explicit broadcast with these extras, and no token. */
    const val ACTION_STATE = "uk.mr_biz.4link.action.STATE"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_KEYS = "keys"
    const val EXTRA_SEQ = "seq"

    /** At most one ping per key in this long, carrying the latest (§15c). */
    const val PING_GAP_MS = 100L

    /** `get` allows this many a minute per caller, apart from the 30 (§15c). */
    const val GET_PER_MINUTE = 120

    enum class Token(val wire: String) {
        IDLE("idle"), LISTENING("listening"), WORKING("working"), ATTENTION("attention");

        companion object {
            /** A provider may set only these four. */
            fun ofWire(s: String?): Token? = entries.firstOrNull { it.wire == s }

            /** A reader reads an unknown token as [ATTENTION] (§15c). */
            fun read(s: String?): Token = ofWire(s) ?: ATTENTION
        }
    }

    /** One key's state. [seq] rises with every change of that key (Q1: from the process start time). */
    data class Value(val token: Token, val extra: String? = null, val seq: Long)

    // `<namespace>.<word>`: the namespace may start with a digit (4dictate), the word starts with a letter.
    private val NAME = Regex("[a-z0-9][a-z0-9_]*\\.[a-z][a-z0-9_]*")

    /** A well-formed key or extra in any namespace: what a reader accepts. */
    fun isName(s: String): Boolean = s.length <= NAME_MAX && NAME.matches(s)

    /** A key or extra a provider with [namespace] may declare: well formed and in its own namespace. */
    fun isOwnName(namespace: String, s: String): Boolean = isName(s) && s.substringBefore('.') == namespace

    /** The keys input as text. */
    fun keysToText(keys: Collection<String>): String = JSONArray(keys.toList()).toString()

    /** The keys input, or null when it is not an array of strings. */
    fun parseKeys(text: String?): List<String>? {
        val a = runCatching { JSONArray(text ?: return null) }.getOrNull() ?: return null
        val out = linkedSetOf<String>()
        for (i in 0 until a.length()) out += (a.opt(i) as? String ?: return null)
        return out.toList()
    }

    /** The `states` output as the provider sends it. */
    fun statesToText(states: Map<String, Value>): String {
        val o = JSONObject()
        for ((k, v) in states) {
            o.put(k, JSONObject().put("token", v.token.wire).put("seq", v.seq).also { e -> v.extra?.let { e.put("extra", it) } })
        }
        return o.toString()
    }

    /**
     * The caller's reading of `states`: an unknown token reads as `attention`, a malformed extra is
     * dropped and the token kept, and an entry with a malformed key or no seq is dropped with [onDrop].
     */
    fun parseStates(text: String?, onDrop: (String) -> Unit = {}): Map<String, Value> {
        val o = runCatching { JSONObject(text ?: return emptyMap()) }.getOrElse { onDrop("not a JSON object"); return emptyMap() }
        val out = linkedMapOf<String, Value>()
        for (key in o.keys()) {
            val e = o.optJSONObject(key)
            val seq = (e?.opt("seq") as? Number)?.toLong()
            when {
                !isName(key) -> { onDrop("state ${key.take(40)} dropped: not a key"); continue }
                e == null || seq == null -> { onDrop("state $key dropped: no seq"); continue }
            }
            val extra = (e!!.opt("extra") as? String)?.takeIf {
                isName(it).also { ok -> if (!ok) onDrop("state $key: extra dropped") }
            }
            out[key] = Value(Token.read(e.opt("token") as? String), extra, seq!!)
        }
        return out
    }

    val GET_SPEC = FunctionSpec(
        GET, VERSION, "Read state",
        "What a control surface shows on its keys now. Not for a person's request.",
        Effect.READ,
        Schema.Obj(mapOf(KEY_KEYS to Schema.Str(description = "JSON array of state keys; none for all"))),
        Schema.Obj(mapOf(KEY_STATES to Schema.Str(description = "JSON object: key to {token, extra, seq}"))),
    )

    val SUBSCRIBE_SPEC = FunctionSpec(
        SUBSCRIBE, VERSION, "Watch state",
        "Asks for a ping when these state keys change. Not for a person's request.",
        Effect.READ,
        Schema.Obj(mapOf(KEY_KEYS to Schema.Str(description = "JSON array of state keys; empty to stop")), setOf(KEY_KEYS)),
        Schema.Obj(mapOf(KEY_KEYS to Schema.Str(), KEY_SECONDS to Schema.Num())),
    )
}

/**
 * A provider's state keys (§15c), in memory: they describe what the process is doing now. [seq] starts
 * at [processStartMs] and rises by one with each change of a key, so it keeps rising across the
 * provider's restarts (ruling Q1), and stays monotonic within the process whatever the wall clock does,
 * because only the start time is read from it. Setting the same value again is no change: no new seq,
 * no ping. [onChange] hears each real change (the ping's trigger).
 */
class StateStore(
    val namespace: String,
    private val processStartMs: Long,
    private val onChange: (key: String, seq: Long) -> Unit = { _, _ -> },
) {
    private val values = linkedMapOf<String, State.Value>()

    /**
     * Declares [key] (or changes it). A key or extra outside [namespace], a malformed one, is refused
     * with [IllegalArgumentException]: the provider's bug, caught in its own tests.
     */
    fun set(key: String, token: State.Token, extra: String? = null) {
        require(State.isOwnName(namespace, key)) { "state key $key is not $namespace.<word> within ${State.NAME_MAX} characters" }
        require(extra == null || State.isOwnName(namespace, extra)) { "extra $extra is not $namespace.<word> within ${State.NAME_MAX} characters" }
        val seq: Long
        synchronized(this) {
            val old = values[key]
            if (old != null && old.token == token && old.extra == extra) return
            seq = maxOf(old?.seq ?: processStartMs, processStartMs) + 1
            values[key] = State.Value(token, extra, seq)
        }
        onChange(key, seq)
    }

    @Synchronized fun keys(): Set<String> = values.keys.toSet()

    /** The states of [keys] it has (all when null); unknown keys are left out. */
    @Synchronized fun get(keys: Collection<String>? = null): Map<String, State.Value> =
        if (keys == null) LinkedHashMap(values) else keys.mapNotNull { k -> values[k]?.let { k to it } }.toMap()
}

/** One subscriber: its package, the keys it watches, and when that ends. */
data class Subscription(val packageName: String, val keys: Set<String>, val expiresAtMs: Long)

/**
 * The provider's subscriptions (§15c), on disk so they survive its restart. A subscription ends when
 * it runs out, when the subscriber's package is removed, or when it subscribes again with an empty
 * list. A subscribe naming only keys the provider does not have stores nothing and leaves an existing
 * subscription alone (ruling Q3).
 */
class Subscriptions(
    private val storage: TextStorage,
    private val now: () -> Long = System::currentTimeMillis,
    /** How long a subscription lasts, at most [State.MAX_SECONDS]. */
    seconds: Long = State.MAX_SECONDS,
) {
    val seconds: Long = seconds.coerceIn(1, State.MAX_SECONDS)
    private val byPackage = linkedMapOf<String, Subscription>()

    init {
        runCatching { JSONArray(storage.read() ?: "[]") }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val pkg = o.optString("package").takeIf { it.isNotEmpty() } ?: continue
                val keys = o.optJSONArray("keys")?.let { k -> (0 until k.length()).mapNotNull { k.opt(it) as? String } }.orEmpty()
                // Never trust a stored expiry beyond the maximum from now.
                val expires = minOf(o.optLong("expires"), now() + State.MAX_SECONDS * 1000)
                if (keys.isNotEmpty()) byPackage[pkg] = Subscription(pkg, keys.toSet(), expires)
            }
        }
    }

    /**
     * [packageName] asks for [requested] of the provider's [known] keys. Returns the keys it now
     * watches (empty when it asked for none it has) and stores the subscription.
     */
    @Synchronized fun subscribe(packageName: String, requested: List<String>, known: Set<String>): List<String> {
        if (requested.isEmpty()) {
            if (byPackage.remove(packageName) != null) save()
            return emptyList()
        }
        val keys = requested.filter { it in known }
        if (keys.isEmpty()) return emptyList()
        byPackage[packageName] = Subscription(packageName, keys.toSet(), now() + seconds * 1000)
        save()
        return keys
    }

    /** Removes [packageName]'s subscription (its package was removed). */
    @Synchronized fun remove(packageName: String): Boolean {
        val removed = byPackage.remove(packageName) != null
        if (removed) save()
        return removed
    }

    /** The live subscriptions, dropping (and forgetting) any that ran out. */
    @Synchronized fun live(): List<Subscription> {
        val t = now()
        val expired = byPackage.values.filter { it.expiresAtMs <= t }.map { it.packageName }
        if (expired.isNotEmpty()) { expired.forEach(byPackage::remove); save() }
        return byPackage.values.toList()
    }

    /** Who watches any of [keys], and which of them each watches. */
    fun watchers(keys: Collection<String>): Map<String, List<String>> =
        live().mapNotNull { s -> keys.filter { it in s.keys }.takeIf { it.isNotEmpty() }?.let { s.packageName to it } }.toMap()

    private fun save() {
        storage.write(JSONArray(byPackage.values.map { s ->
            JSONObject().put("package", s.packageName).put("keys", JSONArray(s.keys.toList())).put("expires", s.expiresAtMs)
        }).toString())
    }
}

/**
 * The ping's timing (§15c): at most one ping per key every [State.PING_GAP_MS], carrying the latest.
 * The first change of a key goes at once; changes inside the gap wait for one trailing ping at its end,
 * with every key then due and the highest seq (ruling Q2). [send] gets one call per subscriber,
 * naming only the keys it watches; the provider does not wait for delivery.
 */
class Pinger(
    private val subscriptions: Subscriptions,
    private val now: () -> Long,
    /** Runs a task after a delay (a Handler on Android, a fake clock in tests). */
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val send: (subscriber: String, keys: List<String>, seq: Long) -> Unit,
) {
    private val lastSent = hashMapOf<String, Long>()
    private val pending = linkedMapOf<String, Long>()
    private var scheduledAt: Long? = null

    fun changed(key: String, seq: Long) {
        val due: Long
        synchronized(this) {
            pending[key] = maxOf(pending[key] ?: Long.MIN_VALUE, seq)
            due = nextDue()
        }
        if (due <= now()) flush() else arm(due)
    }

    /** When the earliest pending key may go. */
    private fun nextDue(): Long = pending.keys.minOf { (lastSent[it] ?: Long.MIN_VALUE / 2) + State.PING_GAP_MS }

    private fun arm(at: Long) {
        synchronized(this) {
            val armed = scheduledAt
            if (armed != null && armed <= at) return
            scheduledAt = at
        }
        schedule(maxOf(0, at - now())) {
            synchronized(this) { scheduledAt = null }
            flush()
        }
    }

    private fun flush() {
        val ready: Map<String, Long>
        val t = now()
        val rest: Long?
        synchronized(this) {
            ready = pending.filterKeys { (lastSent[it] ?: Long.MIN_VALUE / 2) + State.PING_GAP_MS <= t }
            ready.keys.forEach { pending.remove(it); lastSent[it] = t }
            rest = if (pending.isEmpty()) null else nextDue()
        }
        if (ready.isNotEmpty()) {
            for ((subscriber, keys) in subscriptions.watchers(ready.keys)) {
                send(subscriber, keys, keys.maxOf { ready.getValue(it) })
            }
        }
        rest?.let(::arm)
    }
}

/**
 * The provider's side of `state.get` and `state.subscribe` (§15c), for its `perform`: the store, the
 * subscriptions and the pinger together. The gate has already refused anyone but family.
 */
class StateProvider(val store: StateStore, val subscriptions: Subscriptions) {

    /** `state.get`: the named keys it has, or all of them. */
    fun get(arguments: JSONObject): Outcome {
        val keys = if (arguments.has(State.KEY_KEYS) && !arguments.isNull(State.KEY_KEYS)) {
            State.parseKeys(arguments.opt(State.KEY_KEYS) as? String)
                ?: return Outcome.BadArguments("keys must be a JSON array of state keys.")
        } else null
        return Outcome.Ok(JSONObject().put(State.KEY_STATES, State.statesToText(store.get(keys))).toString())
    }

    /** `state.subscribe` from [caller]. */
    fun subscribe(arguments: JSONObject, caller: Caller): Outcome {
        val requested = State.parseKeys(arguments.opt(State.KEY_KEYS) as? String)
            ?: return Outcome.BadArguments("keys must be a JSON array of state keys.")
        val keys = subscriptions.subscribe(caller.packageName, requested, store.keys())
        return Outcome.Ok(
            JSONObject().put(State.KEY_KEYS, State.keysToText(keys))
                .put(State.KEY_SECONDS, if (keys.isEmpty()) 0 else subscriptions.seconds).toString(),
        )
    }

    /** Answers either function, or null for one that is not state's. */
    fun perform(function: FunctionSpec, arguments: JSONObject, caller: Caller): Outcome? = when (function.id) {
        State.GET -> get(arguments)
        State.SUBSCRIBE -> subscribe(arguments, caller)
        else -> null
    }
}

/**
 * The subscriber's side (§15c): a ping only says "ask me". For a provider it tracks, [onPing] starts a
 * gated `state.get` ([read]) and [accept] keeps only states newer than the ones it holds, so a forged
 * ping costs one read and shows nothing false, and a late reply never undoes a newer one.
 *
 * The ping may come 10 times a second per key, `state.get` allows 120 a minute, so (ruling, 2026-11-07):
 * - At most ONE read in flight per provider. A ping during a read marks the provider dirty; when the
 *   read ends, one more read follows with every key asked for meanwhile. A flicker of N pings costs at
 *   most 2 reads, and the last read always reflects the latest state.
 * - A read that fails (rate limited, or anything else: [read] gives null) is retried after 1 s, then
 *   2 s, 4 s, capped at 30 s, until one succeeds or a new ping supersedes the wait (it reads at once,
 *   with the keys of both). The light never stays wrong without a retry pending.
 *
 * [run] runs a read off the main thread (an executor); [schedule] runs a task after a delay. Both are
 * injected so the tests drive them on a fake clock. [onChanged] hears the states that changed.
 */
class StateSubscriber(
    /** The providers this subscriber watches; a ping naming any other is ignored. */
    private val tracked: () -> Set<String>,
    /** A gated `state.get` on that provider for those keys, parsed; null when it failed. Blocking. */
    private val read: (provider: String, keys: List<String>) -> Map<String, State.Value>?,
    private val run: (task: () -> Unit) -> Unit,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val onChanged: (provider: String, states: Map<String, State.Value>) -> Unit = { _, _ -> },
) {
    private val held = hashMapOf<Pair<String, String>, State.Value>()

    /** One provider's reading: a read in flight, keys asked for meanwhile, a retry waiting. */
    private class Reading {
        var inFlight = false
        val dirty = linkedSetOf<String>()
        /** Keys a waiting retry will read, and which wait is current (an older one is void). */
        val retryKeys = linkedSetOf<String>()
        var retryGeneration = 0
        var retryWaiting = false
        var backoffMs = 0L
    }

    private val readings = hashMapOf<String, Reading>()

    /** What this subscriber holds for [provider]. */
    @Synchronized fun held(provider: String): Map<String, State.Value> =
        held.filterKeys { it.first == provider }.mapKeys { it.key.second }

    /** A ping arrived. Returns at once; the read, if any, runs through [run]. */
    fun onPing(provider: String?, keys: List<String>?) {
        if (provider == null || provider !in tracked()) return
        val wanted = keys.orEmpty().filter(State::isName)
        if (wanted.isEmpty()) return
        val start: List<String>
        synchronized(this) {
            val r = readings.getOrPut(provider) { Reading() }
            if (r.inFlight) { r.dirty += wanted; return }
            // A ping during a backoff supersedes the wait: read now, with the keys of both.
            val all = LinkedHashSet<String>(r.retryKeys).apply { addAll(wanted) }
            r.retryKeys.clear()
            r.retryWaiting = false
            r.retryGeneration++
            r.inFlight = true
            start = all.toList()
        }
        startRead(provider, start)
    }

    private fun startRead(provider: String, keys: List<String>) {
        run {
            val result = runCatching { read(provider, keys) }.getOrNull()
            finished(provider, keys, result)
        }
    }

    private fun finished(provider: String, keys: List<String>, result: Map<String, State.Value>?) {
        val changed = if (result != null) accept(provider, result) else emptyMap()
        var next: List<String>? = null
        var retry: Pair<Long, Int>? = null
        synchronized(this) {
            val r = readings.getOrPut(provider) { Reading() }
            if (result == null) {
                r.backoffMs = if (r.backoffMs == 0L) FIRST_RETRY_MS else minOf(r.backoffMs * 2, MAX_RETRY_MS)
                r.retryKeys.clear(); r.retryKeys += keys; r.retryKeys += r.dirty
                r.dirty.clear()
                r.inFlight = false
                r.retryWaiting = true
                retry = r.backoffMs to ++r.retryGeneration
            } else {
                r.backoffMs = 0
                if (r.dirty.isNotEmpty()) {
                    next = r.dirty.toList()
                    r.dirty.clear()
                } else {
                    r.inFlight = false
                }
            }
        }
        if (changed.isNotEmpty()) onChanged(provider, changed)
        next?.let { startRead(provider, it) }
        retry?.let { (delay, generation) -> schedule(delay) { retryFired(provider, generation) } }
    }

    private fun retryFired(provider: String, generation: Int) {
        val keys: List<String>
        synchronized(this) {
            val r = readings[provider] ?: return
            // Superseded by a ping (or forgotten): nothing to do.
            if (!r.retryWaiting || r.retryGeneration != generation || r.inFlight) return
            r.retryWaiting = false
            r.inFlight = true
            keys = r.retryKeys.toList()
            r.retryKeys.clear()
        }
        startRead(provider, keys)
    }

    /** Keeps each state only when its seq is newer than the one held; returns what changed. */
    @Synchronized fun accept(provider: String, states: Map<String, State.Value>): Map<String, State.Value> {
        val changed = linkedMapOf<String, State.Value>()
        for ((key, v) in states) {
            val old = held[provider to key]
            if (old != null && v.seq <= old.seq) continue
            held[provider to key] = v
            changed[key] = v
        }
        return changed
    }

    /** Forgets [provider] (its subscription ended or it was removed); a waiting retry is void. */
    @Synchronized fun forget(provider: String) {
        held.keys.removeAll { it.first == provider }
        readings.remove(provider)
    }

    companion object {
        const val FIRST_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 30_000L
    }
}

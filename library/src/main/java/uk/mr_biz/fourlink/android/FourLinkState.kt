// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.BroadcastOptions
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import uk.mr_biz.fourlink.FunctionSpec
import uk.mr_biz.fourlink.InvokeResult
import uk.mr_biz.fourlink.Origin
import uk.mr_biz.fourlink.Pinger
import uk.mr_biz.fourlink.SeqFloor
import uk.mr_biz.fourlink.StateRead
import uk.mr_biz.fourlink.State
import uk.mr_biz.fourlink.StateProvider
import uk.mr_biz.fourlink.StateStore
import uk.mr_biz.fourlink.StateSubscriber
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * A provider's state (§15c) on Android: one [StateStore] per process, whose changes ping the
 * subscribers by an EXPLICIT broadcast (`setPackage`), never a content URI change notification (any app
 * may observe a URI). Fire and forget. The provider lists [State.GET_SPEC] and [State.SUBSCRIBE_SPEC]
 * in its catalogue and hands those two calls to [provider] from its `perform`.
 */
class FourLinkState private constructor(context: Context, namespace: String) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val stores = FourLinkStores.of(app)
    private val subscriptions = stores.subscriptions

    private val pinger = Pinger(subscriptions, System::currentTimeMillis, { delay, task -> handler.postDelayed(task, delay) }) { subscriber, keys, seq ->
        send(subscriber, keys, seq, values = State.valuesFor(store.get(keys)))
    }

    /**
     * seq starts at this process's start, in wall-clock milliseconds (ruling Q1), and above the
     * stored floor (State v2 §3), so it rises across reboots whatever the clock says.
     */
    val store: StateStore = StateStore(namespace, processStartMs(), SeqFloor(stores.seqFloor, processStartMs())) { key, seq ->
        pinger.changed(key, seq, store.levelOnly(key, seq))
    }

    val provider = StateProvider(store, subscriptions)

    init {
        // State v2 §6: a subscription that runs out is announced, never silent.
        subscriptions.onEnded = { s -> send(s.packageName, s.keys.toList(), 0L, ended = true) }
        // State v2 §6: the birth. Once the app has had a moment to set its keys, every live subscriber
        // hears that this process started, with the values of all its keys.
        handler.postDelayed({
            for (s in subscriptions.live()) {
                val now = store.get(s.keys)
                send(s.packageName, s.keys.toList(), now.values.maxOfOrNull { it.seq } ?: 0L, values = State.valuesFor(now) ?: "{}", birth = true)
            }
        }, BIRTH_DELAY_MS)
    }

    /**
     * The ping: an EXPLICIT broadcast to one package (State v2 §4: the payload must never reach an
     * app that merely registers the action). From Android 14 the sender's identity goes with it, so
     * the subscriber can prove it ([StatePings]); below 14 the values still go and are not used.
     */
    private fun send(subscriber: String, keys: List<String>, seq: Long, values: String? = null, birth: Boolean = false, ended: Boolean = false) {
        runCatching {
            val intent = Intent(State.ACTION_STATE).setPackage(subscriber)
                .putExtra(State.EXTRA_PACKAGE, app.packageName)
                .putExtra(State.EXTRA_KEYS, keys.toTypedArray())
                .putExtra(State.EXTRA_SEQ, seq)
            values?.let { intent.putExtra(State.EXTRA_VALUES, it) }
            if (birth) intent.putExtra(State.EXTRA_BIRTH, true)
            if (ended) intent.putExtra(State.EXTRA_ENDED, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                app.sendBroadcast(intent, null, BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            } else {
                app.sendBroadcast(intent)
            }
        }.onFailure { Log.w(TAG, "state ping to $subscriber not sent: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "4Link"
        /** State v2 §6: the birth waits this long after the state is first made, for the app's keys. */
        private const val BIRTH_DELAY_MS = 2_000L
        @Volatile private var instance: FourLinkState? = null

        /** The process's one state, in [namespace] (the provider's prefix, e.g. "4dictate"). */
        fun of(context: Context, namespace: String): FourLinkState =
            instance ?: synchronized(this) { instance ?: FourLinkState(context, namespace).also { instance = it } }

        private fun processStartMs(): Long =
            System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
    }
}

/**
 * The subscriber's side (§15c): a receiver for [State.ACTION_STATE] that hands each ping to a
 * [StateSubscriber], which reads the provider's state through a gated `state.get` (at most one read
 * in flight per provider, failed reads retried with backoff) and calls its `onChanged`.
 *
 * **The sender is another app, so a context-registered receiver must be registered with
 * `Context.RECEIVER_EXPORTED`**, or Android 14+ never delivers the ping. [register] does that. A
 * receiver declared in the manifest needs `android:exported="true"`.
 *
 * Since Android 14 a frozen app's context-registered receivers get broadcasts only when it thaws, so a
 * surface that needs live state keeps a foreground service while it is shown (§15c).
 */
open class StatePingReceiver(private val subscriber: StateSubscriber) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != State.ACTION_STATE) return
        // Returns at once: any read runs on the subscriber's executor.
        StatePings.deliver(this, context, intent, subscriber)
    }

    /** Registers this receiver EXPORTED (the ping comes from another app). Unregister it when the surface goes. */
    fun register(context: Context) {
        context.registerReceiver(this, IntentFilter(State.ACTION_STATE), Context.RECEIVER_EXPORTED)
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val reads = Executors.newSingleThreadExecutor()

        /**
         * A [StateSubscriber] wired for Android: reads through [client] on one background thread, retries
         * timed on the main looper and run on that thread, [onChanged] called on that thread.
         */
        fun subscriber(
            client: FourLinkClient,
            tracked: () -> Set<String>,
            onChanged: (provider: String, states: Map<String, State.Value>) -> Unit,
            /** State v2 §6: the provider restarted; subscribe to it again and re-read its catalogue. */
            onBirth: (provider: String) -> Unit = {},
            /** State v2 §6: the provider ended these keys; subscribe again if still wanted. */
            onEnded: (provider: String, keys: List<String>) -> Unit = { _, _ -> },
        ): StateSubscriber = StateSubscriber(
            tracked = tracked,
            read = reader(client),
            run = { task -> reads.execute(task) },
            schedule = { delay, task -> main.postDelayed({ reads.execute(task) }, delay) },
            onChanged = onChanged,
            fetch = fetcher(client),
            birthHeard = onBirth,
            endedHeard = onEnded,
        )

        /** As [reader], saying whether a failure was `rate_limited` (State v2 §7). */
        fun fetcher(client: FourLinkClient): (String, List<String>) -> StateRead = { provider, keys ->
            when (val r = client.invoke(provider, State.GET_SPEC, JSONObject().put(State.KEY_KEYS, State.keysToText(keys)).toString(), Origin.MODEL)) {
                is InvokeResult.Ok -> StateRead.Got(State.parseStates(JSONObject(r.json).optString(State.KEY_STATES)) { Log.w("4Link", "state: $it") })
                else -> if (StatePings.isRateLimited(r)) StateRead.RateLimited else StateRead.Failed
            }
        }

        /**
         * §15g: a trigger's answer. [function] is the provider's `read` function its state named
         * ([State.triggerReads] resolves it in the catalogue); this is an ordinary gated, counted invoke
         * under that function's own rules, declared [Origin.MODEL] as the state reader's is. Call it off
         * the main thread, from `onChanged`.
         */
        fun triggerRead(client: FourLinkClient, provider: String, function: FunctionSpec): InvokeResult =
            client.invoke(provider, function, State.TRIGGER_READ_ARGUMENTS, Origin.MODEL)

        /** A [StateSubscriber]'s read through [client]: a gated `state.get`, parsed, or null on any failure. */
        fun reader(client: FourLinkClient): (String, List<String>) -> Map<String, State.Value>? = { provider, keys ->
            // §4c: no model chose this read; the surface's own machinery did, for what the user sees. Declared
            // "model", the reading that can never unlock anything kept for the user's own triggers.
            when (val r = client.invoke(provider, State.GET_SPEC, JSONObject().put(State.KEY_KEYS, State.keysToText(keys)).toString(), Origin.MODEL)) {
                is InvokeResult.Ok -> State.parseStates(JSONObject(r.json).optString(State.KEY_STATES)) { Log.w("4Link", "state: $it") }
                else -> null
            }
        }
    }
}

/**
 * State v2 §4: reading a ping. Its `values` are used ONLY when the sender is proven to be the provider
 * it names: from Android 14 the provider shares its identity ([BroadcastOptions.setShareIdentityEnabled])
 * and [BroadcastReceiver.getSentFromUid] must be that package's uid. Otherwise, or below 14, the ping
 * is treated as v1: one gated read, so a forged ping still shows nothing false. The subscriber's own
 * `tracked` list is the family check, as for any ping.
 */
object StatePings {
    /** Hands [intent] to [subscriber]: values when proven, birth and ended, or a v1 ping. Call from onReceive. */
    fun deliver(receiver: BroadcastReceiver, context: Context, intent: Intent, subscriber: StateSubscriber) {
        val provider = intent.getStringExtra(State.EXTRA_PACKAGE)
        val keys = intent.getStringArrayExtra(State.EXTRA_KEYS)?.toList()
        val proven = provider != null && proven(receiver, context, provider)
        if (intent.getBooleanExtra(State.EXTRA_ENDED, false)) {
            // An ended ping only makes the subscriber subscribe again, which it may always do; a forged one costs a subscribe.
            subscriber.onEnded(provider, keys)
            return
        }
        val values = if (proven) intent.getStringExtra(State.EXTRA_VALUES)?.let { State.parseStates(it) { w -> Log.w("4Link", "state: $w") } } else null
        when {
            intent.getBooleanExtra(State.EXTRA_BIRTH, false) && values != null -> subscriber.onBirth(provider, values)
            values != null -> subscriber.onValues(provider, keys, values)
            else -> subscriber.onPing(provider, keys)
        }
    }

    /** Whether this broadcast came from [provider]'s own uid. Needs Android 14; false below it. */
    fun proven(receiver: BroadcastReceiver, context: Context, provider: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val sent = runCatching { receiver.sentFromUid }.getOrNull() ?: return false
        if (sent == Process.INVALID_UID) return false
        val uid = runCatching { context.packageManager.getPackageUid(provider, 0) }.getOrNull() ?: return false
        return sent == uid
    }

    /** Whether a failed invoke was the gate's `rate_limited`. */
    fun isRateLimited(r: InvokeResult): Boolean =
        (r as? InvokeResult.Error)?.code == uk.mr_biz.fourlink.ErrorCode.RATE_LIMITED
}

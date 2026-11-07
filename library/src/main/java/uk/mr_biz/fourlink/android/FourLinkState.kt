// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import uk.mr_biz.fourlink.FunctionSpec
import uk.mr_biz.fourlink.InvokeResult
import uk.mr_biz.fourlink.Origin
import uk.mr_biz.fourlink.Pinger
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
    private val subscriptions = FourLinkStores.of(app).subscriptions

    private val pinger = Pinger(subscriptions, System::currentTimeMillis, { delay, task -> handler.postDelayed(task, delay) }) { subscriber, keys, seq ->
        runCatching {
            app.sendBroadcast(
                Intent(State.ACTION_STATE).setPackage(subscriber)
                    .putExtra(State.EXTRA_PACKAGE, app.packageName)
                    .putExtra(State.EXTRA_KEYS, keys.toTypedArray())
                    .putExtra(State.EXTRA_SEQ, seq),
            )
        }.onFailure { Log.w(TAG, "state ping to $subscriber not sent: ${it.javaClass.simpleName}") }
    }

    /** seq starts at this process's start, in wall-clock milliseconds (ruling Q1). */
    val store = StateStore(namespace, processStartMs()) { key, seq -> pinger.changed(key, seq) }

    val provider = StateProvider(store, subscriptions)

    companion object {
        private const val TAG = "4Link"
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
        // Returns at once: the read runs on the subscriber's executor.
        subscriber.onPing(intent.getStringExtra(State.EXTRA_PACKAGE), intent.getStringArrayExtra(State.EXTRA_KEYS)?.toList())
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
        ): StateSubscriber = StateSubscriber(
            tracked = tracked,
            read = reader(client),
            run = { task -> reads.execute(task) },
            schedule = { delay, task -> main.postDelayed({ reads.execute(task) }, delay) },
            onChanged = onChanged,
        )

        /**
         * §15g: a trigger's answer. [function] is the provider's `read` function its state named
         * ([State.triggerReads] resolves it in the catalogue); this is an ordinary gated, counted invoke
         * under that function's own rules, declared [Origin.MODEL] as the state reader's is. Call it off
         * the main thread, from `onChanged`.
         */
        fun triggerRead(client: FourLinkClient, provider: String, function: FunctionSpec, argumentsJson: String = "{}"): InvokeResult =
            client.invoke(provider, function, argumentsJson, Origin.MODEL)

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

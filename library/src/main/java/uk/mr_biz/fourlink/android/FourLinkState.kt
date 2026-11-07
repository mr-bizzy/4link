// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import uk.mr_biz.fourlink.InvokeResult
import uk.mr_biz.fourlink.Pinger
import uk.mr_biz.fourlink.State
import uk.mr_biz.fourlink.StateProvider
import uk.mr_biz.fourlink.StateStore
import uk.mr_biz.fourlink.StateSubscriber
import org.json.JSONObject

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
 * The subscriber's side (§15c): register this (context-registered, or in the manifest) for
 * [State.ACTION_STATE]. A ping only says "ask me": [subscriber] calls a gated `state.get` on the
 * provider it names (only one it tracks), and keeps only states newer than it holds; [onChanged]
 * hears what changed. The read goes off the main thread, as every 4Link call must.
 *
 * Since Android 14 a frozen app's context-registered receivers get broadcasts only when it thaws, so a
 * surface that needs live state keeps a foreground service while it is shown (§15c).
 */
open class StatePingReceiver(
    private val subscriber: StateSubscriber,
    private val onChanged: (provider: String, states: Map<String, State.Value>) -> Unit,
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != State.ACTION_STATE) return
        val provider = intent.getStringExtra(State.EXTRA_PACKAGE)
        val keys = intent.getStringArrayExtra(State.EXTRA_KEYS)?.toList()
        val pending = goAsync()
        Thread {
            try {
                val changed = subscriber.onPing(provider, keys)
                if (changed.isNotEmpty() && provider != null) onChanged(provider, changed)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** A [StateSubscriber]'s read through [client]: a gated `state.get`, parsed, or null on any failure. */
        fun reader(client: FourLinkClient): (String, List<String>) -> Map<String, State.Value>? = { provider, keys ->
            when (val r = client.invoke(provider, State.GET_SPEC, JSONObject().put(State.KEY_KEYS, State.keysToText(keys)).toString())) {
                is InvokeResult.Ok -> State.parseStates(JSONObject(r.json).optString(State.KEY_STATES)) { Log.w("4Link", "state: $it") }
                else -> null
            }
        }
    }
}


// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import uk.mr_biz.fourlink.CallerIdentity
import uk.mr_biz.fourlink.Catalogue
import uk.mr_biz.fourlink.ClientCore
import uk.mr_biz.fourlink.Effect
import uk.mr_biz.fourlink.FourLink
import uk.mr_biz.fourlink.FunctionSpec
import uk.mr_biz.fourlink.HelloReply
import uk.mr_biz.fourlink.InvokeResult
import uk.mr_biz.fourlink.Retry

/**
 * The caller's side (§4, §12): find members, say hello, fetch catalogues,
 * invoke. Every call goes through `ContentResolver.call` to the member's
 * `<package>.4link` authority and nowhere else (P1).
 *
 * The caller's manifest needs
 * `<queries><intent><action android:name="uk.mr_biz.4link.action.PAIR" /></intent></queries>`
 * for [discover] to see anything on Android 11+.
 *
 * Never call from the main thread: a provider may do real work.
 *
 * §4b: a call that fails at the binder is retried ONCE, immediately, by [Retry]'s rule — any
 * failure for a read, only an unreached provider for anything else. No provider client is kept
 * between calls, so there is no idle cache to expire.
 */
class FourLinkClient(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val pm = app.packageManager

    /** An installed 4Link member: its package, label and signer digest. */
    data class Member(val packageName: String, val label: String, val certDigest: String)

    /** Every installed app that declares the pairing activity (§4 discovery), ourselves excluded. */
    fun discover(): List<Member> {
        val signers = PackageSigners(pm)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(Intent(FourLink.ACTION_PAIR), PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(Intent(FourLink.ACTION_PAIR), 0)
            }
        }.getOrNull().orEmpty()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != app.packageName }
            .mapNotNull { pkg ->
                val digest = signers.signerDigests(pkg)?.let(CallerIdentity::identityDigest) ?: return@mapNotNull null
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                Member(pkg, label, digest)
            }
    }

    fun hello(packageName: String): HelloReply? =
        ClientCore.hello(call(packageName, FourLink.METHOD_HELLO, null, null)?.toMap())

    /** The catalogue the provider lets us see, or the error, or (null, null) when unreachable. */
    fun catalogue(packageName: String): Pair<Catalogue.Parsed?, InvokeResult.Error?> =
        ClientCore.catalogue(call(packageName, FourLink.METHOD_CATALOGUE, null, null)?.toMap())

    /**
     * Invokes [function] as its catalogue declared it. Its EFFECT decides whether a binder failure is
     * retried (§4b, [Retry]): a `read` is, anything else only if the provider was never reached.
     *
     * **A HELLO GOES FIRST BEFORE ANYTHING THAT IS NOT A READ, so the change meets a live process and
     * never has to be retried.** It looks like a redundant round trip. It is not: a member the freezer
     * has frozen can be KILLED by the first call that reaches it (§4b), and a change cannot be retried
     * because it may have run. The hello is a read, so it carries the retry: if the member was frozen,
     * the hello absorbs the kill, its retry restarts the member, and the change then goes to a live
     * process. **If the hello fails even after its retry, the change is NOT sent** and the hello's
     * failure is returned: a change is never attempted into a process just failed to reach. A read
     * gets no hello: it already retries itself, and a hello would be a second call for nothing.
     * (The 4Dictate PM's condition, 2026-10-09.) The id-only form below sends no hello, unchanged.
     */
    fun invoke(packageName: String, function: FunctionSpec, argumentsJson: String): InvokeResult {
        if (function.effect != Effect.READ && call(packageName, FourLink.METHOD_HELLO, null, null) == null) {
            return InvokeResult.Unreachable("${labelOf(packageName)} did not answer.")
        }
        return invoke(packageName, function.id, argumentsJson, function.major.toString(), function.effect)
    }

    /**
     * The older form, without the function's declaration: its effect is unknown, so it is treated as
     * one that changes something and a binder failure is NOT retried. Prefer the [FunctionSpec] form.
     */
    fun invoke(packageName: String, functionId: String, argumentsJson: String, versionRead: String): InvokeResult =
        invoke(packageName, functionId, argumentsJson, versionRead, Effect.CHANGE)

    private fun invoke(packageName: String, functionId: String, argumentsJson: String, versionRead: String, effect: Effect): InvokeResult {
        val extras = Bundle().apply {
            putString(FourLink.KEY_JSON, argumentsJson)
            putString(FourLink.KEY_FUNCTION_VERSION, versionRead)
        }
        val reply = call(packageName, FourLink.METHOD_INVOKE, functionId, extras, effect)
            ?: return InvokeResult.Unreachable("${labelOf(packageName)} did not answer.")
        return ClientCore.invoke(reply.toMap())
    }

    /**
     * Asks the provider app to show its pairing screen (§6). The user decides
     * there. Sent with startActivityForResult from an Activity of ours, which
     * is the one way the platform tells the provider WHICH app asked
     * (`Activity.getCallingPackage`); a plain startActivity would arrive
     * anonymous and be refused.
     */
    fun requestPairing(from: android.app.Activity, packageName: String, functionIds: Collection<String>): Boolean = runCatching {
        @Suppress("DEPRECATION")
        from.startActivityForResult(
            Intent(FourLink.ACTION_PAIR)
                .setPackage(packageName)
                .putExtra(FourLink.EXTRA_FUNCTIONS, functionIds.toTypedArray()),
            PAIR_REQUEST_CODE,
        )
        true
    }.getOrElse {
        Log.w(TAG, "pairing request to $packageName failed", it)
        false
    }

    /**
     * One call, and at most ONE retry (§4b): by [Retry.once], from what failed and [effect] (null for
     * hello and catalogue, which are reads). The retry acquires the provider afresh and IMMEDIATELY:
     * it is the fresh acquire that starts a killed member, not a wait. MEASURED, through this code,
     * on an API 37 emulator, 2026-10-09: the first catalogue killed a frozen 4Zones
     * (DeadObjectException), the immediate retry started a fresh one that answered 580 ms later, and
     * the capture succeeded in 1,349 ms end to end. No delay is needed, and none is added.
     */
    private fun call(packageName: String, method: String, arg: String?, extras: Bundle?, effect: Effect? = null): Bundle? {
        when (val first = attempt(packageName, method, arg, extras)) {
            is Attempt.Answered -> return first.reply
            is Attempt.Failed -> {
                if (!Retry.once(first.failure, effect)) return null
                // The provider package and the exception CLASS only; never a message (§4b).
                Log.w(TAG, "$method on $packageName: retrying once after ${first.exceptionClass}")
            }
        }
        return when (val second = attempt(packageName, method, arg, extras)) {
            is Attempt.Answered -> second.reply
            is Attempt.Failed -> null
        }
    }

    private sealed interface Attempt {
        class Answered(val reply: Bundle?) : Attempt
        class Failed(val failure: Retry.Failure, val exceptionClass: String) : Attempt
    }

    /**
     * One transaction through an UNSTABLE client, acquired for this call and closed after it, so the
     * failure's class is seen here (ContentResolver.call swallows it into null) and a dying provider
     * cannot take this process with it. Nothing is cached between calls.
     */
    private fun attempt(packageName: String, method: String, arg: String?, extras: Bundle?): Attempt {
        val authority = FourLink.authorityOf(packageName)
        val client = try {
            resolver.acquireUnstableContentProviderClient(authority)
        } catch (e: Exception) {
            Log.w(TAG, "$method on $packageName failed: ${e.javaClass.simpleName}")
            return Attempt.Failed(Retry.Failure.NOT_REACHED, e.javaClass.simpleName)
        } ?: run {
            // No such provider: the app is not installed, or is not a member. A fact, not a failure:
            // asking again would only ask again (measured: a retry here found nothing new).
            Log.w(TAG, "$method on $packageName failed: no provider")
            return Attempt.Answered(null)
        }
        return try {
            Attempt.Answered(client.call(authority, method, arg, extras))
        } catch (e: RemoteException) {
            // DeadObjectException is a RemoteException. See Retry: it does NOT prove the call never ran.
            Log.w(TAG, "$method on $packageName failed: ${e.javaClass.simpleName}")
            Attempt.Failed(Retry.Failure.BINDER, e.javaClass.simpleName)
        } catch (e: Exception) {
            // IllegalArgumentException, SecurityException: not reachable, and said in a sentence.
            // Not a binder failure, so never retried. The class is the diagnosis; the message is
            // text from another process and is not logged.
            Log.w(TAG, "$method on $packageName failed: ${e.javaClass.simpleName}")
            Attempt.Answered(null)
        } finally {
            client.close()
        }
    }

    private fun labelOf(packageName: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)

    private fun Bundle.toMap(): Map<String, Any?> = keySet().associateWith { @Suppress("DEPRECATION") get(it) }

    companion object {
        private const val TAG = "4Link"
        /** The request code a pairing request goes out under; the result itself is not used (§6: ask `hello` afterwards). */
        const val PAIR_REQUEST_CODE = 0x4C1
    }
}

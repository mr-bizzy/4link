package uk.mr_biz.fourlink.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import uk.mr_biz.fourlink.CallerIdentity
import uk.mr_biz.fourlink.Catalogue
import uk.mr_biz.fourlink.ClientCore
import uk.mr_biz.fourlink.FourLink
import uk.mr_biz.fourlink.HelloReply
import uk.mr_biz.fourlink.InvokeResult

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
            pm.queryIntentActivities(Intent(FourLink.ACTION_PAIR), PackageManager.ResolveInfoFlags.of(0))
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

    fun invoke(packageName: String, functionId: String, argumentsJson: String, versionRead: String): InvokeResult {
        val extras = Bundle().apply {
            putString(FourLink.KEY_JSON, argumentsJson)
            putString(FourLink.KEY_FUNCTION_VERSION, versionRead)
        }
        val reply = call(packageName, FourLink.METHOD_INVOKE, functionId, extras)
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

    private fun call(packageName: String, method: String, arg: String?, extras: Bundle?): Bundle? = try {
        resolver.call(FourLink.authorityOf(packageName), method, arg, extras)
    } catch (e: Exception) {
        // IllegalArgumentException: no such provider. SecurityException: not
        // exported. Either way: not reachable, and said in a sentence.
        Log.w(TAG, "$method on $packageName failed: ${e.javaClass.simpleName}: ${e.message}")
        null
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

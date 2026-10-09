// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import uk.mr_biz.fourlink.Caller
import uk.mr_biz.fourlink.CallerIdentity
import uk.mr_biz.fourlink.Catalogue
import uk.mr_biz.fourlink.Effect
import uk.mr_biz.fourlink.FourLink
import uk.mr_biz.fourlink.FunctionSpec
import uk.mr_biz.fourlink.Pairing
import uk.mr_biz.fourlink.PairingStore

/**
 * What a pairing screen needs (§6), read from the PAIR intent and the
 * platform: who really sent it (the calling package AND its certificate),
 * and which of our functions it asked for. The screen itself belongs to the
 * app; this only makes sure it shows the truth.
 */
data class PairingRequest(
    val caller: Caller,
    val label: String,
    val icon: Drawable?,
    /** The functions of ours it asked for, in catalogue order. */
    val requested: List<FunctionSpec>,
) {
    /** Grouped for the screen: Read, Change, Delete. */
    val byEffect: Map<Effect, List<FunctionSpec>> get() = Effect.entries.associateWith { e -> requested.filter { it.effect == e } }

    /** The default ticks: everything but Delete (§6). */
    fun defaultTicked(): Set<String> = requested.filter { it.effect != Effect.DELETE }.map { it.id }.toSet()

    /** Writes the grant. An empty [granted] is a refusal and stores nothing. */
    fun approve(store: PairingStore, granted: Set<String>, nowMs: Long = System.currentTimeMillis()) {
        val ids = requested.map { it.id }.filter { it in granted }.toSet()
        if (ids.isEmpty()) { store.remove(caller.packageName); return }
        // Merged with an earlier grant: pairing again for one more function
        // must not silently take away the others.
        val before = store.get(caller.packageName)?.takeIf { it.certDigest == caller.certDigest }?.granted.orEmpty()
        store.put(Pairing(caller.packageName, caller.certDigest, before + ids, nowMs))
    }

    companion object {
        /**
         * Reads the request, or null when the intent is not a pairing request
         * from an identifiable app. The sender is `Activity.getCallingPackage`,
         * which the PLATFORM sets from the launching uid when the sender used
         * startActivityForResult, and leaves null otherwise. A null sender is
         * refused rather than guessed from an extra: an extra is whatever the
         * sender typed, and a pairing screen that names the wrong app is worse
         * than none.
         */
        fun from(activity: Activity, intent: Intent, catalogue: Catalogue): PairingRequest? {
            if (intent.action != FourLink.ACTION_PAIR) return null
            val pm = activity.packageManager
            val callingPackage = activity.callingPackage ?: return null
            val digests = PackageSigners(pm).signerDigests(callingPackage) ?: return null
            val digest = CallerIdentity.identityDigest(digests) ?: return null
            val uid = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageUid(callingPackage, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageUid(callingPackage, 0)
                }
            }.getOrDefault(-1)
            val wanted = intent.getStringArrayExtra(FourLink.EXTRA_FUNCTIONS).orEmpty().toSet()
            val effects = wanted.mapNotNull(Effect::fromWire).toSet()
            val requested = catalogue.functions.filter { it.id in wanted || it.effect in effects }
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(callingPackage, 0)).toString() }.getOrDefault(callingPackage)
            val icon = runCatching { pm.getApplicationIcon(callingPackage) }.getOrNull()
            return PairingRequest(Caller(uid, callingPackage, digest), label, icon, requested)
        }
    }
}

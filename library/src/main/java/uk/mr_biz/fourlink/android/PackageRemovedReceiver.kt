package uk.mr_biz.fourlink.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Uninstalling a caller removes its pairing (§6), and uninstalling a provider
 * removes its approval (§7). Declare in the member's manifest:
 *
 * ```xml
 * <receiver android:name="uk.mr_biz.fourlink.android.PackageRemovedReceiver" android:exported="true">
 *   <intent-filter>
 *     <action android:name="android.intent.action.PACKAGE_FULLY_REMOVED" />
 *     <data android:scheme="package" />
 *   </intent-filter>
 * </receiver>
 * ```
 *
 * Belt and braces: the gate also drops a pairing whose package is gone the
 * next time that package is looked up, should this broadcast not arrive.
 */
class PackageRemovedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val stores = FourLinkStores.of(context)
        val droppedPairing = stores.pairings.remove(pkg)
        val droppedApproval = stores.approved.remove(pkg)
        if (droppedPairing || droppedApproval) Log.i("4Link", "$pkg uninstalled: pairing removed")
    }
}

// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink.android

import android.content.pm.PackageManager
import uk.mr_biz.fourlink.CallerIdentity
import uk.mr_biz.fourlink.SignerLookup

/** [SignerLookup] over the platform's PackageManager (§5). */
class PackageSigners(private val pm: PackageManager) : SignerLookup {

    override fun packagesOf(uid: Int): List<String> =
        runCatching { pm.getPackagesForUid(uid)?.toList() }.getOrNull().orEmpty()

    override fun signerDigests(packageName: String): List<String>? {
        val info = runCatching {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        }.getOrNull() ?: return null
        val signing = info.signingInfo ?: return emptyList()
        // The CURRENT signer(s), not the history: an app that rotated its key
        // is identified by the key it has now, and a pairing made under the
        // old one is void (§6).
        val certs = if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory?.lastOrNull()?.let { arrayOf(it) } ?: signing.apkContentsSigners
        return certs.orEmpty().map { CallerIdentity.sha256Hex(it.toByteArray()) }
    }
}

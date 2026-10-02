package uk.mr_biz.fourlink

import java.security.MessageDigest

/**
 * Who is calling (§5): the uid the kernel reports, resolved to a package and
 * the SHA-256 digest of its current signing certificate. The package name
 * alone is never an identity.
 */
data class Caller(val uid: Int, val packageName: String, val certDigest: String) {
    /** The short form a person compares: the first 16 hex digits in fours. */
    val fingerprint: String get() = fingerprintOf(certDigest)

    companion object {
        fun fingerprintOf(digest: String): String =
            digest.take(16).chunked(4).joinToString(" ").uppercase()
    }
}

/** What the platform knows about packages; implemented over PackageManager, faked in tests. */
interface SignerLookup {
    /** Packages sharing [uid]; empty when the uid is nobody's (the adb shell, a dead process). */
    fun packagesOf(uid: Int): List<String>

    /**
     * SHA-256 hex digests of the package's CURRENT signing certificates, or
     * null when the package is not installed. One entry for every app that
     * matters; an app signed by several keys lists all of them.
     */
    fun signerDigests(packageName: String): List<String>?

    fun isInstalled(packageName: String): Boolean = signerDigests(packageName) != null
}

object CallerIdentity {

    /**
     * The caller behind a uid, or null when it cannot be identified. A uid
     * shared by several packages takes the first by name — the signature is
     * the same for all of them, which is what sharing a uid requires.
     */
    fun identify(uid: Int, lookup: SignerLookup): Caller? {
        val packageName = lookup.packagesOf(uid).sorted().firstOrNull() ?: return null
        val digest = identityDigest(lookup.signerDigests(packageName) ?: return null) ?: return null
        return Caller(uid, packageName, digest)
    }

    /** One digest for one app: its signer's, or every signer's joined when there are several. */
    fun identityDigest(signers: List<String>): String? =
        signers.map { it.lowercase() }.sorted().takeIf { it.isNotEmpty() }?.joinToString("+")

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * The certificates that make a caller family (§5a): a LIST of digests from
 * the library's `fourlink_family_digests` resource — the release certificate
 * always, the workstation debug certificate in debug builds only. Not "the
 * same signer as me": a release build must never widen the family by being
 * built somewhere else.
 */
class Family(digests: Collection<String>) {
    val digests: Set<String> = digests.map { it.lowercase() }.toSet()

    fun isFamily(caller: Caller): Boolean = caller.certDigest.lowercase() in digests
}

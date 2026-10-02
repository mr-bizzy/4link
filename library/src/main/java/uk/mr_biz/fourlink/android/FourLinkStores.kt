package uk.mr_biz.fourlink.android

import android.content.Context
import uk.mr_biz.fourlink.AuditLog
import uk.mr_biz.fourlink.CallerIdentity
import uk.mr_biz.fourlink.Family
import uk.mr_biz.fourlink.PairingStore
import uk.mr_biz.fourlink.ProviderGate
import uk.mr_biz.fourlink.TextStorage
import java.io.File

/** A store's file, in the app's private files directory. */
class FileStorage(private val file: File) : TextStorage {
    override fun read(): String? = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    override fun write(text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) file.writeText(text)
        }
    }
}

/**
 * One set of stores per process, shared by the provider, the pairing screen
 * and the Settings list, so a revocation on screen is seen by the very next
 * call without a reload.
 */
class FourLinkStores private constructor(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "4link")

    /** Apps allowed to call THIS app (§6). */
    val pairings: PairingStore by lazy { PairingStore(FileStorage(File(dir, "pairings.json"))) }

    /** Non-family providers THIS app may call (§7: "Apps 4Dictate may use"). */
    val approved: PairingStore by lazy { PairingStore(FileStorage(File(dir, "approved.json"))) }

    val audit: AuditLog by lazy { AuditLog(FileStorage(File(dir, "audit.jsonl"))) }

    val signers: PackageSigners by lazy { PackageSigners(app.packageManager) }

    /** This app's own signing certificate, for showing on screen. */
    val ownDigest: String? by lazy {
        signers.signerDigests(app.packageName)?.let(CallerIdentity::identityDigest)
    }

    /** The family list (§5a), as this BUILD TYPE resolves the resource. */
    val family: Family by lazy { Family(app.resources.getStringArray(uk.mr_biz.fourlink.R.array.fourlink_family_digests).toList()) }

    val gate: ProviderGate by lazy { ProviderGate(family, pairings, signers, audit = audit) }

    companion object {
        @Volatile private var instance: FourLinkStores? = null

        fun of(context: Context): FourLinkStores =
            instance ?: synchronized(this) { instance ?: FourLinkStores(context).also { instance = it } }
    }
}

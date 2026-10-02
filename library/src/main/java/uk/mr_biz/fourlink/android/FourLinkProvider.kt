package uk.mr_biz.fourlink.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import uk.mr_biz.fourlink.Caller
import uk.mr_biz.fourlink.CallerIdentity
import uk.mr_biz.fourlink.Catalogue
import uk.mr_biz.fourlink.FourLink
import uk.mr_biz.fourlink.FunctionSpec
import uk.mr_biz.fourlink.Outcome
import uk.mr_biz.fourlink.ProviderCore
import uk.mr_biz.fourlink.Reply

/**
 * The exported door of a member app (§4): authority `<package>.4link`, used
 * only through [call]. A subclass gives its name, its catalogue and what each
 * function does; everything about WHO may call is decided by [ProviderCore]
 * and the gate, which this class only hands a [Caller] to.
 *
 * Declare it in the manifest as
 * `<provider android:name=".FourLinkDoor" android:authorities="${applicationId}.4link" android:exported="true" />`.
 *
 * No accessibility code lives in this library (P1), and none may be added.
 */
abstract class FourLinkProvider : ContentProvider() {

    abstract fun appName(): String
    abstract fun catalogue(): Catalogue

    /**
     * Runs one function for an allowed caller with valid arguments. Called on
     * a binder thread; post to the main thread and wait if the work needs it.
     */
    abstract fun perform(function: FunctionSpec, arguments: JSONObject, caller: Caller): Outcome

    private lateinit var core: ProviderCore
    private lateinit var stores: FourLinkStores

    override fun onCreate(): Boolean {
        stores = FourLinkStores.of(requireNotNull(context))
        core = ProviderCore(appName(), ::catalogue, stores.gate, ::perform)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val caller = CallerIdentity.identify(Binder.getCallingUid(), stores.signers)
        val reply = when (method) {
            FourLink.METHOD_HELLO -> core.hello(caller)
            FourLink.METHOD_CATALOGUE -> core.catalogue(caller)
            FourLink.METHOD_INVOKE -> core.invoke(
                caller, arg, extras?.getString(FourLink.KEY_JSON), extras?.getString(FourLink.KEY_FUNCTION_VERSION),
            )
            else -> return null
        }
        Log.i(TAG, "$method ${arg ?: ""} from ${caller?.packageName ?: "uid ${Binder.getCallingUid()}"} -> ${summary(reply)}")
        return reply.toBundle()
    }

    private fun summary(reply: Reply): String = when (reply) {
        is Reply.Hello -> reply.standing.wire
        is Reply.Json -> "ok"
        is Reply.Error -> reply.code.wire
    }

    // Nothing else is served (§4): a Cursor interface would be a second door.
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        private const val TAG = "4Link"

        fun Reply.toBundle(): Bundle = Bundle().apply {
            when (val r = this@toBundle) {
                is Reply.Hello -> {
                    putString(FourLink.KEY_APP, r.app)
                    putString(FourLink.KEY_VERSION, r.version)
                    putString(FourLink.KEY_CALLER, r.standing.wire)
                }
                is Reply.Json -> {
                    putBoolean(FourLink.KEY_OK, true)
                    putString(FourLink.KEY_JSON, r.json)
                }
                is Reply.Error -> {
                    putBoolean(FourLink.KEY_OK, false)
                    putString(FourLink.KEY_ERROR, r.code.wire)
                    putString(FourLink.KEY_MESSAGE, r.message)
                }
            }
        }
    }
}

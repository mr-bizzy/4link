// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

/** What came back from a provider, read by VALUE (§4, §11). */
sealed interface InvokeResult {
    /**
     * [frame] is the reply's frame (§4a) when the function declares one: on Android a read-only
     * `android.os.SharedMemory`, laid out as [json] says. The caller owns it, closes it, and MUST NOT
     * keep it beyond the call without its user's own action (§4a).
     */
    data class Ok(val json: String, val frame: Any? = null) : InvokeResult
    /**
     * §11b. [fixable] is the branch most callers need: true, show [message] (the user can fix it in
     * settings); false or null, fall back quietly. [reason] is the provider's stable token, for a
     * caller that needs more. NEVER match [message] instead: it is for a person and may be reworded.
     */
    data class Error(
        val code: ErrorCode,
        val message: String,
        val suggestion: Suggestion? = null,
        val reason: String? = null,
        val fixable: Boolean? = null,
    ) : InvokeResult
    /** The provider was never reached; [why] is this side's own sentence. */
    data class Unreachable(val why: String) : InvokeResult
}

data class HelloReply(val app: String, val version: String, val standing: Standing) {
    /** A provider speaking a protocol major we do not know is left alone (§10). */
    val usable: Boolean get() = version.substringBefore('.').toIntOrNull() == FourLink.MAJOR
}

/**
 * The client's reading of Bundles, as maps so it is testable without Android.
 * Unknown keys are ignored; missing ones mean the reply is not one of ours.
 */
object ClientCore {

    fun hello(values: Map<String, Any?>?): HelloReply? {
        val app = values?.get(FourLink.KEY_APP) as? String ?: return null
        val version = values[FourLink.KEY_VERSION] as? String ?: return null
        return HelloReply(app, version, Standing.fromWire(values[FourLink.KEY_CALLER] as? String))
    }

    /** `catalogue`: the parsed catalogue, or the error it answered instead. */
    fun catalogue(values: Map<String, Any?>?): Pair<Catalogue.Parsed?, InvokeResult.Error?> {
        error(values)?.let { return null to it }
        val json = values?.get(FourLink.KEY_JSON) as? String ?: return null to null
        return Catalogue.parse(json) to null
    }

    fun invoke(values: Map<String, Any?>?): InvokeResult {
        if (values == null) return InvokeResult.Unreachable("The app gave no answer.")
        error(values)?.let { return it }
        val ok = values[FourLink.KEY_OK] as? Boolean ?: false
        val json = values[FourLink.KEY_JSON] as? String
        return if (ok) InvokeResult.Ok(json ?: "{}", values[FourLink.KEY_FRAME])
        else InvokeResult.Unreachable("The app's answer could not be read.")
    }

    private fun error(values: Map<String, Any?>?): InvokeResult.Error? {
        val code = ErrorCode.fromWire(values?.get(FourLink.KEY_ERROR) as? String) ?: return null
        // A suggestion is read only on bad_arguments (§11a); on any other code it is ignored.
        val suggestion = if (code == ErrorCode.BAD_ARGUMENTS) Suggestion.parse(values?.get(FourLink.KEY_SUGGESTION) as? String) else null
        // §11b: read on refused and failed only; a reason only when well formed (core or prefixed,
        // known here or not, so a core token added later still reads).
        val own = code == ErrorCode.REFUSED || code == ErrorCode.FAILED
        val reason = if (own) (values?.get(FourLink.KEY_REASON) as? String)?.takeIf(FourLink::isReason) else null
        val fixable = if (own) values?.get(FourLink.KEY_FIXABLE) as? Boolean else null
        return InvokeResult.Error(code, values?.get(FourLink.KEY_MESSAGE) as? String ?: code.wire, suggestion, reason, fixable)
    }

    /** The user's sentence for a result. */
    fun spoken(result: InvokeResult, appName: String, title: String): String = when (result) {
        is InvokeResult.Ok -> "$appName: $title — done."
        is InvokeResult.Error -> when (result.code) {
            ErrorCode.NOT_GRANTED -> "$appName did not allow “$title”: ${result.message}"
            ErrorCode.NOT_PAIRED -> "$appName is not paired with 4Dictate: ${result.message}"
            else -> "$appName: $title — ${result.code.wire.replace('_', ' ')}: ${result.message}"
        }
        is InvokeResult.Unreachable -> result.why
    }
}

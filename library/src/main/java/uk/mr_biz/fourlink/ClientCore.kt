package uk.mr_biz.fourlink

/** What came back from a provider, read by VALUE (§4, §11). */
sealed interface InvokeResult {
    data class Ok(val json: String) : InvokeResult
    data class Error(val code: ErrorCode, val message: String) : InvokeResult
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
        return if (ok) InvokeResult.Ok(json ?: "{}")
        else InvokeResult.Unreachable("The app's answer could not be read.")
    }

    private fun error(values: Map<String, Any?>?): InvokeResult.Error? {
        val code = ErrorCode.fromWire(values?.get(FourLink.KEY_ERROR) as? String) ?: return null
        return InvokeResult.Error(code, values?.get(FourLink.KEY_MESSAGE) as? String ?: code.wire)
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

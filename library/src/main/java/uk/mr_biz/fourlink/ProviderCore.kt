// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject

/** A reply, before it becomes a Bundle (§4). */
sealed interface Reply {
    data class Hello(val app: String, val version: String, val standing: Standing) : Reply
    data class Json(val json: String) : Reply
    data class Error(val code: ErrorCode, val message: String) : Reply
}

/** What a function did (the provider's handler answers one of these). */
sealed interface Outcome {
    /** The result object as JSON text, matching the function's output schema. */
    data class Ok(val json: String = "{}") : Outcome
    /** The provider declined for its own reason (§11 `refused`). */
    data class Refused(val message: String) : Outcome
    /** It ran and failed (§11 `failed`). */
    data class Failed(val message: String) : Outcome
}

/**
 * The three methods of §4, decided without Android: who is calling comes
 * in as a [Caller] (or null when the uid could not be resolved), and what
 * goes back is a [Reply]. [FourLinkProvider] does only the Bundle work.
 */
class ProviderCore(
    private val appName: String,
    private val catalogue: () -> Catalogue,
    private val gate: ProviderGate,
    /** Runs one function. Only reached for a known function, an allowed caller and valid arguments. */
    private val perform: (FunctionSpec, JSONObject, Caller) -> Outcome,
) {

    /** Always answered; names the app and the caller's standing, never a function. */
    fun hello(caller: Caller?): Reply.Hello =
        Reply.Hello(appName, FourLink.VERSION, caller?.let(gate::standing) ?: Standing.UNKNOWN)

    fun catalogue(caller: Caller?): Reply {
        if (caller == null) return unidentified()
        return when (val d = gate.gateCatalogue(caller)) {
            is Decision.Refused -> Reply.Error(d.code, d.message)
            Decision.Allowed -> Reply.Json(gate.visible(caller, catalogue()).toJson())
        }
    }

    fun invoke(caller: Caller?, functionId: String?, argumentsJson: String?, versionRead: String?): Reply {
        if (caller == null) return unidentified()
        val id = functionId.orEmpty()
        val gated = gate.gateInvoke(caller, id)
        if (gated is Decision.Refused) {
            gate.record(caller.packageName, id, gated.code.wire)
            return Reply.Error(gated.code, gated.message)
        }
        val function = catalogue().find(id)
        if (function == null || !majorMatches(function, versionRead)) {
            return refuse(caller, id, ErrorCode.UNKNOWN_FUNCTION, "No function called “$id” (version ${versionRead ?: "?"}).")
        }
        Validation.problem(function, argumentsJson)?.let { why ->
            return refuse(caller, id, ErrorCode.BAD_ARGUMENTS, "Bad arguments: $why.")
        }
        val arguments = JSONObject(argumentsJson ?: "{}")
        val outcome = runCatching { perform(function, arguments, caller) }
            .getOrElse { Outcome.Failed(it.message ?: "it failed") }
        return when (outcome) {
            is Outcome.Ok -> { gate.record(caller.packageName, id, "ok"); Reply.Json(outcome.json) }
            is Outcome.Refused -> refuse(caller, id, ErrorCode.REFUSED, outcome.message)
            is Outcome.Failed -> refuse(caller, id, ErrorCode.FAILED, outcome.message)
        }
    }

    /** A caller sends the major it read (§10); absent means "whatever you have", for family tools and tests. */
    private fun majorMatches(function: FunctionSpec, versionRead: String?): Boolean {
        val read = versionRead?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        return read.substringBefore('.').toIntOrNull() == function.major
    }

    private fun refuse(caller: Caller, what: String, code: ErrorCode, message: String): Reply.Error {
        gate.record(caller.packageName, what, code.wire)
        return Reply.Error(code, message)
    }

    private fun unidentified(): Reply.Error =
        Reply.Error(ErrorCode.NOT_PAIRED, "The caller could not be identified.")
}

package uk.mr_biz.fourlink

/** What the gate decided about one call. */
sealed interface Decision {
    data object Allowed : Decision
    data class Refused(val code: ErrorCode, val message: String) : Decision
}

/**
 * The provider-side gate (§5, §6): family passes; a paired caller passes for
 * the functions it was granted; everyone else is refused. Every call is
 * rate-limited and logged here, so no provider can forget to.
 */
class ProviderGate(
    private val family: Family,
    private val pairings: PairingStore,
    private val lookup: SignerLookup,
    private val rate: RateLimiter = RateLimiter(),
    private val audit: AuditLog,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Family, paired (still the same app, still installed) or unknown (§4 `hello`). */
    fun standing(caller: Caller): Standing = when {
        family.isFamily(caller) -> Standing.FAMILY
        pairing(caller) != null -> Standing.PAIRED
        else -> Standing.UNKNOWN
    }

    /** The caller's live pairing, or null — dropping one whose app is gone or re-signed. */
    private fun pairing(caller: Caller): Pairing? {
        if (!lookup.isInstalled(caller.packageName)) {
            pairings.remove(caller.packageName)
            return null
        }
        return pairings.validFor(caller)
    }

    /** `catalogue`: family or paired, within the rate limit. */
    fun gateCatalogue(caller: Caller): Decision {
        val standing = standing(caller)
        val decision = when {
            standing == Standing.UNKNOWN -> Decision.Refused(ErrorCode.NOT_PAIRED, NOT_PAIRED_SENTENCE)
            !rate.allow(caller.packageName) -> Decision.Refused(ErrorCode.RATE_LIMITED, RATE_LIMITED_SENTENCE)
            else -> Decision.Allowed
        }
        audit.record(AuditEntry(now(), caller.packageName, FourLink.METHOD_CATALOGUE, resultOf(decision)))
        return decision
    }

    /**
     * `invoke`: family, or paired and granted, within the rate limit. The
     * function's existence is the provider's question, asked first by the
     * caller of this method; here only WHO may call it.
     */
    fun gateInvoke(caller: Caller, functionId: String): Decision {
        val standing = standing(caller)
        val decision = when {
            standing == Standing.UNKNOWN -> Decision.Refused(ErrorCode.NOT_PAIRED, NOT_PAIRED_SENTENCE)
            !rate.allow(caller.packageName) -> Decision.Refused(ErrorCode.RATE_LIMITED, RATE_LIMITED_SENTENCE)
            standing == Standing.PAIRED && pairings.get(caller.packageName)?.allows(functionId) != true ->
                Decision.Refused(ErrorCode.NOT_GRANTED, "This app was not allowed to use that.")
            else -> Decision.Allowed
        }
        if (decision is Decision.Allowed && standing == Standing.PAIRED) {
            pairings.touch(caller.packageName, functionId, now())
        }
        return decision
    }

    /** What a caller may see of a catalogue: all of it, or its grant. */
    fun visible(caller: Caller, catalogue: Catalogue): Catalogue = when (standing(caller)) {
        Standing.FAMILY -> catalogue
        Standing.PAIRED -> {
            val granted = pairings.get(caller.packageName)?.granted.orEmpty()
            catalogue.copy(functions = catalogue.functions.filter { it.id in granted })
        }
        Standing.UNKNOWN -> catalogue.copy(functions = emptyList())
    }

    /** The audit line for a finished invoke (or a refused one). */
    fun record(callerPackage: String, what: String, result: String) {
        audit.record(AuditEntry(now(), callerPackage, what, result))
    }

    fun resultOf(decision: Decision): String = when (decision) {
        Decision.Allowed -> "ok"
        is Decision.Refused -> decision.code.wire
    }

    companion object {
        const val NOT_PAIRED_SENTENCE = "This app is not paired with us. Ask the user to pair it first."
        const val RATE_LIMITED_SENTENCE = "Too many calls in the last minute. Try again shortly."
    }
}

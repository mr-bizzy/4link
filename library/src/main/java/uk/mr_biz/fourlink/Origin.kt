// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

/**
 * Who chose an invoke (§4c): [USER] when the user triggered this exact call (a key or gesture they
 * pressed, a fixed voice command mapped to a fixed action, a link they ran), [MODEL] when a model
 * chose the function or filled its arguments. A family caller MUST set it on every invoke, which is
 * why [android.FourLinkClient.invoke] takes it with no default.
 *
 * The spec's honesty clause (§4c): "`origin` is the caller's HONEST DECLARATION of provenance, not
 * a proof. No provider can verify that a person pressed anything. It is trusted only because a
 * family caller is already trusted by its certificate (§5a), and the risk it guards against is an
 * honest app's model reaching something it should not, not a hostile caller, which could lie about
 * anything. A provider must not rely on it against a caller it would not otherwise trust, and must
 * not build a security control on it."
 */
enum class Origin(val wire: String) {
    USER(FourLink.ORIGIN_USER),
    MODEL(FourLink.ORIGIN_MODEL);

    companion object {
        /**
         * The provider's reading (§4c): `"user"` from a family caller is [USER]; a missing or unknown
         * value, and every call from a paired (or unknown) caller, is [MODEL].
         */
        fun read(wire: String?, standing: Standing): Origin =
            if (standing == Standing.FAMILY && wire == FourLink.ORIGIN_USER) USER else MODEL

        /** The sentence a provider gives when it refuses something to a model, unless it has its own. */
        const val NOT_FOR_MODEL_SENTENCE = "That runs only when you trigger it yourself, not on a model's choice."

        /**
         * The provider's helper (§4c): null when [caller]'s call is the user's own, else the refusal
         * to return: `refused`, reason `not_for_model`, not fixable. For a function, or for one
         * argument value, that must never run on a model's choice.
         */
        fun refuseUnlessUser(caller: Caller, message: String = NOT_FOR_MODEL_SENTENCE): Outcome.Refused? =
            if (caller.origin == USER) null
            else Outcome.Refused(message, reason = FourLink.REASON_NOT_FOR_MODEL, fixable = false)
    }
}

// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

/**
 * §4b — WHETHER A FAILED CALL IS TRIED AGAIN, once, decided by what failed and by the function's
 * DECLARED effect, never by a flag the caller passes.
 *
 * Why it exists (measured by the 4Screenshots implementer on an API 37 emulator, 9 October 2026):
 * after a minute idle, Android's freezer had frozen 4Zones; the next call reached the frozen
 * process, system_server logged "sent binder code 21 … got error" and killed it ("Sync transaction
 * while frozen"), and the caller saw no answer. A second call 150 ms later started a fresh 4Zones
 * and succeeded. Then reproduced through THIS retry (23ab15f, a17_desk, after 90 s idle): the kill
 * arrived as DeadObjectException, the immediate retry started a fresh 4Zones (answered in 580 ms),
 * and the capture succeeded. Not reproduced on a second API 37 image in two runs, where the frozen
 * provider was thawed and answered; one difference seen is that the killed 4Zones was cached at
 * oom adj 905. The conditions are not fully known; the retry is the remedy wherever they arise.
 *
 * THE RULE, and why it is no wider:
 *
 *  - [Failure.NOT_REACHED]: acquiring the provider THREW. Nothing reached it, so nothing ran.
 *    Retried for every effect. (A provider that simply does not exist is not a failure and is not
 *    retried: asking again cannot make it exist.)
 *  - [Failure.BINDER]: the transaction failed (`DeadObjectException`, `RemoteException`). The frozen
 *    kill arrives as this — but so does a provider that ran the call and died before replying, and
 *    the two are THE SAME EXCEPTION CLASS. It therefore does not prove the call never ran, and it is
 *    retried only for `read`, where running twice changes nothing.
 *  - A timeout, or any answer at all, is never a failure here and is never retried. A `change`,
 *    `create` or `delete` that may have run must not run twice: that is the desk moved twice.
 *
 * Do not widen this by catching more. A wider catch is how a light gets switched twice.
 */
object Retry {
    enum class Failure { NOT_REACHED, BINDER }

    /** [effect] null: the caller did not say which function (hello, catalogue) — those are reads. */
    fun once(failure: Failure, effect: Effect?): Boolean = when (failure) {
        Failure.NOT_REACHED -> true
        Failure.BINDER -> effect == null || effect == Effect.READ
    }
}

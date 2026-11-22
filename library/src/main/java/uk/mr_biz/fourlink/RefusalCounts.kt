// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

/** One caller's refusals for one reason: in the last minute, and since the process started. */
data class RefusalCount(val minute: Int, val total: Int)

/**
 * State v2 §9: a gate that refuses a call counts it, per caller and reason code. Counts, never log
 * lines: a reason code and a number, nothing about what the user was doing. In memory; a restart
 * starts again at zero.
 */
class RefusalCounts(private val now: () -> Long, private val windowMs: Long = 60_000L) {
    private val recent = hashMapOf<Pair<String, String>, ArrayDeque<Long>>()
    private val totals = hashMapOf<Pair<String, String>, Int>()

    @Synchronized fun add(callerPackage: String, reason: String) {
        val k = callerPackage to reason
        recent.getOrPut(k) { ArrayDeque() }.addLast(now())
        totals[k] = (totals[k] ?: 0) + 1
    }

    @Synchronized fun of(callerPackage: String): Map<String, RefusalCount> {
        val t = now()
        return totals.filterKeys { it.first == callerPackage }.entries.associate { (k, total) ->
            val q = recent.getValue(k)
            while (q.isNotEmpty() && t - q.first() >= windowMs) q.removeFirst()
            k.second to RefusalCount(q.size, total)
        }
    }
}

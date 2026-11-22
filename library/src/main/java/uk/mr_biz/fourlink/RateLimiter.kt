// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

/**
 * At most [limit] calls per [windowMs] per key (§6): a sliding window, so a
 * burst that fills the minute waits for its oldest call to age out rather
 * than for a clock boundary. `hello` is not counted (§6).
 */
class RateLimiter(
    private val limit: Int = FourLink.RATE_LIMIT_PER_MINUTE,
    private val windowMs: Long = 60_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val calls = hashMapOf<String, ArrayDeque<Long>>()

    /** Counts the call and says whether it is within the limit. */
    @Synchronized fun allow(key: String): Boolean = allow(key, limit)

    /**
     * As [allow], but this call may only use the first [upTo] places of the window (State v2 §8:
     * background up to 100 of a family caller's 120, the user's own call all of it). A refused call
     * is not counted, so being refused never drives a caller further into refusal.
     */
    @Synchronized fun allow(key: String, upTo: Int): Boolean {
        val t = now()
        val q = calls.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && t - q.first() >= windowMs) q.removeFirst()
        if (q.size >= minOf(upTo, limit)) return false
        q.addLast(t)
        return true
    }
}

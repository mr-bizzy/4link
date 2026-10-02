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
    @Synchronized fun allow(key: String): Boolean {
        val t = now()
        val q = calls.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && t - q.first() >= windowMs) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(t)
        return true
    }
}

package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** §9 the bounded log; §6 the sliding window on its own. */
class AuditAndRateTest {

    @Test fun `the log keeps the last N and survives a restart`() {
        val storage = MemoryStorage()
        val log = AuditLog(storage, limit = 3)
        (1..5).forEach { log.record(AuditEntry(it.toLong(), "a", "echo.say", "ok")) }
        assertEquals(listOf(3L, 4L, 5L), log.all().map { it.timeMs })
        assertEquals(listOf(3L, 4L, 5L), AuditLog(storage, limit = 3).all().map { it.timeMs })
        log.clear()
        assertTrue(log.all().isEmpty())
        assertTrue(AuditLog(storage).all().isEmpty())
    }

    @Test fun `a corrupt line is skipped`() {
        val log = AuditLog(MemoryStorage("""{"t":1,"who":"a","what":"x","result":"ok"}""" + "\nrubbish\n"))
        assertEquals(1, log.all().size)
    }

    @Test fun `an entry has no room for arguments`() {
        val fields = AuditEntry::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(setOf("timeMs", "callerPackage", "what", "result"), fields)
    }

    @Test fun `the window slides rather than resets`() {
        val clock = FixedClock(0)
        val limiter = RateLimiter(limit = 3, windowMs = 1_000, now = clock)
        assertTrue(limiter.allow("a")); clock.now = 100
        assertTrue(limiter.allow("a")); clock.now = 200
        assertTrue(limiter.allow("a"))
        assertTrue(!limiter.allow("a"))
        clock.now = 1_000 // the first call ages out exactly now
        assertTrue(limiter.allow("a"))
        assertTrue(!limiter.allow("a"))
        assertTrue("another key is unaffected", limiter.allow("b"))
    }
}

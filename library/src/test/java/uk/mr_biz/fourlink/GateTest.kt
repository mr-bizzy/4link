package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §5 identity, §5a family, §6 pairing: who gets through the gate, including
 * the one case the whole design exists for — a stranger wearing a known
 * package name under a different certificate.
 */
class GateTest {

    private val signers = FakeSigners()
        .install(10001, "uk.mr_biz.fourdictate", OUR_DIGEST)
        .install(10002, "com.example.stranger", OTHER_DIGEST)
        .install(10003, "com.example.nobody", "dd".repeat(32))
    private val pairings = PairingStore(MemoryStorage())
    private val audit = AuditLog(MemoryStorage())
    private val clock = FixedClock()
    private val gate = ProviderGate(family(), pairings, signers, RateLimiter(now = clock), audit, clock)

    private fun caller(uid: Int) = CallerIdentity.identify(uid, signers)!!

    @Test fun `identity is uid to package to certificate digest`() {
        val c = caller(10001)
        assertEquals("uk.mr_biz.fourdictate", c.packageName)
        assertEquals(OUR_DIGEST, c.certDigest)
        assertEquals("AAAA AAAA AAAA AAAA", c.fingerprint)
        assertNull("the adb shell has no package", CallerIdentity.identify(2000, signers))
    }

    @Test fun `family has full access`() {
        val c = caller(10001)
        assertEquals(Standing.FAMILY, gate.standing(c))
        assertEquals(Decision.Allowed, gate.gateCatalogue(c))
        assertEquals(Decision.Allowed, gate.gateInvoke(c, "echo.wipe"))
        assertEquals(ECHO, gate.visible(c, ECHO))
    }

    @Test fun `an unpaired app is not_paired and sees no functions`() {
        val c = caller(10002)
        assertEquals(Standing.UNKNOWN, gate.standing(c))
        assertEquals(ErrorCode.NOT_PAIRED, (gate.gateCatalogue(c) as Decision.Refused).code)
        assertEquals(ErrorCode.NOT_PAIRED, (gate.gateInvoke(c, "echo.say") as Decision.Refused).code)
        assertTrue(gate.visible(c, ECHO).functions.isEmpty())
    }

    @Test fun `a paired app may call its grant and nothing else`() {
        val c = caller(10002)
        pairings.put(Pairing(c.packageName, c.certDigest, setOf("echo.say", "echo.note"), clock.now))
        assertEquals(Standing.PAIRED, gate.standing(c))
        assertEquals(Decision.Allowed, gate.gateInvoke(c, "echo.note"))
        assertEquals(ErrorCode.NOT_GRANTED, (gate.gateInvoke(c, "echo.wipe") as Decision.Refused).code)
        assertEquals(listOf("echo.say", "echo.note"), gate.visible(c, ECHO).functions.map { it.id })
        // "when it last did it"
        assertEquals(clock.now, pairings.get(c.packageName)!!.lastUsed["echo.note"])
        assertNull(pairings.get(c.packageName)!!.lastUsed["echo.wipe"])
    }

    @Test fun `THE SPOOF - same package name, different certificate, is a stranger and voids the pairing`() {
        val real = caller(10002)
        pairings.put(Pairing(real.packageName, real.certDigest, setOf("echo.say"), clock.now))
        assertEquals(Standing.PAIRED, gate.standing(real))

        // The user uninstalls the real app; someone else publishes one with the
        // same package name under their own key.
        signers.uninstall("com.example.stranger")
        signers.install(10042, "com.example.stranger", SPOOF_DIGEST)
        val spoof = caller(10042)
        assertEquals(real.packageName, spoof.packageName)

        assertEquals(Standing.UNKNOWN, gate.standing(spoof))
        assertEquals(ErrorCode.NOT_PAIRED, (gate.gateInvoke(spoof, "echo.say") as Decision.Refused).code)
        assertNull("the voided pairing is gone, so the list cannot show a grant that no longer holds", pairings.get("com.example.stranger"))
    }

    @Test fun `an app is always family to itself, by uid, whatever its family list says`() {
        signers.install(10077, "com.example.stranger", OTHER_DIGEST)
        val selfGate = ProviderGate(Family(emptyList()), pairings, signers, RateLimiter(now = clock), audit, clock, isSelf = { it.uid == 10077 })
        assertEquals(Standing.FAMILY, selfGate.standing(caller(10077)))
        assertEquals(Standing.UNKNOWN, selfGate.standing(caller(10001)))
    }

    @Test fun `a spoofed FAMILY package name is not family`() {
        signers.install(10043, "uk.mr_biz.fourzones", SPOOF_DIGEST)
        assertEquals(Standing.UNKNOWN, gate.standing(caller(10043)))
    }

    @Test fun `uninstalling the caller removes its pairing`() {
        val c = caller(10002)
        pairings.put(Pairing(c.packageName, c.certDigest, setOf("echo.say"), clock.now))
        signers.uninstall("com.example.stranger")
        assertEquals(Standing.UNKNOWN, gate.standing(c))
        assertNull(pairings.get(c.packageName))
    }

    @Test fun `thirty calls a minute, then rate_limited, then the window slides`() {
        val c = caller(10001)
        repeat(30) { assertEquals(Decision.Allowed, gate.gateInvoke(c, "echo.say")) }
        assertEquals(ErrorCode.RATE_LIMITED, (gate.gateInvoke(c, "echo.say") as Decision.Refused).code)
        assertEquals(ErrorCode.RATE_LIMITED, (gate.gateCatalogue(c) as Decision.Refused).code)
        clock.now += 60_000
        assertEquals(Decision.Allowed, gate.gateInvoke(c, "echo.say"))
    }

    @Test fun `the limit is per app`() {
        val a = caller(10001)
        pairings.put(Pairing("com.example.stranger", OTHER_DIGEST, setOf("echo.say"), clock.now))
        val b = caller(10002)
        repeat(30) { gate.gateInvoke(a, "echo.say") }
        assertEquals(Decision.Allowed, gate.gateInvoke(b, "echo.say"))
    }

    @Test fun `every catalogue call and every refusal is logged, without arguments`() {
        gate.gateCatalogue(caller(10001))
        gate.gateCatalogue(caller(10002))
        gate.record("com.example.stranger", "echo.say", "not_paired")
        val log = audit.all()
        assertEquals(
            listOf(
                Triple("uk.mr_biz.fourdictate", "catalogue", "ok"),
                Triple("com.example.stranger", "catalogue", "not_paired"),
                Triple("com.example.stranger", "echo.say", "not_paired"),
            ),
            log.map { Triple(it.callerPackage, it.what, it.result) },
        )
        assertTrue(log.all { it.timeMs == clock.now })
    }

    @Test fun `family is a list of digests, never a bare package name`() {
        val f = Family(listOf(FourLink.FAMILY_RELEASE_DIGEST, OTHER_DIGEST))
        assertTrue(f.isFamily(Caller(1, "anything", FourLink.FAMILY_RELEASE_DIGEST)))
        assertTrue(f.isFamily(Caller(1, "anything", OTHER_DIGEST.uppercase())))
        assertTrue(!f.isFamily(Caller(1, "uk.mr_biz.fourdictate", SPOOF_DIGEST)))
        assertEquals(64, FourLink.FAMILY_RELEASE_DIGEST.length)
    }

    @Test fun `a shared uid resolves to one package and an app with two signers to one digest`() {
        signers.install(10050, "b.second", OTHER_DIGEST).install(10050, "a.first", OTHER_DIGEST)
        assertEquals("a.first", CallerIdentity.identify(10050, signers)!!.packageName)
        assertEquals("$OUR_DIGEST+$OTHER_DIGEST", CallerIdentity.identityDigest(listOf(OTHER_DIGEST, OUR_DIGEST)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CallerIdentity.sha256Hex("abc".toByteArray()))
    }
}

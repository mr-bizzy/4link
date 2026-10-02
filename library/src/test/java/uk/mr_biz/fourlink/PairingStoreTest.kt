// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §6 what is stored, and that it is stored at once. */
class PairingStoreTest {

    @Test fun `a pairing survives a restart`() {
        val storage = MemoryStorage()
        val p = Pairing("com.example.stranger", OTHER_DIGEST.uppercase(), setOf("echo.say", "echo.note"), 123L, "1.0", mapOf("echo.say" to 456L))
        PairingStore(storage).put(p)
        val back = PairingStore(storage).get("com.example.stranger")!!
        assertEquals(p.copy(certDigest = OTHER_DIGEST), back)
    }

    @Test fun `remove is in force immediately and persisted`() {
        val storage = MemoryStorage()
        val store = PairingStore(storage)
        store.put(Pairing("a", OTHER_DIGEST, setOf("x"), 1L))
        assertTrue(store.remove("a"))
        assertTrue(!store.remove("a"))
        assertNull(PairingStore(storage).get("a"))
    }

    @Test fun `validFor voids a pairing whose certificate changed`() {
        val store = PairingStore(MemoryStorage())
        store.put(Pairing("a", OTHER_DIGEST, setOf("x"), 1L))
        assertNull(store.validFor(Caller(1, "a", SPOOF_DIGEST)))
        assertNull("gone, not merely refused", store.get("a"))
    }

    @Test fun `touch records the last use per function`() {
        val store = PairingStore(MemoryStorage())
        store.put(Pairing("a", OTHER_DIGEST, setOf("x", "y"), 1L))
        store.touch("a", "x", 500L)
        store.touch("a", "x", 900L)
        assertEquals(mapOf("x" to 900L), store.get("a")!!.lastUsed)
        store.touch("nobody", "x", 1L) // no crash, nothing stored
        assertEquals(1, store.all().size)
    }

    @Test fun `corrupt storage is an empty store, not a crash`() {
        assertTrue(PairingStore(MemoryStorage("{not json")).all().isEmpty())
        assertTrue(PairingStore(MemoryStorage("""[{"package":"a"}]""")).all().isEmpty())
    }
}

// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §15f `offered`: false greys a link; absent, true or anything ill-formed is offered, and the link is kept. */
class LinkOfferedTest {

    private fun el(offered: Any?) = JSONObject()
        .put("id", "dictation.stop").put("name", "Stop").put("function", "dictation.stop").put("version", "1.0")
        .put("arguments", JSONObject()).put("state", "4dictate.mic")
        .also { if (offered != null) it.put("offered", offered) }

    private fun one(offered: Any?, drops: MutableList<String> = mutableListOf()) =
        Links.parse(JSONArray().put(el(offered)).toString(), onDrop = { drops += it }).single()

    @Test fun `absent means offered, and nothing is written`() {
        val l = one(null)
        assertNull(l.offered)
        assertTrue(l.isOffered)
        assertFalse(l.toJson().has("offered"))
    }

    @Test fun `false greys the link and survives the round trip`() {
        val l = one(false)
        assertFalse(l.isOffered)
        assertEquals(l, Links.parse(Links.toText(listOf(l))).single())
        assertTrue(one(true).isOffered)
    }

    @Test fun `an ill-formed offered is dropped and the link kept, offered`() {
        listOf("no", 0, "false", JSONObject()).forEach { raw ->
            val drops = mutableListOf<String>()
            val l = one(raw, drops)
            assertNull(l.offered)
            assertTrue(l.isOffered)
            assertEquals(listOf("element 0: offered dropped, link kept"), drops)
        }
    }

    @Test fun `offered is part of equality`() {
        val a = one(false)
        assertFalse(a == a.copy(offered = null))
    }
}

// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** §15f `icon`: the picture id for the link as it stands now; absent or ill-formed means the link's own id. */
class LinkIconTest {

    private fun el(icon: Any?) = JSONObject()
        .put("id", "dictation.toggle").put("name", "Dictate").put("function", "dictation.toggle").put("version", "1.0")
        .put("arguments", JSONObject()).put("state", "4dictate.mic")
        .also { if (icon != null) it.put("icon", icon) }

    private fun one(icon: Any?, drops: MutableList<String> = mutableListOf()) =
        Links.parse(JSONArray().put(el(icon)).toString(), onDrop = { drops += it }).single()

    @Test fun `absent means the link's own id, and nothing is written`() {
        val l = one(null)
        assertNull(l.icon)
        assertEquals("dictation.toggle", l.iconId)
        assertFalse(l.toJson().has("icon"))
    }

    @Test fun `an icon id survives the round trip`() {
        val l = one("dictation.stop")
        assertEquals("dictation.stop", l.iconId)
        assertEquals(l, Links.parse(Links.toText(listOf(l))).single())
    }

    @Test fun `an ill-formed icon is dropped and the link kept`() {
        listOf(7, true, "", "x".repeat(Links.ID_MAX + 1), JSONObject()).forEach { raw ->
            val drops = mutableListOf<String>()
            val l = one(raw, drops)
            assertNull(l.icon)
            assertEquals("dictation.toggle", l.iconId)
            assertEquals(listOf("element 0: icon dropped, link kept"), drops)
        }
    }

    @Test fun `icon is part of equality, and a bad one is left out on writing`() {
        val a = one("dictation.stop")
        assertFalse(a == a.copy(icon = null))
        val drops = mutableListOf<String>()
        val text = Links.toText(listOf(a.copy(icon = " ")), onDrop = { drops += it })
        assertFalse(JSONArray(text).getJSONObject(0).has("icon"))
        assertEquals(1, drops.size)
    }
}

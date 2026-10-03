// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3 catalogue format and §10 versioning, as read back from JSON. */
class CatalogueTest {

    @Test fun `a catalogue survives a round trip through JSON`() {
        val parsed = Catalogue.parse(ECHO.toJson())!!
        assertEquals(ECHO, parsed.catalogue)
        assertTrue(parsed.dropped.isEmpty())
    }

    @Test fun `titles and descriptions are truncated by the reader, not trusted from the writer`() {
        val long = ECHO.copy(functions = listOf(ECHO.functions[0].copy(title = "T".repeat(200), description = "D".repeat(1000))))
        val f = Catalogue.parse(long.toJson())!!.catalogue.functions.single()
        assertEquals(FourLink.TITLE_MAX, f.title.length)
        assertEquals(FourLink.DESCRIPTION_MAX, f.description.length)
    }

    @Test fun `a function outside the schema subset is dropped and the rest kept`() {
        val json = JSONObject(ECHO.toJson())
        json.getJSONArray("functions").getJSONObject(1).getJSONObject("input").getJSONObject("properties")
            .put("tags", JSONObject().put("type", "array"))
        val parsed = Catalogue.parse(json.toString())!!
        assertEquals(listOf("echo.say", "echo.wipe"), parsed.catalogue.functions.map { it.id })
        assertEquals("echo.note", parsed.dropped.single().first)
        assertTrue(parsed.dropped.single().second.contains("array"))
    }

    @Test fun `a bad id, a missing effect or a bad version drops that function`() {
        val json = JSONObject(ECHO.toJson())
        val fs = json.getJSONArray("functions")
        fs.getJSONObject(0).put("id", "Say")
        fs.getJSONObject(1).put("effect", "")
        fs.getJSONObject(2).put("version", "two")
        val parsed = Catalogue.parse(json.toString())!!
        assertTrue(parsed.catalogue.functions.isEmpty())
        assertEquals(3, parsed.dropped.size)
    }

    @Test fun `an unknown protocol major is not a catalogue we read`() {
        val json = JSONObject(ECHO.toJson()).put(FourLink.KEY_VERSION, "2.0").toString()
        assertNull(Catalogue.parse(json))
        assertNotNull(Catalogue.parse(JSONObject(ECHO.toJson()).put(FourLink.KEY_VERSION, "1.7").toString()))
    }

    @Test fun `unknown fields are ignored`() {
        val json = JSONObject(ECHO.toJson()).put("colour", "blue")
        json.getJSONArray("functions").getJSONObject(0).put("icon", "x")
        assertEquals(ECHO, Catalogue.parse(json.toString())!!.catalogue)
    }

    @Test fun `garbage is not a catalogue`() {
        assertNull(Catalogue.parse("not json"))
        assertNull(Catalogue.parse("{}"))
    }

    @Test fun `duplicate ids keep the first`() {
        val json = JSONObject(ECHO.toJson())
        json.getJSONArray("functions").put(json.getJSONArray("functions").getJSONObject(0))
        val parsed = Catalogue.parse(json.toString())!!
        assertEquals(3, parsed.catalogue.functions.size)
        assertEquals("duplicate id", parsed.dropped.single().second)
    }

    @Test fun `major is read from the version`() {
        assertEquals(1, ECHO.functions[0].major)
        assertEquals(2, ECHO.functions[2].major)
    }
}

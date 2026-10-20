// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §14a: the links.list element format (a ready-made 4Link call) and its limits. */
class LinksTest {
    private val relax = Links.Link(
        "light.john_s_room/scene.john_s_room_relax", "John's room Relax",
        "home.scene.start", "2.0", JSONObject().put("entity", "scene.john_s_room_relax"), "John's room",
    )
    private val region = Links.Link("region", "Screenshot a region", "screen.region", "1.0", JSONObject())

    private fun el(
        id: Any? = "a", name: Any? = "A", function: Any? = "app.run", version: Any? = "1.0",
        arguments: Any? = JSONObject().put("x", 1), group: Any? = null,
    ) = JSONObject().also { o ->
        id?.let { o.put("id", it) }; name?.let { o.put("name", it) }; group?.let { o.put("group", it) }
        function?.let { o.put("function", it) }; version?.let { o.put("version", it) }; arguments?.let { o.put("arguments", it) }
    }

    @Test fun `links survive their own text, arguments included`() {
        val back = Links.parse(Links.toText(listOf(relax, region)))
        assertEquals(listOf(relax, region), back)
        assertEquals("scene.john_s_room_relax", back[0].arguments.getString("entity"))
    }

    @Test fun `there is no url field`() {
        assertFalse(JSONArray(Links.toText(listOf(relax))).getJSONObject(0).has("url"))
    }

    @Test fun `the group is optional and a blank one is none`() {
        val read = Links.parse(JSONArray().put(el(group = " ")).put(el(id = "b")).toString())
        assertEquals(listOf(null, null), read.map { it.group })
        assertFalse(JSONArray(Links.toText(listOf(region))).getJSONObject(0).has("group"))
    }

    @Test fun `each limit drops the element, exactly at the limit is kept`() {
        val a = JSONArray()
            .put(el(id = "i".repeat(200)))
            .put(el(id = "j".repeat(201)))
            .put(el(id = "n1", name = "n".repeat(60)))
            .put(el(id = "n2", name = "n".repeat(61)))
            .put(el(id = "g1", group = "g".repeat(60)))
            .put(el(id = "g2", group = "g".repeat(61)))
        val drops = mutableListOf<String>()
        val read = Links.parse(a.toString()) { drops += it }
        assertEquals(listOf("i".repeat(200), "n1", "g1"), read.map { it.id })
        assertEquals(3, drops.size)
    }

    @Test fun `the function must be a 4Link id and the version major dot minor`() {
        val a = JSONArray()
            .put(el(id = "1", function = "Home.Light")).put(el(id = "2", function = "nodot")).put(el(id = "3", function = "a.b c"))
            .put(el(id = "4", version = "1")).put(el(id = "5", version = "v1.0")).put(el(id = "6", version = "1.0.2"))
            .put(el(id = "ok", function = "home.light.set", version = "2.1"))
        assertEquals(listOf("ok"), Links.parse(a.toString()).map { it.id })
    }

    @Test fun `arguments must be a JSON object`() {
        val a = JSONArray().put(el(id = "1", arguments = "{}")).put(el(id = "2", arguments = JSONArray())).put(el(id = "3", arguments = null))
            .put(el(id = "ok", arguments = JSONObject()))
        assertEquals(listOf("ok"), Links.parse(a.toString()).map { it.id })
    }

    @Test fun `a missing or non-text field drops the element`() {
        val a = JSONArray().put(el(id = null)).put(el(name = null)).put(el(function = null)).put(el(version = null))
            .put(el(id = 5)).put(el(name = "")).put("x").put(el(id = "ok"))
        assertEquals(listOf("ok"), Links.parse(a.toString()).map { it.id })
    }

    @Test fun `a duplicate id is dropped, the first kept`() {
        val a = JSONArray().put(el(id = "d", name = "First")).put(el(id = "d", name = "Second"))
        assertEquals(listOf("First"), Links.parse(a.toString()).map { it.name })
    }

    @Test fun `text that is not an array gives none`() {
        assertEquals(emptyList<Links.Link>(), Links.parse("{}"))
        assertEquals(emptyList<Links.Link>(), Links.parse(null))
        assertEquals(emptyList<Links.Link>(), Links.parse("nonsense"))
    }

    @Test fun `building applies the same rules, so nothing written is dropped by the reader`() {
        val drops = mutableListOf<String>()
        val text = Links.toText(
            listOf(relax, relax.copy(name = "again"), region.copy(function = "BAD"), region.copy(id = "r2", name = "n".repeat(61))),
        ) { drops += it }
        assertEquals(listOf(relax), Links.parse(text))
        assertEquals(3, drops.size)
        assertNull(Links.problem(relax))
        assertTrue(Links.problem(region.copy(version = "2")) != null)
    }
}

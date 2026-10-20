// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §14a: the links.list element format and its limits. */
class LinksTest {
    private val relax = Links.Link(
        "light.john_s_room/scene.john_s_room_relax", "John's room Relax",
        "fourhome://run?link=light.john_s_room%2Fscene.john_s_room_relax", "John's room",
    )
    private val region = Links.Link("region", "Screenshot a region", "fourscreenshots://region")

    private fun el(id: Any? = "a", name: Any? = "A", url: Any? = "app://a", group: Any? = null) = JSONObject().also { o ->
        id?.let { o.put("id", it) }; name?.let { o.put("name", it) }; url?.let { o.put("url", it) }; group?.let { o.put("group", it) }
    }

    @Test fun `links survive their own text`() {
        assertEquals(listOf(relax, region), Links.parse(Links.toText(listOf(relax, region))))
    }

    @Test fun `the group is optional and a blank one is none`() {
        val read = Links.parse(JSONArray().put(el(group = " ")).put(el(id = "b")).toString())
        assertEquals(listOf(null, null), read.map { it.group })
        assertTrue(!JSONArray(Links.toText(listOf(region))).getJSONObject(0).has("group"))
    }

    @Test fun `each limit drops the element, exactly at the limit is kept`() {
        val a = JSONArray()
            .put(el(id = "i".repeat(200)))
            .put(el(id = "j".repeat(201)))
            .put(el(id = "n1", name = "n".repeat(60)))
            .put(el(id = "n2", name = "n".repeat(61)))
            .put(el(id = "u1", url = "app://" + "u".repeat(494)))
            .put(el(id = "u2", url = "app://" + "u".repeat(495)))
            .put(el(id = "g1", group = "g".repeat(60)))
            .put(el(id = "g2", group = "g".repeat(61)))
        val drops = mutableListOf<String>()
        val read = Links.parse(a.toString()) { drops += it }
        assertEquals(listOf("i".repeat(200), "n1", "u1", "g1"), read.map { it.id })
        assertEquals(4, drops.size)
    }

    @Test fun `a url is never http or https, and needs a scheme`() {
        val a = JSONArray().put(el(id = "1", url = "http://x")).put(el(id = "2", url = "HTTPS://x")).put(el(id = "3", url = "no-scheme"))
            .put(el(id = "4", url = "fourhome://run?link=x"))
        assertEquals(listOf("4"), Links.parse(a.toString()).map { it.id })
    }

    @Test fun `a missing or non-text field drops the element`() {
        val a = JSONArray().put(el(id = null)).put(el(name = null)).put(el(url = null)).put(el(id = 5)).put(el(name = "")).put("x")
            .put(el(id = "ok"))
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
        val text = Links.toText(listOf(relax, relax.copy(name = "again"), region.copy(url = "https://x"), region.copy(id = "r2", name = "n".repeat(61)))) { drops += it }
        assertEquals(listOf(relax), Links.parse(text))
        assertEquals(3, drops.size)
        assertNull(Links.problem(relax))
    }
}

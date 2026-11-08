// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §14a `app`: the package a link opens, for its launcher icon; an ill-formed one is dropped and the link kept; family only. */
class LinkAppTest {

    private val music = Links.Link("open:ytmusic", "YouTube Music", "actions.run", "1.0",
        JSONObject().put("id", "open:ytmusic"), app = "com.google.android.apps.youtube.music")
    private val snap = Links.Link("snap1", "Snap left", "actions.run", "1.0", JSONObject().put("id", "snap1"))

    private fun el(app: Any?, id: String = "open:x") = JSONObject()
        .put("id", id).put("name", "Open X").put("function", "actions.run").put("version", "1.0")
        .put("arguments", JSONObject()).also { o -> app?.let { o.put("app", it) } }

    @Test fun `app round-trips, and a link without one has none`() {
        val text = Links.toText(listOf(music, snap))
        assertEquals(listOf(music, snap), Links.parse(text))
        assertEquals("com.google.android.apps.youtube.music", JSONArray(text).getJSONObject(0).getString("app"))
        assertFalse(JSONArray(text).getJSONObject(1).has("app"))
    }

    @Test fun `a long, ill-formed or one-segment app is dropped and the link kept`() {
        val bad = listOf<Any>(
            "com." + "a".repeat(97),          // 101 characters
            "youtube",                         // one segment
            "com..google", "1com.google", "com.google.", "com.goo gle", "com.google-music", ".com.google", 42, true,
        )
        val drops = mutableListOf<String>()
        val a = JSONArray()
        bad.forEachIndexed { i, v -> a.put(el(v, "open:$i")) }
        val read = Links.parse(a.toString(), onDrop = drops::add)
        assertEquals(bad.size, read.size)
        assertTrue(read.all { it.app == null })
        assertEquals(bad.size, drops.size)
        assertTrue(drops.all { it.contains("app dropped, link kept") })
    }

    @Test fun `exactly 100 characters and Java grammar are kept`() {
        val hundred = "com." + "a".repeat(96)
        assertEquals(100, hundred.length)
        val read = Links.parse(JSONArray().put(el(hundred)).put(el("a1.B_2.c3", "open:y")).toString())
        assertEquals(listOf(hundred, "a1.B_2.c3"), read.map { it.app })
    }

    @Test fun `the provider strips app for a paired or unknown caller and keeps it for family`() {
        val links = listOf(music, snap)
        assertEquals(links, Links.forCaller(links, Standing.FAMILY))
        listOf(Standing.PAIRED, Standing.UNKNOWN).forEach { s ->
            val stripped = Links.forCaller(links, s)
            assertTrue(stripped.all { it.app == null })
            assertEquals(links.map { it.id }, stripped.map { it.id })
            assertFalse(Links.toTextFor(links, s).contains("youtube.music"))
        }
        assertTrue(Links.toTextFor(links, Standing.FAMILY).contains("com.google.android.apps.youtube.music"))
    }

    @Test fun `the provider writes no ill-formed app, and keeps the link`() {
        val drops = mutableListOf<String>()
        val text = Links.toText(listOf(music.copy(app = "not a package")), drops::add)
        val back = Links.parse(text)
        assertEquals(1, back.size)
        assertNull(back.single().app)
        assertEquals(1, drops.size)
    }
}

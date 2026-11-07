// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §15f: a state's level and on, and a link's short, level and state, with the limits enforced on reading. */
class LevelsTest {

    private val start = 1_700_000_000_000L

    @Test fun `a state carries level and on, and the caller reads what the provider wrote`() {
        val store = StateStore("4home", start)
        store.set("4home.desk_lamp", State.Token.IDLE, level = 40.0, on = true)
        store.set("4home.blind", State.Token.WORKING, level = 12.5)
        val back = State.parseStates(State.statesToText(store.get()))
        assertEquals(State.Value(State.Token.IDLE, null, start + 1, 40.0, true), back["4home.desk_lamp"])
        assertEquals(12.5, back["4home.blind"]!!.level!!, 0.0)
        assertNull(back["4home.blind"]!!.on)
        assertEquals(40, JSONObject(State.statesToText(store.get())).getJSONObject("4home.desk_lamp").get("level"))
    }

    @Test fun `a change of level or on alone is a change`() {
        val store = StateStore("4home", start)
        store.set("4home.lamp", State.Token.IDLE, level = 40.0, on = true)
        store.set("4home.lamp", State.Token.IDLE, level = 41.0, on = true)
        store.set("4home.lamp", State.Token.IDLE, level = 41.0, on = false)
        store.set("4home.lamp", State.Token.IDLE, level = 41.0, on = false)
        assertEquals(start + 3, store.get()["4home.lamp"]!!.seq)
    }

    @Test fun `the provider refuses a level outside 0 to 100`() {
        val store = StateStore("4home", start)
        listOf(-0.1, 100.1, Double.NaN).forEach { assertTrue("$it", runCatching { store.set("4home.lamp", State.Token.IDLE, level = it) }.isFailure) }
        store.set("4home.lamp", State.Token.IDLE, level = 0.0)
        store.set("4home.lamp", State.Token.IDLE, level = 100.0)
    }

    @Test fun `the reader drops a bad level or on and keeps the token`() {
        val text = JSONObject()
            .put("4home.a", JSONObject().put("token", "listening").put("seq", 1).put("level", 101))
            .put("4home.b", JSONObject().put("token", "idle").put("seq", 2).put("level", -1).put("on", "yes"))
            .put("4home.c", JSONObject().put("token", "idle").put("seq", 3).put("level", "40"))
            .put("4home.d", JSONObject().put("token", "idle").put("seq", 4).put("level", 100).put("on", false))
            .toString()
        val drops = mutableListOf<String>()
        val s = State.parseStates(text, drops::add)
        assertEquals(State.Value(State.Token.LISTENING, null, 1), s["4home.a"])
        assertEquals(State.Value(State.Token.IDLE, null, 2), s["4home.b"])
        assertEquals(State.Value(State.Token.IDLE, null, 3), s["4home.c"])
        assertEquals(State.Value(State.Token.IDLE, null, 4, 100.0, false), s["4home.d"])
        assertEquals(4, drops.size)
    }

    private val home = Catalogue("4Home", listOf(
        FunctionSpec("home.light.set", "1.1", "Set a light", "Sets a light.", Effect.CHANGE,
            Schema.Obj(mapOf("name" to Schema.Str(), "brightness_percent" to Schema.Num(), "on" to Schema.Bool()), setOf("name"))),
    ))

    private fun el(short: Any? = null, level: Any? = null, state: Any? = null, id: String = "light.lamp") = JSONObject()
        .put("id", id).put("name", "Desk lamp").put("function", "home.light.set").put("version", "1.1")
        .put("arguments", JSONObject().put("name", "Desk lamp"))
        .also { o -> short?.let { o.put("short", it) }; level?.let { o.put("level", it) }; state?.let { o.put("state", it) } }

    @Test fun `a link carries short, level and state, and they survive their own text`() {
        val link = Links.Link("light.lamp", "Desk lamp", "home.light.set", "1.1", JSONObject().put("name", "Desk lamp"),
            short = "Lamp", level = "brightness_percent", state = "4home.light_desk_lamp")
        assertEquals(listOf(link), Links.parse(Links.toText(listOf(link)), home))
    }

    @Test fun `short at 16 is kept, at 17 or empty the element is dropped`() {
        val drops = mutableListOf<String>()
        val read = Links.parse(JSONArray().put(el(short = "x".repeat(16))).put(el(short = "x".repeat(17), id = "b"))
            .put(el(short = " ", id = "c")).toString(), home, drops::add)
        assertEquals(listOf("light.lamp"), read.map { it.id })
        assertEquals(2, drops.size)
    }

    @Test fun `level must name a number argument of the call, when the catalogue is known`() {
        val drops = mutableListOf<String>()
        val read = Links.parse(JSONArray()
            .put(el(level = "brightness_percent"))
            .put(el(level = "name", id = "b"))
            .put(el(level = "missing", id = "c"))
            .put(el(level = "", id = "d")).toString(), home, drops::add)
        assertEquals(listOf("light.lamp"), read.map { it.id })
        assertEquals(3, drops.size)
        assertEquals("without the catalogue only its shape is checked", 3, Links.parse(JSONArray()
            .put(el(level = "brightness_percent")).put(el(level = "name", id = "b")).put(el(level = "missing", id = "c")).toString()).size)
    }

    @Test fun `state must be a state key, and the three must be text`() {
        val drops = mutableListOf<String>()
        val read = Links.parse(JSONArray()
            .put(el(state = "4home.lamp"))
            .put(el(state = "Not A Key", id = "b"))
            .put(el(short = 5, id = "c"))
            .put(el(level = true, id = "d")).toString(), home, drops::add)
        assertEquals(listOf("light.lamp"), read.map { it.id })
        assertEquals(3, drops.size)
    }

    @Test fun `a link without level is a key`() {
        assertNull(Links.parse(JSONArray().put(el()).toString(), home).single().level)
    }
}

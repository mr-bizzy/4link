// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §15b `icons.get`: the limits, the path grammar, and an entry dropped while the rest are kept. */
class IconsTest {

    @Test fun `the spec's own examples are paths`() {
        assertTrue(Icons.isPath("M3 3h8v8H3z"))
        assertTrue(Icons.isPath("M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z"))
    }

    @Test fun `numbers may carry a sign, a point and an exponent, with commas or none between`() {
        assertTrue(Icons.isPath("M-1.5,2e-3L+.5 .5 1.2.3Z"))
        assertTrue(Icons.isPath("m0 0c1,2,3,4,5,6s1 1 2 2q3 3 4 4t1 1a5 5 0 0 1 10 10z"))
        assertTrue(Icons.isPath("  M 1 1\n L 2 2\t z"))
    }

    @Test fun `anything outside the grammar is not a path`() {
        listOf(
            "", "   ", "L 1 1", "1 1 M 2 2", "M 1 1 x 2", "M1 1<svg/>", "M1 1 url(#a)", "M1e 1", "M1e+ 1",
            "M 1 1 #", "M - 1", "M . 1", "M1 1;", "M1 1 Ω", "M1 1 B 2 2", "M1 1 e5",
        ).forEach { assertFalse(it, Icons.isPath(it)) }
    }

    @Test fun `d at the limit is kept, one over is dropped`() {
        val at = "M0 0" + " ".repeat(Icons.D_MAX - 4)
        assertEquals(Icons.D_MAX, at.length)
        assertTrue(Icons.isPath(at))
        assertFalse(Icons.isPath(at + " "))
    }

    @Test fun `a hostile 4096 characters is a linear scan`() {
        val hostile = "M" + "1".repeat(Icons.D_MAX - 2) + "x"
        val t = System.nanoTime()
        repeat(100) { assertFalse(Icons.isPath(hostile)) }
        assertTrue("took ${(System.nanoTime() - t) / 1_000_000} ms", System.nanoTime() - t < 2_000_000_000L)
    }

    @Test fun `a bad entry is dropped with a log line and the rest are kept`() {
        val text = JSONObject()
            .put("snap1", JSONObject().put("d", "M3 3h8v8H3z").put("evenodd", true))
            .put("tasks.add", JSONObject().put("d", "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z"))
            .put("bad.grammar", JSONObject().put("d", "M1 1 <script>"))
            .put("bad.long", JSONObject().put("d", "M0 0" + " 1".repeat(3000)))
            .put("bad.fill", JSONObject().put("d", "M0 0z").put("evenodd", "yes"))
            .put("bad.nod", JSONObject().put("colour", "red"))
            .put("bad.shape", "M0 0z")
            .toString()
        val drops = mutableListOf<String>()
        val icons = Icons.parse(text) { drops += it }
        assertEquals(mapOf("snap1" to Icons.Icon("M3 3h8v8H3z", true), "tasks.add" to Icons.Icon("M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z")), icons)
        assertEquals(5, drops.size)
        assertTrue(drops.all { it.startsWith("icon bad.") })
    }

    @Test fun `nothing else travels, and what the provider writes the caller reads`() {
        val icons = mapOf("a.b" to Icons.Icon("M0 0h1z", evenodd = true), "c" to Icons.Icon("M1 1z"))
        val text = Icons.toText(icons)
        assertEquals(icons, Icons.parse(text))
        assertEquals(setOf("d", "evenodd"), JSONObject(text).getJSONObject("a.b").keys().asSequence().toSet())
        assertEquals(setOf("d"), JSONObject(text).getJSONObject("c").keys().asSequence().toSet())
    }

    @Test fun `the provider leaves out a picture it may not send`() {
        val drops = mutableListOf<String>()
        val text = Icons.toText(mapOf("ok" to Icons.Icon("M0 0z"), "bad" to Icons.Icon("<path/>")), drops::add)
        assertEquals(setOf("ok"), JSONObject(text).keys().asSequence().toSet())
        assertEquals(1, drops.size)
    }

    @Test fun `text that is not an object gives none`() {
        assertTrue(Icons.parse("[]").isEmpty())
        assertTrue(Icons.parse(null).isEmpty())
    }

    @Test fun `at most 32 ids a call`() {
        val ids32 = (1..32).map { "f$it.x" }
        assertEquals(ids32, Icons.parseIds(Icons.idsToText(ids32)))
        assertNull(Icons.parseIds(JSONArray((1..33).map { "f$it.x" }).toString()))
        assertNull(Icons.parseIds("""["a", 1]"""))
        assertNull(Icons.parseIds("not json"))
        assertTrue(runCatching { Icons.idsToText((1..33).map { "x" }) }.isFailure)
    }

    @Test fun `the handler answers only the ids it has, and refuses 33`() {
        val have = mapOf("snap1" to Icons.Icon("M0 0z"), "snap2" to Icons.Icon("M1 1z"))
        val ok = Icons.handle(JSONObject().put("ids", """["snap1","missing"]"""), pictures = { ids -> have.filterKeys { it in ids } + ("extra" to Icons.Icon("M2 2z")) })
        ok as Outcome.Ok
        assertEquals(mapOf("snap1" to Icons.Icon("M0 0z")), Icons.parse(JSONObject(ok.json).getString("icons")))
        val tooMany = Icons.handle(JSONObject().put("ids", JSONArray((1..33).map { "a$it.b" }).toString()), pictures = { emptyMap() })
        assertTrue(tooMany is Outcome.BadArguments)
    }

    @Test fun `the catalogue entry is a read in the schema subset`() {
        assertEquals(Effect.READ, Icons.SPEC.effect)
        val back = Catalogue.parse(Catalogue("x", listOf(Icons.SPEC)).toJson())!!
        assertEquals(listOf(Icons.FUNCTION), back.catalogue.functions.map { it.id })
    }
}

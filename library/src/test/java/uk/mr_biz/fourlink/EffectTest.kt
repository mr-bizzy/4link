// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four effects (§5 P3, §7): read, create, change, delete. */
class EffectTest {
    @Test fun `create is a wire word of its own`() {
        assertEquals(Effect.CREATE, Effect.fromWire("create"))
        assertEquals("create", Effect.CREATE.wire)
        assertEquals(listOf("read", "create", "change", "delete"), Effect.entries.map { it.wire })
    }

    @Test fun `by default everything but a read needs the user's confirmation, create included`() {
        assertFalse(Effect.READ.needsConfirmation)
        assertTrue(Effect.CREATE.needsConfirmation)
        assertTrue(Effect.CHANGE.needsConfirmation)
        assertTrue(Effect.DELETE.needsConfirmation)
    }

    @Test fun `a word this version does not know is read as change, so a caller still asks`() {
        assertEquals(Effect.CHANGE, Effect.fromWireOrChange("archive"))
        assertEquals(Effect.CREATE, Effect.fromWireOrChange("create"))
        assertEquals(Effect.READ, Effect.fromWireOrChange("read"))
        assertNull(Effect.fromWireOrChange(""))
        assertNull(Effect.fromWireOrChange(null))
        assertTrue(Effect.fromWireOrChange("archive")!!.needsConfirmation)
    }

    @Test fun `a catalogue with a create function round-trips, and an unknown effect in it becomes change`() {
        val cat = Catalogue(
            "App",
            listOf(
                FunctionSpec("app.make", "1.0", "Make", "Makes one. Use when asked to make something.", Effect.CREATE,
                    Schema.Obj(mapOf("name" to Schema.Str()), setOf("name")), Schema.NOTHING),
            ),
        )
        assertEquals(cat, Catalogue.parse(cat.toJson().toString())!!.catalogue)
        val json = JSONObject(cat.toJson().toString())
        json.getJSONArray("functions").getJSONObject(0).put("effect", "teleport")
        assertEquals(Effect.CHANGE, Catalogue.parse(json.toString())!!.catalogue.functions.single().effect)
    }
}

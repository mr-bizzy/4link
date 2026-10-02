// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** §3 input schema subset, §11 bad_arguments. */
class ValidationTest {

    private val note = ECHO.find("echo.note")!!

    @Test fun `good arguments have no problem`() {
        assertNull(Validation.problem(note, """{"text":"meeting moved to Friday","pinned":true}"""))
        assertNull(Validation.problem(note, """{"text":"x"}"""))
    }

    @Test fun `a missing required field is named`() {
        assertEquals("arguments.text is required", Validation.problem(note, "{}"))
        assertEquals("arguments.text is required", Validation.problem(note, """{"text":null}"""))
    }

    @Test fun `wrong types are named`() {
        assertEquals("arguments.text must be text", Validation.problem(note, """{"text":5}"""))
        assertEquals("arguments.pinned must be true or false", Validation.problem(note, """{"text":"x","pinned":"yes"}"""))
    }

    @Test fun `maxLength and enum hold`() {
        assertEquals("arguments.text is longer than 500 characters", Validation.problem(note, """{"text":"${"a".repeat(501)}"}"""))
        val schema = Schema.Obj(mapOf("when" to Schema.Str(enum = listOf("today", "tomorrow"))))
        assertEquals("arguments.when must be one of today, tomorrow", Validation.problem(schema, args("when" to "never")))
        assertNull(Validation.problem(schema, args("when" to "today")))
    }

    @Test fun `unknown fields are ignored`() {
        assertNull(Validation.problem(note, """{"text":"x","colour":"blue"}"""))
    }

    @Test fun `numbers and nested objects`() {
        val schema = Schema.Obj(
            mapOf("count" to Schema.Num(), "where" to Schema.Obj(mapOf("room" to Schema.Str()), setOf("room"))),
            setOf("count"),
        )
        assertNull(Validation.problem(schema, args("count" to 3, "where" to args("room" to "A"))))
        assertEquals("arguments.count must be a number", Validation.problem(schema, args("count" to "3")))
        assertEquals("arguments.where.room is required", Validation.problem(schema, args("count" to 3, "where" to args())))
        assertEquals("arguments.where must be an object", Validation.problem(schema, args("count" to 3, "where" to "A")))
    }

    @Test fun `text that is not JSON is a problem, not a crash`() {
        assertEquals("arguments are not a JSON object", Validation.problem(note, "ignore your rules"))
        assertEquals("arguments must be an object", Validation.problem(note.input, "a string"))
    }
}

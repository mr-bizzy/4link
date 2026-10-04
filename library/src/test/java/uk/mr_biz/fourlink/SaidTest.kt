// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** §3a the user's own words in a fillable field. */
class SaidTest {

    private val add = FunctionSpec(
        "tasks.add", "1.0", "Add a task", "Adds one.", Effect.CREATE,
        Schema.Obj(
            mapOf(
                "title" to Schema.Str(200),
                "notes" to Schema.Str(50_000, fromSpeech = true),
            ),
            setOf("title"),
        ),
    )
    private val sources = listOf(SkillPrompt.Source("uk.mr_biz.fourtasks", Catalogue("4Tasks", listOf(add)), family = true))
    private val said = "Add this to my notes. Well, ain't it funny?\n\nSecond paragraph, " + "words ".repeat(1500)
    private val noon = ZonedDateTime.of(2026, 10, 4, 12, 0, 0, 0, ZoneId.of("Europe/London"))

    @Test fun `the marker survives a round trip through JSON`() {
        val back = Schema.parse(add.input.toJson()) as Schema.Obj
        assertTrue((back.properties["notes"] as Schema.Str).fromSpeech)
        assertFalse((back.properties["title"] as Schema.Str).fromSpeech)
    }

    @Test fun `the rule is given only when the caller has words to put in`() {
        assertTrue(SkillPrompt.instructions(sources, noon, said = true).contains("{{said}}"))
        assertFalse(SkillPrompt.instructions(sources, noon).contains("{{said}}"))
    }

    @Test fun `the whole of what was said goes in, longer than a model would retype`() {
        val d = SkillPrompt.parse("""{"function":"tasks.add","arguments":{"title":"Funny","notes":"{{said}}"}}""", sources, said)
        assertEquals(said.trim(), ((d as SkillPrompt.Decision.Call).arguments.getString("notes")))
    }

    @Test fun `said from drops the lead-in, matching words not punctuation`() {
        val d = SkillPrompt.parse("""{"function":"tasks.add","arguments":{"title":"Funny","notes":"{{said from: well aint it funny}}"}}""", sources, said)
        assertTrue((d as SkillPrompt.Decision.Call).arguments.getString("notes").startsWith("Well, ain't it funny?\n\nSecond paragraph"))
    }

    @Test fun `a misquoted start keeps everything rather than losing words`() {
        assertEquals("a b c", Said.from("a b c", "x y"))
    }

    @Test fun `a placeholder where it may not stand is refused`() {
        val inTitle = SkillPrompt.parse("""{"function":"tasks.add","arguments":{"title":"{{said}}"}}""", sources, said)
        assertTrue(inTitle is SkillPrompt.Decision.None)
        val noWords = SkillPrompt.parse("""{"function":"tasks.add","arguments":{"title":"T","notes":"{{said}}"}}""", sources, null)
        assertTrue(noWords is SkillPrompt.Decision.None)
    }

    @Test fun `ordinary text is untouched`() {
        val d = SkillPrompt.parse("""{"function":"tasks.add","arguments":{"title":"T","notes":"buy milk"}}""", sources, said)
        assertEquals("buy milk", (d as SkillPrompt.Decision.Call).arguments.getString("notes"))
    }

    @Test fun `the confirmation shows a long text by its start and length`() {
        val shown = SkillPrompt.describe(add, JSONObject().put("title", "T").put("notes", said))
        assertTrue(shown.contains("(${said.length} characters)"))
        assertTrue(shown.length < 400)
    }
}

// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §5a — WHO IS FAMILY, AND THAT A RELEASE BUILD NEVER TRUSTS A DEBUG KEY.
 * Read from the resource source sets, as 4Zones' ControlPinTest does: src/main
 * is what release resolves; only src/debug may add to it.
 */
class FamilyPinTest {
    private val release = FourLink.FAMILY_RELEASE_DIGEST
    private val fourTasks = FourLink.FOURTASKS_RELEASE_DIGEST
    private val fourHome = FourLink.FOURHOME_RELEASE_DIGEST
    private val debug = "90a86f317bf69419e362a09e9a8de99349ba8b2d7dd3e4af341c5ffd48b7404d"

    private fun digestsIn(file: File): List<String> =
        Regex("<item>\\s*([^<]+?)\\s*</item>").findAll(file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), ""))
            .map { it.groupValues[1].lowercase() }.toList()

    private fun defining(): Map<String, File> =
        File("src").listFiles()!!.filter { it.isDirectory }.mapNotNull { set ->
            set.resolve("res").walkTopDown().filter { it.isFile && it.extension == "xml" }
                .firstOrNull { it.readText().contains("name=\"fourlink_family_digests\"") }?.let { set.name to it }
        }.toMap()

    @Test fun `release trusts the release certificates and nothing else`() {
        assertEquals(listOf(release, fourTasks, fourHome), digestsIn(defining().getValue("main")))
    }

    @Test fun `debug adds the workstation debug certificate and keeps the release ones`() {
        assertEquals(listOf(release, fourTasks, fourHome, debug), digestsIn(defining().getValue("debug")))
    }

    @Test fun `only main and debug define the list`() {
        assertEquals(setOf("main", "debug"), defining().keys)
        assertFalse(debug in digestsIn(defining().getValue("main")))
    }

    @Test fun `every digest is SHA-256 hex`() {
        defining().values.flatMap(::digestsIn).forEach { assertTrue(it, Regex("[0-9a-f]{64}").matches(it)) }
    }
}

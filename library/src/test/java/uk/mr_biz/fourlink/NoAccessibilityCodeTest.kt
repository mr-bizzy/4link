package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1: the library contains no accessibility code, and no way to type into or
 * tap another app. Read from the source set so it runs with every other test
 * and fails the moment someone adds an import.
 */
class NoAccessibilityCodeTest {

    private val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList()

    @Test fun `the main source set is where we think it is`() {
        assertTrue(sources.any { it.name == "FourLinkProvider.kt" })
    }

    @Test fun `no accessibility, input-method or gesture API is referenced`() {
        val forbidden = listOf(
            "accessibilityservice", "AccessibilityService", "AccessibilityNodeInfo", "AccessibilityEvent",
            "performGlobalAction", "dispatchGesture", "ACTION_CLICK", "ACTION_SET_TEXT",
            "InputMethodService", "inputmethod", "ClipboardManager", "UiAutomation",
        )
        val hits = sources.flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun `the only Android entry points are the provider, the client, the pairing helper, the stores and the receiver`() {
        val android = sources.filter { it.path.contains("/android/") }.map { it.name }.sorted()
        assertEquals(
            listOf("FourLinkClient.kt", "FourLinkProvider.kt", "FourLinkStores.kt", "PackageRemovedReceiver.kt", "PackageSigners.kt", "PairingRequest.kt"),
            android,
        )
        // And nothing outside that folder imports android.* — the deciding code is pure.
        val leaks = sources.filter { !it.path.contains("/android/") && it.extension == "kt" && it.readText().contains("import android.") }
        assertEquals(emptyList<File>(), leaks)
    }
}

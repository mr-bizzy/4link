// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4b — one retry, from what failed and the declared effect; never a change that may have run. */
class RetryTest {

    @Test fun `an unreached provider is retried for every effect, because nothing ran`() {
        (Effect.entries + listOf<Effect?>(null)).forEach { assertTrue("$it", Retry.once(Retry.Failure.NOT_REACHED, it)) }
    }

    @Test fun `a binder failure is retried for a read, and for hello and catalogue`() {
        assertTrue(Retry.once(Retry.Failure.BINDER, Effect.READ))
        assertTrue("hello and catalogue pass no effect, and are reads", Retry.once(Retry.Failure.BINDER, null))
    }

    @Test fun `a binder failure is NOT retried for anything that changes something, because it may have run`() {
        listOf(Effect.CREATE, Effect.CHANGE, Effect.DELETE).forEach { assertFalse("$it", Retry.once(Retry.Failure.BINDER, it)) }
    }

    @Test fun `the client keeps no provider client, retries at most once, and logs no message`() {
        val client = java.io.File("src/main/java/uk/mr_biz/fourlink/android/FourLinkClient.kt").readText()
        assertTrue(client.contains("acquireUnstableContentProviderClient"))
        assertTrue(client.contains("client.close()"))
        val call = client.substringAfter("private fun call(").substringBefore("private sealed interface Attempt")
        assertFalse("no loop", Regex("""while\s*\(|for\s*\(""").containsMatchIn(call))
        assertTrue("two attempts at most", Regex("""attempt\(""").findAll(call).count() == 2)
        assertFalse("no message logged", client.contains("e.message"))
    }

    @Test fun `the old invoke form, with no declared effect, is treated as a change`() {
        val client = java.io.File("src/main/java/uk/mr_biz/fourlink/android/FourLinkClient.kt").readText()
        assertTrue(client.contains("invoke(packageName, functionId, argumentsJson, versionRead, Effect.CHANGE)"))
    }
}

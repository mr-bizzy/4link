package uk.mr_biz.fourlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §4 as read by the caller, by VALUE; §10 the protocol major. */
class ClientCoreTest {

    @Test fun `hello is read and an unknown major is unusable`() {
        val h = ClientCore.hello(mapOf("app" to "4Link Echo", "4link" to "1.0", "caller" to "paired"))!!
        assertEquals(HelloReply("4Link Echo", "1.0", Standing.PAIRED), h)
        assertTrue(h.usable)
        assertTrue(!ClientCore.hello(mapOf("app" to "x", "4link" to "2.0"))!!.usable)
        assertEquals(Standing.UNKNOWN, ClientCore.hello(mapOf("app" to "x", "4link" to "1.0", "caller" to "weird"))!!.standing)
        assertNull(ClientCore.hello(mapOf("ok" to true)))
        assertNull(ClientCore.hello(null))
    }

    @Test fun `catalogue reply or its error`() {
        val (parsed, err) = ClientCore.catalogue(mapOf("ok" to true, "json" to ECHO.toJson()))
        assertEquals(ECHO, parsed!!.catalogue)
        assertNull(err)
        val (none, refused) = ClientCore.catalogue(mapOf("ok" to false, "error" to "not_paired", "message" to "Pair first."))
        assertNull(none)
        assertEquals(InvokeResult.Error(ErrorCode.NOT_PAIRED, "Pair first."), refused)
        assertEquals(null to null, ClientCore.catalogue(null))
    }

    @Test fun `invoke reply by value, unknown keys ignored`() {
        assertEquals(InvokeResult.Ok("""{"count":1}"""), ClientCore.invoke(mapOf("ok" to true, "json" to """{"count":1}""", "extra" to 1)))
        assertEquals(InvokeResult.Error(ErrorCode.NOT_GRANTED, "No."), ClientCore.invoke(mapOf("ok" to false, "error" to "not_granted", "message" to "No.")))
        assertTrue(ClientCore.invoke(null) is InvokeResult.Unreachable)
        assertTrue("an unknown error code is not a success", ClientCore.invoke(mapOf("ok" to false, "error" to "teapot")) is InvokeResult.Unreachable)
        assertTrue(ClientCore.invoke(mapOf("json" to "{}")) is InvokeResult.Unreachable)
    }

    @Test fun `sentences for the user`() {
        assertEquals("4Link Echo: Save a note — done.", ClientCore.spoken(InvokeResult.Ok("{}"), "4Link Echo", "Save a note"))
        assertEquals(
            "4Link Stranger did not allow “Wipe”: This app was not allowed to use that.",
            ClientCore.spoken(InvokeResult.Error(ErrorCode.NOT_GRANTED, "This app was not allowed to use that."), "4Link Stranger", "Wipe"),
        )
        assertEquals("It did not answer.", ClientCore.spoken(InvokeResult.Unreachable("It did not answer."), "x", "y"))
    }
}

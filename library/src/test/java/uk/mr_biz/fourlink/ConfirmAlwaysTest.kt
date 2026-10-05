// SPDX-License-Identifier: Apache-2.0
package uk.mr_biz.fourlink

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** §P3: a function its provider marks "always confirm". */
class ConfirmAlwaysTest {
    @Test fun `the mark survives a round trip and is absent by default`() {
        val f = FunctionSpec("home.switch.set", "1.0", "Switch", "On or off.", Effect.CHANGE, confirmAlways = true)
        assertTrue(FunctionSpec.parse(f.toJson()).confirmAlways)
        assertFalse(FunctionSpec.parse(f.copy(confirmAlways = false).toJson()).confirmAlways)
        assertFalse(f.copy(confirmAlways = false).toJson().has("confirm"))
    }

    @Test fun `an unknown value is not always`() {
        val o = FunctionSpec("home.light.set", "1.0", "L", "L", Effect.CHANGE).toJson().put("confirm", "sometimes")
        assertFalse(FunctionSpec.parse(JSONObject(o.toString())).confirmAlways)
    }
}

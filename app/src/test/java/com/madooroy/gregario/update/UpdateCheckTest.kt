package com.madooroy.gregario.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun `a later tag is newer`() {
        assertTrue(UpdateCheck.newer("0.1.0", "v0.1.1"))
        assertTrue(UpdateCheck.newer("0.1.0", "0.2"))
        assertTrue(UpdateCheck.newer("0.1.9", "v0.1.10"))
        assertTrue(UpdateCheck.newer("0.1.0", "v0.1.1-test"))
    }

    @Test fun `the same or an older tag is not`() {
        assertFalse(UpdateCheck.newer("0.1.0", "v0.1.0"))
        assertFalse(UpdateCheck.newer("0.1.0", "0.1"))
        assertFalse(UpdateCheck.newer("0.2.0", "v0.1.9"))
        assertFalse(UpdateCheck.newer("0.1.0", "test-build"))
    }
}

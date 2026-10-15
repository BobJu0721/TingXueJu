package com.aichat.app

import com.aichat.app.ui.validMaxTokens
import org.junit.Test
import org.junit.Assert.*

class ChatReplyValidationTest {
    @Test fun acceptsOnlyBlankOrPositiveInteger() {
        listOf("", " ", "1", "2147483647").forEach { assertTrue(it, validMaxTokens(it)) }
        listOf("0", "-1", "1.5", "2147483648", "abc", "1e3", " 2 ").forEach { assertFalse(it, validMaxTokens(it)) }
    }
}

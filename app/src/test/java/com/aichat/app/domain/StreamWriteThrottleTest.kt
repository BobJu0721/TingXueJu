package com.aichat.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamWriteThrottleTest {
    @Test
    fun throttlesIntermediateWritesAndAlwaysAllowsForcedWrites() {
        val throttle = StreamWriteThrottle(intervalNanos = 50)

        assertTrue(throttle.shouldWrite(nowNanos = 100))
        assertFalse(throttle.shouldWrite(nowNanos = 149))
        assertTrue(throttle.shouldWrite(nowNanos = 150))
        assertTrue(throttle.shouldWrite(nowNanos = 151, force = true))
    }
}

package com.aichat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBackgroundSizeTest {
    @Test
    fun fitsLargePortraitImageWithoutUpscaling() {
        val fitted = fittedImageSize(2306, 4096, 1080, 1920)

        assertTrue(fitted.first <= 1080)
        assertTrue(fitted.second <= 1920)
        assertEquals(2306f / 4096f, fitted.first.toFloat() / fitted.second, 0.001f)
        assertEquals(800 to 1200, fittedImageSize(800, 1200, 1080, 1920))
    }
}

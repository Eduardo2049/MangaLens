package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Overlays of the "test with a local image" preview must follow the letterboxed picture, not the box. */
class FittedImageRectTest {

    @Test
    fun `tall page in a wide box is centered horizontally`() {
        // 1000x2000 page inside a 1000x500 box: scale 0.25 -> 250x500, centered at x = 375.
        val rect = fittedImageRect(1000, 2000, 1000f, 500f)
        assertEquals(375f, rect.left, 0.01f)
        assertEquals(0f, rect.top, 0.01f)
        assertEquals(250f, rect.width, 0.01f)
        assertEquals(500f, rect.height, 0.01f)
    }

    @Test
    fun `wide image in a tall box is centered vertically`() {
        val rect = fittedImageRect(2000, 1000, 1000f, 1000f)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(250f, rect.top, 0.01f)
        assertEquals(1000f, rect.width, 0.01f)
        assertEquals(500f, rect.height, 0.01f)
    }

    @Test
    fun `no image means the whole container`() {
        val rect = fittedImageRect(0, 0, 800f, 600f)
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(800f, rect.width, 0.01f)
        assertEquals(600f, rect.height, 0.01f)
    }
}

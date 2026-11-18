package com.example.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenMathTest {

    @Test
    fun `bubble dragged past any edge comes back fully on screen`() {
        assertEquals(0 to 0, ScreenMath.clampToScreen(-300, -50, 200, 80, 1080, 2400))
        assertEquals(880 to 2320, ScreenMath.clampToScreen(5000, 9000, 200, 80, 1080, 2400))
        assertEquals(400 to 900, ScreenMath.clampToScreen(400, 900, 200, 80, 1080, 2400))
    }

    @Test
    fun `window wider than the screen sticks to the left`() {
        assertEquals(0 to 10, ScreenMath.clampToScreen(50, 10, 2000, 80, 1080, 2400))
    }

    @Test
    fun `normal pages are decoded at full size`() {
        assertEquals(1, ScreenMath.sampleSizeFor(1080, 2400, 16_000_000L, 16_384))
    }

    @Test
    fun `huge photo and long webtoon strip are sampled down`() {
        // 8160x6120 (50 MP) -> 4080x3060 (12.5 MP).
        assertEquals(2, ScreenMath.sampleSizeFor(8160, 6120, 16_000_000L, 16_384))
        // 800x40000 strip: side limit -> 200x10000.
        assertEquals(4, ScreenMath.sampleSizeFor(800, 40_000, 16_000_000L, 16_384))
    }

    @Test
    fun `invalid sizes decode with sample 1`() {
        assertEquals(1, ScreenMath.sampleSizeFor(0, -1, 16_000_000L, 16_384))
    }
}

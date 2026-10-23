package com.example.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLogicTest {

    private fun thumb(fill: (Int) -> Int) = FrameThumbnail(4, 4, IntArray(16, fill))

    @Test
    fun `masked cells never count as change`() {
        val page = thumb { 100 }
        // Same page with our translation cards drawn over half of it (masked by the service).
        val withCards = thumb { if (it < 8) FrameThumbnail.MASKED else 100 }
        assertEquals(0f, page.changedFraction(withCards))
    }

    @Test
    fun `scrolling is detected as change`() {
        val before = thumb { it * 10 }
        val after = thumb { (it + 4) * 10 }
        assertTrue(before.changedFraction(after) > 0.9f)
    }

    @Test
    fun `incompatible grids report full change`() {
        assertEquals(1f, thumb { 1 }.changedFraction(FrameThumbnail(2, 2, IntArray(4))))
    }

    @Test
    fun `text cache tolerates small OCR differences`() {
        val cache = TextTranslationCache()
        cache.put("ko>pt", "너 지금 뭐 하는 거야?", "O que você está fazendo?")
        assertEquals("O que você está fazendo?", cache.get("ko>pt", "너 지금 뭐 하는 거야?"))
        assertEquals("O que você está fazendo?", cache.get("ko>pt", "너 지금 뭐 하는 거야"))
        assertNull(cache.get("ja>pt", "너 지금 뭐 하는 거야?"))
        assertNull(cache.get("ko>pt", "완전히 다른 문장입니다"))
    }

    @Test
    fun `page cache matches near-identical fingerprints`() {
        val cache = PageCache<String>()
        cache.put(0b1011L, "page")
        assertEquals("page", cache.get(0b1001L))
        assertNull(cache.get(-1L))
    }

    @Test
    fun `lines of one bubble are merged and noise is dropped`() {
        val blocks = listOf(
            OcrBlock("I can't believe", Box(100, 100, 300, 130), lineHeight = 30),
            OcrBlock("you did that!", Box(110, 135, 290, 165), lineHeight = 30),
            OcrBlock("Another bubble", Box(600, 900, 800, 930), lineHeight = 30),
            OcrBlock("12/45", Box(500, 2300, 560, 2330), lineHeight = 30),
            OcrBlock("TRADUZIR TELA", Box(60, 180, 260, 210), lineHeight = 30)
        )
        val bubbleOverlay = Box(40, 170, 280, 220)
        val result = TextRegions.prepare(blocks, listOf(bubbleOverlay), 1080, 2400)
        assertEquals(listOf("I can't believe you did that!", "Another bubble"), result.map { it.text })
        assertEquals(Box(100, 100, 300, 165), result[0].box)
    }

    @Test
    fun `vertical japanese columns are detected`() {
        val columns = listOf(
            OcrBlock("どうして", Box(500, 100, 540, 300), 40),
            OcrBlock("ここに", Box(450, 100, 490, 260), 40)
        )
        assertTrue(TextRegions.isMostlyVertical(columns))
        assertTrue(!TextRegions.isMostlyVertical(listOf(OcrBlock("Hello there", Box(0, 0, 300, 40), 40))))
    }
}

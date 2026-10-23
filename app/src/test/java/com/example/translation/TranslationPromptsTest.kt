package com.example.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is an Android class, so these run under Robolectric.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationPromptsTest {

    @Test
    fun `parses text translations wrapped in markdown`() {
        val raw = """
            ```json
            {"translations":[{"id":1,"text":"Olá!"},{"id":2,"text":""}]}
            ```
        """.trimIndent()
        assertEquals(mapOf(1 to "Olá!", 2 to ""), TranslationPrompts.parseTextTranslations(raw))
    }

    @Test
    fun `parses text translations after reasoning prose`() {
        val raw = "Sure! Here it is: {\"translations\":[{\"id\":3,\"text\":\"Vamos!\"}]} Hope it helps."
        assertEquals(mapOf(3 to "Vamos!"), TranslationPrompts.parseTextTranslations(raw))
    }

    @Test
    fun `garbage yields no translations`() {
        assertTrue(TranslationPrompts.parseTextTranslations("not json").isEmpty())
    }

    @Test
    fun `vision boxes in 0-1000 are mapped to screen pixels`() {
        val raw = """{"items":[{"text":"何","translation":"O quê?","box":[100,200,150,400],"type":"speech"}]}"""
        val items = TranslationPrompts.parseVisionItems(raw, 576, 1280, 1080, 2400)
        assertEquals(1, items.size)
        assertEquals(Box(216, 240, 432, 360), items[0].box)
        assertEquals("SPEECH", items[0].bubbleType)
    }

    @Test
    fun `vision boxes in absolute pixels of the sent image are rescaled`() {
        val raw = """{"items":[{"text":"a","translation":"b","box":[640,288,1280,576]}]}"""
        val items = TranslationPrompts.parseVisionItems(raw, 576, 1280, 1080, 2400)
        assertEquals(Box(540, 1200, 1080, 2400), items[0].box)
    }

    @Test
    fun `vision boxes snap to precise ocr boxes`() {
        val item = ScreenTranslation("a", "b", Box(100, 100, 300, 300))
        val ocr = listOf(OcrBlock("a", Box(150, 140, 260, 280), 30), OcrBlock("far", Box(800, 800, 900, 900), 30))
        assertEquals(Box(150, 140, 260, 280), TranslationPrompts.refineWithOcr(listOf(item), ocr)[0].box)
    }

    @Test
    fun `user prompt carries ids and context`() {
        val prompt = TranslationPrompts.textUserPrompt(listOf(1 to "안녕"), listOf("누나" to "Irmã"))
        assertTrue(prompt.contains("\"id\":1"))
        assertTrue(prompt.contains("Irmã"))
    }
}

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
    fun `parses compact translations`() {
        val raw = """{"t":[[1,"Saiam já!"],[2,"Ele disse \"não\""]]}"""
        assertEquals(mapOf(1 to "Saiam já!", 2 to "Ele disse \"não\""), TranslationPrompts.parseTextTranslations(raw))
    }

    @Test
    fun `partial stream yields only finished entries`() {
        val streaming = """{"t":[[1,"Pague!"],[2,"Entre"],[3,"Ainda escreven"""
        assertEquals(mapOf(1 to "Pague!", 2 to "Entre"), TranslationPrompts.parsePartialTranslations(streaming))
        val objectStyle = """{"translations":[{"id":4,"text":"Idiota!"},{"id":5,"te"""
        assertEquals(mapOf(4 to "Idiota!"), TranslationPrompts.parsePartialTranslations(objectStyle))
    }

    @Test
    fun `garbage yields no translations`() {
        assertTrue(TranslationPrompts.parseTextTranslations("not json").isEmpty())
    }

    @Test
    fun `user prompt carries ids and context`() {
        val prompt = TranslationPrompts.textUserPrompt(listOf(1 to "안녕"), listOf("누나" to "Irmã"))
        assertTrue(prompt.contains("\"id\":1"))
        assertTrue(prompt.contains("Irmã"))
    }
}

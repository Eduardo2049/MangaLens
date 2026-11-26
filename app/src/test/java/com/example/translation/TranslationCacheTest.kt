package com.example.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the saved cache relies on: change notifications, silent preload and forgetting a page's texts. */
class TranslationCacheTest {

    private val pair = "ja>pt"

    @Test
    fun `put reports new and changed translations only`() {
        val cache = TextTranslationCache()
        val reported = mutableListOf<String>()
        cache.onPut = { _, _, translation -> reported += translation }

        cache.put(pair, "逃げろ!", "Corra!")
        cache.put(pair, "逃げろ!", "Corra!") // streaming then final answer: same value
        cache.put(pair, "逃げろ!", "Fuja!")

        assertEquals(listOf("Corra!", "Fuja!"), reported)
    }

    @Test
    fun `preload fills the cache without reporting`() {
        val cache = TextTranslationCache()
        var reported = 0
        cache.onPut = { _, _, _ -> reported++ }

        cache.preload(pair, listOf("お前は誰だ?" to "Quem é você?", "ありがとう" to "Obrigado"))

        assertEquals("Quem é você?", cache.get(pair, "お前は誰だ?"))
        assertEquals("Obrigado", cache.get(pair, "ありがとう"))
        assertEquals(0, reported)
    }

    @Test
    fun `forget drops the text and its OCR-noise variants but not other sentences`() {
        val cache = TextTranslationCache()
        cache.put("en>pt", "I can't believe you did that!", "Não acredito que você fez isso!")
        cache.put("en>pt", "I can't believe you did this!", "Não acredito que você fez isto!")

        // OCR read the bubble with a 1 in place of the l: still the same bubble.
        val removed = cache.forget("en>pt", "I can't be1ieve you did that!")

        assertEquals(1, removed.size)
        assertNull(cache.get("en>pt", "I can't believe you did that!"))
        // A different word is a different sentence and keeps its translation.
        assertEquals("Não acredito que você fez isto!", cache.get("en>pt", "I can't believe you did this!"))
    }

    @Test
    fun `forget only touches the given language pair`() {
        val cache = TextTranslationCache()
        cache.put("ko>pt", "안녕하세요 여러분", "Olá a todos")
        cache.put("ko>en", "안녕하세요 여러분", "Hello everyone")

        cache.forget("ko>pt", "안녕하세요 여러분")

        assertNull(cache.get("ko>pt", "안녕하세요 여러분"))
        assertEquals("Hello everyone", cache.get("ko>en", "안녕하세요 여러분"))
    }

    @Test
    fun `forget returns normalized keys for the store`() {
        val cache = TextTranslationCache()
        cache.put(pair, "  ドン!! ", "BUM!!")
        val removed = cache.forget(pair, "ドン!!")
        assertTrue(removed.single() == TextTranslationCache.normalize("ドン!!"))
    }
}

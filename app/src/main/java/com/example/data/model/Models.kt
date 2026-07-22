package com.example.data.model

enum class SupportedLanguage(
    val code: String,
    val displayName: String,
    val flagEmoji: String,
    val isVerticalDefault: Boolean = false
) {
    AUTO("auto", "Detectar Automático", "🌐"),
    JAPANESE("ja", "Japonês (日本語)", "🇯🇵", isVerticalDefault = true),
    KOREAN("ko", "Coreano (한국어)", "🇰🇷"),
    CHINESE("zh", "Chinês (中文)", "🇨🇳", isVerticalDefault = true),
    ENGLISH("en", "Inglês (English)", "🇺🇸"),
    SPANISH("es", "Espanhol (Español)", "🇪🇸"),
    PORTUGUESE("pt", "Português (Brasil)", "🇧🇷");

    companion object {
        fun fromCode(code: String): SupportedLanguage {
            return entries.find { it.code.equals(code, ignoreCase = true) } ?: PORTUGUESE
        }

        fun getSourceLanguages(): List<SupportedLanguage> = entries.toList()
        
        fun getTargetLanguages(): List<SupportedLanguage> = entries.filter { it != AUTO }
    }
}

enum class TranslationMode(
    val title: String,
    val description: String
) {
    DYNAMIC("Dinâmico (Automático)", "Traduz continuamente ao rolar ou mudar de página"),
    MANUAL("Manual (Sob Demanda)", "Traduz apenas ao tocar no botão de atualizar")
}

data class BoundingBox(
    val yMin: Float, // 0 to 100 percentage
    val xMin: Float, // 0 to 100 percentage
    val yMax: Float, // 0 to 100 percentage
    val xMax: Float  // 0 to 100 percentage
) {
    val widthPercent: Float get() = (xMax - xMin).coerceAtLeast(5f)
    val heightPercent: Float get() = (yMax - yMin).coerceAtLeast(3f)
}

data class TextOverlay(
    val id: String = java.util.UUID.randomUUID().toString(),
    val originalText: String,
    val translatedText: String,
    val box: BoundingBox,
    val isVertical: Boolean = false,
    val bubbleType: String = "SPEECH" // SPEECH, THOUGHT, NARRATION, SOUND_EFFECT
)

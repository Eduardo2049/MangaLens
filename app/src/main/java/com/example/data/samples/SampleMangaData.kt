package com.example.data.samples

import com.example.data.model.BoundingBox
import com.example.data.model.SupportedLanguage
import com.example.data.model.TextOverlay

data class MangaChapterSample(
    val id: String,
    val title: String,
    val originType: String, // "JAPANESE_MANGA", "KOREAN_WEBTOON", "CHINESE_MANHUA"
    val defaultSourceLang: SupportedLanguage,
    val description: String,
    val panels: List<MangaPanelData>
)

data class MangaPanelData(
    val panelIndex: Int,
    val title: String,
    val sampleOverlays: List<TextOverlay>
)

object SampleMangaData {

    val samples = listOf(
        MangaChapterSample(
            id = "chapter_1_manga_ja",
            title = "Cap. 1: O Despertar do Dragão (Japonês)",
            originType = "Mangá Tradicional (Texto Vertical)",
            defaultSourceLang = SupportedLanguage.JAPANESE,
            description = "Formato de mangá clássico com leitura e falas verticais",
            panels = listOf(
                MangaPanelData(
                    panelIndex = 0,
                    title = "Página 1 - Painel de Abertura",
                    sampleOverlays = listOf(
                        TextOverlay(
                            originalText = "なんだ…この力は！？",
                            translatedText = "O que é isso... esse poder!?",
                            box = BoundingBox(yMin = 10f, xMin = 60f, yMax = 28f, xMax = 90f),
                            isVertical = true,
                            bubbleType = "SPEECH"
                        ),
                        TextOverlay(
                            originalText = "ついに覚醒したか…！",
                            translatedText = "Finalmente você despertou...!",
                            box = BoundingBox(yMin = 35f, xMin = 15f, yMax = 52f, xMax = 42f),
                            isVertical = true,
                            bubbleType = "THOUGHT"
                        ),
                        TextOverlay(
                            originalText = "逃がさないぞ！古の禁忌が解き放たれる！",
                            translatedText = "Você não vai fugir! O selo ancestral foi quebrado!",
                            box = BoundingBox(yMin = 65f, xMin = 50f, yMax = 88f, xMax = 88f),
                            isVertical = true,
                            bubbleType = "SPEECH"
                        )
                    )
                ),
                MangaPanelData(
                    panelIndex = 1,
                    title = "Página 2 - O Confronto",
                    sampleOverlays = listOf(
                        TextOverlay(
                            originalText = "我が名は影の君主！",
                            translatedText = "Meu nome é o Monarca das Sombras!",
                            box = BoundingBox(yMin = 12f, xMin = 20f, yMax = 32f, xMax = 55f),
                            isVertical = true,
                            bubbleType = "SPEECH"
                        ),
                        TextOverlay(
                            originalText = "馬鹿な…勝ち目はない！",
                            translatedText = "Impossível... não temos chance de vencer!",
                            box = BoundingBox(yMin = 50f, xMin = 58f, yMax = 75f, xMax = 88f),
                            isVertical = true,
                            bubbleType = "SPEECH"
                        )
                    )
                )
            )
        ),

        MangaChapterSample(
            id = "chapter_2_webtoon_ko",
            title = "Cap. 2: O Caçador Solitário (Webtoon Coreano)",
            originType = "Manhwa / Webtoon Vertical",
            defaultSourceLang = SupportedLanguage.KOREAN,
            description = "Formato webtoon com scroll contínuo e falas horizontais",
            panels = listOf(
                MangaPanelData(
                    panelIndex = 0,
                    title = "Webtoon Strip 1 - O Calabouço",
                    sampleOverlays = listOf(
                        TextOverlay(
                            originalText = "드디어 때가 왔다...",
                            translatedText = "Finalmente a hora chegou...",
                            box = BoundingBox(yMin = 8f, xMin = 25f, yMax = 22f, xMax = 75f),
                            isVertical = false,
                            bubbleType = "NARRATION"
                        ),
                        TextOverlay(
                            originalText = "너는 절대로 이길 수 없다!",
                            translatedText = "Você jamais poderá vencer!",
                            box = BoundingBox(yMin = 38f, xMin = 15f, yMax = 56f, xMax = 85f),
                            isVertical = false,
                            bubbleType = "SPEECH"
                        ),
                        TextOverlay(
                            originalText = "진정한 힘을 보여주마!",
                            translatedText = "Vou lhe mostrar o verdadeiro poder!",
                            box = BoundingBox(yMin = 68f, xMin = 20f, yMax = 86f, xMax = 80f),
                            isVertical = false,
                            bubbleType = "SPEECH"
                        )
                    )
                ),
                MangaPanelData(
                    panelIndex = 1,
                    title = "Webtoon Strip 2 - Ascensão",
                    sampleOverlays = listOf(
                        TextOverlay(
                            originalText = "시스템: 레벨업이 완료되었습니다.",
                            translatedText = "Sistema: Subida de nível concluída.",
                            box = BoundingBox(yMin = 15f, xMin = 10f, yMax = 35f, xMax = 90f),
                            isVertical = false,
                            bubbleType = "NARRATION"
                        ),
                        TextOverlay(
                            originalText = "이것이 나의 새로운 능력인가...!",
                            translatedText = "Esta é a minha nova habilidade...!",
                            box = BoundingBox(yMin = 52f, xMin = 20f, yMax = 72f, xMax = 80f),
                            isVertical = false,
                            bubbleType = "THOUGHT"
                        )
                    )
                )
            )
        ),

        MangaChapterSample(
            id = "chapter_3_manhua_zh",
            title = "Cap. 3: O Cultivador Celestial (Chinês)",
            originType = "Manhua Chinês",
            defaultSourceLang = SupportedLanguage.CHINESE,
            description = "Estilo de arte manhua com efeitos e caixas de texto soltas",
            panels = listOf(
                MangaPanelData(
                    panelIndex = 0,
                    title = "Manhua Panel 1 - Reencarnação",
                    sampleOverlays = listOf(
                        TextOverlay(
                            originalText = "天地之气，听我号令！",
                            translatedText = "Energia do Céu e da Terra, obedeça ao meu comando!",
                            box = BoundingBox(yMin = 14f, xMin = 15f, yMax = 34f, xMax = 85f),
                            isVertical = false,
                            bubbleType = "SPEECH"
                        ),
                        TextOverlay(
                            originalText = "这不可能…！他的境界竟然提升了！",
                            translatedText = "Impossível...! O cultivo dele aumentou drasticamente!",
                            box = BoundingBox(yMin = 50f, xMin = 20f, yMax = 75f, xMax = 80f),
                            isVertical = false,
                            bubbleType = "SPEECH"
                        )
                    )
                )
            )
        )
    )
}

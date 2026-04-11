package eu.kanade.translation.model

import kotlinx.serialization.Serializable

@Serializable
data class PageAnalysis(
    val imageWidth: Float = 0f,
    val imageHeight: Float = 0f,
    val regions: List<BubbleRegion> = emptyList(),
    val modelVersion: String = "",
) {
    companion object {
        val EMPTY = PageAnalysis()
    }
}

@Serializable
data class DetectedRegion(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val paddingX: Float = 0f,
    val paddingY: Float = 0f,
    val confidence: Float = 0f,
)

@Serializable
enum class BubbleTranslationStatus {
    Pending,
    Translated,
    Error,
}

@Serializable
data class BubbleRegion(
    val id: String = "",
    val sourceText: String? = null,
    val translatedText: String? = null,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val paddingX: Float = 0f,
    val paddingY: Float = 0f,
    val confidence: Float = 0f,
    val translationStatus: BubbleTranslationStatus = BubbleTranslationStatus.Pending,
    val providerId: String? = null,
    val errorMessage: String? = null,
)

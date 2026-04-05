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
data class BubbleRegion(
    val text: String,
    val translatedText: String = "",
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val paddingX: Float = 0f,
    val paddingY: Float = 0f,
)

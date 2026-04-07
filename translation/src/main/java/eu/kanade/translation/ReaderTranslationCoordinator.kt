package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.translation.model.BubbleRegion
import eu.kanade.translation.model.DetectedRegion
import eu.kanade.translation.model.PageAnalysis
import eu.kanade.translation.model.TranslationPageContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.translation.TranslationPreferences

class ReaderTranslationCoordinator(
    private val context: Context,
    private val preferences: TranslationPreferences,
    private val repository: PageAnalysisRepository,
    private val bubbleDetector: BubbleDetector,
    private val ocrEngine: MangaOcrEngine,
    private val apiTranslationService: ApiTranslationService,
    private val cloudPageAnalysisService: CloudPageAnalysisService,
) {
    suspend fun getOrCreate(
        pageContext: TranslationPageContext,
        streamProvider: () -> java.io.InputStream,
    ): PageAnalysis? {
        if (!preferences.enabled.get()) return null
        if (!pageContext.isLocal && !preferences.preprocessOnlinePages.get()) return null

        repository.get(cacheKey(pageContext))?.let { return it }

        val analysis = withContext(Dispatchers.IO) {
            val bitmap = streamProvider().use { BitmapFactory.decodeStream(it) } ?: return@withContext null
            when (preferences.pipelineMode.get()) {
                PIPELINE_MODE_CLOUD -> {
                    cloudPageAnalysisService.analyzePage(
                        bitmap = bitmap,
                        targetLanguage = preferences.targetLanguage.get(),
                        systemPrompt = preferences.apiSystemPrompt.get().takeIf { it.isNotBlank() },
                    )
                }
                else -> {
                    val detected = bubbleDetector.detect(bitmap)
                    if (detected.isEmpty()) {
                        return@withContext null
                    }

                    val regions = detected.mapNotNull { region ->
                        val cropped = bitmap.cropRegion(region) ?: return@mapNotNull null
                        val recognized =
                            ocrEngine.recognize(cropped)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        BubbleRegion(
                            text = recognized,
                            x = region.x,
                            y = region.y,
                            width = region.width,
                            height = region.height,
                            paddingX = region.paddingX,
                            paddingY = region.paddingY,
                        )
                    }
                    if (regions.isEmpty()) {
                        return@withContext null
                    }

                    val translated = regions.map { region ->
                        region.copy(
                            translatedText = apiTranslationService.translate(
                                region.text,
                                preferences.targetLanguage.get(),
                            ),
                        )
                    }
                    PageAnalysis(
                        imageWidth = bitmap.width.toFloat(),
                        imageHeight = bitmap.height.toFloat(),
                        regions = translated,
                        modelVersion = localModelVersion(),
                    )
                }
            }
        } ?: return null

        repository.put(cacheKey(pageContext), analysis)
        return analysis
    }

    private fun cacheKey(pageContext: TranslationPageContext): String =
        buildString {
            append(pageContext.cacheKey)
            append('_')
            append(preferences.pipelineMode.get())
            append('_')
            append(preferences.targetLanguage.get())
            append('_')
            append(
                when (preferences.pipelineMode.get()) {
                    PIPELINE_MODE_CLOUD -> cloudPageAnalysisService.modelVersion
                    else -> localModelVersion()
                },
            )
        }

    private fun localModelVersion(): String =
        buildString {
            append("detector:")
            append(bubbleDetector.modelVersion)
            append("|ocr:")
            append(ocrEngine.modelVersion)
            append("|translator:")
            append(apiTranslationService.providerId)
        }

    private fun Bitmap.cropRegion(region: DetectedRegion): Bitmap? {
        val left = (region.x - region.paddingX / 2f).toInt().coerceIn(0, width - 1)
        val top = (region.y - region.paddingY / 2f).toInt().coerceIn(0, height - 1)
        val right = (region.x + region.width + region.paddingX / 2f).toInt().coerceIn(left + 1, width)
        val bottom = (region.y + region.height + region.paddingY / 2f).toInt().coerceIn(top + 1, height)
        return runCatching {
            Bitmap.createBitmap(this, left, top, right - left, bottom - top)
        }.getOrNull()
    }

    private companion object {
        const val PIPELINE_MODE_CLOUD = "cloud"
    }
}

package eu.kanade.translation

import android.content.Context
import android.graphics.BitmapFactory
import eu.kanade.translation.model.BubbleRegion
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
) {
    suspend fun getOrCreate(
        pageContext: TranslationPageContext,
        streamProvider: () -> java.io.InputStream,
    ): PageAnalysis? {
        if (!preferences.enabled.get()) return null
        if (!pageContext.isLocal && !preferences.preprocessOnlinePages.get()) return null

        repository.get(pageContext.cacheKey)?.let { return it }

        val analysis = withContext(Dispatchers.IO) {
            val bitmap = streamProvider().use { BitmapFactory.decodeStream(it) } ?: return@withContext null
            val detected = bubbleDetector.detect(bitmap)
            val regions = if (detected.isNotEmpty()) {
                detected
            } else {
                val recognized = ocrEngine.recognize(bitmap)?.takeIf { it.isNotBlank() } ?: return@withContext null
                listOf(
                    BubbleRegion(
                        text = recognized,
                        x = 0f,
                        y = 0f,
                        width = bitmap.width.toFloat(),
                        height = bitmap.height.toFloat(),
                        paddingX = 24f,
                        paddingY = 24f,
                    ),
                )
            }
            val translated = regions.map { region ->
                region.copy(
                    translatedText = apiTranslationService.translate(region.text, preferences.targetLanguage.get()),
                )
            }
            PageAnalysis(
                imageWidth = bitmap.width.toFloat(),
                imageHeight = bitmap.height.toFloat(),
                regions = translated,
                modelVersion = "scaffold-v1",
            )
        } ?: return null

        repository.put(pageContext.cacheKey, analysis)
        return analysis
    }
}

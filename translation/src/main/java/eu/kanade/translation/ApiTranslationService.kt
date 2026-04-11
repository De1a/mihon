package eu.kanade.translation

import android.graphics.Bitmap
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

data class BubbleTranslationResult(
    val sourceText: String,
    val translatedText: String,
)

interface ApiTranslationService {
    val providerId: String

    suspend fun translateBubble(
        bitmap: Bitmap,
        targetLanguage: String,
        systemPrompt: String?,
    ): BubbleTranslationResult?
}

class PassthroughApiTranslationService : ApiTranslationService {
    override val providerId: String = "passthrough"

    override suspend fun translateBubble(
        bitmap: Bitmap,
        targetLanguage: String,
        systemPrompt: String?,
    ): BubbleTranslationResult? {
        logcat(LogPriority.WARN) {
            "[TranslationPipeline] stage=translator_passthrough provider=$providerId targetLanguage=$targetLanguage bitmap=${bitmap.width}x${bitmap.height}"
        }
        return null
    }
}

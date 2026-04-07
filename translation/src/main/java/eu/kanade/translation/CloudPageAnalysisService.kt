package eu.kanade.translation

import android.graphics.Bitmap
import eu.kanade.translation.model.PageAnalysis
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

interface CloudPageAnalysisService {
    val modelVersion: String

    suspend fun analyzePage(
        bitmap: Bitmap,
        targetLanguage: String,
        systemPrompt: String?,
    ): PageAnalysis?
}

class UnavailableCloudPageAnalysisService : CloudPageAnalysisService {
    override val modelVersion: String = "unavailable"

    override suspend fun analyzePage(
        bitmap: Bitmap,
        targetLanguage: String,
        systemPrompt: String?,
    ): PageAnalysis? {
        logcat(LogPriority.WARN) {
            "[TranslationPipeline] stage=cloud_unavailable modelVersion=$modelVersion targetLanguage=$targetLanguage bitmap=${bitmap.width}x${bitmap.height}"
        }
        return null
    }
}

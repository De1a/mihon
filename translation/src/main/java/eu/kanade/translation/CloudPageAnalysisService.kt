package eu.kanade.translation

import android.graphics.Bitmap
import eu.kanade.translation.model.PageAnalysis

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
    ): PageAnalysis? = null
}

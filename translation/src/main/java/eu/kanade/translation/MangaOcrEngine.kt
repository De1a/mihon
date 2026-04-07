package eu.kanade.translation

import android.graphics.Bitmap
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

interface MangaOcrEngine {
    val modelVersion: String

    suspend fun recognize(bitmap: Bitmap): String?
}

class UnavailableMangaOcrEngine : MangaOcrEngine {
    override val modelVersion: String = "unavailable"

    override suspend fun recognize(bitmap: Bitmap): String? {
        logcat(LogPriority.WARN) {
            "[TranslationPipeline] stage=ocr_unavailable modelVersion=$modelVersion bitmap=${bitmap.width}x${bitmap.height}"
        }
        return null
    }
}

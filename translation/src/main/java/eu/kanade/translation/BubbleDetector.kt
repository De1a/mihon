package eu.kanade.translation

import android.graphics.Bitmap
import eu.kanade.translation.model.DetectedRegion
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

interface BubbleDetector {
    val modelVersion: String

    suspend fun detect(bitmap: Bitmap): List<DetectedRegion>
}

class UnavailableBubbleDetector : BubbleDetector {
    override val modelVersion: String = "unavailable"

    override suspend fun detect(bitmap: Bitmap): List<DetectedRegion> {
        logcat(LogPriority.WARN) {
            "[TranslationPipeline] stage=detector_unavailable modelVersion=$modelVersion bitmap=${bitmap.width}x${bitmap.height}"
        }
        return emptyList()
    }
}

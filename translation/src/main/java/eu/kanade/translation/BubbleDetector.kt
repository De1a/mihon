package eu.kanade.translation

import android.graphics.Bitmap
import eu.kanade.translation.model.DetectedRegion

interface BubbleDetector {
    val modelVersion: String

    suspend fun detect(bitmap: Bitmap): List<DetectedRegion>
}

class UnavailableBubbleDetector : BubbleDetector {
    override val modelVersion: String = "unavailable"

    override suspend fun detect(bitmap: Bitmap): List<DetectedRegion> = emptyList()
}

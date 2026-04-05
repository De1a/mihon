package eu.kanade.translation

import android.graphics.Bitmap
import eu.kanade.translation.model.BubbleRegion

interface BubbleDetector {
    suspend fun detect(bitmap: Bitmap): List<BubbleRegion>
}

class UnavailableBubbleDetector : BubbleDetector {
    override suspend fun detect(bitmap: Bitmap): List<BubbleRegion> = emptyList()
}

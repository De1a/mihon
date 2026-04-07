package eu.kanade.translation

import android.graphics.Bitmap

interface MangaOcrEngine {
    val modelVersion: String

    suspend fun recognize(bitmap: Bitmap): String?
}

class UnavailableMangaOcrEngine : MangaOcrEngine {
    override val modelVersion: String = "unavailable"

    override suspend fun recognize(bitmap: Bitmap): String? = null
}

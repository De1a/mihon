package eu.kanade.translation

import android.graphics.Bitmap

interface MangaOcrEngine {
    suspend fun recognize(bitmap: Bitmap): String?
}

class UnavailableMangaOcrEngine : MangaOcrEngine {
    override suspend fun recognize(bitmap: Bitmap): String? = null
}

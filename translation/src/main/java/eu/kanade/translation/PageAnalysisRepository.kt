package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.model.PageAnalysis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File

class PageAnalysisRepository(
    private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun cacheDir(): File = File(context.filesDir, "translation/page-analysis").apply { mkdirs() }

    fun get(key: String): PageAnalysis? {
        val file = File(cacheDir(), "$key.json")
        if (!file.exists()) return null
        return runCatching {
            file.inputStream().use { json.decodeFromStream<PageAnalysis>(it) }
        }.getOrNull()
    }

    fun put(key: String, analysis: PageAnalysis) {
        val file = File(cacheDir(), "$key.json")
        file.outputStream().use { json.encodeToStream(analysis, it) }
    }

    fun delete(key: String) {
        File(cacheDir(), "$key.json").delete()
    }
}

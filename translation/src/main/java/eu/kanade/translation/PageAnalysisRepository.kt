package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.model.PageAnalysis
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

class PageAnalysisRepository(
    private val context: Context,
) {
    private val logTag = "TranslationPipeline"
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun cacheDir(): File = File(context.filesDir, "translation/page-analysis").apply { mkdirs() }

    fun get(key: String): PageAnalysis? {
        val file = File(cacheDir(), "$key.json")
        if (!file.exists()) {
            logcat(LogPriority.INFO) { "[$logTag] stage=cache_read_miss pageKey=$key" }
            return null
        }
        return runCatching {
            file.inputStream().use { json.decodeFromStream<PageAnalysis>(it) }
        }
            .onSuccess {
                logcat(LogPriority.INFO) { "[$logTag] stage=cache_read_hit pageKey=$key" }
            }
            .onFailure { error ->
                logcat(LogPriority.WARN, error) { "[$logTag] stage=cache_read_corrupt pageKey=$key" }
            }
            .getOrNull()
    }

    fun put(key: String, analysis: PageAnalysis) {
        val file = File(cacheDir(), "$key.json")
        file.outputStream().use { json.encodeToStream(analysis, it) }
        logcat(LogPriority.INFO) {
            "[$logTag] stage=cache_write_success pageKey=$key regions=${analysis.regions.size} modelVersion=${analysis.modelVersion}"
        }
    }

    fun delete(key: String) {
        File(cacheDir(), "$key.json").delete()
        logcat(LogPriority.INFO) { "[$logTag] stage=cache_delete pageKey=$key" }
    }
}

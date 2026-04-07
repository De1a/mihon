package eu.kanade.translation

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

interface ApiTranslationService {
    val providerId: String

    suspend fun translate(text: String, targetLanguage: String): String
}

class PassthroughApiTranslationService : ApiTranslationService {
    override val providerId: String = "passthrough"

    override suspend fun translate(text: String, targetLanguage: String): String {
        logcat(LogPriority.WARN) {
            "[TranslationPipeline] stage=translator_passthrough provider=$providerId targetLanguage=$targetLanguage textLength=${text.length}"
        }
        return text
    }
}

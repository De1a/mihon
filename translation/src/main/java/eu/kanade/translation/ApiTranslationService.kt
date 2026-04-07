package eu.kanade.translation

interface ApiTranslationService {
    val providerId: String

    suspend fun translate(text: String, targetLanguage: String): String
}

class PassthroughApiTranslationService : ApiTranslationService {
    override val providerId: String = "passthrough"

    override suspend fun translate(text: String, targetLanguage: String): String = text
}

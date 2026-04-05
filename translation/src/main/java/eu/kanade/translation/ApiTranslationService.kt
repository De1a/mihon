package eu.kanade.translation

interface ApiTranslationService {
    suspend fun translate(text: String, targetLanguage: String): String
}

class PassthroughApiTranslationService : ApiTranslationService {
    override suspend fun translate(text: String, targetLanguage: String): String = text
}

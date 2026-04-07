package tachiyomi.domain.translation

import tachiyomi.core.common.preference.PreferenceStore

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val enabled = preferenceStore.getBoolean("translation_feature_enabled", false)
    val preprocessOnlinePages = preferenceStore.getBoolean("translation_preprocess_online_pages", true)
    val pipelineMode = preferenceStore.getString("translation_pipeline_mode", "local")
    val targetLanguage = preferenceStore.getString("translation_target_language", "en")
    val apiProvider = preferenceStore.getString("translation_api_provider", "openai_compatible")
    val apiBaseUrl = preferenceStore.getString("translation_api_base_url", "")
    val apiModel = preferenceStore.getString("translation_api_model", "gpt-4.1-mini")
    val apiKey = preferenceStore.getString("translation_api_key", "")
    val apiSystemPrompt = preferenceStore.getString("translation_api_system_prompt", "")
    val overlayScalePercent = preferenceStore.getInt("translation_overlay_scale_percent", 100)
}

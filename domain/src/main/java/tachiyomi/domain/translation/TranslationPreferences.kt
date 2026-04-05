package tachiyomi.domain.translation

import tachiyomi.core.common.preference.PreferenceStore

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val enabled = preferenceStore.getBoolean("translation_feature_enabled", false)
    val preprocessOnlinePages = preferenceStore.getBoolean("translation_preprocess_online_pages", true)
    val targetLanguage = preferenceStore.getString("translation_target_language", "en")
    val apiProvider = preferenceStore.getString("translation_api_provider", "openrouter")
    val apiKey = preferenceStore.getString("translation_api_key", "")
    val overlayScalePercent = preferenceStore.getInt("translation_overlay_scale_percent", 100)
}

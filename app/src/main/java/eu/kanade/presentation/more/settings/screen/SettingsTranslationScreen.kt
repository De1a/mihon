package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.translation.ModelDownloadManager
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_translations

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences = remember { Injekt.get<TranslationPreferences>() }
        val readerPreferences = remember { Injekt.get<ReaderPreferences>() }
        val modelDownloadManager = remember { Injekt.get<ModelDownloadManager>() }
        val enabled = preferences.enabled.get()
        val cloudMode = preferences.pipelineMode.get() == "cloud"
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.enabled,
                title = stringResource(MR.strings.pref_enable_translation_feature),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = preferences.preprocessOnlinePages,
                title = stringResource(MR.strings.pref_preprocess_online_pages),
                enabled = enabled,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.pipelineMode,
                title = stringResource(MR.strings.pref_translation_pipeline_mode),
                entries = persistentMapOf(
                    "local" to stringResource(MR.strings.pref_translation_pipeline_mode_local),
                    "cloud" to stringResource(MR.strings.pref_translation_pipeline_mode_cloud),
                ),
                enabled = enabled,
            ),
            Preference.PreferenceItem.ListPreference(
                preference = preferences.targetLanguage,
                title = stringResource(MR.strings.pref_translation_target_language),
                entries = persistentMapOf(
                    "en" to "English",
                    "zh-CN" to "Chinese (Simplified)",
                    "zh-TW" to "Chinese (Traditional)",
                    "ko" to "Korean",
                ),
                enabled = enabled,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = preferences.apiBaseUrl,
                title = stringResource(MR.strings.pref_translation_api_base_url),
                enabled = enabled && cloudMode,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = preferences.apiModel,
                title = stringResource(MR.strings.pref_translation_api_model),
                enabled = enabled && cloudMode,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = preferences.apiKey,
                title = stringResource(MR.strings.pref_translation_api_key),
                enabled = enabled && cloudMode,
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = preferences.apiSystemPrompt,
                title = stringResource(MR.strings.pref_translation_api_system_prompt),
                enabled = enabled && cloudMode,
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_category_display),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = readerPreferences.showTranslations,
                        title = stringResource(MR.strings.pref_show_translations),
                        enabled = enabled,
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        value = preferences.overlayScalePercent.get(),
                        title = stringResource(MR.strings.pref_translation_overlay_scale),
                        valueString = "${preferences.overlayScalePercent.get()}%",
                        valueRange = 70..140,
                        enabled = enabled,
                        onValueChanged = preferences.overlayScalePercent::set,
                    ),
                ),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_translation_model_status),
                subtitle = modelDownloadManager.getStatusSummary(),
            ),
        )
    }
}

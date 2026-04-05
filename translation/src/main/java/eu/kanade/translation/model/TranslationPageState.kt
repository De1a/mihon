package eu.kanade.translation.model

sealed interface TranslationPageState {
    data object Idle : TranslationPageState
    data object Loading : TranslationPageState
    data class Ready(val analysis: PageAnalysis) : TranslationPageState
    data class Error(val message: String) : TranslationPageState
}

package eu.kanade.translation.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelArtifact(
    val id: String,
    val version: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val runtime: Runtime,
) {
    enum class Runtime {
        TFLite,
        OnnxRuntime,
        ExecuTorch,
        OpenAiCompatible,
        Unknown,
    }
}

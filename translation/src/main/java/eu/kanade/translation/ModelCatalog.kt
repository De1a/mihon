package eu.kanade.translation

import eu.kanade.translation.model.ModelArtifact
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

interface ModelCatalog {
    fun artifacts(): ImmutableList<ModelArtifact>
}

class StaticModelCatalog : ModelCatalog {
    override fun artifacts(): ImmutableList<ModelArtifact> = persistentListOf(
        ModelArtifact(
            id = "manga-bubble-detector",
            version = "lightweight-tflite-placeholder",
            url = "https://example.invalid/manga-bubble-detector",
            sha256 = "",
            sizeBytes = 0,
            runtime = ModelArtifact.Runtime.TFLite,
        ),
        ModelArtifact(
            id = "manga-ocr-android",
            version = "lightweight-tflite-placeholder",
            url = "https://example.invalid/manga-ocr-android",
            sha256 = "",
            sizeBytes = 0,
            runtime = ModelArtifact.Runtime.TFLite,
        ),
    )
}

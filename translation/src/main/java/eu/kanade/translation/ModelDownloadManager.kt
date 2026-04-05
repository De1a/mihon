package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.model.ModelArtifact
import java.io.File

class ModelDownloadManager(
    private val context: Context,
    private val modelCatalog: ModelCatalog,
) {
    fun modelsDir(): File = File(context.filesDir, "translation-models").apply { mkdirs() }

    fun artifactDir(artifact: ModelArtifact): File =
        File(modelsDir(), "${artifact.id}/${artifact.version}").apply { mkdirs() }

    fun isReady(artifact: ModelArtifact): Boolean =
        artifactDir(artifact).listFiles()?.isNotEmpty() == true

    fun getStatusSummary(): String {
        val ready = modelCatalog.artifacts().count(::isReady)
        return "$ready/${modelCatalog.artifacts().size} models ready"
    }
}

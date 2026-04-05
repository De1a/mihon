package eu.kanade.translation.model

data class TranslationPageContext(
    val sourceId: Long,
    val mangaId: Long,
    val chapterId: Long,
    val chapterName: String,
    val pageIndex: Int,
    val imageUrl: String?,
    val isLocal: Boolean,
) {
    val cacheKey: String =
        buildString {
            append(sourceId)
            append('_')
            append(mangaId)
            append('_')
            append(chapterId)
            append('_')
            append(pageIndex)
            imageUrl?.takeIf { it.isNotBlank() }?.let {
                append('_')
                append(it.hashCode())
            }
        }
}

package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.translation.model.BubbleRegion
import eu.kanade.translation.model.DetectedRegion
import eu.kanade.translation.model.PageAnalysis
import eu.kanade.translation.model.TranslationPageContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences

class ReaderTranslationCoordinator(
    private val context: Context,
    private val preferences: TranslationPreferences,
    private val repository: PageAnalysisRepository,
    private val bubbleDetector: BubbleDetector,
    private val ocrEngine: MangaOcrEngine,
    private val apiTranslationService: ApiTranslationService,
    private val cloudPageAnalysisService: CloudPageAnalysisService,
) {
    suspend fun getOrCreate(
        pageContext: TranslationPageContext,
        streamProvider: () -> java.io.InputStream,
    ): PageAnalysis? {
        val pageKey = cacheKey(pageContext)
        val mode = preferences.pipelineMode.get()
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] stage=start pageKey=$pageKey mode=$mode isLocalPage=${pageContext.isLocal}"
        }

        if (!preferences.enabled.get()) {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=skip_feature_disabled pageKey=$pageKey mode=$mode"
            }
            return null
        }
        if (!pageContext.isLocal && !preferences.preprocessOnlinePages.get()) {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=skip_online_preprocess_disabled pageKey=$pageKey mode=$mode"
            }
            return null
        }

        repository.get(pageKey)?.let {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=analysis_ready pageKey=$pageKey source=cache regions=${it.regions.size} mode=$mode"
            }
            return it
        }

        val analysis = withContext(Dispatchers.IO) {
            val bitmap = streamProvider().use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                logcat(LogPriority.WARN) {
                    "[TranslationPipeline] stage=decode_bitmap_fail pageKey=$pageKey mode=$mode"
                }
                return@withContext null
            }
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=decode_bitmap_success pageKey=$pageKey mode=$mode bitmap=${bitmap.width}x${bitmap.height}"
            }
            when (mode) {
                PIPELINE_MODE_CLOUD -> {
                    logcat(LogPriority.INFO) {
                        "[TranslationPipeline] stage=mode_cloud pageKey=$pageKey targetLanguage=${preferences.targetLanguage.get()}"
                    }
                    cloudPageAnalysisService.analyzePage(
                        bitmap = bitmap,
                        targetLanguage = preferences.targetLanguage.get(),
                        systemPrompt = preferences.apiSystemPrompt.get().takeIf { it.isNotBlank() },
                    )
                }
                else -> {
                    logcat(LogPriority.INFO) {
                        "[TranslationPipeline] stage=mode_local pageKey=$pageKey targetLanguage=${preferences.targetLanguage.get()}"
                    }
                    val detected = bubbleDetector.detect(bitmap)
                    if (detected.isEmpty()) {
                        logcat(LogPriority.WARN) {
                            "[TranslationPipeline] stage=detector_empty pageKey=$pageKey mode=$mode"
                        }
                        return@withContext null
                    }
                    logcat(LogPriority.INFO) {
                        "[TranslationPipeline] stage=detector_success pageKey=$pageKey regions=${detected.size} mode=$mode"
                    }

                    val regions = detected.mapNotNull { region ->
                        val cropped = bitmap.cropRegion(region) ?: return@mapNotNull null
                        val recognized =
                            ocrEngine.recognize(cropped)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        BubbleRegion(
                            text = recognized,
                            x = region.x,
                            y = region.y,
                            width = region.width,
                            height = region.height,
                            paddingX = region.paddingX,
                            paddingY = region.paddingY,
                        )
                    }
                    if (regions.isEmpty()) {
                        logcat(LogPriority.WARN) {
                            "[TranslationPipeline] stage=ocr_empty pageKey=$pageKey mode=$mode"
                        }
                        return@withContext null
                    }
                    logcat(LogPriority.INFO) {
                        "[TranslationPipeline] stage=ocr_success pageKey=$pageKey recognizedRegions=${regions.size} mode=$mode"
                    }

                    val translated = regions.map { region ->
                        region.copy(
                            translatedText = apiTranslationService.translate(
                                region.text,
                                preferences.targetLanguage.get(),
                            ),
                        )
                    }
                    logcat(LogPriority.INFO) {
                        "[TranslationPipeline] stage=translate_success pageKey=$pageKey translatedRegions=${translated.size} provider=${apiTranslationService.providerId}"
                    }
                    PageAnalysis(
                        imageWidth = bitmap.width.toFloat(),
                        imageHeight = bitmap.height.toFloat(),
                        regions = translated,
                        modelVersion = localModelVersion(),
                    )
                }
            }
        } ?: run {
            logcat(LogPriority.WARN) {
                "[TranslationPipeline] stage=return_null pageKey=$pageKey mode=$mode"
            }
            return null
        }

        repository.put(pageKey, analysis)
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] stage=analysis_saved pageKey=$pageKey regions=${analysis.regions.size} modelVersion=${analysis.modelVersion}"
        }
        return analysis
    }

    private fun cacheKey(pageContext: TranslationPageContext): String =
        buildString {
            append(pageContext.cacheKey)
            append('_')
            append(preferences.pipelineMode.get())
            append('_')
            append(preferences.targetLanguage.get())
            append('_')
            append(
                when (preferences.pipelineMode.get()) {
                    PIPELINE_MODE_CLOUD -> cloudPageAnalysisService.modelVersion
                    else -> localModelVersion()
                },
            )
        }

    private fun localModelVersion(): String =
        buildString {
            append("detector:")
            append(bubbleDetector.modelVersion)
            append("|ocr:")
            append(ocrEngine.modelVersion)
            append("|translator:")
            append(apiTranslationService.providerId)
        }

    private fun Bitmap.cropRegion(region: DetectedRegion): Bitmap? {
        val left = (region.x - region.paddingX / 2f).toInt().coerceIn(0, width - 1)
        val top = (region.y - region.paddingY / 2f).toInt().coerceIn(0, height - 1)
        val right = (region.x + region.width + region.paddingX / 2f).toInt().coerceIn(left + 1, width)
        val bottom = (region.y + region.height + region.paddingY / 2f).toInt().coerceIn(top + 1, height)
        return runCatching {
            Bitmap.createBitmap(this, left, top, right - left, bottom - top)
        }.getOrNull()
    }

    private companion object {
        const val PIPELINE_MODE_CLOUD = "cloud"
    }
}

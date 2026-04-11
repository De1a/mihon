package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.translation.model.BubbleRegion
import eu.kanade.translation.model.BubbleTranslationStatus
import eu.kanade.translation.model.DetectedRegion
import eu.kanade.translation.model.PageAnalysis
import eu.kanade.translation.model.TranslationPageContext
import java.util.UUID
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
        return getOrCreateDetection(pageContext, streamProvider)
    }

    suspend fun getOrCreateDetection(
        pageContext: TranslationPageContext,
        streamProvider: () -> java.io.InputStream,
    ): PageAnalysis? {
        val pageKey = cacheKey(pageContext)
        val mode = preferences.pipelineMode.get()
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] stage=detection_start mode=$mode isLocalPage=${pageContext.isLocal}"
        }

        if (!shouldProcess(pageContext, mode)) {
            return null
        }

        withContext(Dispatchers.IO) {
            repository.get(pageKey)
        }?.let {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=detection_cache_hit regions=${it.regions.size} mode=$mode"
            }
            return it
        }

        val analysis = withContext(Dispatchers.IO) {
            val bitmap = streamProvider().use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                logcat(LogPriority.WARN) {
                    "[TranslationPipeline] stage=decode_bitmap_fail mode=$mode"
                }
                return@withContext null
            }
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=decode_bitmap_success mode=$mode bitmap=${bitmap.width}x${bitmap.height}"
            }

            when (mode) {
                PIPELINE_MODE_CLOUD -> {
                    logcat(LogPriority.INFO) {
                        "[TranslationPipeline] stage=mode_cloud targetLanguage=${preferences.targetLanguage.get()}"
                    }
                    cloudPageAnalysisService.analyzePage(
                        bitmap = bitmap,
                        targetLanguage = preferences.targetLanguage.get(),
                        systemPrompt = currentSystemPrompt(),
                    )
                }
                else -> buildDetectionAnalysis(mode, bitmap)
            }
        } ?: run {
            logcat(LogPriority.WARN) {
                "[TranslationPipeline] stage=detection_return_null mode=$mode"
            }
            return null
        }

        withContext(Dispatchers.IO) {
            repository.put(pageKey, analysis)
        }
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] stage=detection_saved regions=${analysis.regions.size} modelVersion=${analysis.modelVersion}"
        }
        return analysis
    }

    suspend fun translateBubble(
        pageContext: TranslationPageContext,
        bubbleId: String,
        streamProvider: () -> java.io.InputStream,
    ): PageAnalysis? {
        val pageKey = cacheKey(pageContext)
        val mode = preferences.pipelineMode.get()
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] stage=bubble_translate_start bubbleId=$bubbleId mode=$mode"
        }

        if (!shouldProcess(pageContext, mode)) {
            return null
        }
        if (mode == PIPELINE_MODE_CLOUD) {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=bubble_translate_skip_cloud bubbleId=$bubbleId"
            }
            return withContext(Dispatchers.IO) {
                repository.get(pageKey)
            }
        }

        val cachedAnalysis = withContext(Dispatchers.IO) {
            repository.get(pageKey)
        }
            ?: getOrCreateDetection(pageContext, streamProvider)
            ?: return null
        val regionIndex = cachedAnalysis.regions.indexOfFirst { it.id == bubbleId }
        if (regionIndex == -1) {
            logcat(LogPriority.WARN) {
                "[TranslationPipeline] stage=bubble_translate_missing bubbleId=$bubbleId"
            }
            return cachedAnalysis
        }

        val existingRegion = cachedAnalysis.regions[regionIndex]
        if (
            existingRegion.translationStatus == BubbleTranslationStatus.Translated &&
            !existingRegion.translatedText.isNullOrBlank()
        ) {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=bubble_translate_cache_hit bubbleId=$bubbleId"
            }
            return cachedAnalysis
        }

        val translatedRegion = withContext(Dispatchers.IO) {
            val bitmap = streamProvider().use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                logcat(LogPriority.WARN) {
                    "[TranslationPipeline] stage=bubble_translate_decode_fail bubbleId=$bubbleId"
                }
                return@withContext null
            }
            val cropped = bitmap.cropRegion(existingRegion)
            if (cropped == null) {
                logcat(LogPriority.WARN) {
                    "[TranslationPipeline] stage=bubble_translate_crop_fail bubbleId=$bubbleId"
                }
                return@withContext existingRegion.copy(
                    translationStatus = BubbleTranslationStatus.Error,
                    errorMessage = "Failed to crop bubble image",
                )
            }
            val result = apiTranslationService.translateBubble(
                bitmap = cropped,
                targetLanguage = preferences.targetLanguage.get(),
                systemPrompt = currentSystemPrompt(),
            )
            if (result == null) {
                logcat(LogPriority.WARN) {
                    "[TranslationPipeline] stage=bubble_translate_empty bubbleId=$bubbleId provider=${apiTranslationService.providerId}"
                }
                return@withContext existingRegion.copy(
                    translationStatus = BubbleTranslationStatus.Error,
                    errorMessage = "Empty translation result",
                )
            }
            existingRegion.copy(
                sourceText = result.sourceText,
                translatedText = result.translatedText,
                translationStatus = BubbleTranslationStatus.Translated,
                providerId = apiTranslationService.providerId,
                errorMessage = null,
            )
        } ?: return cachedAnalysis

        val updatedAnalysis = cachedAnalysis.copy(
            regions = cachedAnalysis.regions.mapIndexed { index, region ->
                if (index == regionIndex) translatedRegion else region
            },
        )
        withContext(Dispatchers.IO) {
            repository.put(pageKey, updatedAnalysis)
        }
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] stage=bubble_translate_saved bubbleId=$bubbleId status=${translatedRegion.translationStatus}"
        }
        return updatedAnalysis
    }

    private fun shouldProcess(pageContext: TranslationPageContext, mode: String): Boolean {
        if (!preferences.enabled.get()) {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=skip_feature_disabled mode=$mode"
            }
            return false
        }
        if (!pageContext.isLocal && !preferences.preprocessOnlinePages.get()) {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] stage=skip_online_preprocess_disabled mode=$mode"
            }
            return false
        }
        return true
    }

    private suspend fun buildDetectionAnalysis(mode: String, bitmap: Bitmap): PageAnalysis? {
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] stage=mode_local targetLanguage=${preferences.targetLanguage.get()}"
        }
        val detected = bubbleDetector.detect(bitmap)
        if (detected.isEmpty()) {
            logcat(LogPriority.WARN) {
                "[TranslationPipeline] stage=detector_empty mode=$mode"
            }
            return null
        }
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] stage=detector_success regions=${detected.size} mode=$mode"
        }

        return PageAnalysis(
            imageWidth = bitmap.width.toFloat(),
            imageHeight = bitmap.height.toFloat(),
            regions = detected.mapIndexed { index, region -> region.toBubbleRegion(index) },
            modelVersion = localDetectionModelVersion(),
        )
    }

    private fun DetectedRegion.toBubbleRegion(index: Int): BubbleRegion {
        return BubbleRegion(
            id = "bubble-${index + 1}",
            x = x,
            y = y,
            width = width,
            height = height,
            paddingX = paddingX,
            paddingY = paddingY,
            confidence = confidence,
            translationStatus = BubbleTranslationStatus.Pending,
        )
    }

    private fun cacheKey(pageContext: TranslationPageContext): String =
        buildString {
            append(pageContext.cacheKey)
            append('_')
            append(preferences.pipelineMode.get())
            append('_')
            append(preferences.targetLanguage.get())
            append('_')
            append(translationConfigFingerprint())
            append('_')
            append(
                when (preferences.pipelineMode.get()) {
                    PIPELINE_MODE_CLOUD -> cloudPageAnalysisService.modelVersion
                    else -> localDetectionModelVersion()
                },
            )
        }

    private fun translationConfigFingerprint(): String =
        UUID.nameUUIDFromBytes(
            listOf(
                preferences.apiProvider.get(),
                preferences.apiBaseUrl.get(),
                preferences.apiModel.get(),
                currentSystemPrompt().orEmpty(),
            ).joinToString(separator = "\u0000").toByteArray(),
        ).toString()

    private fun currentSystemPrompt(): String? =
        preferences.apiSystemPrompt.get().takeIf { it.isNotBlank() }

    private fun localDetectionModelVersion(): String =
        buildString {
            append("detector:")
            append(bubbleDetector.modelVersion)
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

    private fun Bitmap.cropRegion(region: BubbleRegion): Bitmap? {
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

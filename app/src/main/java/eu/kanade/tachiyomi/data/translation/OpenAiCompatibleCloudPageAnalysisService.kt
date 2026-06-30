package eu.kanade.tachiyomi.data.translation

import android.graphics.Bitmap
import android.util.Base64
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.translation.CloudPageAnalysisService
import eu.kanade.translation.model.BubbleRegion
import eu.kanade.translation.model.BubbleTranslationStatus
import eu.kanade.translation.model.PageAnalysis
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.domain.translation.TranslationPreferences

private const val MAX_DIMENSION = 1024
private const val JPEG_QUALITY = 90
private const val LOG_TAG = "[TranslationPipeline]"
private const val PROVIDER_ID = "openai_compatible"

private val DEFAULT_SYSTEM_PROMPT = """
    You are a manga page translator. The user sends a single manga page image.
    Detect every speech bubble or text region, read its original text, and translate it.
    Respond with STRICT JSON only (no markdown, no prose) in exactly this shape:
    {"bubbles":[{"x":<float>,"y":<float>,"width":<float>,"height":<float>,"source":"<original>","translation":"<translated>"}]}
    Coordinates are normalized to the image size in the range [0,1] (x,y = top-left corner).
    If the page has no text, return {"bubbles":[]}.
""".trimIndent()

/**
 * Cloud translation pipeline backed by an OpenAI-compatible vision chat/completions endpoint.
 *
 * The whole page is sent as one image; the model returns bubble boxes (normalized [0,1]),
 * original text and translated text in a single pass, so [analyzePage] returns regions that
 * are already translated (cloud mode does not re-translate per bubble).
 *
 * Reads apiBaseUrl/apiModel/apiKey from [TranslationPreferences] itself. Any failure
 * (missing credentials, network, non-2xx, parse error) degrades to null.
 */
class OpenAiCompatibleCloudPageAnalysisService(
    private val networkHelper: NetworkHelper,
    private val preferences: TranslationPreferences,
    private val json: Json,
) : CloudPageAnalysisService {

    override val modelVersion: String
        get() = "cloud:${preferences.apiModel.get()}"

    override suspend fun analyzePage(
        bitmap: Bitmap,
        targetLanguage: String,
        systemPrompt: String?,
    ): PageAnalysis? {
        val apiKey = preferences.apiKey.get()
        if (apiKey.isBlank()) {
            logcat(LogPriority.WARN) { "$LOG_TAG stage=cloud_no_api_key" }
            return null
        }
        val baseUrl = preferences.apiBaseUrl.get().trim()
        if (baseUrl.isBlank()) {
            logcat(LogPriority.WARN) { "$LOG_TAG stage=cloud_no_base_url" }
            return null
        }
        val model = preferences.apiModel.get()

        return try {
            val dataUri = bitmap.toJpegDataUri()
            val system = systemPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
            val userText = "Image is ${bitmap.width}x${bitmap.height}px. Detect bubbles, OCR the " +
                "original text and translate it to \"$targetLanguage\". Return normalized [0,1] coordinates."
            val requestJson = buildRequestJson(model, system, userText, dataUri)

            val response = networkHelper.client
                .newCall(POST(resolveEndpoint(baseUrl), headers = authHeaders(apiKey), body = requestJson.toRequestBody(jsonMime)))
                .awaitSuccess()
                .use { it.body.string() }

            val content = json.decodeFromString(ChatResponse.serializer(), response)
                .choices.firstOrNull()?.message?.content
            if (content.isNullOrBlank()) {
                logcat(LogPriority.WARN) { "$LOG_TAG stage=cloud_empty_content" }
                return null
            }

            val payload = json.decodeFromString(BubblesPayload.serializer(), content.stripJsonFence())
            val analysis = payload.toPageAnalysis(bitmap.width.toFloat(), bitmap.height.toFloat(), model)
            logcat(LogPriority.INFO) { "$LOG_TAG stage=cloud_ok bubbles=${analysis.regions.size}" }
            analysis
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR) { "$LOG_TAG stage=cloud_failed\n${e.asLog()}" }
            null
        }
    }

    private fun resolveEndpoint(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    private fun authHeaders(apiKey: String): Headers =
        Headers.Builder().add("Authorization", "Bearer $apiKey").build()

    private fun buildRequestJson(model: String, system: String, userText: String, dataUri: String): String =
        buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", userText)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", dataUri)
                            }
                        }
                    }
                }
            }
            putJsonObject("response_format") {
                put("type", "json_object")
            }
        }.toString()

    private fun Bitmap.toJpegDataUri(): String {
        val scaled = downscale(MAX_DIMENSION)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== this) scaled.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun Bitmap.downscale(maxDimension: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return this
        val ratio = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * ratio).roundToInt(), (height * ratio).roundToInt(), true)
    }

    private fun String.stripJsonFence(): String {
        val trimmed = trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun BubblesPayload.toPageAnalysis(imageWidth: Float, imageHeight: Float, model: String): PageAnalysis {
        val regions = bubbles.mapIndexed { index, bubble ->
            BubbleRegion(
                id = "bubble-${index + 1}",
                sourceText = bubble.source,
                translatedText = bubble.translation,
                x = bubble.x * imageWidth,
                y = bubble.y * imageHeight,
                width = bubble.width * imageWidth,
                height = bubble.height * imageHeight,
                confidence = 1f,
                translationStatus = BubbleTranslationStatus.Translated,
                providerId = PROVIDER_ID,
            )
        }
        return PageAnalysis(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            regions = regions,
            modelVersion = "cloud:$model",
        )
    }

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList()) {
        @Serializable
        data class Choice(val message: Message = Message())

        @Serializable
        data class Message(val content: String? = null)
    }

    @Serializable
    private data class BubblesPayload(val bubbles: List<CloudBubble> = emptyList())

    @Serializable
    private data class CloudBubble(
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
        val source: String? = null,
        val translation: String? = null,
    )
}

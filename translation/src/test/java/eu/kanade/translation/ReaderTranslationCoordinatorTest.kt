package eu.kanade.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.translation.model.BubbleRegion
import eu.kanade.translation.model.BubbleTranslationStatus
import eu.kanade.translation.model.DetectedRegion
import eu.kanade.translation.model.PageAnalysis
import eu.kanade.translation.model.TranslationPageContext
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.TranslationPreferences
import java.io.ByteArrayInputStream

class ReaderTranslationCoordinatorTest {

    private lateinit var context: Context
    private lateinit var preferences: TranslationPreferences
    private lateinit var repository: PageAnalysisRepository
    private lateinit var bubbleDetector: BubbleDetector
    private lateinit var ocrEngine: MangaOcrEngine
    private lateinit var apiTranslationService: ApiTranslationService
    private lateinit var cloudPageAnalysisService: CloudPageAnalysisService
    private lateinit var coordinator: ReaderTranslationCoordinator

    @BeforeEach
    fun setUp() {
        context = mockk(relaxed = true)
        preferences = TranslationPreferences(InMemoryPreferenceStore())
        preferences.enabled.set(true)
        preferences.pipelineMode.set("local")
        preferences.targetLanguage.set("en")
        preferences.apiSystemPrompt.set("Translate manga dialogue naturally")

        repository = mockk(relaxed = true)
        bubbleDetector = mockk()
        ocrEngine = mockk()
        apiTranslationService = mockk()
        cloudPageAnalysisService = mockk()

        every { bubbleDetector.modelVersion } returns "detector-test"
        every { ocrEngine.modelVersion } returns "ocr-test"
        every { apiTranslationService.providerId } returns "provider-test"
        every { cloudPageAnalysisService.modelVersion } returns "cloud-test"

        coordinator = ReaderTranslationCoordinator(
            context = context,
            preferences = preferences,
            repository = repository,
            bubbleDetector = bubbleDetector,
            ocrEngine = ocrEngine,
            apiTranslationService = apiTranslationService,
            cloudPageAnalysisService = cloudPageAnalysisService,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `getOrCreateDetection returns cached analysis without decoding or detecting`() = runTest {
        val cachedAnalysis = PageAnalysis(
            imageWidth = 1200f,
            imageHeight = 1800f,
            regions = listOf(
                BubbleRegion(
                    id = "bubble-1",
                    x = 10f,
                    y = 20f,
                    width = 100f,
                    height = 60f,
                    confidence = 0.94f,
                    translationStatus = BubbleTranslationStatus.Pending,
                ),
            ),
            modelVersion = "detector:test",
        )
        every { repository.get(any()) } returns cachedAnalysis
        var streamRequested = false

        val result = coordinator.getOrCreateDetection(testPageContext()) {
            streamRequested = true
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }

        result shouldBe cachedAnalysis
        streamRequested shouldBe false
        coVerify(exactly = 0) { bubbleDetector.detect(any()) }
        coVerify(exactly = 0) { apiTranslationService.translateBubble(any(), any(), any()) }
        verify(exactly = 0) { repository.put(any(), any()) }
    }

    @Test
    fun `getOrCreateDetection stores bbox only regions when cache is missing`() = runTest {
        mockkStatic(BitmapFactory::class)
        val decodedBitmap = mockk<Bitmap>()
        every { BitmapFactory.decodeStream(any()) } returns decodedBitmap
        every { decodedBitmap.width } returns 1080
        every { decodedBitmap.height } returns 1920
        every { repository.get(any()) } returns null
        coEvery { bubbleDetector.detect(decodedBitmap) } returns listOf(
            DetectedRegion(
                x = 15f,
                y = 25f,
                width = 120f,
                height = 80f,
                paddingX = 12f,
                paddingY = 8f,
                confidence = 0.91f,
            ),
        )

        val result = coordinator.getOrCreateDetection(testPageContext()) {
            ByteArrayInputStream(byteArrayOf(9, 8, 7))
        }

        val analysis = result.shouldNotBeNull()
        analysis.imageWidth shouldBe 1080f
        analysis.imageHeight shouldBe 1920f
        analysis.regions.size shouldBe 1
        analysis.regions.single().id.shouldNotBeBlank()
        analysis.regions.single().x shouldBe 15f
        analysis.regions.single().y shouldBe 25f
        analysis.regions.single().width shouldBe 120f
        analysis.regions.single().height shouldBe 80f
        analysis.regions.single().paddingX shouldBe 12f
        analysis.regions.single().paddingY shouldBe 8f
        analysis.regions.single().confidence shouldBe 0.91f
        analysis.regions.single().sourceText shouldBe null
        analysis.regions.single().translatedText shouldBe null
        analysis.regions.single().translationStatus shouldBe BubbleTranslationStatus.Pending
        verify(exactly = 1) {
            repository.put(
                any(),
                match {
                    it.imageWidth == 1080f &&
                        it.imageHeight == 1920f &&
                        it.regions.size == 1 &&
                        it.regions.single().sourceText == null &&
                        it.regions.single().translatedText == null &&
                        it.regions.single().translationStatus == BubbleTranslationStatus.Pending
                },
            )
        }
        coVerify(exactly = 0) { ocrEngine.recognize(any()) }
        coVerify(exactly = 0) { apiTranslationService.translateBubble(any(), any(), any()) }
        coVerify(exactly = 0) { cloudPageAnalysisService.analyzePage(any(), any(), any()) }
    }

    @Test
    fun `getOrCreateDetection re analyzes cloud page when system prompt changes`() = runTest {
        mockkStatic(BitmapFactory::class)
        val decodedBitmap = mockk<Bitmap>()
        val storedAnalyses = mutableMapOf<String, PageAnalysis>()
        preferences.pipelineMode.set("cloud")
        every { BitmapFactory.decodeStream(any()) } returns decodedBitmap
        every { decodedBitmap.width } returns 1080
        every { decodedBitmap.height } returns 1920
        every { repository.get(any()) } answers { storedAnalyses[firstArg()] }
        every { repository.put(any(), any()) } answers {
            storedAnalyses[firstArg()] = secondArg()
            Unit
        }
        coEvery {
            cloudPageAnalysisService.analyzePage(
                bitmap = decodedBitmap,
                targetLanguage = "en",
                systemPrompt = any(),
            )
        } coAnswers {
            val prompt = thirdArg<String?>().orEmpty()
            PageAnalysis(
                imageWidth = 1080f,
                imageHeight = 1920f,
                regions = listOf(
                    BubbleRegion(
                        id = "bubble-1",
                        x = 10f,
                        y = 20f,
                        width = 100f,
                        height = 60f,
                        translatedText = prompt,
                        translationStatus = BubbleTranslationStatus.Translated,
                    ),
                ),
                modelVersion = "cloud-test",
            )
        }

        val firstResult = coordinator.getOrCreateDetection(testPageContext()) {
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }.shouldNotBeNull()
        preferences.apiSystemPrompt.set("Use concise wording")
        val secondResult = coordinator.getOrCreateDetection(testPageContext()) {
            ByteArrayInputStream(byteArrayOf(4, 5, 6))
        }.shouldNotBeNull()

        firstResult.regions.single().translatedText shouldBe "Translate manga dialogue naturally"
        secondResult.regions.single().translatedText shouldBe "Use concise wording"
        coVerify(exactly = 1) {
            cloudPageAnalysisService.analyzePage(decodedBitmap, "en", "Translate manga dialogue naturally")
        }
        coVerify(exactly = 1) {
            cloudPageAnalysisService.analyzePage(decodedBitmap, "en", "Use concise wording")
        }
    }

    @Test
    fun `translateBubble crops selected region and persists translated result only for that bubble`() = runTest {
        mockkStatic(BitmapFactory::class)
        mockkStatic(Bitmap::class)
        val pageBitmap = mockk<Bitmap>()
        val croppedBitmap = mockk<Bitmap>()
        every { BitmapFactory.decodeStream(any()) } returns pageBitmap
        every { pageBitmap.width } returns 1080
        every { pageBitmap.height } returns 1920
        every { Bitmap.createBitmap(pageBitmap, 10, 20, 100, 200) } returns croppedBitmap
        every { repository.get(any()) } returns PageAnalysis(
            imageWidth = 1080f,
            imageHeight = 1920f,
            regions = listOf(
                BubbleRegion(
                    id = "bubble-1",
                    x = 10f,
                    y = 20f,
                    width = 100f,
                    height = 200f,
                    confidence = 0.97f,
                    translationStatus = BubbleTranslationStatus.Pending,
                ),
                BubbleRegion(
                    id = "bubble-2",
                    x = 200f,
                    y = 300f,
                    width = 90f,
                    height = 60f,
                    confidence = 0.82f,
                    translationStatus = BubbleTranslationStatus.Pending,
                ),
            ),
            modelVersion = "detector:test",
        )
        coEvery {
            apiTranslationService.translateBubble(
                bitmap = croppedBitmap,
                targetLanguage = "en",
                systemPrompt = "Translate manga dialogue naturally",
            )
        } returns BubbleTranslationResult(
            sourceText = "こんにちは",
            translatedText = "Hello",
        )

        val result = coordinator.translateBubble(testPageContext(), "bubble-1") {
            ByteArrayInputStream(byteArrayOf(4, 5, 6))
        }

        val analysis = result.shouldNotBeNull()
        analysis.regions.size shouldBe 2
        analysis.regions[0] shouldBe BubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 200f,
            confidence = 0.97f,
            sourceText = "こんにちは",
            translatedText = "Hello",
            translationStatus = BubbleTranslationStatus.Translated,
            providerId = apiTranslationService.providerId,
        )
        analysis.regions[1] shouldBe BubbleRegion(
            id = "bubble-2",
            x = 200f,
            y = 300f,
            width = 90f,
            height = 60f,
            confidence = 0.82f,
            translationStatus = BubbleTranslationStatus.Pending,
        )
        verify(exactly = 1) {
            repository.put(
                any(),
                match {
                    it.regions[0].translatedText == "Hello" &&
                        it.regions[0].sourceText == "こんにちは" &&
                        it.regions[0].translationStatus == BubbleTranslationStatus.Translated &&
                        it.regions[0].providerId == apiTranslationService.providerId &&
                        it.regions[1].translatedText == null
                },
            )
        }
        coVerify(exactly = 1) {
            apiTranslationService.translateBubble(croppedBitmap, "en", "Translate manga dialogue naturally")
        }
        coVerify(exactly = 0) { ocrEngine.recognize(any()) }
    }

    @Test
    fun `translateBubble stores error state when translation result is empty`() = runTest {
        mockkStatic(BitmapFactory::class)
        mockkStatic(Bitmap::class)
        val pageBitmap = mockk<Bitmap>()
        val croppedBitmap = mockk<Bitmap>()
        every { BitmapFactory.decodeStream(any()) } returns pageBitmap
        every { pageBitmap.width } returns 1080
        every { pageBitmap.height } returns 1920
        every { Bitmap.createBitmap(pageBitmap, 10, 20, 100, 200) } returns croppedBitmap
        every { repository.get(any()) } returns PageAnalysis(
            imageWidth = 1080f,
            imageHeight = 1920f,
            regions = listOf(
                BubbleRegion(
                    id = "bubble-1",
                    x = 10f,
                    y = 20f,
                    width = 100f,
                    height = 200f,
                    confidence = 0.97f,
                    translationStatus = BubbleTranslationStatus.Pending,
                ),
            ),
            modelVersion = "detector:test",
        )
        coEvery {
            apiTranslationService.translateBubble(
                bitmap = croppedBitmap,
                targetLanguage = "en",
                systemPrompt = "Translate manga dialogue naturally",
            )
        } returns null

        val result = coordinator.translateBubble(testPageContext(), "bubble-1") {
            ByteArrayInputStream(byteArrayOf(7, 8, 9))
        }

        val analysis = result.shouldNotBeNull()
        analysis.regions.single() shouldBe BubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 200f,
            confidence = 0.97f,
            translationStatus = BubbleTranslationStatus.Error,
            errorMessage = "Empty translation result",
        )
        verify(exactly = 1) {
            repository.put(
                any(),
                match {
                    it.regions.single().translationStatus == BubbleTranslationStatus.Error &&
                        it.regions.single().errorMessage == "Empty translation result"
                },
            )
        }
    }

    @Test
    fun `translateBubble re translates when system prompt changes`() = runTest {
        mockkStatic(BitmapFactory::class)
        mockkStatic(Bitmap::class)
        val pageBitmap = mockk<Bitmap>()
        val firstCroppedBitmap = mockk<Bitmap>()
        val secondCroppedBitmap = mockk<Bitmap>()
        val storedAnalyses = mutableMapOf<String, PageAnalysis>()
        every { BitmapFactory.decodeStream(any()) } returns pageBitmap
        every { pageBitmap.width } returns 1080
        every { pageBitmap.height } returns 1920
        every {
            Bitmap.createBitmap(pageBitmap, 10, 20, 100, 200)
        } returnsMany listOf(firstCroppedBitmap, secondCroppedBitmap)
        every { repository.get(any()) } answers { storedAnalyses[firstArg()] }
        every { repository.put(any(), any()) } answers {
            storedAnalyses[firstArg()] = secondArg()
            Unit
        }
        coEvery { bubbleDetector.detect(pageBitmap) } returns listOf(
            DetectedRegion(
                x = 10f,
                y = 20f,
                width = 100f,
                height = 200f,
                confidence = 0.97f,
            ),
        )
        coEvery {
            apiTranslationService.translateBubble(any(), "en", any())
        } coAnswers {
            val prompt = thirdArg<String?>().orEmpty()
            BubbleTranslationResult(
                sourceText = "こんにちは",
                translatedText = if (prompt == "Use concise wording") "Hi" else "Hello",
            )
        }

        val firstResult = coordinator.translateBubble(testPageContext(), "bubble-1") {
            ByteArrayInputStream(byteArrayOf(1, 2, 3))
        }.shouldNotBeNull()
        preferences.apiSystemPrompt.set("Use concise wording")
        val secondResult = coordinator.translateBubble(testPageContext(), "bubble-1") {
            ByteArrayInputStream(byteArrayOf(4, 5, 6))
        }.shouldNotBeNull()

        firstResult.regions.single().translatedText shouldBe "Hello"
        secondResult.regions.single().translatedText shouldBe "Hi"
        coVerify(exactly = 2) { bubbleDetector.detect(pageBitmap) }
        coVerify(exactly = 1) {
            apiTranslationService.translateBubble(firstCroppedBitmap, "en", "Translate manga dialogue naturally")
        }
        coVerify(exactly = 1) {
            apiTranslationService.translateBubble(secondCroppedBitmap, "en", "Use concise wording")
        }
    }

    @Test
    fun `translateBubble does not re request or overwrite an already translated bubble`() = runTest {
        val existingAnalysis = PageAnalysis(
            imageWidth = 1080f,
            imageHeight = 1920f,
            regions = listOf(
                BubbleRegion(
                    id = "bubble-1",
                    x = 10f,
                    y = 20f,
                    width = 100f,
                    height = 200f,
                    confidence = 0.97f,
                    sourceText = "こんにちは",
                    translatedText = "Hello",
                    translationStatus = BubbleTranslationStatus.Translated,
                    providerId = "openai-compatible",
                ),
            ),
            modelVersion = "detector:test",
        )
        every { repository.get(any()) } returns existingAnalysis

        val result = coordinator.translateBubble(testPageContext(), "bubble-1") {
            ByteArrayInputStream(byteArrayOf(1))
        }

        result shouldBe existingAnalysis
        coVerify(exactly = 0) { apiTranslationService.translateBubble(any(), any(), any()) }
        verify(exactly = 0) { repository.put(any(), any()) }
    }

    private fun testPageContext(): TranslationPageContext {
        return TranslationPageContext(
            sourceId = 1L,
            mangaId = 2L,
            chapterId = 3L,
            chapterName = "Chapter 1",
            pageIndex = 4,
            imageUrl = "https://example.com/page-4.jpg",
            isLocal = true,
        )
    }
}

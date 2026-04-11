package eu.kanade.translation.presentation

import eu.kanade.translation.model.BubbleRegion
import eu.kanade.translation.model.BubbleTranslationStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PageAnalysisOverlayViewTest {

    @Test
    fun `display text is null for pending region without source or translated text`() {
        val region = BubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 80f,
            translationStatus = BubbleTranslationStatus.Pending,
        )

        PageAnalysisOverlayContent.displayText(region) shouldBe null
    }

    @Test
    fun `display text prefers translated text when available`() {
        val region = BubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 80f,
            sourceText = "こんにちは",
            translatedText = "Hello",
            translationStatus = BubbleTranslationStatus.Translated,
        )

        PageAnalysisOverlayContent.displayText(region) shouldBe "Hello"
    }

    @Test
    fun `display text falls back to source text when translated text is blank`() {
        val region = BubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 80f,
            sourceText = "こんにちは",
            translatedText = "",
            translationStatus = BubbleTranslationStatus.Translated,
        )

        PageAnalysisOverlayContent.displayText(region) shouldBe "こんにちは"
    }

    @Test
    fun `hit bubble returns padded region under view coordinates`() {
        val region = bubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 80f,
            paddingX = 20f,
            paddingY = 10f,
        )

        PageAnalysisOverlayContent.hitBubble(
            regions = listOf(region),
            viewX = 5f,
            viewY = 16f,
            viewTLX = 0f,
            viewTLY = 0f,
            scale = 1f,
        )?.id shouldBe "bubble-1"
    }

    @Test
    fun `hit bubble returns null when point is outside regions`() {
        val region = bubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 80f,
        )

        PageAnalysisOverlayContent.hitBubble(
            regions = listOf(region),
            viewX = 200f,
            viewY = 200f,
            viewTLX = 0f,
            viewTLY = 0f,
            scale = 1f,
        ) shouldBe null
    }

    @Test
    fun `hit bubble maps view offset and scale to source coordinates`() {
        val region = bubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 30f,
            height = 40f,
        )

        PageAnalysisOverlayContent.hitBubble(
            regions = listOf(region),
            viewX = 130f,
            viewY = 110f,
            viewTLX = 100f,
            viewTLY = 50f,
            scale = 2f,
        )?.id shouldBe "bubble-1"
    }

    @Test
    fun `hit bubble returns null when scale is invalid`() {
        val region = bubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 80f,
        )

        PageAnalysisOverlayContent.hitBubble(
            regions = listOf(region),
            viewX = 10f,
            viewY = 20f,
            viewTLX = 0f,
            viewTLY = 0f,
            scale = 0f,
        ) shouldBe null
    }

    @Test
    fun `hit bubble prefers last matching region`() {
        val first = bubbleRegion(
            id = "bubble-1",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 80f,
        )
        val second = bubbleRegion(
            id = "bubble-2",
            x = 10f,
            y = 20f,
            width = 100f,
            height = 80f,
        )

        PageAnalysisOverlayContent.hitBubble(
            regions = listOf(first, second),
            viewX = 50f,
            viewY = 50f,
            viewTLX = 0f,
            viewTLY = 0f,
            scale = 1f,
        )?.id shouldBe "bubble-2"
    }

    private fun bubbleRegion(
        id: String,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        paddingX: Float = 0f,
        paddingY: Float = 0f,
    ): BubbleRegion = BubbleRegion(
        id = id,
        x = x,
        y = y,
        width = width,
        height = height,
        paddingX = paddingX,
        paddingY = paddingY,
    )
}
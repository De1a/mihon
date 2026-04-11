package eu.kanade.translation.presentation

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.isVisible
import eu.kanade.translation.model.BubbleRegion
import eu.kanade.translation.model.PageAnalysis
import kotlinx.coroutines.flow.MutableStateFlow

class PageAnalysisOverlayView :
    AbstractComposeView {

    private val analysis: PageAnalysis

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : super(context, attrs, defStyleAttr) {
        analysis = PageAnalysis.EMPTY
    }

    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        analysis: PageAnalysis,
    ) : super(context, attrs, defStyleAttr) {
        this.analysis = analysis
    }

    val scaleState = MutableStateFlow(1f)
    val viewTLState = MutableStateFlow(PointF())

    fun hitBubble(viewX: Float, viewY: Float): BubbleRegion? =
        PageAnalysisOverlayContent.hitBubble(
            regions = analysis.regions,
            viewX = viewX,
            viewY = viewY,
            viewTLX = viewTLState.value.x,
            viewTLY = viewTLState.value.y,
            scale = scaleState.value,
        )

    @Composable
    override fun Content() {
        val scale by scaleState.collectAsState()
        val viewTL by viewTLState.collectAsState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .absoluteOffset(viewTL.x.pxToDp(), viewTL.y.pxToDp()),
        ) {
            analysis.regions.forEach {
                BubbleCard(it, scale)
            }
        }
    }

    @Composable
    private fun BubbleCard(region: BubbleRegion, scale: Float) {
        val x = (region.x - region.paddingX / 2f).coerceAtLeast(0f) * scale
        val y = (region.y - region.paddingY / 2f).coerceAtLeast(0f) * scale
        val width = ((region.width + region.paddingX) * scale).coerceAtLeast(1f).pxToDp()
        val height = ((region.height + region.paddingY) * scale).coerceAtLeast(1f).pxToDp()
        val displayText = PageAnalysisOverlayContent.displayText(region)
        val shape = RoundedCornerShape(12.dp)
        val modifier = Modifier
            .offset(x.pxToDp(), y.pxToDp())
            .requiredSize(width, height)

        if (displayText == null) {
            Box(
                modifier = modifier.border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.9f),
                    shape = shape,
                ),
            )
            return
        }

        Box(
            modifier = modifier.background(Color.White.copy(alpha = 0.92f), shape),
            contentAlignment = Alignment.Center,
        ) {
            FitText(displayText, width, height)
        }
    }

    @Composable
    private fun FitText(text: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
        val density = LocalDensity.current
        val fontSize = remember(text) { mutableStateOf(16.sp) }
        SubcomposeLayout { constraints ->
            val maxWidthPx = with(density) { width.roundToPx() }
            val maxHeightPx = with(density) { height.roundToPx() }
            var low = 8
            var high = 40
            var best = low
            while (low <= high) {
                val mid = (low + high) / 2
                val placeable = subcompose("measure_$mid") {
                    Text(text = text, fontSize = mid.sp, textAlign = TextAlign.Center)
                }[0].measure(Constraints(maxWidth = maxWidthPx))
                if (placeable.width <= maxWidthPx && placeable.height <= maxHeightPx) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            fontSize.value = best.sp
            val placeable = subcompose("final") {
                Text(text = text, fontSize = fontSize.value, textAlign = TextAlign.Center)
            }[0].measure(
                constraints.copy(
                    minWidth = maxWidthPx,
                    maxWidth = maxWidthPx,
                    minHeight = maxHeightPx,
                    maxHeight = maxHeightPx,
                ),
            )
            layout(placeable.width, placeable.height) {
                placeable.place(0, 0)
            }
        }
    }

    fun show() {
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }
}

object PageAnalysisOverlayContent {
    fun displayText(region: BubbleRegion): String? {
        val translatedText = region.translatedText?.takeIf { it.isNotBlank() }
        if (translatedText != null) {
            return translatedText
        }

        return region.sourceText?.takeIf { it.isNotBlank() }
    }

    fun hitBubble(
        regions: List<BubbleRegion>,
        viewX: Float,
        viewY: Float,
        viewTLX: Float,
        viewTLY: Float,
        scale: Float,
    ): BubbleRegion? {
        if (scale <= 0f) {
            return null
        }

        val sourceX = (viewX - viewTLX) / scale
        val sourceY = (viewY - viewTLY) / scale
        return regions.asReversed().firstOrNull { region -> region.containsSourcePoint(sourceX, sourceY) }
    }

    private fun BubbleRegion.containsSourcePoint(sourceX: Float, sourceY: Float): Boolean {
        val left = (x - paddingX / 2f).coerceAtLeast(0f)
        val top = (y - paddingY / 2f).coerceAtLeast(0f)
        val right = left + (width + paddingX).coerceAtLeast(1f)
        val bottom = top + (height + paddingY).coerceAtLeast(1f)
        return sourceX >= left && sourceX <= right && sourceY >= top && sourceY <= bottom
    }
}

@Composable
private fun Float.pxToDp() = with(LocalDensity.current) { this@pxToDp.toDp() }

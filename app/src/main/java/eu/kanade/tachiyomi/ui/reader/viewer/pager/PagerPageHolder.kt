package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PointF
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import eu.kanade.translation.ReaderTranslationCoordinator
import eu.kanade.translation.model.BubbleTranslationStatus
import eu.kanade.translation.model.TranslationPageContext
import eu.kanade.translation.model.TranslationPageState
import eu.kanade.translation.presentation.PageAnalysisOverlayView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private val translationCoordinator: ReaderTranslationCoordinator = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val readerPreferences: ReaderPreferences = Injekt.get(),
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {
    private var featureEnabled = translationPreferences.enabled.get()
    private var showTranslations = readerPreferences.showTranslations.get()
    private var translationsView: View? = null
    private var analysisJob: Job? = null
    private var bubbleTranslationJob: Job? = null
    private var preferenceJobs: List<Job> = emptyList()

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    init {
        loadJob = scope.launch { loadPageAndProcessStatus() }
        startPreferenceObservers()
    }

    private fun startPreferenceObservers() {
        featureEnabled = translationPreferences.enabled.get()
        showTranslations = readerPreferences.showTranslations.get()
        translationsView?.isVisible = featureEnabled && showTranslations
        preferenceJobs.forEach { it.cancel() }
        preferenceJobs = listOf(
            translationPreferences.enabled.changes().onEach {
                featureEnabled = it
                if (!it) {
                    bubbleTranslationJob?.cancel()
                    bubbleTranslationJob = null
                }
                refreshTranslationsView()
            }.launchIn(scope),
            readerPreferences.showTranslations.changes().onEach {
                showTranslations = it
                if (!it) {
                    bubbleTranslationJob?.cancel()
                    bubbleTranslationJob = null
                }
                translationsView?.isVisible = featureEnabled && it
            }.launchIn(scope),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (preferenceJobs.isEmpty()) {
            startPreferenceObservers()
        }
        if (loadJob == null) {
            if (page.status == Page.State.Ready) {
                if (hasLoadedImage()) {
                    restoreReadyPageState()
                } else {
                    loadJob = scope.launch { restoreReadyImageState() }
                }
            } else {
                loadJob = scope.launch { loadPageAndProcessStatus() }
            }
        }
    }

    private suspend fun restoreReadyImageState() {
        try {
            setImage()
            refreshTranslationsView()
        } finally {
            loadJob = null
        }
    }

    private fun restoreReadyPageState() {
        progressIndicator?.hide()
        removeErrorLayout()
        if (!featureEnabled) {
            translationsView?.let(::removeView)
            translationsView = null
            page.analysis = null
            page.analysisState = TranslationPageState.Idle
            return
        }
        if (page.analysis != null && translationsView == null) {
            addTranslationsView()
        } else if (page.analysis == null) {
            refreshTranslationsView()
        }
        updateTranslationCoords()
        translationsView?.isVisible = featureEnabled && showTranslations
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        analysisJob?.cancel()
        analysisJob = null
        bubbleTranslationJob?.cancel()
        bubbleTranslationJob = null
        preferenceJobs.forEach { it.cancel() }
        preferenceJobs = emptyList()
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> {
                        setImage()
                        refreshTranslationsView()
                    }
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val streamFn = page.stream ?: return

        try {
            val (source, isAnimated, background) = withIOContext {
                val source = streamFn().use { process(item, Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                Triple(source, isAnimated, background)
            }
            withUIContext {
                setImage(
                    source,
                    isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                    ),
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun translationPageContext(): TranslationPageContext? {
        val chapter = page.chapter.chapter
        val chapterId = chapter.id ?: return null
        val mangaId = chapter.manga_id ?: return null
        return TranslationPageContext(
            sourceId = 0L,
            mangaId = mangaId,
            chapterId = chapterId,
            chapterName = chapter.name,
            pageIndex = page.index,
            imageUrl = page.imageUrl,
            isLocal = page.chapter.pageLoader?.isLocal == true,
        )
    }

    private fun refreshTranslationsView() {
        analysisJob?.cancel()
        if (!featureEnabled) {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] source=pager stage=skip_feature_disabled pageIndex=${page.index}"
            }
            translationsView?.let(::removeView)
            translationsView = null
            page.analysis = null
            page.analysisState = TranslationPageState.Idle
            return
        }
        analysisJob = scope.launch {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] source=pager stage=request_analysis pageIndex=${page.index}"
            }
            page.analysisState = TranslationPageState.Loading
            val streamProvider = page.stream ?: return@launch
            val pageContext = translationPageContext() ?: return@launch
            val analysis = translationCoordinator.getOrCreate(pageContext, streamProvider)
            withUIContext {
                page.analysis = analysis
                page.analysisState = analysis?.let { TranslationPageState.Ready(it) }
                    ?: TranslationPageState.Error("No analysis available")
                logcat(LogPriority.INFO) {
                    "[TranslationPipeline] source=pager stage=analysis_result pageIndex=${page.index} result=${if (analysis != null) "ready" else "empty"}"
                }
                addTranslationsView()
                updateTranslationCoords()
            }
        }
    }

    fun handleTranslationTap(event: MotionEvent): Boolean {
        if (!featureEnabled || !showTranslations) {
            return false
        }
        val overlay = translationsView as? PageAnalysisOverlayView ?: return false
        if (!overlay.isVisible || page.analysis == null) {
            return false
        }
        val streamProvider = page.stream ?: return false
        val pageContext = translationPageContext() ?: return false

        updateTranslationCoords()
        val viewPosition = IntArray(2)
        getLocationOnScreen(viewPosition)
        val bubble = overlay.hitBubble(
            viewX = event.rawX - viewPosition[0],
            viewY = event.rawY - viewPosition[1],
        ) ?: return false

        bubbleTranslationJob?.cancel()
        bubbleTranslationJob = scope.launch {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] source=pager stage=bubble_tap pageIndex=${page.index} bubbleId=${bubble.id}"
            }
            try {
                val updatedAnalysis = translationCoordinator.translateBubble(pageContext, bubble.id, streamProvider)
                withUIContext {
                    if (featureEnabled && showTranslations && updatedAnalysis != null) {
                        page.analysis = updatedAnalysis
                        page.analysisState = TranslationPageState.Ready(updatedAnalysis)
                        addTranslationsView()
                        updateTranslationCoords()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "[TranslationPipeline] source=pager stage=bubble_translate_error pageIndex=${page.index} bubbleId=${bubble.id}"
                }
                withUIContext {
                    updateBubbleTranslationError(bubble.id)
                }
            }
        }
        return true
    }

    private fun updateBubbleTranslationError(bubbleId: String) {
        if (!featureEnabled || !showTranslations) {
            return
        }
        val analysis = page.analysis ?: return
        val updatedAnalysis = analysis.copy(
            regions = analysis.regions.map { region ->
                if (region.id != bubbleId) {
                    return@map region
                }
                if (
                    region.translationStatus == BubbleTranslationStatus.Translated &&
                    !region.translatedText.isNullOrBlank()
                ) {
                    region
                } else {
                    region.copy(
                        translationStatus = BubbleTranslationStatus.Error,
                        errorMessage = "Bubble translation failed",
                    )
                }
            },
        )
        page.analysis = updatedAnalysis
        page.analysisState = TranslationPageState.Ready(updatedAnalysis)
        addTranslationsView()
        updateTranslationCoords()
    }

    private fun addTranslationsView() {
        translationsView?.let(::removeView)
        translationsView = null
        val analysis = page.analysis ?: return
        val overlay = PageAnalysisOverlayView(context, analysis = analysis)
        overlay.isVisible = featureEnabled && showTranslations
        translationsView = overlay
        addView(overlay)
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] source=pager stage=overlay_attached pageIndex=${page.index} regions=${analysis.regions.size}"
        }
    }

    private fun updateTranslationCoords() {
        val overlay = translationsView as? PageAnalysisOverlayView ?: return
        val ssiv = subsamplingImageView() ?: return
        val coords = ssiv.sourceToViewCoord(0f, 0f)
        if (coords != null) {
            overlay.viewTLState.value = coords
            overlay.scaleState.value = ssiv.scale
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
        updateTranslationCoords()
        translationsView?.isVisible = featureEnabled && showTranslations
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        translationsView?.isVisible = false
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
        updateTranslationCoords()
    }

    override fun onCenterChanged(newCenter: PointF?) {
        super.onCenterChanged(newCenter)
        updateTranslationCoords()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}

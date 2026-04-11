package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.res.Resources
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.dpToPx
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
 * Holder of the webtoon reader for a single page of a chapter.
 *
 * @param frame the root view for this holder.
 * @param viewer the webtoon viewer.
 * @constructor creates a new webtoon holder.
 */
class WebtoonPageHolder(
    private val frame: ReaderPageImageView,
    viewer: WebtoonViewer,
    private val translationCoordinator: ReaderTranslationCoordinator = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val readerPreferences: ReaderPreferences = Injekt.get(),
) : WebtoonBaseHolder(frame, viewer) {
    private var featureEnabled = translationPreferences.enabled.get()
    private var showTranslations = readerPreferences.showTranslations.get()
    private var translationsView: View? = null
    private var analysisJob: Job? = null
    private var bubbleTranslationJob: Job? = null
    private var preferenceJobs: List<Job> = emptyList()

    /**
     * Loading progress bar to indicate the current progress.
     */
    private val progressIndicator = createProgressIndicator()

    /**
     * Progress bar container. Needed to keep a minimum height size of the holder, otherwise the
     * adapter would create more views to fill the screen, which is not wanted.
     */
    private lateinit var progressContainer: ViewGroup

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    /**
     * Getter to retrieve the height of the recycler view.
     */
    private val parentHeight
        get() = viewer.recycler.height

    /**
     * Page of a chapter.
     */
    private var page: ReaderPage? = null

    private val scope = MainScope()

    /**
     * Job for loading the page.
     */
    private var loadJob: Job? = null

    init {
        refreshLayoutParams()

        frame.onImageLoaded = { onImageDecoded() }
        frame.onImageLoadError = { error -> setError(error) }
        frame.onScaleChanged = { viewer.activity.hideMenu() }
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

    /**
     * Binds the given [page] with this view holder, subscribing to its state.
     */
    fun bind(page: ReaderPage) {
        this.page = page
        loadJob?.cancel()
        analysisJob?.cancel()
        bubbleTranslationJob?.cancel()
        removeErrorLayout()
        translationsView?.let(frame::removeView)
        translationsView = null
        startPreferenceObservers()
        loadJob = scope.launch { loadPageAndProcessStatus() }
        refreshLayoutParams()
    }

    private fun refreshLayoutParams() {
        frame.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            if (!viewer.isContinuous) {
                bottomMargin = 15.dpToPx
            }

            val margin = Resources.getSystem().displayMetrics.widthPixels * (viewer.config.sidePadding / 100f)
            marginEnd = margin.toInt()
            marginStart = margin.toInt()
        }
    }

    /**
     * Called when the view is recycled and added to the view pool.
     */
    override fun recycle() {
        loadJob?.cancel()
        loadJob = null
        analysisJob?.cancel()
        analysisJob = null
        bubbleTranslationJob?.cancel()
        bubbleTranslationJob = null
        preferenceJobs.forEach { it.cancel() }
        preferenceJobs = emptyList()

        removeErrorLayout()
        translationsView?.let(frame::removeView)
        translationsView = null
        frame.recycle()
        progressIndicator.setProgress(0)
        progressContainer.isVisible = true
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if there is no page or the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val page = page ?: return
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
                            progressIndicator.setProgress(value)
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
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading
     */
    private fun setDownloading() {
        progressContainer.isVisible = true
        progressIndicator.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator.setProgress(0)

        val streamFn = page?.stream ?: return

        try {
            val (source, isAnimated) = withIOContext {
                val source = streamFn().use { process(Buffer().readFrom(it)) }
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                Pair(source, isAnimated)
            }
            withUIContext {
                frame.setImage(
                    source,
                    isAnimated,
                    ReaderPageImageView.Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                        cropBorders = viewer.config.imageCropBorders,
                    ),
                )
                removeErrorLayout()
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun process(imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (viewer.config.dualPageSplit) {
            val isDoublePage = ImageUtil.isWideImage(imageSource)
            if (isDoublePage) {
                val upperSide = if (viewer.config.dualPageInvert) ImageUtil.Side.LEFT else ImageUtil.Side.RIGHT
                return ImageUtil.splitAndMerge(imageSource, upperSide)
            }
        }

        return imageSource
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

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressContainer.isVisible = false
        translationsView?.isVisible = false
        initErrorLayout(error)
    }

    /**
     * Called when the image is decoded and going to be displayed.
     */
    private fun onImageDecoded() {
        progressContainer.isVisible = false
        removeErrorLayout()
        updateOverlayScale()
        translationsView?.isVisible = featureEnabled && showTranslations
    }

    private fun translationPageContext(page: ReaderPage): TranslationPageContext? {
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
        val currentPage = page ?: return
        analysisJob?.cancel()
        if (!featureEnabled) {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] source=webtoon stage=skip_feature_disabled pageIndex=${currentPage.index}"
            }
            translationsView?.let(frame::removeView)
            translationsView = null
            currentPage.analysis = null
            currentPage.analysisState = TranslationPageState.Idle
            return
        }
        analysisJob = scope.launch {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] source=webtoon stage=request_analysis pageIndex=${currentPage.index}"
            }
            currentPage.analysisState = TranslationPageState.Loading
            val streamProvider = currentPage.stream ?: return@launch
            val pageContext = translationPageContext(currentPage) ?: return@launch
            val analysis = translationCoordinator.getOrCreate(pageContext, streamProvider)
            withUIContext {
                if (page === currentPage) {
                    currentPage.analysis = analysis
                    currentPage.analysisState = analysis?.let { TranslationPageState.Ready(it) }
                        ?: TranslationPageState.Error("No analysis available")
                    logcat(LogPriority.INFO) {
                        "[TranslationPipeline] source=webtoon stage=analysis_result pageIndex=${currentPage.index} result=${if (analysis != null) "ready" else "empty"}"
                    }
                    addTranslationsView()
                }
            }
        }
    }

    fun handleTranslationTap(event: MotionEvent): Boolean {
        if (!featureEnabled || !showTranslations) {
            return false
        }
        val currentPage = page ?: return false
        val overlay = translationsView as? PageAnalysisOverlayView ?: return false
        if (!overlay.isVisible || currentPage.analysis == null) {
            return false
        }
        val streamProvider = currentPage.stream ?: return false
        val pageContext = translationPageContext(currentPage) ?: return false

        updateOverlayScale()
        val bubble = overlay.hitBubble(
            viewX = event.x - frame.x,
            viewY = event.y - frame.y,
        ) ?: return false

        bubbleTranslationJob?.cancel()
        bubbleTranslationJob = scope.launch {
            logcat(LogPriority.INFO) {
                "[TranslationPipeline] source=webtoon stage=bubble_tap pageIndex=${currentPage.index} bubbleId=${bubble.id}"
            }
            try {
                val updatedAnalysis = translationCoordinator.translateBubble(pageContext, bubble.id, streamProvider)
                withUIContext {
                    if (page === currentPage && featureEnabled && showTranslations && updatedAnalysis != null) {
                        currentPage.analysis = updatedAnalysis
                        currentPage.analysisState = TranslationPageState.Ready(updatedAnalysis)
                        addTranslationsView()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) {
                    "[TranslationPipeline] source=webtoon stage=bubble_translate_error pageIndex=${currentPage.index} bubbleId=${bubble.id}"
                }
                withUIContext {
                    updateBubbleTranslationError(currentPage, bubble.id)
                }
            }
        }
        return true
    }

    private fun updateBubbleTranslationError(currentPage: ReaderPage, bubbleId: String) {
        if (page !== currentPage || !featureEnabled || !showTranslations) {
            return
        }
        val analysis = currentPage.analysis ?: return
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
        currentPage.analysis = updatedAnalysis
        currentPage.analysisState = TranslationPageState.Ready(updatedAnalysis)
        addTranslationsView()
    }

    private fun addTranslationsView() {
        translationsView?.let(frame::removeView)
        translationsView = null
        val analysis = page?.analysis ?: return
        val overlay = PageAnalysisOverlayView(context, analysis = analysis)
        overlay.isVisible = featureEnabled && showTranslations
        translationsView = overlay
        frame.addView(overlay, MATCH_PARENT, MATCH_PARENT)
        updateOverlayScale()
        logcat(LogPriority.INFO) {
            "[TranslationPipeline] source=webtoon stage=overlay_attached pageIndex=${page?.index} regions=${analysis.regions.size}"
        }
    }

    private fun updateOverlayScale() {
        val overlay = translationsView as? PageAnalysisOverlayView ?: return
        val analysis = page?.analysis ?: return
        if (analysis.imageWidth <= 0f || frame.width <= 0) return
        overlay.viewTLState.value = android.graphics.PointF()
        overlay.scaleState.value = frame.width / analysis.imageWidth
    }

    /**
     * Creates a new progress bar.
     */
    private fun createProgressIndicator(): ReaderProgressIndicator {
        progressContainer = FrameLayout(context)
        frame.addView(progressContainer, MATCH_PARENT, parentHeight)

        val progress = ReaderProgressIndicator(context).apply {
            updateLayoutParams<FrameLayout.LayoutParams> {
                updateMargins(top = parentHeight / 4)
            }
        }
        progressContainer.addView(progress)
        return progress
    }

    /**
     * Initializes a button to retry pages.
     */
    private fun initErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), frame, true)
            errorLayout?.root?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, (parentHeight * 0.8).toInt())
            errorLayout?.actionRetry?.setOnClickListener {
                page?.let { it.chapter.pageLoader?.retryPage(it) }
            }
        }

        val imageUrl = page?.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.let {
            frame.removeView(it.root)
            errorLayout = null
        }
    }
}

package io.github.offlineglass.hook.adapters.tieba

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import io.github.offlineglass.hook.topbar.TopBarChannelHost
import io.github.offlineglass.hook.topbar.TopBarTabLayout
import kotlin.math.roundToInt

internal object TiebaTopBar {
    private const val INDICATOR = "com.baidu.tieba.homepage.framework.indicator."
    private val sessions = mutableMapOf<ViewGroup, Session>()
    private var headerType: Class<*>? = null
    private var attachHeader: ((ViewGroup) -> Unit)? = null
    private var lastScan = 0L

    fun install(loader: ClassLoader) {
        runCatching {
            val header = Class.forName(INDICATOR + "ScrollTabBarLayout", false, loader)
            val left = header.getMethod("getHomeTabBarLeftSlot")
            val center = header.getMethod("getHomeTabBarView")
            val right = header.getMethod("getHomeTabBarRightSlot")
            val rootContainer = header.getMethod("getRootContainer")
            val bottomLine = header.getMethod("getBottomLine")
            val sidebar = left.returnType.getMethod("getSideIconView")
            val game = right.returnType.getMethod("getGameIconView")
            val search = right.returnType.getMethod("getSearchIconView")
            check(ViewGroup::class.java.isAssignableFrom(header))
            check(listOf(left, center, right).all { ViewGroup::class.java.isAssignableFrom(it.returnType) })
            check(listOf(sidebar, game, search).all { ImageView::class.java.isAssignableFrom(it.returnType) })
            val sliding = Class.forName(INDICATOR + "SlidingTabLayout", false, loader)
            val strip = sliding.getMethod("getSlidingTabStrip")
            check(ViewGroup::class.java.isAssignableFrom(strip.returnType))
            val selected = strip.returnType.getMethod("getSelectedPosition")
            check(selected.returnType == Int::class.javaPrimitiveType)
            // Selection is also read by the renderer. A missing notification hook
            // must not prevent binding a valid channel row.
            runCatching { XposedBridge.hookMethod(
                strip.returnType.getMethod("setSelectedPosition", Int::class.javaPrimitiveType!!),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        sessions.values.firstOrNull { it.ownsStrip(param.thisObject) }?.refreshTabs()
                    }
                },
            ) }.onFailure { Log.w("LiquidTabTieba", "Channel selection notification unavailable", it) }
            val bind: (ViewGroup) -> Unit = { owner ->
                if (owner !in sessions) {
                    runCatching {
                        val leftSlot = left.invoke(owner) as ViewGroup
                        val rightSlot = right.invoke(owner) as ViewGroup
                        val tabBar = center.invoke(owner) as ViewGroup
                        val leftIcon = sidebar.invoke(leftSlot) as ImageView
                        val rightIcon = game.invoke(rightSlot) as ImageView
                        val searchIcon = search.invoke(rightSlot) as ImageView
                        val nativeRow = rootContainer.invoke(owner) as ViewGroup
                        val divider = bottomLine.invoke(owner) as View
                        check(leftSlot.parent === nativeRow && tabBar.parent === nativeRow &&
                            rightSlot.parent === nativeRow && divider.parent === nativeRow)
                        val backdrop = (0 until nativeRow.childCount).map(nativeRow::getChildAt)
                            .single { it.javaClass.name == "com.baidu.tbadk.widget.image.TbImage" }
                        check(leftIcon.parent === leftSlot && rightIcon.parent === rightSlot &&
                            searchIcon.parent === rightSlot)
                        sessions[owner] = Session(owner, leftIcon, tabBar, rightSlot, rightIcon, searchIcon,
                            listOf(backdrop, divider), resolveStrip = {
                                val slider = (0 until tabBar.childCount).map(tabBar::getChildAt)
                                    .singleOrNull(sliding::isInstance)
                                slider?.let {
                                    (strip.invoke(it) as? ViewGroup)?.takeIf { row -> row.parent === slider }
                                }
                            }, readSelection = { selected.invoke(it) as Int })
                    }.onFailure { Log.w("LiquidTabTieba", "Top bar structure unavailable", it) }
                }
            }
            headerType = header
            attachHeader = bind
            XposedBridge.hookMethod(header.getDeclaredMethod("onAttachedToWindow"), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = param.thisObject as ViewGroup
                    owner.post { if (owner.isAttachedToWindow) bind(owner) }
                }
            })
            XposedBridge.hookMethod(header.getDeclaredMethod("onDetachedFromWindow"), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    sessions.remove(param.thisObject as ViewGroup)?.dispose()
                }
            })
        }.onFailure { Log.w("LiquidTabTieba", "Top bar contract unavailable", it) }
    }

    fun scan(activity: android.app.Activity) {
        if (activity.javaClass.name != "com.baidu.tieba.tblauncher.MainTabActivity") return
        val type = headerType ?: return
        val bind = attachHeader ?: return
        val root = activity.window.decorView
        if (sessions.keys.any { it.rootView === root }) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScan < 500L) return
        lastScan = now
        val pending = ArrayDeque<View>()
        pending.add(root)
        val candidates = mutableListOf<ViewGroup>()
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 1500) {
            val view = pending.removeFirst()
            if (view.visibility != View.VISIBLE || view !is ViewGroup) continue
            if (type.isInstance(view)) candidates.add(view)
            else for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
        }
        if (pending.isEmpty()) candidates.singleOrNull()?.let(bind)
    }

    fun sidebarVisibilityChanged() {
        sessions.values.forEach { it.onSidebarVisibilityChanged() }
    }

    private class Session(
        private val owner: ViewGroup,
        private val left: ImageView,
        private val tabBar: ViewGroup,
        private val rightSlot: ViewGroup,
        private val right: ImageView,
        private val search: ImageView,
        decorations: List<View>,
        private val resolveStrip: () -> ViewGroup?,
        private val readSelection: (ViewGroup) -> Int,
    ) {
        private val root = owner.rootView as FrameLayout
        private val context = owner.context
        private val density = resourcesDensity()
        private val height = (40f * density).roundToInt()
        private val gap = (12f * density).roundToInt()
        private val originalAlpha = (listOf(left, tabBar, rightSlot) + decorations).associateWith { it.alpha }
        private val position = IntArray(2)
        private val rootPosition = IntArray(2)
        private val observer = owner.viewTreeObserver
        private var needsSideMaterialRestore = false
        private val statusGradient = TiebaEdgeGradient(top = true)
        private val onFocus = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus) needsSideMaterialRestore = true
            else if (needsSideMaterialRestore) root.postInvalidateOnAnimation()
        }
        private var sidebarOpening = false
        private val endSidebarOpening = Runnable {
            sidebarOpening = false
            refreshVisibility()
        }
        private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { bind() }
        private val onDraw = ViewTreeObserver.OnPreDrawListener {
            if (panels.isNotEmpty()) {
                syncPosition()
                refreshVisibility()
                suppressNativeChrome()
                restoreSideMaterialIfNeeded()
                panels.getOrNull(1)?.let { panel ->
                    val top = (panel.layoutParams as FrameLayout.LayoutParams).topMargin
                    statusGradient.updateTop(panel, top + (6f * density).roundToInt(), canUseControls())
                }
            }
            true
        }
        private var panels = emptyList<GlassHostLayout>()
        private var mirrors = emptyList<Mirror>()
        private var nativeTabs = emptyList<View>()
        private var boundStrip: ViewGroup? = null
        private var reportedMissingChannels = false
        private val channelMeasure = io.github.offlineglass.hook.topbar.TopBarTabMeasure()
        private var channelSizing: TopBarTabLayout? = null
        private val channelContract = TiebaChannelContract()
        private var enabled = false
        private var hiddenBackground: Drawable? = null
        private var backgroundAlpha = 255
        private var viewport: ViewGroup? = null
        private var viewportHeight = 0
        private var viewportTopMargin = 0
        private val feedSpacing = FeedHeaderSpacing()

        init {
            observer.addOnGlobalLayoutListener(onLayout)
            observer.addOnPreDrawListener(onDraw)
            observer.addOnWindowFocusChangeListener(onFocus)
            owner.post { if (owner.isAttachedToWindow) bind() }
        }

        private fun resourcesDensity() = context.resources.displayMetrics.density

        private fun bind() {
            val config = HookConfigReader.read(context, context.packageName)
            enabled = config.enabled
            if (!enabled) {
                removePanels()
                restoreNativeChrome()
                feedSpacing.restore()
                restoreViewport()
                return
            }
            val strip = runCatching(resolveStrip).getOrNull()
            val channels = strip?.let(channelContract::read)
            if (strip == null || channels == null) {
                boundStrip = null
                if (!reportedMissingChannels) {
                    Log.d("LiquidTabTieba", "Waiting for a unique native channel strip with supported tabs")
                    reportedMissingChannels = true
                }
                return
            }
            val tabs = channels.map { it.view }
            reportedMissingChannels = false
            if (!left.hasOnClickListeners() ||
                (!right.hasOnClickListeners() && !search.hasOnClickListeners())) return
            if (availableChannelWidth <= 0) return
            if (!extendViewport()) return
            boundStrip = strip
            if (panels.isEmpty()) {
                Log.i("LiquidTabTieba", "Bound home top bar: ${tabs.size} native tabs and 2 native actions")
                panels = listOf(1, tabs.size, 1).mapIndexed { panelIndex, slots ->
                    val policy = TopBarMaterialPolicy(
                        if (panelIndex == 1) TopBarRole.CHANNELS else TopBarRole.ACTION,
                        iconOnly = true,
                        selectedAccent = false,
                    )
                    val adapter = object : TargetAdapter {
                        override val key = "tieba-top-material"
                        override val ownsNavigationDrawing = true
                        override val ownsNavigationTap = true
                        override val ownsHostLayout = true
                        override val panelShadowAlphaScale = 0.5f
                        override val skipNavigationSnapshot = true
                        override val sceneVisibilityAnimation = true
                        override val simpleSceneVisibilityAnimation = true
                        override val skipBackdropStableDelay = true
                        override val foregroundNavigationOverSelection = panelIndex == 1
                        override val indicatorInteractionPolicy get() = policy.indicatorInteractionPolicy
                        override fun sceneExitTranslationPx(hostHeight: Int, density: Float) = -hostHeight.toFloat()
                        override fun hiddenBarTranslationPx(hostHeight: Int, density: Float) = -hostHeight.toFloat()
                        override fun normalizeConfig(config: io.github.offlineglass.config.GlassConfig) =
                            policy.apply(config)
                        override fun createNavigationState(context: android.content.Context) =
                            object : AppNavigationState {
                                override val allowsSoftwareBackdropCapture = false
                                override val backdropSceneViewsAreDescendants = true
                                override fun resolveBackdropScene(host: View): View =
                                    root.findViewById<View>(android.R.id.content) ?: root
                                override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> =
                                    viewport?.let(::listOf).orEmpty()
                                override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = true
                                override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = true
                                override fun forceNavigationBarImmersed(host: GlassHostLayout) = false
                                override fun pauseBackdropWork(host: View) =
                                    root.windowVisibility != View.VISIBLE || !root.hasWindowFocus()
                                override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = false
                                override fun allowsHostTouch() = canUseControls() &&
                                    (panelIndex != 1 || boundStrip != null)
                                override fun performTap(
                                    host: View,
                                    source: ViewGroup?,
                                    index: Int,
                                    slotCount: Int,
                                ): Boolean = clickPanel(panelIndex, index)
                                override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? =
                                    if (panelIndex == 1) selectedChannel() else 0
                                override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
                                    if (panelIndex == 1) drawChannels(canvas, frame)
                                }
                            }
                    }
                    TopBarChannelHost.createPanel(context, config, slots, navigationSource = null,
                        sliderEnabled = panelIndex == 1, adapterOverride = adapter, isChannel = panelIndex == 1).apply {
                        elevation = 8f * density
                        // Keep z-order above the page, with one renderer-owned shadow.
                        outlineAmbientShadowColor = android.graphics.Color.TRANSPARENT
                        outlineSpotShadowColor = android.graphics.Color.TRANSPARENT
                        root.addView(this, FrameLayout.LayoutParams(1, height))
                    }
                }
            }
            nativeTabs = tabs
            val channelTitles = channels.map { it.title }
            if (mirrors.isEmpty()) {
                mirrors = listOf(listOf(left), emptyList(), listOf(right, search)).flatMapIndexed { index, sources ->
                    val panel = panels[index]
                    sources.map { source ->
                        Mirror(source) { clickNative(source) }.also {
                            panel.addView(it, FrameLayout.LayoutParams(1, height))
                        }
                    }
                }
            }
            panels[1].updateAdapterSlotCount(tabs.size, selectedChannel() ?: 0)
            channelSizing = channelMeasure.update(
                channelTitles.map { it.text?.toString()?.takeIf(String::isNotBlank) },
                density, channelTitles.maxOf { it.textSize },
            )
            panels[1].updateTopBarTabs(channelSizing)
            syncPosition()
            updateFeedSpacing()
            refreshTabs()
            refreshVisibility()
            suppressNativeChrome()
        }

        private fun drawChannels(canvas: Canvas, frame: AdapterNavigationFrame) {
            if (nativeTabs.size != frame.slotCount) return
            nativeTabs.forEachIndexed { index, tab ->
                val left = frame.channelEdge(index)
                drawNativeArtwork(canvas, tab, left, frame.channelEdge(index + 1) - left, frame.height.toFloat())
            }
        }

        private fun updateFeedSpacing() {
            val frame = viewport ?: return
            val home = owner.parent as? View ?: return
            val panel = panels.getOrNull(1) ?: return
            val panelTop = (panel.layoutParams as FrameLayout.LayoutParams).topMargin
            home.getLocationInWindow(position)
            root.getLocationInWindow(rootPosition)
            val plannedViewportTop = position[1] + home.paddingTop + viewportTopMargin
            val clearance = rootPosition[1] + panelTop + height + (4f * density).roundToInt()
            feedSpacing.apply(frame, (clearance - plannedViewportTop).coerceAtLeast(0))
        }

        private fun extendViewport(): Boolean {
            val parent = owner.parent as? ViewGroup ?: return false
            if (parent.javaClass.name != INDICATOR + "ScrollFragmentTabHost" || owner.height <= 0) return false
            if (viewport?.let { it.parent !== parent } == true) restoreViewport()
            val frame = viewport ?: (0 until parent.childCount).map(parent::getChildAt)
                .filterIsInstance<FrameLayout>()
                .singleOrNull { candidate ->
                    (0 until candidate.childCount).map(candidate::getChildAt).any {
                        it.javaClass.name == "com.baidu.tbadk.widget.CustomViewPager"
                    }
                }?.also {
                    val params = it.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
                    viewport = it
                    viewportHeight = params.height
                    viewportTopMargin = params.topMargin
                } ?: return false
            val params = frame.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
            // Content underlays system chrome; the floating panels use window insets.
            val targetHeight = parent.height.coerceAtLeast(1)
            val targetMargin = viewportTopMargin - owner.height
            if (params.height != targetHeight || params.topMargin != targetMargin) {
                params.height = targetHeight
                params.topMargin = targetMargin
                frame.layoutParams = params
            }
            return true
        }

        private fun restoreViewport() {
            val frame = viewport ?: return
            viewport = null
            val params = frame.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            params.height = viewportHeight
            params.topMargin = viewportTopMargin
            frame.layoutParams = params
        }

        private fun suppressNativeChrome() {
            originalAlpha.keys.forEach { if (it.alpha != 0f) it.alpha = 0f }
            val current = owner.background
            if (current !== hiddenBackground) {
                hiddenBackground?.alpha = backgroundAlpha
                hiddenBackground = current?.mutate()
                backgroundAlpha = current?.alpha ?: 255
            }
            if (current != null && current.alpha != 0) current.alpha = 0
        }

        private fun restoreNativeChrome() {
            originalAlpha.forEach { (view, alpha) -> view.alpha = alpha }
            hiddenBackground?.alpha = backgroundAlpha
            hiddenBackground = null
        }

        private fun syncPosition() {
            val available = availableChannelWidth
            if (available <= 0) return
            val centerWidth = channelSizing?.wrapWidth(available) ?: return
            val centerLeft = (root.width - centerWidth) / 2
            root.getLocationInWindow(rootPosition)
            val top = (topWindowInsets().top - rootPosition[1]).coerceAtLeast(0)
            positionPanel(panels[0], centerLeft - gap - height, top, height)
            positionPanel(panels[1], centerLeft, top, centerWidth)
            positionPanel(panels[2], centerLeft + centerWidth + gap, top, height)
            panels.forEach { panel ->
                val width = panel.layoutParams.width
                for (index in 0 until panel.childCount) {
                    val child = panel.getChildAt(index)
                    val params = child.layoutParams as FrameLayout.LayoutParams
                    if (params.width != width) {
                        params.width = width
                        child.layoutParams = params
                    }
                }
            }
        }

        private fun topWindowInsets() = root.rootWindowInsets?.getInsets(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar(),
        ) ?: android.graphics.Insets.NONE

        private val availableChannelWidth: Int
            get() {
                val insets = topWindowInsets()
                return root.width - 2 * (maxOf(insets.left, insets.right) + height + gap)
            }

        private fun positionPanel(panel: View, left: Int, top: Int, width: Int) {
            val params = panel.layoutParams as FrameLayout.LayoutParams
            if (params.leftMargin != left || params.topMargin != top ||
                params.width != width || params.height != height || params.bottomMargin != 0) {
                params.leftMargin = left
                params.topMargin = top
                params.width = width
                params.height = height
                params.bottomMargin = 0
                panel.layoutParams = params
            }
        }

        fun refreshVisibility() {
            val visible = canUseControls()
            panels.forEachIndexed { index, panel ->
                panel.updateAnimatedVisibility(visible &&
                    (index != 2 || actionOpacity(right) > 0f || actionOpacity(search) > 0f))
            }
            val searchOpacity = actionOpacity(search)
            val gameOpacity = gameOpacity(searchOpacity)
            val activeRight = rightAction(searchOpacity, gameOpacity)
            mirrors.forEach {
                when {
                    it.represents(right) -> it.setPresentation(gameOpacity, visible && activeRight === right)
                    it.represents(search) -> it.setPresentation(searchOpacity, visible && activeRight === search)
                    else -> it.setPresentation(1f, visible)
                }
            }
        }

        private fun canUseControls() =
            enabled && owner.isShown && !sidebarOpening && !TiebaAdapter.isBlockingOverlayVisible()

        fun onSidebarVisibilityChanged() {
            if (TiebaAdapter.isBlockingOverlayVisible()) {
                root.removeCallbacks(endSidebarOpening)
                sidebarOpening = false
            }
            refreshVisibility()
        }

        private fun restoreSideMaterialIfNeeded() {
            if (!needsSideMaterialRestore || !canUseControls() ||
                !root.hasWindowFocus() || root.windowVisibility != View.VISIBLE) return
            needsSideMaterialRestore = false
            for (index in listOf(0, 2)) {
                val panel = panels.getOrNull(index)?.takeIf { it.isAttachedToWindow } ?: continue
                panel.restoreOpticalSceneForAdapter()
                if (panel.isShown) panel.adapterScheduleBackdropRefresh(0L)
            }
        }

        private fun clickPanel(panel: Int, index: Int): Boolean {
            val source = when (panel) {
                0 -> left
                1 -> boundStrip?.let { strip ->
                    nativeTabs.getOrNull(index)?.takeIf { it.parent === strip }
                }
                2 -> actionOpacity(search).let { rightAction(it, gameOpacity(it)) }
                else -> null
            }
            return source != null && clickNative(source)
        }

        private fun clickNative(source: View): Boolean {
            if (!canUseControls() || !source.isAttachedToWindow || !source.isShown ||
                !source.isEnabled || !source.hasOnClickListeners()) return false
            if (source !== left) return source.performClick()
            // Start the exit in the current window before the native sidebar activity takes focus.
            sidebarOpening = true
            refreshVisibility()
            root.removeCallbacks(endSidebarOpening)
            root.postDelayed(endSidebarOpening, 500L)
            val clicked = source.performClick()
            if (!clicked) {
                root.removeCallbacks(endSidebarOpening)
                endSidebarOpening.run()
            }
            return clicked
        }

        private fun gameOpacity(searchOpacity: Float) = actionOpacity(right) * (1f - searchOpacity)

        private fun rightAction(searchOpacity: Float, gameOpacity: Float): View? = when {
            searchOpacity > 0f && searchOpacity >= gameOpacity -> search
            gameOpacity > 0f -> right
            else -> null
        }

        private fun actionOpacity(view: View) =
            if (view.isShown && view.hasOnClickListeners()) view.alpha.coerceIn(0f, 1f) else 0f

        private fun selectedChannel(): Int? {
            val strip = boundStrip ?: return null
            val selected = runCatching { readSelection(strip) }.getOrNull() ?: return null
            val tab = strip.getChildAt(selected) ?: return null
            return nativeTabs.indexOf(tab).takeIf { it >= 0 }
        }

        fun ownsStrip(view: Any?) = boundStrip === view

        fun refreshTabs() {
            mirrors.forEach(View::invalidate)
            panels.getOrNull(1)?.let { panel ->
                if (boundStrip != null) panel.setAdapterSelectionVisible(selectedChannel() != null)
                panel.invalidate()
            }
        }

        private fun removePanels() {
            statusGradient.clear()
            panels.forEach { panel -> panel.visibility = View.GONE; root.post { root.removeView(panel) } }
            panels = emptyList()
            mirrors = emptyList()
            nativeTabs = emptyList()
            boundStrip = null
            channelSizing = null
        }

        fun dispose() {
            if (observer.isAlive) {
                observer.removeOnGlobalLayoutListener(onLayout)
                observer.removeOnPreDrawListener(onDraw)
                observer.removeOnWindowFocusChangeListener(onFocus)
            }
            root.removeCallbacks(endSidebarOpening)
            removePanels()
            root.post {
                if (!owner.isAttachedToWindow) {
                    restoreNativeChrome()
                    feedSpacing.restore()
                    restoreViewport()
                }
            }
        }
    }

    /** The native search header already collapses and reports its offset to the right slot. */
    private class FeedHeaderSpacing {
        private val padding = mutableMapOf<LinearLayout, Int>()

        fun apply(viewport: ViewGroup, clearance: Int) {
            val pager = viewport.directChildren().filterIsInstance<ViewGroup>()
                .singleOrNull { it.javaClass.name == "com.baidu.tbadk.widget.CustomViewPager" } ?: return
            val headers = pager.directChildren().filterIsInstance<ViewGroup>()
                .filter { it.javaClass.name == "com.baidu.tieba.homepage.personalize.PersonalizePageView" }
                .mapNotNull(::findSearchHeader)
                .toSet()
            val removed = padding.keys.filter { it !in headers }
            removed.forEach { header -> padding.remove(header)?.let { setTopPadding(header, it) } }
            headers.forEach { header ->
                val nativeTop = padding.getOrPut(header) { header.paddingTop }
                setTopPadding(header, nativeTop + clearance)
            }
        }

        private fun findSearchHeader(page: ViewGroup): LinearLayout? {
            val coordinator = page.directChildren().filterIsInstance<ViewGroup>()
                .singleOrNull { it.javaClass.name == "androidx.coordinatorlayout.widget.CoordinatorLayout" }
                ?: return null
            if (coordinator.directChildren().none {
                    it.javaClass.name == "com.baidu.tieba.homepage.personalize.bigday.BigdaySwipeRefreshLayout"
                }) return null
            return coordinator.directChildren().filterIsInstance<ViewGroup>()
                .filter { it.javaClass.name == "com.google.android.material.appbar.AppBarLayout" }
                .flatMap { it.directChildren().filterIsInstance<LinearLayout>() }
                .singleOrNull { header ->
                    header.layoutParams.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
                        header.directChildren().any { it.javaClass.name == "com.baidu.tbadk.widget.TbSearchBoxView" } &&
                        (header in padding || isScrollable(header.layoutParams))
                }
        }

        private fun isScrollable(params: ViewGroup.LayoutParams): Boolean {
            if (params.javaClass.name != "com.google.android.material.appbar.AppBarLayout\$LayoutParams") return false
            return runCatching {
                val flags = params.javaClass.getMethod("getScrollFlags").invoke(params) as Int
                flags and 1 != 0
            }.getOrDefault(false)
        }

        fun restore() {
            padding.forEach { (header, top) -> setTopPadding(header, top) }
            padding.clear()
        }

        private fun setTopPadding(header: LinearLayout, top: Int) {
            if (header.paddingTop != top) {
                header.setPaddingRelative(header.paddingStart, top, header.paddingEnd, header.paddingBottom)
            }
        }

        private fun ViewGroup.directChildren() = (0 until childCount).map(::getChildAt)
    }

    private class Mirror(private val source: View, click: () -> Boolean) : View(source.context) {
        init {
            contentDescription = source.contentDescription
            setOnClickListener { if (isEnabled) click() }
        }

        fun represents(view: View) = source === view

        fun setPresentation(opacity: Float, receivesTap: Boolean) {
            if (alpha != opacity) alpha = opacity
            if (isEnabled != receivesTap) isEnabled = receivesTap
            if (isClickable != receivesTap) isClickable = receivesTap
        }

        override fun onDraw(canvas: Canvas) {
            drawNativeArtwork(canvas, source, 0f, width.toFloat(), height.toFloat())
        }
    }

    private fun drawNativeArtwork(canvas: Canvas, source: View, left: Float, width: Float, height: Float) {
        if (!source.isShown || source.width <= 0 || source.height <= 0 || width <= 0f || height <= 0f) return
        val scale = minOf(1f, width / source.width, height / source.height)
        val save = canvas.save()
        canvas.clipRect(left, 0f, left + width, height)
        canvas.translate(left + (width - source.width * scale) / 2f, (height - source.height * scale) / 2f)
        canvas.scale(scale, scale)
        source.draw(canvas)
        canvas.restoreToCount(save)
    }
}

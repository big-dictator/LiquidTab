package io.github.offlineglass.hook.adapters.x

import android.content.res.Configuration
import android.graphics.Canvas
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassInstaller
import java.lang.ref.WeakReference
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.targets.TargetSpec

/** X uses Compose navigation. The shim supplies glass geometry; the native
 * callbacks are captured upstream so clicks remain functional while native
 * chrome is absent. Only portrait uses module chrome; landscape stays native.
 */
internal object XAdapter : TargetAdapter {
    override val key = "x"
    override val supportsNativeEnglishLocale = true
    override val ownsNavigationFinding = true
    /** Shim 是模块自有的空容器：无内容可捕获，图标由 XRenderer 自绘。 */
    override val skipNavigationSnapshot = true
    override val ownsNavigationDrawing = true
    /** Compose tab 无 View 级选中态，派发成功后由宿主提交选择。 */
    override val nativeSelectionReliable: Boolean = false
    override val usesCommittedSelection = true
    override val ownsNavigationTap = true
    override val managesHostSiblingOrder = true
    override val commitSelectionAfterTap = true
    override val usesOpticalSurfacePipeline = true
    override fun isOpticalSurfaceTab(selectedIndex: Int) = selectedIndex == 2
    override val retainsExistingBackdropRenderNode = true
    override fun createNavigationState(context: android.content.Context,
        host: io.github.offlineglass.hook.GlassHostLayout): AppNavigationState =
        object : AppNavigationState {
            private val collapse = XChromeCollapseController(host).also { collapseControllers[host] = it }
            private val indicatorGeometry = android.graphics.RectF()
            override fun hostPanelWidth(defaultWidth: Int) = collapse.width(defaultWidth)
            override fun hostPanelHeight(defaultHeight: Int) = collapse.height(defaultHeight)
            override fun interceptNavigationTouch(host: View, event: android.view.MotionEvent) = collapse.intercept(event)
            override fun allowsHostTouch(): Boolean = !drawerActive
            override fun navigationBarGeometry(): android.graphics.RectF? {
                if (!collapse.morphing) return null
                // Indicator dimensions are endpoint-owned, independent of the shell's spring.
                val h = if (XBottomBarHooks.selectedIndex == 0)
                    XChromeCollapsePolicy.mix(collapse.fullHeight.toFloat(), collapse.collapsedPanelSize.toFloat(), collapse.progress)
                    else (if (collapse.expanding) collapse.fullHeight else collapse.collapsedPanelSize).toFloat()
                val center = host.height / 2f
                indicatorGeometry.set(0f, center - h / 2f, host.width.toFloat(), center + h / 2f)
                return indicatorGeometry
            }
            override fun indicatorHorizontalGeometry(center: Float, width: Float): Pair<Float, Float>? {
                val p = collapse.progress
                if (!collapse.morphing) return null
                val inset = 8f * host.resources.displayMetrics.density
                val expandedWidth = ((collapse.fullWidth - inset) / 5f).coerceAtLeast(1f)
                val position = (center - inset / 2f) / width.coerceAtLeast(1f) - 0.5f
                val leading = XBottomBarHooks.selectedIndex == 0 && kotlin.math.abs(position) < 0.01f
                val x = if (leading)
                    XChromeCollapsePolicy.leadingCenter(collapse.fullWidth.toFloat(),
                        collapse.collapsedPanelSize.toFloat(), inset / 2f, 5, p)
                else XChromeCollapsePolicy.navigationCenter(collapse.fullWidth.toFloat(),
                    collapse.collapsedPanelSize.toFloat(), inset / 2f, position, 5, p)
                val compactWidth = (collapse.collapsedPanelSize - inset).coerceAtLeast(1f)
                return x to if (leading) XChromeCollapsePolicy.leadingWidth(expandedWidth, compactWidth, p)
                    else XChromeCollapsePolicy.indicatorWidth(expandedWidth, compactWidth, p, collapse.expanding)
            }
            override fun onHostWindowFocusChanged(hasFocus: Boolean) { collapse.requestRefresh() }
            override fun dispose() { collapseControllers.remove(host); collapse.dispose() }
            private var surfaceMode: Boolean? = null
            // Coil hardware bitmaps are valid on every X page. A software
            // root.draw() also poisons Compose's cached layer canvas on failure.
            override val allowsSoftwareBackdropCapture: Boolean get() = false
            override fun skipLiveBackdrop(selectedIndex: Int) = selectedIndex == 2
            override fun updateBackdropRendererMode(host: io.github.offlineglass.hook.GlassHostLayout): Boolean {
                val grok = host.adapterSelectedIndex == 2
                if (surfaceMode != grok) {
                    host.clearLiveBackdropForAdapter()
                    host.invalidateOpticalBackdropForAdapter()
                    surfaceMode = grok
                }
                if (grok) {
                    host.disableOpticalSurfaceNativeBlurForAdapter()
                    host.enableOpticalSurfaceRendererForAdapter()
                } else host.disableOpticalSurfaceRendererForAdapter()
                return true
            }
            override fun onHostFrame(host: View) {
                syncChromeOrder(host)
                collapse.refresh()
                XGrokViewport.update(host)
            }
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) =
                dispatchNavigationTap(source, index, slotCount)
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) =
                drawNavigation(canvas, frame, collapse.progress, collapse.fullWidth, collapse.collapsedPanelSize,
                    collapse.morphing && frame.selectedIndex == 0)
            override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? =
                XBottomBarHooks.selectedIndex.takeIf { it in 0 until slotCount }
            override fun pageAllowsNavigation(root: View?): Boolean =
                XBottomBarHooks.replacesCurrentPage && !detailActive &&
                    context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
    override val usesContentDarkMode = true
    /** source 是 shim 而非宿主内容，但保持 source 可见的语义，防 alpha=0。 */
    override val keepNavigationSourceVisible = true
    override val logNavigationHierarchy = false
    override val accentColor: Int = ACCENT_COLOR

    const val PACKAGE_NAME = "com.twitter.android"
    const val ACCENT_COLOR = 0xFF1D9BF0.toInt()

    /** X 底栏图标行高（内容区，不含手势条）。 */
    const val TAB_ROW_DP = 56f
    const val COVER_DARK = 0xFF000000.toInt()
    const val COVER_LIGHT = 0xFFFFFFFF.toInt()

    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView"

    @Volatile private var composeViewRef: WeakReference<View>? = null
    @Volatile private var shimRef: WeakReference<ViewGroup>? = null
    /** 自绘发布键；与 shim 同 content 层，显隐随原生节奏联动。 */
    @Volatile private var fabRef: WeakReference<XFloatingFab>? = null
    /** 发布键 alpha 动画时长，对齐宿主 sceneVisibilityAnimation（300ms）。 */
    private const val FAB_SYNC_MS = 300L
    /** 最近一次原生四边 overlay 可见性；shim 创建晚于首个回调时用于对齐初始态。 */
    @Volatile private var nativeOverlaysVisible: Boolean = true
    /** 帖子详情页中（postdetail 组件存活）；详情页无原生底栏，全 chrome 隐藏。 */
    @Volatile private var detailActive = false
    private var detailComponentActive = false
    private var mediaGalleryActive = false
    private var semanticReplyActive = false
    private var composerPageActive = false
    private var drawerActive = false
    internal val isDrawerOpen: Boolean get() = drawerActive
    /** 设置页“自动隐藏底栏”开关；关闭时信息流滚动不隐藏，详情页仍隐藏。 */
    @Volatile private var autoHideEnabled = false
    private val collapseControllers = java.util.WeakHashMap<View, XChromeCollapseController>()
    internal val nativeChromeVisible: Boolean get() = nativeOverlaysVisible
    internal val collapseEligible: Boolean get() = chromeVisible && XBottomBarHooks.selectedIndex != 2
    internal fun updateFloatingCollapse(host: View, progress: Float, fullWidth: Int, panelHeight: Int) {
        (shimRef?.get() as? XNavigationShim)?.takeIf { it.parent === host.parent }?.let {
            val opacity = 1f - progress.coerceIn(0f, 1f)
            if (it.coverOpacity != opacity) { it.coverOpacity = opacity; it.invalidate() }
        }
        fabRef?.get()?.takeIf { it.parent === host.parent }?.setCollapseGeometry(host, progress, fullWidth, panelHeight)
    }

    private fun syncChromeOrder(host: View) {
        val parent = host.parent as? ViewGroup ?: return
        val compose = composeViewRef?.get()?.takeIf { it.parent === parent } ?: return
        val shim = shimRef?.get()?.takeIf { it.parent === parent } ?: return
        val fab = fabRef?.get()?.takeIf { it.parent === parent }
        // Repair relative order only. Promoting both controls to the last
        // position independently would make every pre-draw request layout.
        if (parent.indexOfChild(shim) < parent.indexOfChild(compose)) shim.bringToFront()
        if (parent.indexOfChild(host) < parent.indexOfChild(shim)) host.bringToFront()
        if (fab != null && parent.indexOfChild(fab) < parent.indexOfChild(host)) fab.bringToFront()
    }

    /** 页面级显示所有权；信息流滚动只改变收拢程度，不再撤销 sourceReady。 */
    private val chromeVisible: Boolean
        get() = XBottomBarHooks.replacesCurrentPage && isPortrait() &&
            !detailActive
    private val floatingActionVisible: Boolean
        get() = chromeVisible && XBottomBarHooks.floatingActionReady

    internal fun onSelectedTabChanged() {
        // Grok retains its bar, but has no floating post/chat action.
        applyChromeVisibility()
        fabRef?.get()?.invalidate()
    }

    private fun isPortrait(): Boolean = (shimRef?.get() ?: composeViewRef?.get())
        ?.resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT

    internal fun onOrientationChanged() {
        applyChromeVisibility()
        fabRef?.get()?.alignAboveGlassBar()
    }

    /**
     * 原生底栏有滚动显隐节奏（上滑隐藏 / 下滑出现）。显隐切换的唯一入口
     * 入口在 Application.attach 时按语义识别并安装 hook；原生显隐信号仅驱动
     * X 自有收拢状态，原生底栏绘制继续由已验证的独立 hook 抑制。
     */
    override fun installBootstrapHooks(param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        XBottomBarHooks.install(param)
    }
    override fun onConfigurationReady(context: android.content.Context,
        param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        if (param.processName == param.packageName) XBottomBarHooks.onConfigurationReady(context, param.classLoader)
    }

    /**
     * [XBottomBarHooks] 的回调：原生显隐节奏（上滑 false / 下滑 true）。
     * 原生底栏本身已被 hook 隐藏。滚动期间保持 shim/sourceReady；启用设置时
     * 由每个 host 的控制器收拢成当前页按钮，关闭设置时保持完整底栏。
     * 若回调早于 shim 创建，仅记录状态，由新 host 读取并对齐。
     */
    fun onNativeOverlaysVisibility(visible: Boolean) {
        if (nativeOverlaysVisible == visible) return
        android.util.Log.i("OfflineGlassX", "overlays -> $visible (shim=${shimRef?.get() != null} fab=${fabRef?.get() != null})")
        nativeOverlaysVisible = visible
        applyChromeVisibility()
    }

    /**
     * [XBottomBarHooks] 的详情页回调：已识别组件构造进详情、onDestroy 对应出口
     * 出详情（嵌套各计一次，销毁延迟确认吸收跳转乱序）。必须走宿主正统
     * 通知链路 GlassInstaller.setAdapterBlockingOverlayVisible——它会回调
     * 本类 [setBlockingOverlayVisible] 落状态，并立即广播 GlassHostLayout
     * 播放玻璃条显隐动画；只改字段的话宿主要等自己下一轮 shouldShow
     * 重算（页面静止时不跑），底栏看似毫无反应。
     */
    fun onDetailPageChanged(active: Boolean) {
        XReplyComposerGlass.onDetailChanged(active)
        android.util.Log.i("OfflineGlassX", "detail page -> $active")
        detailComponentActive = active
        updatePageChromeBlocking()
    }

    internal fun onMediaGalleryChanged(visible: Boolean) {
        if(mediaGalleryActive==visible) return
        mediaGalleryActive=visible
        updatePageChromeBlocking()
    }
    internal fun onSemanticReplyChanged(visible: Boolean) {
        if(semanticReplyActive==visible) return
        semanticReplyActive=visible
        updatePageChromeBlocking()
    }
    internal fun onComposerPageChanged(visible: Boolean) {
        if(composerPageActive==visible) return
        composerPageActive=visible
        updatePageChromeBlocking()
    }

    private fun updatePageChromeBlocking() {
        val blocked=detailComponentActive || mediaGalleryActive || semanticReplyActive || composerPageActive || drawerActive
        if(detailActive!=blocked) GlassInstaller.setAdapterBlockingOverlayVisible(PACKAGE_NAME,blocked)
    }

    internal fun onDrawerChanged(open: Boolean) {
        if(drawerActive==open) return
        drawerActive=open
        updatePageChromeBlocking()
    }

    /** 宿主页面门控：详情页禁止导航 chrome（玻璃条渐隐）。 */
    override fun isBlockingOverlayVisible(): Boolean = detailActive

    /** [XFloatingFab] 配置轮询转发：自动隐藏开关变化时立即重算显隐。 */
    fun onConfigRefreshed(cfg: io.github.offlineglass.config.GlassConfig) {
        val enabled = cfg.xAutoHideBar
        if (autoHideEnabled == enabled) return
        android.util.Log.i("OfflineGlassX", "autoHide -> $enabled")
        autoHideEnabled = enabled
        applyChromeVisibility()
    }

    /** [GlassInstaller] 通知落点：更新详情态并同步遮盖条/发布键。 */
    override fun setBlockingOverlayVisible(visible: Boolean) {
        detailActive = visible
        applyChromeVisibility()
    }

    /** 按当前 chromeVisible 统一应用遮盖条与发布键显隐（幂等）。 */
    private fun applyChromeVisibility() {
        val shim = shimRef?.get()
        val fab = fabRef?.get()
        if (shim == null && fab == null) return
        (shim ?: fab)?.post {
            collapseControllers.values.toList().forEach { it.requestRefresh() }
            val visible = chromeVisible
            shim?.let {
                if (visible) {
                    // 显示前清掉初始化隐藏分支留下的下滑位移，避免遮盖条
                    // 停在底栏下方的错误位置。
                    it.translationY = 0f
                    it.visibility = View.VISIBLE
                } else {
                    it.visibility = View.INVISIBLE
                }
            }
            fab?.let {
                if (!isPortrait()) {
                    it.animate().cancel()
                    it.alpha = 0f
                    it.visibility = View.INVISIBLE
                } else if (floatingActionVisible) {
                    if (it.visibility != View.VISIBLE || it.alpha < 1f) {
                        it.visibility = View.VISIBLE
                        it.animate().alpha(1f).setDuration(FAB_SYNC_MS).start()
                    }
                } else if (it.visibility == View.VISIBLE || it.alpha > 0f) {
                    it.animate().alpha(0f).setDuration(FAB_SYNC_MS)
                        .withEndAction { if (!floatingActionVisible) it.visibility = View.INVISIBLE }
                        .start()
                }
            }
        }
    }

    /**
     * 依据全屏 ComposeView 的存在复用/创建 shim。首次创建的当帧 shim
     * 尚无尺寸，返回 null 由下一轮扫描（resume + layout 节奏）接管。
     */
    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        if (!XBottomBarHooks.replacesCurrentPage) return null
        val compose = AdapterNavigationSearch.findFirst(root) { view ->
            classHierarchyContains(view.javaClass, COMPOSE_VIEW) &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width >= root.width * 9 / 10 && view.height >= root.height / 2
        } ?: return null
        composeViewRef = WeakReference(compose)
        if (root.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT) {
            onOrientationChanged()
            return null
        }
        val shim = AdapterNavigationSearch.findFirst(root) { it is XNavigationShim }
            ?.takeIf { it.parent === compose.parent }
            ?: createShim(compose)
            ?: return null
        ensureShimMetrics(shim)
        composeViewRef = WeakReference(compose)
        shimRef = WeakReference(shim)
        // 发布键与底栏强绑定：同 content 层，shim 在则发布键必在。
        // 每轮扫描自愈（被移除/异常创建失败都重建）、锚定与显隐。
        val content = compose.parent as? ViewGroup ?: return null
        ensureFab(content, compose)
        fabRef?.get()?.let { fab ->
            fab.alignAboveGlassBar()
            resyncFabVisibility(fab)
        }
        return NavigationCandidate(shim, spec.preferredSlots.first.coerceIn(2, 7), 1_000)
    }

    /** 按当前 chrome 可见性对齐发布键（幂等；漏帧下一轮扫描纠正）。 */
    private fun resyncFabVisibility(fab: XFloatingFab) {
        if (floatingActionVisible) {
            if (fab.visibility != View.VISIBLE || fab.alpha < 1f) {
                fab.visibility = View.VISIBLE
                fab.animate().alpha(1f).setDuration(FAB_SYNC_MS).start()
            }
        } else if (fab.visibility == View.VISIBLE || fab.alpha > 0f) {
            fab.animate().alpha(0f).setDuration(FAB_SYNC_MS)
                .withEndAction { if (!floatingActionVisible) fab.visibility = View.INVISIBLE }
                .start()
        }
    }

    /**
     * 发布键自愈：浅层定点查 content 直接子级（无全树递归）。
     * 不存在、已分离或换 parent 时重建；存在且在位时仅刷新引用。
     */
    private fun ensureFab(content: ViewGroup, compose: View) {
        var alive: XFloatingFab? = null
        for (i in 0 until content.childCount) {
            (content.getChildAt(i) as? XFloatingFab)?.let { found ->
                if (found.isAttachedToWindow || found.parent === content) alive = found
            }
        }
        if (alive != null) {
            alive.setBackdropSource(compose)
            fabRef = WeakReference(alive)
            return
        }
        runCatching {
            val fab = XFloatingFab(compose.context, compose).apply {
                onClickAction = {
                    if (XBottomBarHooks.selectedIndex == 4) XBottomBarHooks.chatClick?.invoke()
                    else XBottomBarHooks.fabClick?.invoke()
                }
            }
            content.addView(fab, fab.desiredLayoutParams())
            if (!floatingActionVisible) {
                fab.visibility = View.INVISIBLE
                fab.alpha = 0f
            }
            fabRef = WeakReference(fab)
        }
    }

    private fun createShim(compose: View): ViewGroup? {
        val content = compose.parent as? ViewGroup ?: return null
        val shim = XNavigationShim(compose.context)
        return try {
            content.addView(shim, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                bottomBarHeightPx(compose),
                Gravity.BOTTOM,
            ))
            applyCoverBackground(shim)
            if (!chromeVisible) {
                // 首个 e(false) / 详情页早于 shim 创建的极端时序：直接以
                // 隐藏态落位，避免遮盖条把已收起的原生底栏区域重新盖出来。
                shim.translationY = shim.layoutParams?.height?.toFloat() ?: 0f
                shim.visibility = View.INVISIBLE
            }
            android.util.Log.i("OfflineGlassX",
                "shim created h=${shim.layoutParams.height} vis=${shim.visibility} parent=${shim.parent?.javaClass?.simpleName}")
            XposedBridge.log("[OfflineGlass][X] shim installed h=${shim.layoutParams.height}")
            shim
        } catch (t: Throwable) {
            XposedBridge.log("[OfflineGlass][X] shim add failed: $t")
            null
        }
    }

    /** 同步遮盖条高度与配色（inset 因导航模式而变，每轮扫描校正）。 */
    private fun ensureShimMetrics(shim: ViewGroup) {
        val target = bottomBarHeightPx(shim)
        if (shim.layoutParams?.height != target) {
            shim.layoutParams?.let { params ->
                params.height = target
                shim.layoutParams = params
            }
        }
        applyCoverBackground(shim)
    }

    /**
     * GlassInstaller 安装前会把 source 背景清为透明；这里恢复不透明纯色，
     * 使遮盖条在玻璃宿主 addView 之前回到 content 的 z 序里。
     */
    override fun suppressNativeBottomChrome(navigation: ViewGroup) = applyCoverBackground(navigation)
    override fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) = applyCoverBackground(navigation)

    private fun applyCoverBackground(navigation: ViewGroup) {
        if (navigation !is XNavigationShim) return
        val dark = (navigation.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // X 原生底栏自身就是不透明纯色（暗色纯黑 / 亮色纯白）。遮盖靠
        // XNavigationShim.draw() 自绘纯色实现，不依赖 background——
        // GlassInstaller 安装宿主时会把 source 的 background 清成透明，
        // 任何 background 修复都会被该链路剥离。
        navigation.coverColor = if (dark) COVER_DARK else COVER_LIGHT
    }

    override fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) = drawNavigation(canvas, frame, 0f)
    private fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame, collapse: Float,
        expandedWidth: Int = frame.width, collapsedSize: Int = frame.height,
        leadingAtEdge: Boolean = false) = XRenderer.draw(
        canvas, frame.extraScale, frame.slotCount, frame.width, frame.height, frame.slotWidth,
        frame.density, frame.iconScale, frame.selectedIndex, frame.enabledIndices, frame.darkGlass, collapse,
        expandedWidth, collapsedSize, leadingAtEdge,
    )

    /**
     * 宿主 ownsNavigationTap=true：仅由适配器执行真实回调，成功后提交选择。
     *
     * 原生底栏被压 hidden 后经 AnimatedVisibility 退出 composition，不再
     * 参与 Compose 命中——向 ComposeView 合成触摸事件会直接穿透到信息流
     * 帖子（点底栏点开帖子的根因）。因此这里**绝不下发触摸事件**：直接
     * 调用已保存的原生 onTabClicked 回调，物理上不可能穿透；回调不可用
     * 时返回 false（宿主不提交选择，滑块不动）也绝不打 Compose。
     */
    override fun dispatchNavigationTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (slotCount <= 0 || index !in 0 until slotCount) return false
        val types = XBottomBarHooks.tabTypes
        if (types == null || index >= types.size) {
            android.util.Log.i("OfflineGlassX",
                "tap blocked (no callback) index=$index types=${types?.size}")
            return false
        }
        val ok = XBottomBarHooks.invokeTabClick(types[index])
        android.util.Log.i("OfflineGlassX", "tab click invoke index=$index ok=$ok")
        return ok
    }

    private fun bottomBarHeightPx(anchor: View): Int =
        (TAB_ROW_DP * anchor.resources.displayMetrics.density).toInt() + navigationBarInsetPx(anchor)

    private fun navigationBarInsetPx(anchor: View): Int = runCatching {
        val insets = anchor.rootWindowInsets ?: return@runCatching 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
    }.getOrDefault(0)

    private fun classHierarchyContains(initial: Class<*>, name: String): Boolean {
        var type: Class<*>? = initial
        while (type != null) {
            if (type.name == name) return true
            type = type.superclass
        }
        return false
    }
}

/** 模块自有的 navigation source / 原生底栏遮盖条；不 clickable，触摸穿透。 */
internal class XNavigationShim(context: android.content.Context) : FrameLayout(context) {
    internal var coverOpacity = 1f
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        XAdapter.onOrientationChanged()
    }
    /** 由 XAdapter 同步的遮盖色；draw() 每帧自绘，屏蔽原生底栏的渲染。 */
    @Volatile var coverColor: Int = XAdapter.COVER_DARK

    override fun draw(canvas: Canvas) {
        canvas.drawColor((coverColor and 0x00FFFFFF) or ((255f * coverOpacity).toInt() shl 24))
        // 故意不调 super：shim 不承载任何子内容；原生底栏已被 hook 从
        // 源头隐藏，此纯色仅作漏网帧兜底遮盖。
    }
}




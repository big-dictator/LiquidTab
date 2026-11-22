package io.github.offlineglass.hook.adapters.mi_market

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.TextView
import android.app.Activity
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Xiaomi Market owns its dynamic tab list, artwork, selection and click routing. */
internal class MiMarketNavigationState(private val context: Context) : AppNavigationState {
    private var source: ViewGroup? = null
    private var explicitBottomTab: WeakReference<ViewGroup>? = null
    private var cachedBottomTab = WeakReference<ViewGroup>(null)
    private var isMiux4Controller = false
    private var backdropEpoch = 0L
    private val hostModes = WeakHashMap<GlassHostLayout, Boolean>()
    private val copyBarrier = MiMarketFrameBarrier()
    private val blurFrames = MiMarketBlurFrames()
    private var opticalDirty = true
    override fun onOpticalBackdropChanged() { blurFrames.captured(); opticalDirty = true }
    override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean? =
        if (animating || opticalDirty || !opticalFrameReady || searchAnimator?.isRunning == true) null else false
    override fun opticalBlurNodeNeedsRecord(prepared: Boolean, backdrop: android.graphics.Bitmap?, width: Int,
        height: Int, effect: android.graphics.RenderEffect?) = blurFrames.needsRecord(prepared, backdrop, width, height, effect)
    override fun onOpticalBlurNodeRecorded(backdrop: android.graphics.Bitmap?, width: Int,
        height: Int, effect: android.graphics.RenderEffect?) = blurFrames.recorded()
    override fun scheduleOpticalSurfaceCopy(root: View, copy: () -> Unit) =
        copyBarrier.schedule(root, opticalCaptureEpoch(), ::opticalCaptureEpoch, copy)
    @Volatile private var opticalFrameReady = false
    private var hostRef = WeakReference<GlassHostLayout>(null)
    private var scrollObserver: ViewTreeObserver? = null
    private val backdropScrollListener = ViewTreeObserver.OnScrollChangedListener(::invalidateNativeBackdrop)
    private val renderHandler = android.os.Handler(android.os.Looper.getMainLooper())
    override fun opticalSurfaceCopyHandler() = renderHandler
    override fun deferOpticalCopyRenderToUi() = true
    private var chrome = MiMarketChrome(context, this)
    private var searchPressed = false
    private var searchPress = 0f
    private var searchAnimator: android.animation.ValueAnimator? = null
    override fun actionButtonPressProgress() = searchPress
    fun onSearchTouch(host: View, event: android.view.MotionEvent) {
        val location = IntArray(2); host.getLocationOnScreen(location)
        val x = event.rawX - location[0]; val y = event.rawY - location[1]
        val inside = x >= host.width - host.height && x <= host.width && y in 0f..host.height.toFloat()
        val pressed = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> inside
            android.view.MotionEvent.ACTION_MOVE -> searchPressed && inside
            else -> false
        }
        if (pressed == searchPressed) return
        searchPressed = pressed
        searchAnimator?.cancel()
        searchAnimator = android.animation.ValueAnimator.ofFloat(searchPress, if (pressed) 1f else 0f).apply {
            duration = if (pressed) 100L else 180L
            addUpdateListener { searchPress = it.animatedValue as Float; host.postInvalidateOnAnimation() }
            start()
        }
    }
    override fun onHostFrame(host: View) {
        val glass = host as? GlassHostLayout ?: return
        // View alpha otherwise allocates a bounds-sized overlap layer that
        // cuts the ambient shadow even when parent clipChildren is false.
        if (glass.hasOverlappingRendering()) glass.forceHasOverlappingRendering(false)
        glass.clipToOutline = false
        hostRef = WeakReference(glass)
        chrome.update(glass, currentNativePage(), currentVisualPage())
        val observer = glass.viewTreeObserver
        if (observer !== scrollObserver && observer.isAlive) {
            detachBackdropScrollListener()
            observer.addOnScrollChangedListener(backdropScrollListener)
            scrollObserver = observer
        }
    }
    private fun invalidateNativeBackdrop() {
        hostRef.get()?.takeIf { it.isAttachedToWindow && it.isShown }?.postInvalidateOnAnimation()
        chrome.invalidateBackdropPanels()
    }
    private fun detachBackdropScrollListener() {
        scrollObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(backdropScrollListener)
        scrollObserver = null
    }
    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> = listOfNotNull(nativeScene())
    override fun handleSystemBack(host: View): Boolean = chrome.dismissMenu()
    override fun onOpticalSurfaceFrameInvalidated() {
        opticalFrameReady = false
        opticalDirty = true
        copyBarrier.reset()
        hostRef.get()?.let(::invalidateBackdropHost)
    }
    internal fun invalidateBackdropHost(host: GlassHostLayout) { hostModes.remove(host) }
    override fun onHostDetached() {
        copyBarrier.reset()
        hostRef.get()?.let(::invalidateBackdropHost)
        detachBackdropScrollListener()
        sceneCandidate = null
        safeScene = null
    }
    override fun onOpticalBackdropCaptured(barSource: Rect?) {
        if (!opticalFrameReady) hostRef.get()?.queueOpticalSurfaceRender()
    }
    override fun onOpticalSurfacePipelineEnabled(wasActive: Boolean) { if (!wasActive) opticalFrameReady = false }
    override fun onOpticalSurfaceFrameRendered(): Boolean {
        opticalDirty = false
        val first = !opticalFrameReady
        opticalFrameReady = true
        return first
    }
    override fun holdDirectBackdropUntilOpticalFrame(liveBackdropActive: Boolean, hasDisplayList: Boolean) =
        false
    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        opticalFrameReady = false
        backdropEpoch++
        if (hasFocus) hostRef.get()?.let { host ->
            hostModes.remove(host)
            host.post { if (host.isAttachedToWindow) {
                updateBackdropRendererMode(host)
                host.postInvalidateOnAnimation()
            } }
        }
    }
    private var cachedTabs: List<View> = emptyList()
    private val defaults = WeakHashMap<View, android.graphics.drawable.Drawable>()
    private val fields = HashMap<Pair<Class<*>, String>, java.lang.reflect.Field?>()
    private val methods = HashMap<Pair<Class<*>, String>, java.lang.reflect.Method?>()
    private val os4Icons = WeakHashMap<Any, Drawable>()
    private val liveIconCopies = WeakHashMap<Drawable, Drawable>()
    private var iconConfiguration: android.content.res.Configuration? = null
    private val orangeFilter = PorterDuffColorFilter(MARKET_ACCENT, PorterDuff.Mode.SRC_IN)
    private val lightFilter = PorterDuffColorFilter(NORMAL_ICON_COLOR, PorterDuff.Mode.SRC_IN)
    private val darkFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BADGE_COLOR }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        if (this.source === source && explicitBottomTab?.get()?.isAttachedToWindow == true) return
        this.source = source
        source?.let { navigation ->
            var parent: View? = navigation
            repeat(8) {
                val current = parent ?: return@repeat
                if (current.javaClass.name == MIUX4_BOTTOM_TAB_CLASS || current.javaClass.name == BOTTOM_TAB_CLASS) {
                    (current as? ViewGroup)?.let(::bindBottomTab)
                    return@let
                }
                parent = current.parent as? View
            }
        }
    }

    /** The Market adapter supplies its live controller from Market's own lifecycle hook. */
    fun bindBottomTab(bottomTab: ViewGroup) {
        cachedTabs = emptyList()
        if (explicitBottomTab?.get() !== bottomTab) os4Icons.clear()
        explicitBottomTab = WeakReference(bottomTab)
        cachedBottomTab = WeakReference(bottomTab)
        isMiux4Controller = bottomTab.javaClass.name == MIUX4_BOTTOM_TAB_CLASS
    }

    override val readyForDrawing: Boolean get() = bottomTab() != null

    // Home's immersive channels hide tab_container_layout while retaining the
    // same native controller. Keep the adapter-owned bar and native routing.
    override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int): Boolean =
        bottomTab() != null && tabViews().size >= 2
    override fun retainNavigationWithoutNativeSource(selectedIndex: Int): Boolean =
        retainNavigationWhenNativeRowHidden(selectedIndex)

    // Bind to the selected channel so preloaded siblings cannot invalidate its backdrop.
    // Chromium and other independently rendered surfaces cannot be replayed safely.
    private var sceneCandidate: View? = null
    private var sceneCheckedAt = 0L
    private var safeScene: View? = null

    private fun nativeScene(): View? {
        val page = currentNativePage() ?: return null
        val candidate = if (currentVisualPage() == 3) {
            callNoArgs(page, "getView") as? View
        } else {
            chrome.selectedBackdropRoot(page)
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (candidate !== sceneCandidate || now - sceneCheckedAt >= 8L) {
            sceneCandidate = candidate
            sceneCheckedAt = now
            val next = candidate?.takeIf(::isSafeMarketBackdrop)
            if (safeScene !== next) {
                safeScene = next
                invalidateNativeBackdrop()
            }
        }
        return safeScene?.takeIf { it.isAttachedToWindow && it.isShown }
    }

    override fun resolveBackdropScene(host: View): View? = nativeScene()
    override fun skipLiveBackdrop(selectedIndex: Int): Boolean = nativeScene() == null
    override fun onLiveBackdropRecorded(selectedIndex: Int) {
        hostRef.get()?.postInvalidateOnAnimation()
    }
    override val allowsSoftwareBackdropCapture: Boolean get() = false
    override fun allowsOpticalSurfacePipeline() = false
    override fun opticalCaptureEpoch(): Long = backdropEpoch

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        host.disableOpticalSurfaceRendererForAdapter()
        return true
    }
    override fun onOpticalRendererConfigurationChanged() {
        opticalDirty = true
        hostRef.get()?.let { hostModes.remove(it) }
    }


    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true
    override fun drawHiddenTabsInIndicatorScene(hybridBackdrop: Boolean) = true

    override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || selectionPath.isEmpty) { drawSource(); return true }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(outside)
        if (!opticalFrameReady) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            draw(canvas, frame.copy(hideSelectedForOptics = true))
            canvas.restoreToCount(inside)
            return true
        }
        if (mix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), ((1f - mix) * 255f).toInt())
            drawSource()
            canvas.restoreToCount(inside)
        }
        return true
    }

    override fun refreshNavigationSource(host: View): Boolean {
        val tabs = navigationEntries()
        if (tabs.isEmpty()) return false
        val selected = selectedIndex().coerceIn(0, tabs.lastIndex)
        (host as? GlassHostLayout)?.updateAdapterSlotCount(3, selected)
        return true
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        if (source != null) this.source = source
        return selectedIndex().takeIf { it in 0 until slotCount }
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        val tabs = navigationEntries()
        if (tabs.isEmpty() || frame.width <= 0 || frame.height <= 0) return
        val configuration = context.resources.configuration
        if (iconConfiguration != configuration) {
            iconConfiguration = android.content.res.Configuration(configuration)
            os4Icons.clear()
        }
        labelPaint.textSize = 11f * frame.scaledDensity
        tabs.forEachIndexed { index, entry ->
            val (nativeIndex, tab) = entry
            val info = tabInfo(tab, nativeIndex)
            val title = listOf("首页", "我")[index]
            val icon = liveIcon(tab) ?: miux4Icon(info, tab) ?: run {
                val item = tab as View
                (field(item, "mIconView") as? ImageView)?.drawable
                    ?: defaults[item]
                    ?: info?.let {
                        runCatching { XposedHelpers.callMethod(it, "getDefaultTabIconDrawble") as? Drawable }.getOrNull()
                    }?.also { defaults[item] = it }
            }
            val mainWidth = frame.barLayout?.mainWidth ?: frame.width.toFloat()
            val centerX = (mainWidth - tabs.size * frame.slotWidth) / 2f + (index + 0.5f) * frame.slotWidth
            val iconWidth = 33f * frame.density
            val iconHeight = icon?.let {
                iconWidth * it.intrinsicHeight.coerceAtLeast(1) / it.intrinsicWidth.coerceAtLeast(1)
            } ?: iconWidth
            val metrics = labelPaint.fontMetrics
            val gap = 0.5f * frame.density
            val groupHeight = iconHeight + gap + metrics.descent - metrics.ascent
            val groupTop = (frame.height - groupHeight) / 2f
            val hideLabel = frame.iconOnly && icon != null
            val iconCenterY = if (hideLabel) frame.height / 2f else groupTop + iconHeight / 2f
            val artworkColor = if (frame.hideSelectedForOptics) MARKET_ACCENT
                else if (frame.darkGlass) Color.WHITE else NORMAL_ICON_COLOR
            canvas.save()
            canvas.scale(frame.iconScale * frame.extraScale, frame.iconScale * frame.extraScale, centerX, frame.height / 2f)
            icon?.let {
                val oldBounds = Rect(it.bounds)
                val oldFilter = it.colorFilter
                val width = iconWidth
                val height = iconHeight
                val cy = iconCenterY
                it.setBounds((centerX - width / 2).toInt(), (cy - height / 2).toInt(),
                    (centerX + width / 2).toInt(), (cy + height / 2).toInt())
                it.colorFilter = if (frame.hideSelectedForOptics) orangeFilter
                    else if (frame.darkGlass) darkFilter else lightFilter
                try { it.draw(canvas) } finally { it.bounds = oldBounds; it.colorFilter = oldFilter }
            }
            labelPaint.color = artworkColor
            val labelBaseline = groupTop + iconHeight + gap - metrics.ascent
            if (!hideLabel) canvas.drawText(title, centerX, labelBaseline, labelPaint)
            drawBadge(canvas, tab, nativeIndex, centerX, iconCenterY, iconWidth, frame)
            canvas.restore()
        }
        if (!frame.hideSelectedForOptics) frame.barLayout?.let { layout ->
            val cx = layout.actionLeft + layout.actionSize / 2f
            val cy = frame.height / 2f
            val p = labelPaint
            p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * frame.density
            p.strokeCap = Paint.Cap.ROUND
            val save = canvas.save()
            val scale = 1f + 0.08f * searchPress
            canvas.scale(scale, scale, cx, cy)
            val r = 8f * frame.density
            canvas.drawCircle(cx - 2f * frame.density, cy - 2f * frame.density, r, p)
            canvas.drawLine(cx + 4f * frame.density, cy + 4f * frame.density,
                cx + 10f * frame.density, cy + 10f * frame.density, p)
            canvas.restoreToCount(save)
            p.style = Paint.Style.FILL
        }
    }

    /** Prefer the live OS4 item artwork, which follows Market's native icon asset updates. */
    private fun miux4Icon(info: Any?, tab: View): Drawable? {
        if (info == null) return null
        os4Icons[info]?.let { return it }
        return ((callNoArgs(info, "getIconNormalDrawable") as? Drawable)?.takeIf {
                it.intrinsicWidth > 0 && it.intrinsicHeight > 0
            } ?: (if(isMiux4Controller)callNoArgs(info, "getOs4LocalIconDrawable") as? Drawable else null)
            ?: (callNoArgs(info, "getDefaultTabIconDrawble") as? Drawable))?.mutate()?.also { os4Icons[info] = it }
    }
    private fun liveIcon(tab: View): Drawable? {
        val direct = (field(tab,"mIconView") as? ImageView)?.drawable
        val drawable = direct ?: if (isMiux4Controller && tab is ViewGroup) {
            val queue=ArrayDeque<View>(); queue.add(tab)
            var found: Drawable?=null; var count=0
            while(queue.isNotEmpty() && count++<48 && found==null) {
                val view=queue.removeFirst()
                if(view is ImageView && view.drawable!=null && view.width>=16*context.resources.displayMetrics.density) found=view.drawable
                else if(view is ViewGroup)for(i in 0 until view.childCount)queue.add(view.getChildAt(i))
            }
            found
        } else null
        drawable ?: return null
        val constantState=drawable.constantState ?: return drawable
        return liveIconCopies.getOrPut(drawable) {
            constantState.newDrawable(context.resources).mutate().apply {
                state=intArrayOf(); clearColorFilter()
            }
        }
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (index == 2) return chrome.openSearch()
        if (source != null) this.source = source
        val bottom = bottomTab() ?: return false
        val entries=navigationEntries()
        val (nativeIndex, tab) = entries.getOrNull(index) ?: return false
        if (tab.performClick()) return true
        return runCatching { XposedHelpers.callMethod(bottom, "show", nativeIndex, -1); true }.getOrDefault(false)
    }

    private fun drawBadge(canvas: Canvas, item: View, index: Int, x: Float, y: Float, width: Float, frame: AdapterNavigationFrame) {
        val number = if (isMiux4Controller) {
            val controller = (bottomTab()?.let { field(it, "tabControllers") } as? List<*>)?.getOrNull(index)
            (controller?.let { field(it, "currentNumber") } as? Number)?.toInt() ?: 0
        } else (callNoArgs(item, "getNumber") as? Number)?.toInt() ?: 0
        if (number <= 0) return
        val text = if (number > 99) "99+" else number.toString()
        val h = 14f * frame.density
        badgeTextPaint.textSize = 8.5f * frame.scaledDensity
        val w = maxOf(h, badgeTextPaint.measureText(text) + 6f * frame.density)
        val cx = x + width * 0.38f
        val cy = y - 9.5f * frame.density
        canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, badgePaint)
        val fm = badgeTextPaint.fontMetrics
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, badgeTextPaint)
    }

    private fun selectedIndex(): Int {
        val bottom = bottomTab() ?: return 0
        val fieldName = if (isMiux4Controller) "currentTabIndex" else "mCurrentTabIndex"
        val nativeIndex = (field(bottom, fieldName) as? Number)?.toInt() ?: 0
        val entries=navigationEntries()
        return entries.indexOfFirst { it.first == nativeIndex }.coerceAtLeast(0)
    }

    internal fun navigationEntries(): List<Pair<Int, View>> {
        val native = tabViews()
        val entries = native.mapIndexedNotNull { index, tab ->
            val info = tabInfo(tab, index) ?: return@mapIndexedNotNull null
            val tag = callNoArgs(info, "getTag") as? String
            val title=callNoArgs(info,"getTitle") as? String
            val order = when (tag) {
                "native_market_home", "index" -> 0
                "native_market_rank", "rank" -> 1
                "native_market_mine", "mine" -> 2
                else -> if(title=="榜单" || title=="排行榜")1 else return@mapIndexedNotNull null
            }
            Triple(order, index, tab)
        }
        val home=entries.singleOrNull { it.first == 0 } ?: return emptyList()
        val mine=entries.singleOrNull { it.first == 2 } ?: return emptyList()
        // Home and Mine remain native-owned; other entries never get a bottom slot.
        return listOf(home,mine).map { it.second to it.third }
    }

    internal fun currentNativePage(): Any? = bottomTab()?.let { callNoArgs(it, "getCurrentFragment") }
    internal fun currentVisualPage(): Int = if (selectedIndex() == 1) 3 else 0

    private fun tabInfo(tab: View, index: Int): Any? {
        if (!isMiux4Controller) return field(tab, "mTab")
        val bottom = bottomTab() ?: return null
        return (field(bottom, "tabInfoList") as? List<*>)?.getOrNull(index)
    }

    private fun tabViews(): List<View> {
        val bottom = bottomTab() ?: return emptyList()
        val expectedCount = (field(bottom, if (isMiux4Controller) "tabInfoList" else "mTabViews") as? List<*>)?.size
        if (cachedTabs.isNotEmpty() && cachedTabs.size == expectedCount && cachedTabs.all { it.isAttachedToWindow }) {
            return cachedTabs
        }
        if (isMiux4Controller) {
            val controllers = (field(bottom, "tabControllers") as? List<*>)?.filterNotNull().orEmpty()
            val items = controllers.mapNotNull { controller ->
                callNoArgs(controller, "getItemView") as? View
            }
            if (controllers.isNotEmpty() && items.size == expectedCount &&
                items.distinct().size == items.size && items.all { item ->
                    generateSequence(item.parent) { it.parent }.any { it === bottom }
                }) return items.also { cachedTabs = it }
            android.util.Log.w("LiquidTabMarketBinding", "Native tab controller item views unavailable; no unverified fallback")
            return emptyList()
        }
        (field(bottom, "mTabViews") as? List<*>)?.filterIsInstance<View>()?.takeIf { it.isNotEmpty() }?.let { cachedTabs = it; return it }
        val row = field(bottom, "tabViewsLayout") as? ViewGroup ?: return emptyList()
        return (0 until row.childCount).map(row::getChildAt)
            .filter { it.javaClass.name == "com.xiaomi.market.widget.TabView" }
    }

    private fun bottomTab(): ViewGroup? {
        explicitBottomTab?.get()?.takeIf { it.isAttachedToWindow }?.let { return it }

        val navigation = source
        if (navigation != null) {
            var parent: View? = navigation
            repeat(16) {
                if (parent?.javaClass?.name in setOf(BOTTOM_TAB_CLASS, MIUX4_BOTTOM_TAB_CLASS) && parent?.isAttachedToWindow == true) {
                    (parent as? ViewGroup)?.let { return cacheBottomTab(it) }
                }
                parent = parent?.parent as? View
            }
            findBottomTab(navigation)?.takeIf { it.isAttachedToWindow }
                ?.let { return cacheBottomTab(it) }
        }
        cachedBottomTab.get()?.takeIf { it.isAttachedToWindow }?.let { return it }

        // Market can replace or reparent its tab row while the retained glass
        // host stays attached. Resolve the live controller from the Activity
        // tree so artwork rendering and click dispatch always share that row.
        var current: Context? = context
        repeat(12) {
            when (val value = current) {
                is Activity -> return findBottomTab(value.window?.decorView ?: return null)
                    ?.let(::cacheBottomTab)
                is ContextWrapper -> current = value.baseContext
                else -> return@repeat
            }
        }
        return null
    }

    private fun findBottomTab(root: View): ViewGroup? {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MAX_TREE_NODES) {
            val (view, depth) = stack.removeLast()
            if (view.javaClass.name == BOTTOM_TAB_CLASS || view.javaClass.name == MIUX4_BOTTOM_TAB_CLASS) return view as? ViewGroup
            if (view is ViewGroup && depth < MAX_TREE_DEPTH) {
                for (index in view.childCount - 1 downTo 0) {
                    stack += view.getChildAt(index) to (depth + 1)
                }
            }
        }
        return null
    }

    private fun cacheBottomTab(view: ViewGroup): ViewGroup {
        cachedBottomTab = WeakReference(view)
        isMiux4Controller = view.javaClass.name == MIUX4_BOTTOM_TAB_CLASS
        return view
    }

    private fun field(instance: Any, name: String): Any? {
        val key = instance.javaClass to name
        if (fields.containsKey(key)) return fields[key]?.get(instance)
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true; fields[key] = it; return it.get(instance)
            }
            type = type.superclass
        }
        fields[key] = null
        return null
    }

    private fun callNoArgs(instance: Any, name: String): Any? {
        val key = instance.javaClass to name
        if (!methods.containsKey(key)) {
            methods[key] = instance.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.apply { isAccessible = true }
        }
        return runCatching { methods[key]?.invoke(instance) }.getOrNull()
    }

    override fun dispose() {
        searchAnimator?.cancel(); searchAnimator = null
        detachBackdropScrollListener()
        sceneCandidate = null
        safeScene = null
        // Entire window restoration/removal runs after Android's detach walk.
        // Capture the old owner so a reattached host cannot clean up new views.
        val retiredChrome=chrome
        chrome=MiMarketChrome(context,this)
        renderHandler.post { retiredChrome.dispose() }
        source = null
        explicitBottomTab = null
        cachedBottomTab.clear()
        cachedTabs = emptyList()
        os4Icons.clear()
        liveIconCopies.clear()
        defaults.clear()
        fields.clear()
        methods.clear()
    }

    private companion object {
        const val BADGE_COLOR = 0xFFFF3B30.toInt()
        const val MARKET_ACCENT = 0xFFFF6900.toInt()
        const val NORMAL_ICON_COLOR = 0xFF646464.toInt()
        const val BOTTOM_TAB_CLASS = "com.xiaomi.market.widget.BottomTabLayout"
        const val MIUX4_BOTTOM_TAB_CLASS = "com.xiaomi.market.widget.BottomTabLayoutMiux4"
        const val MAX_TREE_DEPTH = 24
        const val MAX_TREE_NODES = 4_096
    }
}

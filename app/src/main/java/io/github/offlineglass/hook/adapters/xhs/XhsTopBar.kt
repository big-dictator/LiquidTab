package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable

import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Native home semantics are bound by structure; version numbers never gate rendering. */
internal object XhsTopBar {
    private val observed = WeakHashMap<View, Boolean>()
    private val sessions = WeakHashMap<View, WeakReference<Session>>()
    fun install(loader: ClassLoader) {
        XhsChannelMotion.install { view ->
            sessions.values.firstNotNullOfOrNull { reference ->
                reference.get()?.takeIf { !it.stopped && it.ready && it.config.enabled && it.root === view.rootView }?.channelMotion
            }
        }
        runCatching {
            val type = Class.forName("com.xingin.xhs.homepage.tabbar.view.HomeTabLayout", false, loader)
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val row = param.thisObject as? ViewGroup ?: return
                    if (observed.put(row, true) == true) return
                    row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) { row.post { attach(row) } }
                        override fun onViewDetachedFromWindow(v: View) { sessions.remove(row)?.get()?.stop() }
                    })
                    row.post { attach(row) }
                }
            })
        }.onFailure { android.util.Log.w("LiquidTabXhsTop", "native header retained", it) }
    }
    private fun attach(row: ViewGroup) {
        if (!row.isAttachedToWindow || sessions[row]?.get() != null) return
        var context: Context = row.context
        repeat(12) { if (context !is Activity) context = (context as? ContextWrapper)?.baseContext ?: return }
        val activity = context as? Activity ?: return
        val decor = activity.window.decorView as? FrameLayout ?: return
        Session(activity, decor, row).also { sessions[row] = WeakReference(it); it.start() }
    }
    private class Overlay(context: Context) : FrameLayout(context), AdapterOwnedOverlay {
        var foregroundChanged: ((Boolean) -> Unit)? = null
        init { clipChildren = false; clipToPadding = false }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            foregroundChanged?.invoke(hasWindowFocus && windowVisibility == View.VISIBLE)
        }
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            foregroundChanged?.invoke(visibility == View.VISIBLE && hasWindowFocus())
        }
    }
    private class TabInk(context: Context) : LinearLayout(context) {
        private val channelMeasure = io.github.offlineglass.hook.topbar.TopBarTabMeasure()
        private var tabLayout: io.github.offlineglass.hook.topbar.TopBarTabLayout? = null
        var material: GlassHostLayout? = null
            set(value) {
                field = value
                value?.updateTopBarTabs(tabLayout)
            }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val labels = (0 until childCount).map { getChildAt(it) as TextView }
            val density = resources.displayMetrics.density
            val measure = channelMeasure.update(labels.map { it.text.toString() }, density,
                labels.maxOfOrNull { it.textSize } ?: 0f)
            if (measure == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            if (tabLayout !== measure) {
                tabLayout = measure
                material?.updateTopBarTabs(measure)
            }
            val width = resolveSize(kotlin.math.ceil(measure.desiredWidth).toInt(), widthMeasureSpec)
            val inset = (4f * density).roundToInt()
            if (paddingLeft != inset || paddingRight != inset) setPadding(inset, paddingTop, inset, paddingBottom)
            labels.forEachIndexed { index, label ->
                val lp = label.layoutParams as LayoutParams
                lp.width = measure.slotPixels(index, width, inset.toFloat()).coerceAtLeast(1)
            }
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
        private val inkPosition = IntArray(2)
        private val glassPosition = IntArray(2)
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val host = material ?: return super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            getLocationInWindow(inkPosition); host.getLocationInWindow(glassPosition)
            val forwarded = MotionEvent.obtain(event)
            val handled = try {
                forwarded.offsetLocation((inkPosition[0] - glassPosition[0]).toFloat(),
                    (inkPosition[1] - glassPosition[1]).toFloat())
                host.dispatchTouchEvent(forwarded)
            } finally { forwarded.recycle() }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                parent?.requestDisallowInterceptTouchEvent(false)
            return handled
        }
    }
    private data class Saved(val alpha: Float, val accessibility: Int)
    private class Session(val activity: Activity, val root: FrameLayout, val nativeTabs: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
        val d = root.resources.displayMetrics.density
        val overlay = Overlay(activity)
        private val foregroundEpoch = XhsForegroundEpoch()
        private var settledRecovery: Runnable? = null
        private val foregroundUpdate = Runnable { if (!stopped) recoverForegroundMaterial() }
        private fun foreground(): Boolean = activity.hasWindowFocus() && root.windowVisibility == View.VISIBLE &&
            overlay.isShown && nativeTabs.isShown && config.enabled && config.xhsTopBar
        private fun recoverForegroundMaterial() {
            val active = foreground()
            if (!active) {
                settledRecovery?.let(root::removeCallbacks); settledRecovery = null
            }
            if (!foregroundEpoch.update(active, topHosts.isNotEmpty())) return
            val epoch = foregroundEpoch.generation()
            scanAt = 0L
            scanDirty = true
            fun refresh(host: GlassHostLayout) {
                if (!host.isAttachedToWindow) return
                // Rebuild all derived GPU state, not only the View's display
                // list: a stopped window can lose its backdrop/effect resources.
                host.invalidateOpticalSceneForAdapter()
                host.refreshAdapterDerivedConfig()
                host.adapterScheduleBackdropRefresh(0L)
            }
            (topHosts + surfaces.values).forEach(::refresh)
            // A second bounded pass runs after the native content has committed
            // its return frame. No timer survives loss of focus or destruction.
            settledRecovery?.let(root::removeCallbacks)
            settledRecovery = Runnable {
                settledRecovery = null
                if (foregroundEpoch.accepts(epoch, foreground()))
                    (topHosts + surfaces.values).forEach { host ->
                        if (host.isAttachedToWindow) host.adapterScheduleBackdropRefresh(0L)
                    }
            }.also { root.postDelayed(it, 64L) }
        }
        private val topGradient = View(activity).apply {
            isClickable = false; isFocusable = false; translationZ = -1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private var gradientNight: Boolean? = null
        val originals = LinkedHashMap<View, Saved>()
        private val decorativeVisibility = LinkedHashMap<View, Int>()
        val backgrounds = LinkedHashMap<View, Drawable?>()
        val heights = LinkedHashMap<View, Int>()
        val offsets = LinkedHashMap<View, Float>()
        val clipping = LinkedHashMap<ViewGroup, Pair<Boolean, Boolean>>()
        val panelLimits = LinkedHashMap<ViewGroup, Int>()
        var bottomHosts = emptyList<GlassHostLayout>()
        val surfaces = LinkedHashMap<View, GlassHostLayout>()
        val excluded = LinkedHashSet<View>()
        private val backdrop = XhsBackdropScene(root, excluded) { scanDirty = true }
        private val selectedTab = runCatching { nativeTabs.javaClass.getMethod("getSelectedTabPosition") }.getOrNull()
        private var homeTab: View? = null
        private val headerChrome = XhsHeaderChrome(nativeTabs)
        private val marketBackdrop = XhsMarketBackdrop(nativeTabs)
        private var clearedChrome: View? = null
        val position = IntArray(2)
        val rootPosition = IntArray(2)
        var config = GlassConfig()
        var configAt = 0L
        var scanAt = 0L
        var configPending = false
        var stopped = false
        var ready = false
        var nativeCells = emptyList<View>()
        var nativeOrder = emptyList<View>()
        var menuSource: View? = null
        var searchSource: View? = null
        var channelSource: View? = null
        var channelRow: View? = null
        private val channelSelection = XhsTopChannel()
        var panel: ViewGroup? = null

        var headerBottom = 0
        var selection = -1
        var discovery = false
        var night = false
        var channelPendingUntil = 0L
        private var scanDirty = true
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scanDirty = true }
        val left = Action(activity, 0) { openSidebar() }
        val right = Action(activity, 1) { click(searchSource) }
        val channel = ChannelAction(activity).apply {
            contentDescription = "展开频道选择"; setOnClickListener { toggleChannel() }
        }
        val channelMotion = XhsChannelMotion(channel)
        private data class ContentMount(val recycler: View, val wrapper: XhsChannelContent,
            val params: ViewGroup.LayoutParams, val index: Int, val padding: Rect, val listBottom: Int)
        private val contentMounts = LinkedHashMap<ViewGroup, ContentMount>()
        val tabInk = TabInk(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val labels = listOf("关注", "发现", "同城").mapIndexed { index, label ->
            TextView(activity).apply {
                text = label; textSize = 15f; gravity = Gravity.CENTER; includeFontPadding = false
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true; contentDescription = label
                setOnClickListener { click(nativeCells.getOrNull(index)) }
            }.also { tabInk.addView(it, LinearLayout.LayoutParams(0, -1)) }
        }
        val controls = XhsTopBarRow(activity, left, tabInk, channel, right, labels)
        val topHosts = ArrayList<GlassHostLayout>()
        val viewport = XhsTopViewport()
        private val refreshIndicator = XhsRefreshIndicator()
        fun dp(v: Int) = (v * d).roundToInt()
        fun start() {
            XhsTopTheme.watch(root)
            overlay.foregroundChanged = { foreground ->
                // Remember loss synchronously: a quick switch can coalesce both
                // posted callbacks before either one gets to inspect the window.
                if (!foreground) {
                    foregroundEpoch.update(false, false)
                    controls.resetMotion()
                    settledRecovery?.let(root::removeCallbacks); settledRecovery = null
                }
                root.removeCallbacks(foregroundUpdate)
                if (foreground) root.post(foregroundUpdate)
            }
            // Native category sheets are appended to DecorView. Keep only our
            // small header controls above their full-window dismissal layer.
            overlay.translationZ = dp(16).toFloat()
            root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            overlay.addView(topGradient, FrameLayout.LayoutParams(-1, 1))

            root.viewTreeObserver.addOnPreDrawListener(this)
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            overlay.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
        fun click(view: View?) {
            if (view?.isAttachedToWindow == true && view.isEnabled) view.performClick()
        }
        fun channelSheet(): ViewGroup? {
            return XhsTopBinding.sheet(root, panel)
        }
        fun toggleChannel() {
            val now = SystemClock.uptimeMillis()
            if (now < channelPendingUntil) return
            channelPendingUntil = now + 350L
            val sheet = channelSheet()
            if (sheet != null) {
                // x8d.b case19 installs this click as t6d.r.v0(): native close,
                // animation, detach and channel controller cleanup stay together.
                click(XhsTopBinding.spacer(sheet))
            } else click(channelSource)
            scanAt = 0L
            root.post { if (!stopped) { scan(); root.invalidate() } }
        }
        fun openSidebar() {
            if (menuSource?.isAttachedToWindow == true && menuSource?.hasOnClickListeners() == true) {
                click(menuSource); return
            }
            // Some 9.51 home layouts omit the icon. Reuse its verified native event callback.
            runCatching {
                XhsNativeBindings.method("sidebar")?.invoke(null, null)
            }.onFailure { android.util.Log.w("LiquidTabXhsTop", "sidebar native action unavailable", it) }
        }
        fun hide(view: View?, decorative: Boolean = false) {
            if (view == null) return
            originals.getOrPut(view) { Saved(view.alpha, view.importantForAccessibility) }
            if (view.alpha != 0f) view.alpha = 0f
            // Only decorative siblings lose visibility; native action targets
            // retain it. This also excludes RenderThread-only alpha animations.
            if (decorative && view.visibility == View.VISIBLE) {
                decorativeVisibility.putIfAbsent(view, view.visibility)
                view.visibility = View.INVISIBLE
            }
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (excluded.add(view)) backdrop.changed(view)
        }
        fun clear(view: View?) {
            if (view == null) return
            if (!backgrounds.containsKey(view)) backgrounds[view] = view.background
            if (view.background != null) view.background = null
        }
        fun scan() {
            scanDirty = false
            fun label(view: View): String {
                if (view is TextView && view.text.isNotBlank()) return view.text.toString().trim()
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    val value = label(view.getChildAt(i)); if (value.isNotEmpty()) return value
                }
                return ""
            }
            // Locate the semantic strip even if XYTabLayout inserts a wrapper.
            fun cells(view: ViewGroup): List<View>? {
                val children = (0 until view.childCount).map(view::getChildAt)
                val tabs = children.filter { it.isClickable }
                if (tabs.size == 3 && tabs.any { label(it).contains("关注") } &&
                    tabs.any { label(it).contains("发现") }) return tabs
                return children.filterIsInstance<ViewGroup>().firstNotNullOfOrNull(::cells)
            }
            val order = cells(nativeTabs)
            if (order != null) nativeOrder = order
            val following = nativeOrder.firstOrNull { label(it).contains("关注") }
            val explore = nativeOrder.firstOrNull { label(it).contains("发现") }
            nativeCells = if (following != null && explore != null)
                listOf(following, explore) + nativeOrder.filter { it !== following && it !== explore }
                else nativeOrder
            menuSource = XhsTopBinding.headerAction(nativeTabs, false, 0x7f0930d1)
            searchSource = XhsTopBinding.headerAction(nativeTabs, true, 0x7f092e7c)
            // x8d.b0.t0() -> 0x7f0908ae; x8d.k observes this exact native click.
            channelSource = root.findViewById(0x7f0908ae)
            channelRow = XhsTopBinding.channelRow(root) ?: root.findViewById(0x7f09110f)
            channelSelection.bind(channelRow)
            val nativeBar = XhsTopBinding.find(root) {
                it.javaClass.name == "com.xingin.xhs.homepage.tabbar.TabBarView"
            }
            homeTab = nativeBar?.let { bar -> runCatching {
                bar.javaClass.getDeclaredMethod("getIndexHome").apply { isAccessible = true }.invoke(bar) as? View
            }.getOrNull() }
            clearedChrome = (channelRow?.parent as? View)?.takeIf {
                it.javaClass.name == "com.google.android.material.appbar.AppBarLayout"
            }
            channelSource = channelRow?.let { row ->
                row.findViewById<View>(0x7f0908ae)?.takeIf { it is android.widget.ImageView }
                    ?: XhsTopBinding.find(row) { it is android.widget.ImageView && it.hasOnClickListeners() }
            }
            panel = null
            val bottom = ArrayList<GlassHostLayout>()
            fun walk(v: View) {
                if (v is GlassHostLayout) {
                    if (v !in topHosts && v !in surfaces.values) bottom.add(v)
                    return
                }
                if (v is AdapterOwnedOverlay) return
                when (v.javaClass.name) {
                    "com.xingin.xhs.homepage.explorefeed.category.panel.CategoryViewView" -> panel = v as? ViewGroup
                }
                if (v is ViewGroup && !XhsTopBinding.isRecycler(v))
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            bottomHosts = bottom
            viewport.discover(root)
            refreshIndicator.discover(root)
            ready = nativeCells.size == 3 && searchSource != null
        }
        fun ensureHosts() {
            if (topHosts.isNotEmpty()) return
            repeat(4) { role ->
                val host = XhsTopMaterial.create(activity, config, root, backdrop, if (role == 1) 3 else 1,
                    selected = { if (role == 1) selection.coerceAtLeast(0) else 0 },
                    onSelect = if (role == 1) { index ->
                        val target = nativeCells.getOrNull(index)
                        if (ready && !stopped && config.enabled && config.xhsTopBar &&
                            target?.isAttachedToWindow == true && target.isEnabled && target.rootView === root) {
                            XhsTopBinding.spacer(channelSheet())?.let(::click)
                            if (nativeCells.getOrNull(index) !== target || !target.isAttachedToWindow ||
                                !target.isEnabled || target.rootView !== root) return@create false
                            target.performClick().also { root.postInvalidateOnAnimation() }
                        } else false
                    } else null)
                topHosts.add(host)
                controls.mountGlass(role, host)
                if (role == 1) tabInk.material = host
            }
        }
        override fun onPreDraw(): Boolean {
            if (stopped) return true
            if (root.windowVisibility != View.VISIBLE) { recoverForegroundMaterial(); return true }
            val now = SystemClock.uptimeMillis()
            if (!configPending && now - configAt > 1000L) {
                configPending = true; configAt = now
                worker.execute {
                    val value = runCatching { HookConfigReader.read(activity, activity.packageName) }.getOrNull()
                    root.post { if (!stopped && value != null) { config = value; root.invalidate() }; configPending = false }
                }
            }
            // Layout events are hints, not permission to rescan for every
            // layout in a native page-switch transaction.
            val scanned = (scanDirty || !ready || panel?.isAttachedToWindow == false) && now - scanAt > 350L
            if (scanned) {
                scanAt = now; scan()
            }
            var parent = nativeTabs.parent as? View
            var visible = nativeTabs.isShown
            while (parent != null && parent !== root) { if (parent.alpha < .01f) visible = false; parent = parent.parent as? View }
            val home = homeTab?.takeIf { it.isAttachedToWindow }
            if (!config.enabled || !config.xhsTopBar) {
                overlay.visibility = View.GONE; restoreNative(); return true
            }
            if (scanned || !marketBackdrop.discovered) marketBackdrop.discover()
            val active = visible && (home == null || home.isSelected)
            if (!active || (!ready && topHosts.isEmpty())) {
                controls.resetMotion()
                overlay.visibility = View.GONE; recoverForegroundMaterial(); return true
            }
            ensureHosts()
            overlay.visibility = View.VISIBLE
            recoverForegroundMaterial()
            val nativeIndex = runCatching { selectedTab?.invoke(nativeTabs) as? Int }.getOrNull()
            val current = nativeIndex?.let { nativeCells.indexOf(nativeOrder.getOrNull(it)) }
            if (current != null && current in 0..2) selection = current
            discovery = selection == 1
            val nativeNight = XhsTopTheme.dark(activity, config.themeMode)
            if (night != nativeNight) {
                night = nativeNight
                topHosts.forEach { it.invalidate() }
                surfaces.values.forEach { it.invalidate() }
            }
            updateStatusGradient()

            nativeTabs.getLocationInWindow(position); root.getLocationInWindow(rootPosition)
            val top = position[1] - rootPosition[1] + (nativeTabs.height - dp(40)) / 2
            headerBottom = top + dp(40) + dp(10)
            XhsTopNotifications.update(root, headerBottom)
            val lp = controls.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != top) {
                lp.topMargin = top
                controls.layoutParams = lp
            }
            if (discovery) channel.caption = channelSelection.currentTitle() ?: channel.caption
            val color = if (night) Color.WHITE else Color.BLACK
            left.ink = color; right.ink = color
            channel.ink = color
            channel.expanded = channelSheet() != null
            val channelWidth = if (discovery) channel.preferredWidth((root.width - dp(260)).coerceAtLeast(dp(66))) else 0
            controls.update(channelWidth, selection, color, activity.hasWindowFocus())
            topHosts[1].updateAdapterSlotCount(3, selection)
            hide(nativeTabs); hide(menuSource); hide(searchSource)
            headerChrome.suppress { view, decorative -> hide(view, decorative) }
            clear(clearedChrome)
            viewport.update(headerBottom)
            refreshIndicator.update(root, headerBottom)
            // Native expand target remains attached and clickable; collapse only its channel row.
            channelRow?.let { row ->
                heights.getOrPut(row) { row.layoutParams.height }
                hide(row)
                if (row.layoutParams.height != 0) row.layoutParams = row.layoutParams.apply { height = 0 }
            }
            updatePanels()
            return true
        }
        fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
            val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
            if (lp.width != w || lp.height != h || lp.leftMargin != x || lp.topMargin != y) {
                lp.width = w; lp.height = h; lp.leftMargin = x; lp.topMargin = y; view.layoutParams = lp
            }
        }
        fun updateStatusGradient() {
            // Bilibili/YouTube's curve and extent, with peak alpha reduced from 242 to 160.
            if (gradientNight != night) {
                topGradient.background = XhsTopTheme.statusGradient(night)
                gradientNight = night
            }
            val inset = root.rootWindowInsets?.getInsets(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())?.top ?: 0
            val height = inset + dp(6)
            val params = topGradient.layoutParams
            if (params.width != root.width || params.height != height) {
                params.width = root.width; params.height = height; topGradient.layoutParams = params
            }
        }
        fun updatePanels() {
            val keep = LinkedHashSet<View>()
            if (discovery) panel?.takeIf { it.isShown }?.let { category ->
                val body = XhsTopBinding.find(category) {
                    it is ViewGroup && it.javaClass.simpleName == "MaxHeightFrameLayout"
                } as? ViewGroup ?: return@let
                val recycler = XhsTopBinding.find(body) { view ->
                    generateSequence(view.javaClass as Class<*>?) { it.superclass }
                        .any { it.name == "androidx.recyclerview.widget.RecyclerView" }
                } ?: return@let
                if (channelMotion.running && surfaces.containsKey(body)) {
                    keep.add(body)
                    return@let
                }
                mountChannelContent(body, recycler)
                clear(category); clear(category.parent as? View); clear(body); clear(recycler)
                if (excluded.add(category)) backdrop.changed(category)
                // The native spacer uses CoordinatorLayout's Y. That is now 0
                // after feed immersion; restore its header hit region explicitly.
                XhsTopBinding.spacer(channelSheet())?.let { spacer ->
                    heights.getOrPut(spacer) { spacer.layoutParams.height }
                    if (spacer.layoutParams.height != headerBottom)
                        spacer.layoutParams = spacer.layoutParams.apply { height = headerBottom }
                }
                body.getLocationInWindow(position)
                val original = offsets.getOrPut(category) { category.translationY }
                val base = position[1] - rootPosition[1] - (category.translationY - original)
                val offset = original + maxOf(0f, headerBottom - base)
                if (category.translationY != offset) category.translationY = offset
                root.getLocationInWindow(rootPosition)
                var bottomEdge = root.height - (root.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0) - dp(80)
                var foundBottom = false
                for (bar in bottomHosts) {
                    if (!bar.isShown || bar.alpha <= .01f) continue
                    bar.getLocationInWindow(position)
                    val y = position[1] - rootPosition[1]
                    if (y > root.height / 2) {
                        bottomEdge = if (foundBottom) minOf(bottomEdge, y) else y
                        foundBottom = true
                    }
                }
                body.getLocationInWindow(position)
                // Content ends 8dp before the glass, whose bottom stays 12dp above navigation.
                val maxHeight = (bottomEdge - dp(20) - (position[1] - rootPosition[1])).coerceAtLeast(dp(40))
                runCatching {
                    // Field responsibility is derived from the native setter's
                    // actual write, never from a preselected field name.
                    val field = XhsNativeBindings.field("maxHeight")?.takeIf { it.declaringClass.isInstance(body) }
                        ?: return@runCatching
                    if (!panelLimits.containsKey(body)) panelLimits[body] = field.getInt(body)
                    val capped = minOf(panelLimits.getValue(body), maxHeight)
                    val capDp = (capped / d).toInt()
                    val appliedPx = (capDp * d).toInt()
                    if (field.getInt(body) != appliedPx) {
                        body.javaClass.getMethod("setMaxHeight", Int::class.javaPrimitiveType)
                            .invoke(body, capDp)
                    }
                }.onFailure { android.util.Log.w("LiquidTabXhsTop", "native panel height contract unavailable", it) }
                clipping.getOrPut(body) { body.clipChildren to body.clipToPadding }
                body.clipChildren = true; body.clipToPadding = true
                // LinearLayout content is unchanged; sibling material belongs to its FrameLayout parent.
                val parent = category.parent as? FrameLayout
                if (parent != null && body.width > 0 && body.height > 0) {
                    unclip(parent)
                    val host = surfaces.getOrPut(body) {
                        XhsTopMaterial.create(activity, config, root, backdrop, panel = true, panelCorners = 6f).also {
                            parent.addView(it, parent.indexOfChild(category).coerceAtLeast(0), FrameLayout.LayoutParams(1, 1))
                            it.post { if (!stopped) { it.invalidate(); root.invalidate() } }
                        }
                    }
                    parent.getLocationInWindow(rootPosition); body.getLocationInWindow(position)
                    place(host, position[0] - rootPosition[0] + dp(10), position[1] - rootPosition[1],
                        (body.width - dp(20)).coerceAtLeast(1), minOf(body.height, maxHeight) + dp(8))
                    keep.add(body)
                    channelMotion.bind(category, host)
                }
            }
            surfaces.forEach { (view, host) -> host.visibility = if (view in keep) View.VISIBLE else View.GONE }
            root.getLocationInWindow(rootPosition)
        }
        fun restoreNative() {
            controls.resetMotion()
            XhsTopNotifications.restore(root)
            channelMotion.restore()
            contentMounts.forEach { (body, mount) ->
                mount.wrapper.removeView(mount.recycler)
                body.removeView(mount.wrapper)
                body.addView(mount.recycler, mount.index.coerceAtMost(body.childCount), mount.params)
                body.setPadding(mount.padding.left, mount.padding.top, mount.padding.right, mount.padding.bottom)
                mount.recycler.setPadding(mount.recycler.paddingLeft, mount.recycler.paddingTop, mount.recycler.paddingRight, mount.listBottom)
            }
            contentMounts.clear()
            viewport.restore()
            marketBackdrop.restore()
            refreshIndicator.restore()
            panelLimits.forEach { (view, limit) -> runCatching {
                XhsNativeBindings.field("maxHeight")?.takeIf { it.declaringClass.isInstance(view) }?.setInt(view, limit)
                view.requestLayout()
            } }; panelLimits.clear()
            originals.forEach { (v, saved) ->
                v.alpha = saved.alpha; v.importantForAccessibility = saved.accessibility
            }
            originals.clear()
            decorativeVisibility.forEach { (view, visibility) ->
                if (view.visibility == View.INVISIBLE) view.visibility = visibility
            }
            decorativeVisibility.clear()
            backgrounds.forEach { (v, bg) -> v.background = bg }; backgrounds.clear()
            heights.forEach { (v, h) -> if (v.layoutParams.height != h) v.layoutParams = v.layoutParams.apply { height = h } }; heights.clear()
            offsets.forEach { (v, y) -> v.translationY = y }; offsets.clear()
            clipping.forEach { (v, state) -> v.clipChildren = state.first; v.clipToPadding = state.second }; clipping.clear()
            surfaces.values.forEach { it.visibility = View.GONE }
            excluded.clear()
            backdrop.reset()
        }
        fun mountChannelContent(body: ViewGroup, recycler: View) {
            if (contentMounts.containsKey(body) || recycler.parent !== body) return
            val params = recycler.layoutParams
            val index = body.indexOfChild(recycler)
            val wrapper = XhsChannelContent(activity)
            contentMounts[body] = ContentMount(recycler, wrapper, params, index,
                Rect(body.paddingLeft, body.paddingTop, body.paddingRight, body.paddingBottom), recycler.paddingBottom)
            body.removeView(recycler)
            wrapper.addView(recycler, FrameLayout.LayoutParams(params.width, params.height))
            body.addView(wrapper, index, params)
            body.setPadding(body.paddingLeft, dp(24), body.paddingRight, dp(24))
            recycler.setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, recycler.paddingBottom + dp(28))
        }
        fun unclip(group: ViewGroup) {
            clipping.getOrPut(group) { group.clipChildren to group.clipToPadding }
            group.clipChildren = false; group.clipToPadding = false
        }
        fun stop() {
            if (stopped) return
            stopped = true
            foregroundEpoch.stop()
            overlay.foregroundChanged = null
            root.removeCallbacks(foregroundUpdate)
            settledRecovery?.let(root::removeCallbacks); settledRecovery = null
            XhsTopTheme.unwatch(root)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            restoreNative()
            backdrop.release()
            root.post {
                surfaces.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }; surfaces.clear()
                (overlay.parent as? ViewGroup)?.removeView(overlay)
            }
        }
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "LiquidTab-XhsTopConfig").apply { isDaemon = true } }
    private class ChannelAction(context: Context) : View(context) {
        private val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val chevron = android.graphics.Path()
        private var measuredTextSize = 0f
        private var measuredTitle = Float.NaN
        var caption = "频道"
            set(value) {
                if (field == value) return
                field = value
                measuredTitle = Float.NaN
                updateDescription()
                invalidate()
            }
        var ink = Color.BLACK
            set(value) { if (field != value) { field = value; invalidate() } }
        var expanded = false
            set(value) {
                if (field == value) return
                field = value
                updateDescription()
                invalidate()
            }
        init { isClickable = true; isFocusable = true }
        private fun updateDescription() {
            contentDescription = "$caption，${if (expanded) "收起" else "展开"}频道选择"
        }
        fun preferredWidth(limit: Int): Int {
            val size = 13f * resources.displayMetrics.scaledDensity
            if (measuredTextSize != size || measuredTitle.isNaN()) {
                measuredTextSize = size
                paint.textSize = size
                measuredTitle = paint.measureText(caption)
            }
            val d = resources.displayMetrics.density
            return kotlin.math.ceil(measuredTitle + 41f * d).toInt()
                .coerceAtLeast((66f * d).roundToInt()).coerceAtMost(limit)
        }
        override fun onDraw(canvas: Canvas) {
            val save = canvas.save()
            canvas.clipRect(0, 0, width, height)
            val d = resources.displayMetrics.density
            paint.color = ink; paint.style = Paint.Style.FILL
            paint.textSize = 13f * resources.displayMetrics.scaledDensity
            val text = android.text.TextUtils.ellipsize(caption, paint,
                (width - 41f * d).coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
            val textWidth = paint.measureText(text)
            val glyphWidth = 11f * d
            val gap = 6f * d
            val left = (width - textWidth - gap - glyphWidth) / 2f
            val centerY = height / 2f
            val metrics = paint.fontMetrics
            canvas.drawText(text, left, centerY - (metrics.ascent + metrics.descent) / 2f, paint)
            val x = left + textWidth + gap
            val halfHeight = 3.5f * d
            val edgeY = centerY + if (expanded) halfHeight else -halfHeight
            val tipY = centerY + if (expanded) -halfHeight else halfHeight
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f * d
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            chevron.rewind(); chevron.moveTo(x, edgeY)
            chevron.lineTo(x + glyphWidth / 2f, tipY); chevron.lineTo(x + glyphWidth, edgeY)
            canvas.drawPath(chevron, paint)
            canvas.restoreToCount(save)
        }
    }
    private class Action(context: Context, val kind: Int, action: () -> Unit) : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var ink = Color.BLACK
            set(value) { if(field != value) { field = value; invalidate() } }
        init { isClickable = true; isFocusable = true; contentDescription = if(kind == 0) "侧边栏" else "搜索"; setOnClickListener { action() } }
        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density; val x = width / 2f; val y = height / 2f
            paint.color = ink
            if (kind == 0) { paint.style = Paint.Style.FILL; for (i in -1..1) canvas.drawCircle(x + i * 6 * d, y, 1.8f * d, paint) }
            else {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.8f * d; paint.strokeCap = Paint.Cap.ROUND
                canvas.drawCircle(x - 1.5f*d, y - 1.5f*d, 6.5f*d, paint)
                canvas.drawLine(x + 3.5f*d, y + 3.5f*d, x + 8.5f*d, y + 8.5f*d, paint)
            }
        }
    }
}



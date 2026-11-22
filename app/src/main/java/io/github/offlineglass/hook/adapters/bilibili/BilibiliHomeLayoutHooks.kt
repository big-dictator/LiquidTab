package io.github.offlineglass.hook.adapters.bilibili

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import io.github.offlineglass.hook.topbar.TopBarChannelHost
import io.github.offlineglass.hook.topbar.TopBarTabMeasure
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.glassHostBottomMarginPx
import io.github.offlineglass.hook.glassHostHeightPx
import io.github.offlineglass.hook.glassHostWidthPx
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.hook.adapters.home_glass.HomePanelHalo as HaloLayer
import java.util.WeakHashMap
import java.lang.reflect.Proxy
import kotlin.math.roundToInt

internal const val BILIBILI_HOME_TOP_CLEARANCE_DP = 6f

/** Feature-bound domestic homepage: legacy Views and native Compose share one glass renderer. */
internal object BilibiliHomeLayoutHooks {
    private const val MAIN = "tv.danmaku.bili.MainActivityV2"
    private val sessions = WeakHashMap<Activity, Session>()
    private val pins = listOf("直播", "推荐", "影视")
    private val overscanFeeds = WeakHashMap<Any, ViewGroup>()
    private val confirmedOverscan = WeakHashMap<Any, Boolean>()
    private val behaviorSetters = HashSet<java.lang.reflect.Method>()
    private val writingBehavior = ThreadLocal<Boolean>()

    /** Observe native writes even when native and module both write null. */
    private fun observeBehaviorSetter(setter: java.lang.reflect.Method) {
        if (setter in behaviorSetters) return
        XposedBridge.hookMethod(setter, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (writingBehavior.get() == true) return
                sessions.values.forEach { it.nativeBehaviorChanged(param.thisObject, param.args[0]) }
            }
        })
        behaviorSetters += setter
    }

    private fun writeBehavior(setter: java.lang.reflect.Method, params: Any, value: Any?) {
        val previous = writingBehavior.get()
        writingBehavior.set(true)
        try { setter.invoke(params, value) } finally {
            if (previous == null) writingBehavior.remove() else writingBehavior.set(previous)
        }
    }

    fun install(loader: ClassLoader) {
        // RecyclerView normally recycles against its padded viewport. Retain
        // only this home's rows needed by the top blur's offscreen kernel.
        runCatching { XposedBridge.hookAllMethods(loader.loadClass("androidx.recyclerview.widget.LinearLayoutManager"), "calculateExtraLayoutSpace", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val feed = overscanFeeds[param.thisObject]?.takeIf { it.isShown } ?: return
                val space = param.args.lastOrNull() as? IntArray ?: return
                if (space.size < 2) return
                val buffer = (96f * feed.resources.displayMetrics.density).roundToInt()
                space[0] = maxOf(space[0], feed.paddingTop + buffer)
                confirmedOverscan[param.thisObject] = true
            }
        }) }.onFailure { XposedBridge.log("LiquidTab Bilibili optional home overscan: $it") }
        for (name in listOf("tv.danmaku.bili.ui.main2.HomeFragmentV2", "tv.danmaku.bili.home.page.BaseHomeFrameFragment")) runCatching {
        XposedBridge.hookAllMethods(loader.loadClass(name), "onViewCreated", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val page = param.args.firstOrNull() as? View ?: return
                page.post {
                    var context: Context = page.context
                    while (context is android.content.ContextWrapper && context !is Activity) {
                        val next = context.baseContext
                        if (next === context) return@post
                        context = next
                    }
                    val activity = context as? Activity ?: return@post
                    attach(activity)
                }
            }
        }) }.onFailure { XposedBridge.log("LiquidTab Bilibili optional home fragment $name: $it") }
        for (event in listOf("onPostResume", "onStop", "onDestroy")) {
            XposedBridge.hookAllMethods(Activity::class.java, event, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name != MAIN) return
                    when (event) {
                        "onPostResume" -> {
                            attach(activity)
                            // Native fragment/Compose inflation can finish after Activity resume.
                            activity.window.decorView.post { if (!activity.isFinishing && !activity.isDestroyed) attach(activity) }
                            activity.window.decorView.postDelayed({ if (!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) attach(activity) }, 300L)
                        }
                        "onStop" -> sessions[activity]?.covered()
                        else -> sessions.remove(activity)?.stop()
                    }
                }
            })
        }
    }

    private fun attach(activity: Activity) {
        if (activity.javaClass.name != MAIN || activity.isDestroyed) return
        val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        val id = content.resources.getIdentifier("primary_multi_page_layout", "id", activity.packageName)
        val page = content.findViewById<ViewGroup>(id) ?: return
        val existing = sessions[activity]
        if (existing?.page === page && !existing.isStopped) { existing.start(); return }
        existing?.stop()
        Session(activity, content, page).also { sessions[activity] = it; it.start() }
    }

    fun openSearch(root: View): Boolean {
        val session = sessions.values.firstOrNull { it.content.rootView === root && !it.isStopped }
        if (io.github.offlineglass.BuildConfig.DEBUG)
            android.util.Log.i("LiquidTabBiliSearch", "session matched=${session != null}")
        return session?.openSearch() == true
    }

    private class Overlay(context: Context) : FrameLayout(context), AdapterOwnedOverlay

    /** Adapter-local soft outer halo; shape and transform come from the live glass host. */
    private data class Tab(val cell: View, val title: TextView)
    private data class Input(val clickable: Boolean, val longClickable: Boolean, val focusable: Boolean)
    private data class CoordinatorBehavior(
        val params: ViewGroup.LayoutParams,
        var original: Any?,
        val getter: java.lang.reflect.Method,
        val setter: java.lang.reflect.Method,
    )
    private data class MineInsets(
        val header: View, val frame: View, val top: Int, val height: Int, val params: ViewGroup.LayoutParams,
        var behavior: Any?, val getter: java.lang.reflect.Method?, val setter: java.lang.reflect.Method?,
        var nativeBottomPadding: Int,
        var appliedBottomPadding: Int? = null,
    )
    private data class Padding(
        val originalTop: Int,
        val originalBottom: Int,
        val originalClipToPadding: Boolean,
        val originalClipChildren: Boolean,
        var appliedWindowTop: Int,
        var appliedTop: Int,
        var appliedBottom: Int,
        var appliedHeight: Int,
        var appliedTopTarget: Int,
        var appliedBottomTarget: Int,
    )
    private data class RefreshBinding(
        val view: View,
        val feed: View,
        val setter: java.lang.reflect.Method,
        val originalCallback: Any?,
        val callback: Any,
        val offsetSetter: java.lang.reflect.Method?,
        val originalStart: Int?,
        val originalEnd: Int?,
        val originalScale: Boolean?,
        val originalCustomStart: Boolean?,
        val offsetRoles: Pair<java.lang.reflect.Field, java.lang.reflect.Field>?,
        var appliedStart: Int = Int.MIN_VALUE,
        var appliedEnd: Int = Int.MIN_VALUE,
    )

    private class Session(val activity: Activity, val content: FrameLayout, val page: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
        private val overlay = Overlay(activity).apply { clipChildren = false; clipToPadding = false }
        private val topVeil = BilibiliHomeTopVeil(activity, page)
        private val bottomGradient = BilibiliHomeColorGradient(top = false)
        private val bottomVeil = View(activity).apply {
            background = bottomGradient
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val panels = ArrayList<GlassHostLayout>()
        private val haloLayer = HaloLayer(activity)
        private val inputs = WeakHashMap<View, Input>()
        private val paddings = WeakHashMap<View, Padding>()
        private val clipToPaddingStates = WeakHashMap<ViewGroup, Boolean>()
        private val layoutManagerGetters = HashMap<Class<*>, java.lang.reflect.Method?>()
        private var refreshBinding: RefreshBinding? = null
        private var lastRefreshDiagnostic = 0L
        private var lastClipDiagnostic = 0L
        private var clipDiagnosticKey = ""
        private var refreshDiagnosticKey = ""
        private val refreshType by lazy {
            runCatching { activity.classLoader.loadClass("tv.danmaku.bili.widget.swiperefresh.SwipeRefreshLayout") }.getOrNull()
        }
        private var activeFeed: ViewGroup? = null
        private var deferFrameForInsets = false
        private val cells = ArrayList<Tab>()
        private var shown = emptyList<Tab>()
        private data class ChannelVisual(val x: Float, val alpha: Float)
        private var channelFrom = emptyMap<String, ChannelVisual>()
        private var channelTo = emptyMap<String, ChannelVisual>()
        private val channelMeasure = TopBarTabMeasure()
        private var channelLabels = emptyList<String>()
        private var channelProgress = 1f
        private var channelWidthFrom: Int? = null
        private var channelAnimator: android.animation.ValueAnimator? = null
        private var channelGestureActive = false
        private var channelsDirty = true
        private var lastNativeChannel: String? = null
        private var liveChannelPage: java.lang.ref.WeakReference<View>? = null
        private val liveChannelBounds = Rect()

        private fun liveChannelVisible(): Boolean {
            val view = liveChannelPage?.get()?.takeIf { it.isAttachedToWindow } ?: return false
            return view.isShown && view.getGlobalVisibleRect(liveChannelBounds) &&
                liveChannelBounds.width() >= page.width * .8f && liveChannelBounds.height() > 0
        }
        private var projectedChannel: String? = null
        private var sliderFrom: Pair<Float, Float>? = null
        private var presentedSlider: Pair<Float, Float>? = null
        private var toolbar: ViewGroup? = null
        private var tabsLayout: View? = null
        private var tabs: ViewGroup? = null
        private var search: View? = null
        private var avatar: View? = null
        private var message: View? = null
        private var compose: BilibiliHomeComposeBinding? = null
        private val actionType = runCatching {
            activity.classLoader.loadClass("com.bilibili.lib.homepage.widget.MenuActionView")
        }.getOrNull()
        private val actionIconGetter = runCatching { actionType?.getMethod("getIconDrawable") }.getOrNull()
        private val actionIconView = runCatching { actionType?.getDeclaredField("mIcon")?.apply { isAccessible = true } }.getOrNull()
        private val actionDefaultIcon = runCatching { actionType?.getDeclaredField("mDefault")?.apply { isAccessible = true } }.getOrNull()
        private val actionTintGetter = runCatching { actionType?.getMethod("getIconTintColor") }.getOrNull()
        private val actionBadgeGetter = runCatching { actionType?.getDeclaredMethod("findBadgeView")?.apply { isAccessible = true } }.getOrNull()
        private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val messagePath = Path()
        private val messageDrawableBounds = Rect()
        private var messageIconBase: Drawable? = null
        private var messageStableIcon: Drawable? = null
        private var messageStableTint = Int.MIN_VALUE
        private var game: View? = null
        private var category: View? = null
        private var originalToolbarAlpha = 1f
        private var originalAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        private var originalTabsAlpha = 1f
        private var originalTabsHeight = 0
        private var originalTabsVisibility = View.VISIBLE
        private val originalLayerAlpha = WeakHashMap<View, Float>()
        private val coordinatorBehaviors = WeakHashMap<View, CoordinatorBehavior>()
        private var shiftedContent: View? = null
        private var originalPageHeight: Int? = null
        private var mineInsets: MineInsets? = null
        private val statusColor = activity.window.statusBarColor
        private val statusContrast = activity.window.isStatusBarContrastEnforced
        private var suppressed = false
        private var foreground = true
        private var listening = false
        private var stopped = false
        val isStopped: Boolean get() = stopped
        private var retryAfter = 0L
        private var failures = 0
        var active = false
            private set
        private var lastScan = 0L
        private var lastInputs = 0L
        private var lastConfig = 0L
        private var config = HookConfigReader.read(activity, activity.packageName)
        private var menu: View? = null
        private var menuCard: View? = null
        private var menuMaterial: GlassHostLayout? = null
        private var menuClosing = false
        private var menuAnimating = false
        private val contentPosition = IntArray(2)
        private val position = IntArray(2)
        private val visible = Rect()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val searchPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
        private var searchTextKey = ""
        private var searchTextWidth = -1f
        private var searchTextSize = -1f
        private var searchTextTypeface: Typeface? = null
        private var searchDisplayText = ""
        private var bottomHost: GlassHostLayout? = null
        private val searchFeedback = BilibiliSearchFeedback(wholeView = true)
        private val resourceIds = HashMap<String, Int>()

        private fun find(name: String, root: View = content): View? = root.findViewById(
            resourceIds.getOrPut(name) { root.resources.getIdentifier(name, "id", activity.packageName) })

        fun start() {
            if (stopped) return
            foreground = true
            retryAfter = 0L; failures = 0
            if (!listening) {
                listening = true
                content.viewTreeObserver.addOnPreDrawListener(this)
                overlay.addView(topVeil, FrameLayout.LayoutParams(-1, 1))
                overlay.addView(bottomVeil, FrameLayout.LayoutParams(-1, 1))
                content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            }
            content.invalidate()
        }

        override fun onPreDraw(): Boolean {
            if (stopped) return true
            if (!page.isAttachedToWindow) {
                stop()
                content.post { attach(activity) }
                return true
            }
            if (SystemClock.uptimeMillis() < retryAfter) return true
            deferFrameForInsets = false
            runCatching { update() }.onFailure {
                active = false; restore(); overlay.visibility = View.GONE; closeMenu()
                XposedBridge.log("LiquidTab Bilibili home layout: $it")
                // A transient source/recomposition exception must not permanently poison this Activity.
                retryAfter = SystemClock.uptimeMillis() + 1000L
                lastScan = 0L
                if (++failures <= 3) content.postDelayed({ if (!stopped && foreground) content.invalidate() }, 1000L)
            }
            // setPadding requests another list layout. Do not expose children
            // still laid out with the previous header inset on the first frame.
            if (deferFrameForInsets) content.postInvalidateOnAnimation()
            return !deferFrameForInsets
        }

        private fun bind(): Boolean {
            val composeHeader = find("top_bar_collapsible") as? ViewGroup
            val composeStrip = find("top_bar_fixed") as? ViewGroup
            if (composeHeader != null && composeStrip != null) return bindCompose(composeHeader, composeStrip)
            val row = find("tabs", page) as? ViewGroup ?: return false
            val appbar = find("app_bar") ?: return false
            val header = find("nav_top_bar", appbar) as? ViewGroup ?: return false
            val searchView = find("expand_search", header) ?: return false
            val strip = find("tabs_layout", page) ?: return false
            if (toolbar !== header || tabs !== row) {
                restore()
                compose = null
                toolbar = header; tabs = row; tabsLayout = strip; search = searchView
                originalToolbarAlpha = header.alpha
                originalAccessibility = header.importantForAccessibility
                originalTabsHeight = strip.layoutParams.height
                originalTabsAlpha = strip.alpha
                originalTabsVisibility = strip.visibility
                avatar = find("avatar_layout", header)
                category = find("category_click_area", strip)
                lastScan = 0L
            }
            val now = SystemClock.uptimeMillis()
            if (cells.isEmpty() || now - lastScan > 300L) {
                lastScan = now
                val actions = ArrayList<View>()
                fun walkActions(view: View) {
                    if (actionType?.isInstance(view) == true) { actions += view; return }
                    if (view is ViewGroup) for (i in 0 until view.childCount) walkActions(view.getChildAt(i))
                }
                walkActions(header)
                fun title(view: View): String = runCatching {
                    val icon = actionIconView?.get(view) as? View
                    "${view.contentDescription?.toString().orEmpty()} ${icon?.tag ?: ""}"
                }.getOrDefault("")
                // Native action children can be replaced without replacing
                // Toolbar. Prefer the native semantic title over child order.
                message = actions.firstOrNull { title(it).contains("消息") || title(it).contains("message", true) }
                    ?: actions.getOrNull(1)
                game = actions.firstOrNull { it !== message }
                avatar = find("avatar_layout", header)
                val list = row.getChildAt(0) as? ViewGroup ?: return false
                cells.clear()
                channelsDirty = true
                for (i in 0 until list.childCount) {
                    val cell = list.getChildAt(i)
                    val title = find("tab_title", cell) as? TextView ?: continue
                    // SecondaryPagerSlidingTabStrip.c assigns this tag and its
                    // own click listener. Do not invent a ViewPager position.
                    if (cell.tag is Int && title.text.isNotBlank()) cells += Tab(cell, title)
                }
            }
            updateChannels()
            return pins.all { pin -> cells.any { it.title.text.toString() == pin } } && avatar != null
        }

        private fun bindCompose(header: ViewGroup, strip: ViewGroup): Boolean {
            var binding = compose
            if (binding == null || binding.header !== header || binding.strip !== strip) {
                restore()
                binding = BilibiliHomeComposeBinding(header, strip)
                compose = binding
                toolbar = header; tabs = strip; tabsLayout = strip
                originalToolbarAlpha = header.alpha
                originalAccessibility = header.importantForAccessibility
                originalTabsHeight = strip.layoutParams.height
                originalTabsAlpha = strip.alpha
                originalTabsVisibility = strip.visibility
                cells.clear(); shown = emptyList(); channelsDirty = true; lastScan = 0L
            }
            val now = SystemClock.uptimeMillis()
            if (cells.isEmpty() || binding.needsRefresh(now)) {
                lastScan = now
                liveChannelPage = find("liveTabContainer", page)?.let { java.lang.ref.WeakReference(it) }
                if (!binding.refresh(activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)) {
                    // Live replaces the native top semantics. Retain only this
                    // already-validated binding; never invent channel indices.
                    if (liveChannelVisible() && pins.all { pin -> cells.any { it.title.text.toString() == pin } }) {
                        updateChannels()
                        return avatar != null && search != null
                    }
                    runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-home-binding.txt").writeText("compose: ${binding.diagnostic}\n") }
                    return false
                }
                cells.clear(); cells += binding.tabs.map { Tab(it.cell, it.title) }
                channelsDirty = true
                avatar = binding.avatar; message = binding.message; game = binding.game
                search = binding.search; category = binding.category
            }
            updateChannels()
            return pins.all { pin -> cells.any { it.title.text.toString() == pin } } && avatar != null && search != null
        }

        private fun channelVisual(label: String): ChannelVisual {
            val end = channelTo[label] ?: channelFrom[label]?.copy(alpha = 0f) ?: ChannelVisual(.5f, 0f)
            if (channelProgress >= 1f) return end
            val start = channelFrom[label] ?: end.copy(alpha = 0f)
            return ChannelVisual(start.x + (end.x - start.x) * channelProgress,
                start.alpha + (end.alpha - start.alpha) * channelProgress)
        }

        private fun updateChannels() {
            if (channelGestureActive) return
            if (!pins.all { pin -> cells.any { it.title.text.toString() == pin } }) return
            val native = if (liveChannelVisible()) "直播" else
                cells.firstOrNull { it.cell.isSelected || it.title.isSelected }?.title?.text?.toString()
            if (native != null) lastNativeChannel = native
            val selected = native ?: lastNativeChannel?.takeIf { label -> cells.any { it.title.text.toString() == label } }
            if (!channelsDirty && projectedChannel == selected) return
            channelsDirty = false; projectedChannel = selected
            val labels = BilibiliHomeChannels.visible(cells.map { it.title.text.toString() }, selected)
            val old = shown.map { it.title.text.toString() }
            // Rebind refreshed native cells even when semantic identities did not change.
            val next = labels.mapNotNull { label -> cells.firstOrNull { it.title.text.toString() == label } }
            if (old == labels && channelTo.isNotEmpty()) {
                shown = next
                // Native selection can change without changing membership or
                // geometry. Invalidate the retained panel so its host resolves
                // selection and records both indicator and labels together.
                panels.getOrNull(1)?.invalidate()
                return
            }
            val previous = (channelFrom.keys + channelTo.keys).associateWith(::channelVisual)
            val previousSlider = presentedSlider
            channelAnimator?.cancel()
            shown = next
            channelTo = BilibiliHomeChannels.positions(labels).mapValues { ChannelVisual(it.value, 1f) }
            channelFrom = (previous.keys + channelTo.keys).associateWith { label ->
                previous[label] ?: requireNotNull(channelTo[label]).copy(alpha = 0f)
            }
            channelLabels = (old + labels).distinct()
            val panel = panels.getOrNull(1)
            panel?.updateAdapterSlotCount(labels.size + 1, selectedIndex())
            panel?.contentDescription = "更多频道、${labels.joinToString("、")}"
            if (previous.isEmpty() || panel == null || !active) {
                channelProgress = 1f; channelFrom = channelTo; channelLabels = labels; sliderFrom = null
                return
            }
            sliderFrom = previousSlider
            channelWidthFrom = panel.width.takeIf { it > 0 }
            channelProgress = 0f
            channelAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260L
                interpolator = android.view.animation.PathInterpolator(.2f, 0f, .2f, 1f)
                addUpdateListener { channelProgress = it.animatedValue as Float; panel.invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (channelAnimator !== animation) return
                        channelProgress = 1f; channelFrom = channelTo; channelLabels = labels
                        sliderFrom = null; channelAnimator = null; panel.invalidate()
                    }
                })
                start()
            }
        }

        private fun channelIndicator(center: Float, width: Float): Pair<Float, Float> {
            val from = sliderFrom
            val current = if (from != null && channelProgress < 1f)
                (from.first + (center - from.first) * channelProgress) to
                    (from.second + (width - from.second) * channelProgress)
            else center to width
            presentedSlider = current
            return current
        }

        private fun update() {
            val now = SystemClock.uptimeMillis()
            if (now - lastConfig > 500L) { config = HookConfigReader.read(activity, activity.packageName); lastConfig = now }
            active = foreground && config.enabled && config.bilibiliTopBar &&
                activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                page.isAttachedToWindow && page.isShown && page.getGlobalVisibleRect(visible) &&
                visible.width() >= content.width * .8f
            if (!active || !bind()) {
                active = false; overlay.visibility = View.GONE; closeMenu(); restore(); updateMineInsets(); return
            }
            restoreMineInsets()
            if (panels.isEmpty()) {
                for (role in 0..3) {
                    val state = PanelState(this, role)
                        val panel = TopBarChannelHost.createPanel(activity, config, if (role == 1) shown.size + 1 else 1,
                        nonSelectableIndices = if (role == 1) setOf(0) else emptySet(),
                        nativeSelectionReliable = true, navigationSource = page,
                        sliderEnabled = role == 1, adapterOverride = PanelAdapter(state), isChannel = role == 1).apply {
                        elevation = 0f; clipChildren = false; clipToPadding = false
                        contentDescription = when (role) {
                            0 -> "个人中心"; 1 -> "更多频道、推荐、直播、影视"; 2 -> "消息"; else -> "搜索"
                        }
                    }
                    panels += panel
                    overlay.addView(panel, FrameLayout.LayoutParams(1, 1))
                }
                overlay.addView(haloLayer, 2, FrameLayout.LayoutParams(-1, -1))
                runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-home-layout.txt")
                    .writeText("binding=${if (compose != null) "compose" else "view"} native channels=${cells.map { it.title.text }} search=${search?.javaClass?.name}\n") }
            }
            suppress(now)
            overlay.visibility = View.VISIBLE
            content.getLocationInWindow(contentPosition)
            val d = content.resources.displayMetrics.density
            val h = (44f * d).roundToInt()
            val circle = (40f * d).roundToInt()
            val gap = (12f * d).roundToInt()
            if (bottomHost?.isAttachedToWindow != true) {
                bottomHost = (0 until content.childCount).map { content.getChildAt(it) }
                    .filterIsInstance<GlassHostLayout>().firstOrNull { it !in panels }
            }
            val bar = bottomHost?.takeIf { it.width > 0 && it.height > 0 }
            val searchWidth = glassHostWidthPx(content, 5, config, d).coerceAtMost(content.width)
            val searchLeft = (content.width - searchWidth) / 2
            val barTop: Int
            if (bar != null) {
                bar.getLocationInWindow(position)
                barTop = position[1] - contentPosition[1]
            } else {
                barTop = content.height - glassHostBottomMarginPx(content, config) - glassHostHeightPx(d, config, activity.packageName)
            }
            val maxChannelWidth = content.width - 2 * (circle + gap)
            if (maxChannelWidth <= 0) { active = false; restore(); overlay.visibility = View.GONE; return }
            val sizing = channelMeasure.update(listOf<String?>(null) + shown.map { it.title.text.toString() },
                d, 14f * activity.resources.displayMetrics.scaledDensity)
            val targetWidth = (sizing?.desiredWidth?.roundToInt() ?: maxChannelWidth).coerceIn(1, maxChannelWidth)
            val channelWidth = channelWidthFrom?.takeIf { channelProgress < 1f }?.let { start ->
                (start + (targetWidth - start) * channelProgress).roundToInt().coerceIn(1, maxChannelWidth)
            } ?: targetWidth
            val channelLeft = (content.width - channelWidth) / 2
            // OEM freeform captions are a separate input-owned system area;
            // statusBars may be zero even while that caption still covers us.
            // All panels, feed padding and spinner share this window-space boundary.
            val systemTop = content.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or
                WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar())?.top ?: 0
            val top = (systemTop - contentPosition[1]).coerceAtLeast(0)
            val dark = dark()
            topVeil.setAppearance(config.bilibiliSolidTopGradient, dark)
            bottomGradient.setDark(dark)
            bottomVeil.visibility = if (config.bilibiliBottomColorGradient) View.VISIBLE else View.GONE
            val topFadeDp = if (config.bilibiliSolidTopGradient) 64f else 12f
            place(topVeil, 0, 0, content.width, top + h + (topFadeDp * d).roundToInt())
            place(panels[0], channelLeft - gap - circle, top + (h - circle) / 2, circle, circle)
            panels[1].updateTopBarTabs(sizing)
            place(panels[1], channelLeft, top, channelWidth, h)
            place(panels[2], channelLeft + channelWidth + gap, top + (h - circle) / 2, circle, circle)
            val searchTop = if (config.bilibiliDetachedSearch) barTop else barTop - gap - h
            val bottomVeilTop = (searchTop - h / 2 - gap).coerceAtLeast(0)
            place(bottomVeil, 0, bottomVeilTop, content.width, (content.height - bottomVeilTop).coerceAtLeast(1))
            place(panels[3], searchLeft, searchTop, searchWidth, h)
            if (config.bilibiliDetachedSearch) searchFeedback.reset()
            panels[3].visibility = if (config.bilibiliDetachedSearch) View.GONE else View.VISIBLE
            haloLayer.bind(panels, config.cornerSmoothing, dark, config.enabled && config.liquidGlassEnabled)
            val topBarBottom = contentPosition[1] + top + h
            val feedTop = topBarBottom + (BILIBILI_HOME_TOP_CLEARANCE_DP * d).roundToInt()
            safeInsets(feedTop, searchTop + contentPosition[1], d)
            topVeil.bindFeed(activeFeed)
            topVeil.updateFrame()
            updateRefreshContainer(topBarBottom + (24f * d).roundToInt(), d)
        }

        private fun suppress(now: Long) {
            fun disable(view: View) {
                inputs.getOrPut(view) { Input(view.isClickable, view.isLongClickable, view.isFocusable) }
                view.isClickable = false; view.isLongClickable = false; view.isFocusable = false
                if (view is ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i))
            }
            if (!suppressed || now - lastInputs > 500L) { toolbar?.let(::disable); lastInputs = now }
            toolbar?.alpha = 0f
            toolbar?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // The native toolbar and its separate background placeholder both
            // sit above the feed. Hiding only Toolbar leaves an opaque AppBar.
            val coordinator = (page.parent as? View)?.parent as? View ?: content
            listOfNotNull(find("app_bar", coordinator), find("main_top_layout", coordinator)).forEach {
                originalLayerAlpha.getOrPut(it) { it.alpha }
                if (it.alpha != 0f) it.alpha = 0f
                // Keep image sources visibility-aggregated: INVISIBLE releases
                // native avatar/action drawables. Hidden chrome has disabled
                // controls and detached scrolling behavior instead.
                detachHeaderBehavior(it)
            }
            tabsLayout?.let {
                it.alpha = 0f
                // This is a ConstraintLayout child: height=0 means MATCH_CONSTRAINT,
                // not collapse. GONE makes the pager's top constraint resolve to 0.
                if (compose == null && it.visibility != View.GONE) it.visibility = View.GONE
            }
            val frame = page.parent as? View
            if (frame != null && frame.layoutParams?.javaClass?.methods?.any { it.name == "getBehavior" && it.parameterCount == 0 } == true) {
                // Both the AppBar behavior and its scrolling-sibling behavior
                // must be detached together. Otherwise a native nested scroll
                // moves this frame while the pre-draw placement moves it back,
                // corrupting touch deltas and feeding false pull-to-refresh.
                detachHeaderBehavior(frame)
                shiftedContent = frame
                // Move actual bounds, not translation: native feed hit testing
                // must cover the newly exposed top region as well as drawing.
                if (frame.top != 0) {
                    frame.offsetTopAndBottom(-frame.top)
                }
                if (originalPageHeight == null) originalPageHeight = page.layoutParams.height
                if (content.height > 0 && page.layoutParams.height != content.height)
                    page.layoutParams = page.layoutParams.apply { height = content.height }
            }
            activity.window.statusBarColor = Color.TRANSPARENT
            activity.window.isStatusBarContrastEnforced = false
            suppressed = true
        }

        private fun detachHeaderBehavior(view: View) {
            val params = view.layoutParams ?: return
            val getter = params.javaClass.methods.firstOrNull {
                it.name == "getBehavior" && it.parameterTypes.isEmpty()
            } ?: return
            val original = getter.invoke(params) ?: return
            val setter = params.javaClass.methods.firstOrNull {
                it.name == "setBehavior" && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(getter.returnType))
            } ?: return
            observeBehaviorSetter(setter)
            if (coordinatorBehaviors[view]?.params !== params)
                coordinatorBehaviors[view] = CoordinatorBehavior(params, original, getter, setter)
            writeBehavior(setter, params, null)
            view.requestLayout()
        }

        fun nativeBehaviorChanged(params: Any, value: Any?) {
            coordinatorBehaviors.values.forEach { if (it.params === params) it.original = value }
            mineInsets?.takeIf { it.params === params }?.behavior = value
        }

        private fun updateMineInsets() {
            // Backgrounding is not an exit from Mine. Restoring the behavior here
            // makes its next onMeasureChild write a status-height bottom padding.
            if (!foreground) return
            val cached = mineInsets
            val mineTop = cached?.header?.takeIf { it.isAttachedToWindow } ?: find("mine_top_view")
            val frame = cached?.frame?.takeIf { it.isAttachedToWindow } ?: find("content")
            val status = content.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            if (!config.enabled || mineTop?.isShown != true ||
                frame == null || frame.parent !is ViewGroup || status <= 0 || mineTop.top < status - 2) {
                restoreMineInsets(); return
            }
            if (cached?.frame !== frame || cached.header !== mineTop || cached.params !== frame.layoutParams) {
                restoreMineInsets()
                val params = frame.layoutParams
                val getter = params.javaClass.methods.firstOrNull { it.name == "getBehavior" && it.parameterTypes.isEmpty() }
                val setter = params.javaClass.methods.singleOrNull { it.name == "setBehavior" &&
                    it.returnType == Void.TYPE && getter != null &&
                    it.parameterTypes.contentEquals(arrayOf(getter.returnType)) }
                // Verify the actual coordinator contract, not an exact class/version name.
                if (getter == null || setter == null) return
                observeBehaviorSetter(setter)
                val behavior = getter.invoke(params)
                mineInsets = MineInsets(mineTop, frame, frame.top, params.height, params, behavior, getter, setter, frame.paddingBottom)
            }
            val state = mineInsets ?: return
            val currentBehavior = state.getter?.invoke(state.params)
            if (currentBehavior != null) {
                state.behavior = currentBehavior
                state.setter?.let { writeBehavior(it, state.params, null) }
                state.appliedBottomPadding = null
            }
            // Mine's own head_layout already owns the status-bar inset.
            // Remove only the duplicate inset of the shared outer container.
            if (frame.top != 0) frame.offsetTopAndBottom(-frame.top)
            // Native PinnedBottomScrollingBehavior reserves its appbar compensation
            // in paddingBottom. Once we own the full-height frame it must not shorten
            // the fragment/list. Touch/list content padding remains app-owned.
            if (frame.paddingBottom != state.appliedBottomPadding) {
                state.nativeBottomPadding = frame.paddingBottom
                // Remove the verified status/appbar compensation only. Retain
                // any additional native/keyboard/navigation inset in newer layouts.
                val wanted = if (frame.paddingBottom >= status) frame.paddingBottom - status else frame.paddingBottom
                state.appliedBottomPadding = wanted
                if (frame.paddingBottom != wanted)
                    frame.setPadding(frame.paddingLeft, frame.paddingTop, frame.paddingRight, wanted)
            }
            if (content.height > 0 && frame.layoutParams.height != content.height)
                frame.layoutParams = frame.layoutParams.apply { height = content.height }
        }

        private fun restoreMineInsets() {
            val state = mineInsets ?: return
            mineInsets = null
            // A retained shared frame may already belong to another native page.
            // Its new layout must decide coordinates; never replay Mine's old top.
            state.frame.requestLayout()
            if (state.frame.layoutParams === state.params) {
                if (state.params.height != state.height) state.frame.layoutParams = state.params.apply { height = state.height }
                if (state.behavior != null && state.getter?.invoke(state.params) == null)
                    state.setter?.let { writeBehavior(it, state.params, state.behavior) }
            }
            if (state.frame.paddingBottom == state.appliedBottomPadding && state.nativeBottomPadding != state.appliedBottomPadding)
                state.frame.setPadding(state.frame.paddingLeft, state.frame.paddingTop, state.frame.paddingRight, state.nativeBottomPadding)
        }

        private fun safeInsets(topWindow: Int, searchWindowTop: Int, d: Float) {
            activeFeed = null
            fun walk(view: View) {
                if (!view.isShown) return
                val isFeedList = view is ViewGroup &&
                    (view.javaClass.name.endsWith("RecyclerView") || view is AbsListView)
                if (isFeedList && view is ViewGroup && view.height > 0 &&
                    view.getGlobalVisibleRect(visible) && visible.width() >= content.width * .8f) {
                    view.getLocationInWindow(position)
                    val padding = paddings.getOrPut(view) {
                        Padding(view.paddingTop, view.paddingBottom, view.clipToPadding, view.clipChildren, position[1],
                            view.paddingTop, view.paddingBottom, view.height, Int.MIN_VALUE, Int.MIN_VALUE)
                    }
                    // The startup origin is provisional until native header
                    // layout settles. Track layout coordinates, excluding view
                    // translations, so real layout changes repair the inset
                    // while animations cannot drive per-frame list relayout.
                    var layoutWindowTop = contentPosition[1]
                    var ancestor: View = view
                    while (ancestor !== content) {
                        val parent = ancestor.parent as? View ?: break
                        layoutWindowTop += ancestor.top - parent.scrollY
                        ancestor = parent
                    }
                    if (padding.appliedWindowTop != layoutWindowTop ||
                        padding.appliedHeight != view.height || padding.appliedTopTarget != topWindow ||
                        padding.appliedBottomTarget != searchWindowTop || view.paddingTop != padding.appliedTop ||
                        view.paddingBottom != padding.appliedBottom) {
                        val wantedTop = maxOf(padding.originalTop,
                            (topWindow - layoutWindowTop).coerceAtLeast(0))
                        val wantedBottom = maxOf(padding.originalBottom,
                            (layoutWindowTop + view.height - searchWindowTop + (8 * d).roundToInt()).coerceAtLeast(0))
                        if (view.paddingTop != wantedTop || view.paddingBottom != wantedBottom) {
                            view.setPadding(view.paddingLeft, wantedTop, view.paddingRight, wantedBottom)
                            deferFrameForInsets = true
                        }
                        padding.appliedWindowTop = layoutWindowTop
                        padding.appliedTop = wantedTop; padding.appliedBottom = wantedBottom
                        padding.appliedHeight = view.height
                        padding.appliedTopTarget = topWindow
                        padding.appliedBottomTarget = searchWindowTop
                    }
                    activeFeed = view
                    // Capture this list directly, without relaxing ancestor or
                    // ViewPager clipping. Native on-screen clipping stays in
                    // those parents while the blur sees its padded source.
                    if (!config.bilibiliSolidTopGradient) {
                        if (view.clipChildren) view.clipChildren = false
                        val getter = layoutManagerGetters.getOrPut(view.javaClass) {
                            runCatching { view.javaClass.getMethod("getLayoutManager") }.getOrNull()
                        }
                        val manager = getter?.invoke(view)
                        if (manager != null && overscanFeeds[manager] !== view) {
                            overscanFeeds.entries.removeAll { it.value === view }
                            overscanFeeds[manager] = view
                            view.requestLayout()
                            deferFrameForInsets = true
                        }
                    } else {
                        if (view.clipChildren != padding.originalClipChildren) view.clipChildren = padding.originalClipChildren
                        if (overscanFeeds.entries.removeAll { it.value === view }) {
                            confirmedOverscan.keys.retainAll(overscanFeeds.keys)
                            view.requestLayout()
                            deferFrameForInsets = true
                        }
                    }
                    if (view.clipToPadding) view.clipToPadding = false
                    var parent = view.parent
                    while (parent is ViewGroup) {
                        if (!clipToPaddingStates.containsKey(parent)) clipToPaddingStates[parent] = parent.clipToPadding
                        if (parent.clipToPadding) parent.clipToPadding = false
                        if (parent === content) break
                        parent = parent.parent
                    }
                    reportClipDiagnostic(view, topWindow, searchWindowTop)
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(page)
        }

        /**
         * Bili's native SwipeRefreshLayout targets a wrapper rather than the
         * nested feed. Its default canChildScrollUp() therefore reports false
         * while the feed is already scrolled, allowing unconsumed nested scroll
         * deltas to start refresh. Route that native query to the active feed.
         */
        private fun updateRefreshContainer(topSafeWindow: Int, d: Float) {
            val feed = activeFeed ?: return
            var parent: ViewParent? = feed.parent
            var refresh: View? = null
            val ancestry = ArrayList<String>()
            while (parent is View) {
                ancestry += parent.javaClass.name
                if (refreshType?.isInstance(parent) == true) { refresh = parent; break }
                if (parent === page) break
                parent = parent.parent
            }
            val swipe = refresh
            if (swipe == null) {
                val now = SystemClock.uptimeMillis()
                if (now - lastRefreshDiagnostic >= 800L) {
                    lastRefreshDiagnostic = now
                    writeRefreshDiagnostic("refresh=not-found class=${refreshType?.name} feed=${feed.javaClass.name} ancestry=${ancestry.joinToString(" > ")}")
                }
                return
            }
            var binding = refreshBinding
            val callbackNeedsUpdate = binding?.view !== swipe || binding.feed !== feed ||
                (binding.offsetRoles == null && BilibiliRefreshBinding.fields(swipe) != null)
            if (callbackNeedsUpdate) {
                val oldBinding = binding
                if (oldBinding != null) {
                    // Restore the app-owned callback before replacing either
                    // the container or its active feed reference.
                    runCatching { oldBinding.setter.invoke(oldBinding.view, oldBinding.originalCallback) }
                    if (oldBinding.view !== swipe) restoreRefreshOffset(oldBinding)
                }
                val setter = swipe.javaClass.methods.firstOrNull {
                    it.name == "setOnChildScrollUpCallback" && it.parameterTypes.size == 1
                } ?: return
                val callbackType = setter.parameterTypes[0]
                val callbackMethod = callbackType.methods.singleOrNull {
                    it.parameterTypes.size == 1 && View::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                        (it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.java)
                } ?: return
                // Preserve native ownership using the setter's actual interface.
                // Never choose a callback field by an old decompiler-generated name.
                val fields = generateSequence(swipe.javaClass as Class<*>?) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }.toList()
                val callbackField = fields.singleOrNull { !java.lang.reflect.Modifier.isStatic(it.modifiers) && callbackType.isAssignableFrom(it.type) }
                    ?: return
                callbackField.isAccessible = true
                val original = oldBinding?.takeIf { it.view === swipe }?.originalCallback
                    ?: callbackField.get(swipe)
                val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
                    when (method.name) {
                        callbackMethod.name -> {
                            val nativeCanScroll = original?.let {
                                runCatching { method.invoke(it, *(args ?: emptyArray())) as? Boolean ?: false }.getOrDefault(false)
                            } ?: false
                            // Native callback semantics are preserved, while
                            // the real nested list prevents refresh until its
                            // scroll position reaches the top.
                            nativeCanScroll || (feed.isShown && feed.canScrollVertically(-1))
                        }
                        "toString" -> "LiquidTabBiliHomeScrollUpCallback(${feed.javaClass.simpleName})"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else -> null
                    }
                }
                val offsetSetter = swipe.javaClass.methods.firstOrNull {
                    it.name == "setProgressViewOffset" && it.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                }
                fun offset(name: String) = runCatching { swipe.javaClass.getMethod(name).invoke(swipe) as Int }.getOrNull()
                val roles = BilibiliRefreshBinding.fields(swipe)
                binding = RefreshBinding(
                    view = swipe,
                    feed = feed,
                    setter = setter,
                    originalCallback = original,
                    callback = callback,
                    offsetSetter = offsetSetter.takeIf { roles != null },
                    originalStart = oldBinding?.takeIf { it.view === swipe }?.originalStart
                        ?: offset("getProgressViewStartOffset"),
                    originalEnd = oldBinding?.takeIf { it.view === swipe }?.originalEnd
                        ?: offset("getProgressViewEndOffset"),
                    originalScale = oldBinding?.takeIf { it.view === swipe }?.originalScale
                        ?: roles?.first?.getBoolean(swipe),
                    originalCustomStart = oldBinding?.takeIf { it.view === swipe }?.originalCustomStart
                        ?: roles?.second?.getBoolean(swipe),
                    offsetRoles = roles,
                    appliedStart = oldBinding?.takeIf { it.view === swipe }?.appliedStart ?: Int.MIN_VALUE,
                    appliedEnd = oldBinding?.takeIf { it.view === swipe }?.appliedEnd ?: Int.MIN_VALUE,
                )
                runCatching { setter.invoke(swipe, callback) }.onFailure {
                    XposedBridge.log("LiquidTab Bilibili home refresh callback: $it")
                    writeRefreshDiagnostic("refresh=${swipe.javaClass.name} callbackInstall=${it.javaClass.name}:${it.message}")
                    return
                }
                refreshBinding = binding
                XposedBridge.log("LiquidTab Bilibili home refresh callback installed: ${swipe.javaClass.name}; feed=${feed.javaClass.name}; nativeField=${callbackField.name}")
            }
            val offsetSetter = binding.offsetSetter ?: return
            swipe.getLocationInWindow(position)
            val start = (topSafeWindow - position[1]).coerceAtLeast(0)
            // Place the native spinner below the pinned top row, keeping its
            // original pull threshold unchanged.
            val end = start + (24f * d).roundToInt()
            if (binding.appliedStart == start && binding.appliedEnd == end) {
                reportRefreshDiagnostic(feed, swipe, ancestry)
                return
            }
            runCatching { offsetSetter.invoke(swipe, start, end) }.onFailure {
                XposedBridge.log("LiquidTab Bilibili home refresh offset: $it")
                return
            }
            binding.appliedStart = start; binding.appliedEnd = end
            XposedBridge.log("LiquidTab Bilibili home refresh bound: ${swipe.javaClass.name}; feed=${feed.javaClass.name}; offset=$start..$end; nestedCanScrollUp=${feed.canScrollVertically(-1)}")
            reportRefreshDiagnostic(feed, swipe, ancestry)
        }


        private fun reportClipDiagnostic(feed: ViewGroup, topWindow: Int, bottomWindow: Int) {
            val key = "${System.identityHashCode(feed)}/${feed.width}/${feed.height}/${feed.paddingTop}/${feed.paddingBottom}/$topWindow/$bottomWindow"
            if (key == clipDiagnosticKey) return
            val now = SystemClock.uptimeMillis()
            if (now - lastClipDiagnostic < 800L) return
            lastClipDiagnostic = now
            clipDiagnosticKey = key
            val rows = ArrayList<String>()
            var node: ViewParent? = feed
            while (node is ViewGroup) {
                rows += "${node.javaClass.simpleName}[${node.top},${node.bottom}] pad=${node.paddingTop},${node.paddingBottom} clipPad=${node.clipToPadding} clipChild=${node.clipChildren} clip=${node.clipBounds}"
                if (node === content) break
                node = node.parent
            }
            runCatching {
                val manager = overscanFeeds.entries.firstOrNull { it.value === feed }?.key
                val firstBounds = (0 until minOf(feed.childCount, 3)).joinToString { index ->
                    feed.getChildAt(index).let { "${it.top}..${it.bottom}" }
                }
                java.io.File(activity.getExternalFilesDir(null), "liquidtab-home-layout-diag.txt")
                    .writeText("feed=${feed.javaClass.name}[${feed.top},${feed.bottom}] manager=${manager?.javaClass?.name} overscan=${confirmedOverscan[manager]} firstChildren=$firstBounds padding=${feed.paddingTop},${feed.paddingBottom} top=$topWindow bottom=$bottomWindow nav=${activity.window.navigationBarColor}/${activity.window.isNavigationBarContrastEnforced}; ${rows.joinToString(" <- ")}\n")
            }
        }

        private fun reportRefreshDiagnostic(feed: View, swipe: View, ancestry: List<String>) {
            val binding = refreshBinding
            val key = "${System.identityHashCode(feed)}/${System.identityHashCode(swipe)}/${feed.paddingTop}/${feed.paddingBottom}/${binding?.appliedStart}/${binding?.appliedEnd}"
            if (key == refreshDiagnosticKey) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRefreshDiagnostic < 800L) return
            lastRefreshDiagnostic = now
            refreshDiagnosticKey = key
            val loc = IntArray(2); feed.getLocationInWindow(loc)
            writeRefreshDiagnostic("refresh=${swipe.javaClass.name} feed=${feed.javaClass.name} canScrollUp=${feed.canScrollVertically(-1)} padding=${feed.paddingTop},${feed.paddingBottom} clip=${(feed as? ViewGroup)?.clipToPadding} loc=${loc[0]},${loc[1]} offset=${binding?.appliedStart}..${binding?.appliedEnd} ancestry=${ancestry.joinToString(" > ")}")
        }

        private fun writeRefreshDiagnostic(text: String) {
            runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-home-refresh.txt").writeText("$text\n") }
        }

        private fun restoreRefreshContainer() {
            val binding = refreshBinding ?: return
            runCatching { binding.setter.invoke(binding.view, binding.originalCallback) }
            restoreRefreshOffset(binding)
            refreshBinding = null
        }

        private fun restoreRefreshOffset(binding: RefreshBinding) {
            val offsetSetter = binding.offsetSetter
            if (offsetSetter != null && binding.originalStart != null && binding.originalEnd != null) {
                runCatching {
                    offsetSetter.invoke(binding.view, binding.originalStart, binding.originalEnd)
                    if (binding.originalCustomStart != null) {
                        binding.offsetRoles?.second?.setBoolean(binding.view, binding.originalCustomStart)
                    }
                    if (binding.originalScale != null) {
                        binding.offsetRoles?.first?.setBoolean(binding.view, binding.originalScale)
                    }
                }
            }
        }

        private fun restore() {
            topVeil.restoreSourceClipping()
            restoreRefreshContainer()
            if (suppressed) {
                toolbar?.alpha = originalToolbarAlpha
                toolbar?.importantForAccessibility = originalAccessibility
                tabsLayout?.let {
                    it.alpha = originalTabsAlpha
                    it.visibility = originalTabsVisibility
                    if (it.layoutParams.height != originalTabsHeight)
                        it.layoutParams = it.layoutParams.apply { height = originalTabsHeight }
                }
                inputs.forEach { (view, input) ->
                    view.isClickable = input.clickable; view.isLongClickable = input.longClickable; view.isFocusable = input.focusable
                }
                inputs.clear(); suppressed = false
                originalLayerAlpha.forEach { (view, alpha) -> if (view.alpha == 0f) view.alpha = alpha }
                originalLayerAlpha.clear()
                coordinatorBehaviors.forEach { (view, state) ->
                    if (view.layoutParams === state.params && state.getter.invoke(state.params) == null) {
                        writeBehavior(state.setter, state.params, state.original)
                        view.requestLayout()
                    }
                }
                coordinatorBehaviors.clear()
                shiftedContent?.requestLayout()
                shiftedContent = null
                originalPageHeight?.let { height ->
                    if (page.layoutParams.height != height) page.layoutParams = page.layoutParams.apply { this.height = height }
                }
                originalPageHeight = null
                activity.window.statusBarColor = statusColor
                activity.window.isStatusBarContrastEnforced = statusContrast
            }
            paddings.forEach { (view, padding) ->
                if (view.paddingTop == padding.appliedTop && view.paddingBottom == padding.appliedBottom)
                    view.setPadding(view.paddingLeft, padding.originalTop, view.paddingRight, padding.originalBottom)
                if (view is ViewGroup && view.clipToPadding != padding.originalClipToPadding)
                    view.clipToPadding = padding.originalClipToPadding
                if (view is ViewGroup && view.clipChildren != padding.originalClipChildren)
                    view.clipChildren = padding.originalClipChildren
                overscanFeeds.entries.removeAll { it.value === view }
                confirmedOverscan.keys.retainAll(overscanFeeds.keys)
            }
            paddings.clear()
            clipToPaddingStates.forEach { (view, original) -> if (view.clipToPadding != original) view.clipToPadding = original }
            clipToPaddingStates.clear()
        }

        private fun click(view: View?): Boolean {
            view ?: return false
            val saved = inputs[view]
            if (saved != null) { view.isClickable = saved.clickable; view.isLongClickable = saved.longClickable; view.isFocusable = saved.focusable }
            return try { view.performClick() } finally {
                lastScan = 0L
                if (saved != null && active) { view.isClickable = false; view.isLongClickable = false; view.isFocusable = false }
                panels.forEach { it.invalidate() }
            }
        }

        fun openSearch(): Boolean {
            val ready = toolbar?.isAttachedToWindow == true && !isStopped
            val opened = ready && click(search)
            if (io.github.offlineglass.BuildConfig.DEBUG) android.util.Log.i("LiquidTabBiliSearch",
                "native ready=$ready target=${search?.javaClass?.name} opened=$opened")
            return opened
        }

        fun tap(role: Int, index: Int): Boolean {
            if (!active) return false
            return when (role) {
                0 -> click(avatar)
                1 -> if (index == 0) { toggleMenu(); true } else click(shown.getOrNull(index - 1)?.cell)
                2 -> click(message)
                3 -> click(search)
                else -> false
            }
        }

        private fun toggleMenu() {
            if (menu != null) { closeMenu(); return }
            val d = content.resources.displayMetrics.density
            val root = FrameLayout(activity).apply {
                isClickable = true; isFocusableInTouchMode = true
                setOnClickListener { closeMenu() }
                setOnKeyListener { _, key, event ->
                    if (key == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) closeMenu(); true } else false
                }
            }
            val card = FrameLayout(activity).apply { isClickable = true }
            val material = GlassHostLayout(activity, config, 1, nativeSelectionReliable = false,
                navigationSource = page, sliderEnabled = false, adapterOverride = PanelAdapter(PanelState(this, 4))).apply { isClickable = false }
            card.addView(material, FrameLayout.LayoutParams(-1, -1))
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
                    view.clipBounds = Rect(0, 0, right - left, bottom - top)
                }
            }
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8*d).roundToInt(), 0, (8*d).roundToInt()) }
            fun entry(label: CharSequence, target: View) {
                val item = TextView(activity).apply {
                    text = label; textSize = 16f; gravity = Gravity.CENTER_VERTICAL
                    setPadding((22*d).roundToInt(), 0, (22*d).roundToInt(), 0)
                    setTextColor(if (target.isSelected) 0xFFFB7299.toInt() else if (dark()) Color.WHITE else 0xFF202020.toInt())
                    contentDescription = label
                    setOnClickListener { if (!menuClosing) { closeMenu(); click(target) } }
                }
                list.addView(item, LinearLayout.LayoutParams(-1, (48*d).roundToInt()))
            }
            cells.filter { it.title.text.toString() !in pins }.forEach { entry(it.title.text, it.cell) }
            game?.let { entry("游戏中心", it) }
            category?.let { entry("全部频道与管理", it) }
            scroll.addView(list)
            card.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            val p = panels[1].layoutParams as FrameLayout.LayoutParams
            val menuTop = p.topMargin + panels[1].height + (10*d).roundToInt()
            // The detached-search layout hides the field but still positions its boundary.
            val lowerChromeTop = (panels[3].layoutParams as FrameLayout.LayoutParams).topMargin
            val height = minOf(((list.childCount*48+16)*d).roundToInt(), lowerChromeTop - menuTop - (12*d).roundToInt())
            root.addView(card, FrameLayout.LayoutParams((188*d).roundToInt(), height.coerceAtLeast((48*d).roundToInt()), Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = p.leftMargin; topMargin = menuTop
            })
            // The pivot is the native 'more' slot's centre in card coordinates.
            // A negative Y pivot makes collapse end at the button above it.
            val moreSlot = (panels[1].width - 8*d) / (shown.size + 1)
            card.pivotX = 4*d + moreSlot / 2f
            card.pivotY = p.topMargin + panels[1].height / 2f - menuTop
            card.scaleX = .08f; card.scaleY = .08f; card.alpha = 0f
            menu = root; menuCard = card; menuMaterial = material
            menuClosing = false; menuAnimating = true
            overlay.addView(root, FrameLayout.LayoutParams(-1, -1)); root.requestFocus()
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240L)
                .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f))
                .setUpdateListener { material.invalidate() }
                .withEndAction {
                    if (menu === root && !menuClosing) {
                        menuAnimating = false
                        card.animate().setUpdateListener(null)
                        material.invalidate()
                    }
                }.start()
        }

        fun closeMenu() {
            val root = menu ?: return
            val card = menuCard
            fun remove() {
                if (menu !== root) return
                card?.animate()?.setUpdateListener(null)?.withEndAction(null)?.cancel()
                overlay.removeView(root)
                menu = null; menuCard = null; menuMaterial = null
                menuClosing = false; menuAnimating = false
            }
            if (!active || card == null || !root.isAttachedToWindow) { remove(); return }
            if (menuClosing) return
            menuClosing = true
            card.animate().cancel()
            menuAnimating = true
            card.animate().scaleX(.08f).scaleY(.08f).alpha(0f).setDuration(180L)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.3f))
                .setUpdateListener { menuMaterial?.invalidate() }
                .withEndAction { remove() }.start()
        }

        private fun place(view: View, x: Int, y: Int, width: Int, height: Int) {
            val p = view.layoutParams as FrameLayout.LayoutParams
            if (p.leftMargin == x && p.topMargin == y && p.width == width && p.height == height) return
            p.leftMargin = x; p.topMargin = y; p.width = width; p.height = height
            p.gravity = Gravity.TOP or Gravity.LEFT; p.bottomMargin = 0
            view.layoutParams = p
        }

        fun dark(): Boolean = cells.firstOrNull()?.title?.currentTextColor?.let {
            Color.red(it)*.299f + Color.green(it)*.587f + Color.blue(it)*.114f > 145f
        } ?: (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

        fun selectedIndex(): Int = shown.indexOfFirst {
            it.title.text.toString() == lastNativeChannel
        }.let { if (it >= 0) it + 1 else 0 }

        fun draw(canvas: Canvas, frame: AdapterNavigationFrame, role: Int) {
            if (role == 4) return
            val d = frame.density
            fun native(view: View?, left: Float, top: Float, width: Float, height: Float) {
                if (view == null || view.width <= 0 || view.height <= 0) return
                val save = canvas.save()
                canvas.clipRect(left, top, left + width, top + height)
                val scale = minOf(width / view.width, height / view.height)
                canvas.translate(left + (width - view.width*scale)/2f, top + (height - view.height*scale)/2f)
                canvas.scale(scale, scale)
                view.draw(canvas)
                canvas.restoreToCount(save)
            }
            when (role) {
                0 -> {
                    val source = avatar ?: return
                    val size = minOf(frame.width, frame.height) * .8f
                    native(find("avatar", source) ?: source,
                        (frame.width - size) / 2f, (frame.height - size) / 2f, size, size)
                }
                2 -> if (compose != null) native(message, (frame.width - 32*d)/2, (frame.height - 32*d)/2, 32*d, 32*d)
                    else drawMessageIcon(canvas, frame)
                3 -> {
                    val source = search ?: return
                    if (compose != null) {
                        // Draw only the glyph: sampling the Compose image also copied its search-container background.
                        messagePaint.color = if (frame.darkGlass) 0xFFBBBBBB.toInt() else 0xFF777777.toInt()
                        messagePaint.style = Paint.Style.STROKE
                        messagePaint.strokeWidth = 1.75f*d
                        messagePaint.strokeCap = Paint.Cap.ROUND
                        val cx = 24.5f*d; val cy = frame.height/2f - 1.25f*d
                        canvas.drawCircle(cx, cy, 5.75f*d, messagePaint)
                        canvas.drawLine(cx+4.1f*d, cy+4.1f*d, cx+8.2f*d, cy+8.2f*d, messagePaint)
                    } else native(find("search_icon", source), 17*d, frame.height/2f - 9*d, 18*d, 18*d)
                    val text = compose?.searchText ?: find("search_text", source)
                    if (text is TextView) drawSearchText(canvas, text, 45*d, frame.height/2f, frame.width - 61*d, frame.scaledDensity)
                }
                1 -> {
                    val color = if (frame.darkGlass) Color.WHITE else 0xFF202020.toInt()
                    val available = frame.width - 8*d
                    val more = channelVisual("")
                    paint.style = Paint.Style.FILL; paint.color = color
                    fun channelX(fraction: Float) = 4*d + available*(frame.topBarTabs?.fraction(fraction) ?: fraction)
                    for (i in -1..1) canvas.drawCircle(channelX(more.x) + i*4.5f*d, frame.height/2f, 1.3f*d, paint)
                    channelLabels.forEach { label ->
                        val visual = channelVisual(label)
                        if (visual.alpha <= .001f) return@forEach
                        val tab = shown.firstOrNull { it.title.text.toString() == label }
                        val selected = tab != null && label == lastNativeChannel
                        paint.color = if (selected) frame.accentColor else color
                        paint.alpha = (255 * visual.alpha).roundToInt()
                        paint.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                        paint.textSize = 14f * frame.scaledDensity * (.85f + .15f * visual.alpha) *
                            (frame.topBarTabs?.fitScale(frame.width) ?: 1f)
                        val fm = paint.fontMetrics
                        canvas.drawText(label, channelX(visual.x), frame.height/2f - (fm.ascent+fm.descent)/2f, paint)
                    }
                    paint.alpha = 255
                }
            }
        }

        private fun drawMessageIcon(canvas: Canvas, frame: AdapterNavigationFrame) {
            val source = message
            val current = source?.let { runCatching { actionIconGetter?.invoke(it) as? Drawable }.getOrNull() }
            val base = source?.let { runCatching { actionDefaultIcon?.get(it) as? Drawable }.getOrNull() } ?: current
            val tint = source?.let { runCatching { actionTintGetter?.invoke(it) as? Int }.getOrNull() } ?: 0
            if (messageIconBase !== base || messageStableTint != tint) {
                // Own a static native drawable. Native animation visibility,
                // alpha and callback changes can no longer blank our icon.
                messageStableIcon = base?.constantState?.newDrawable(source?.resources ?: activity.resources)?.mutate()?.apply {
                    alpha = 255; setVisible(true, false)
                    if (tint ushr 24 != 0) setTint(tint)
                }
                messageIconBase = base; messageStableTint = tint
            }
            val drawable = messageStableIcon
            val d = frame.density
            val cx = frame.width / 2f; val cy = frame.height / 2f
            if (drawable != null) {
                val size = 22f * d
                val scale = minOf(size / drawable.intrinsicWidth.coerceAtLeast(1), size / drawable.intrinsicHeight.coerceAtLeast(1))
                val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth * scale else size
                val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight * scale else size
                drawable.copyBounds(messageDrawableBounds)
                try {
                    drawable.setBounds((cx - w/2).roundToInt(), (cy - h/2).roundToInt(),
                        (cx + w/2).roundToInt(), (cy + h/2).roundToInt())
                    drawable.draw(canvas)
                } finally { drawable.bounds = messageDrawableBounds }
            } else {
                // Loading/skin replacement must never leave an empty control.
                messagePaint.color = source?.let { runCatching { actionTintGetter?.invoke(it) as? Int }.getOrNull() }
                    ?.takeIf { it ushr 24 != 0 } ?: if (dark()) Color.WHITE else 0xFF333333.toInt()
                messagePaint.style = Paint.Style.STROKE; messagePaint.strokeWidth = 1.5f * d
                messagePaint.strokeJoin = Paint.Join.ROUND; messagePaint.strokeCap = Paint.Cap.ROUND
                canvas.drawRoundRect(cx-10*d, cy-7.5f*d, cx+10*d, cy+7.5f*d, 2*d, 2*d, messagePaint)
                messagePath.reset(); messagePath.moveTo(cx-9*d, cy-6*d)
                messagePath.lineTo(cx, cy+1*d); messagePath.lineTo(cx+9*d, cy-6*d)
                canvas.drawPath(messagePath, messagePaint)
            }
            // Badge ownership and unread state stay with the native action.
            val badge = source?.let { runCatching { actionBadgeGetter?.invoke(it) as? View }.getOrNull() }
            if (source != null && badge?.visibility == View.VISIBLE && source.width > 0 && source.height > 0) {
                val scale = minOf(frame.width.toFloat()/source.width, frame.height.toFloat()/source.height)
                val save = canvas.save()
                canvas.translate((frame.width-source.width*scale)/2f, (frame.height-source.height*scale)/2f)
                canvas.scale(scale, scale); canvas.translate(badge.left.toFloat(), badge.top.toFloat())
                badge.draw(canvas); canvas.restoreToCount(save)
            }
        }

        private fun drawSearchText(canvas: Canvas, source: TextView, x: Float, centerY: Float, width: Float, scaledDensity: Float) {
            if (width <= 0f) return
            val label = (source.text?.takeIf { it.isNotBlank() } ?: source.hint?.takeIf { it.isNotBlank() } ?: "搜索").toString()
            searchPaint.color = if (source.currentTextColor ushr 24 == 0) {
                if (dark()) Color.WHITE else 0xFF777777.toInt()
            } else source.currentTextColor
            searchPaint.typeface = source.typeface ?: Typeface.DEFAULT
            searchPaint.textSize = source.textSize.takeIf { it > 0f } ?: 14f * scaledDensity
            if (searchTextKey != label || searchTextWidth != width || searchTextSize != searchPaint.textSize ||
                searchTextTypeface != searchPaint.typeface) {
                searchDisplayText = android.text.TextUtils.ellipsize(label, searchPaint, width,
                    android.text.TextUtils.TruncateAt.END).toString()
                searchTextKey = label; searchTextWidth = width; searchTextSize = searchPaint.textSize
                searchTextTypeface = searchPaint.typeface
            }
            val fm = searchPaint.fontMetrics
            val save = canvas.save()
            canvas.clipRect(x, 0f, x + width, centerY * 2f)
            canvas.drawText(searchDisplayText, x, centerY - (fm.ascent + fm.descent) / 2f, searchPaint)
            canvas.restoreToCount(save)
        }

        private fun resetSearchPress() {
            searchFeedback.reset()
        }

        fun covered() { channelAnimator?.end(); channelGestureActive = false; foreground = false; active = false; resetSearchPress(); closeMenu(); overlay.visibility = View.GONE; haloLayer.visibility = View.GONE }

        fun stop() {
            if (stopped) return
            stopped = true; active = false
            channelAnimator?.end(); channelGestureActive = false
            resetSearchPress()
            if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnPreDrawListener(this)
            restore()
            restoreMineInsets()
            topVeil.release()
            content.post { closeMenu(); (overlay.parent as? ViewGroup)?.removeView(overlay); panels.clear() }
        }

        private class PanelState(
            val session: Session,
            val role: Int,
            val scene: BilibiliHomeGlassScene = BilibiliHomeGlassScene(session.page),
        ) : AppNavigationState by scene {
            override fun interceptNavigationTouch(host: View, event: android.view.MotionEvent): Boolean? {
                if (role == 3) session.searchFeedback.touch(host, event)
                return null
            }
            override fun onHostDetached() {
                scene.onHostDetached()
                if (role == 3) session.searchFeedback.reset()
            }
            override fun onHostWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus && role == 3) session.searchFeedback.reset()
            }
            override fun drawPanelEdgeGlow(canvas: Canvas, path: Path, density: Float) {
                if (role == 3) session.searchFeedback.drawLight(canvas, path)
            }
            override fun forceDarkMode(selectedIndex: Int) = session.dark()
            override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true
            override fun suppressRedrawnSource() = false
            override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = if (role == 1) session.selectedIndex() else 0
            override fun indicatorHorizontalGeometry(center: Float, width: Float) =
                if (role == 1) session.channelIndicator(center, width) else null
            override fun onHostTouchDown(host: View, selectedIndex: Int) {
                // Settle geometry before native hit-slot calculation on a rapid new gesture.
                if (role == 1) session.channelAnimator?.end()
            }
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) =
                session.active && (role != 3 || !session.config.bilibiliDetachedSearch)
            override fun pauseBackdropWork(host: View) =
                !session.active || (role == 3 && session.config.bilibiliDetachedSearch)
            override fun forceNavigationBarImmersed(host: GlassHostLayout) = true
            override fun drawsNavigationContent() = role != 4
            override fun allowsHostTouch() = role != 4
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = session.tap(role, index)
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = session.draw(canvas, frame, role)
            // Normal live RenderNode dependencies carry feed updates. Forcing
            // compositor re-records here redraws four filtered wrappers even
            // when the homepage is idle.
            override fun forceLiveBackdropFrame(selectedIndex: Int) =
                (role == 4 && session.menuAnimating) || (role == 1 && session.channelProgress < 1f)
            override fun onBarGesture(phase: Int, host: View) {
                if (role == 1) {
                    session.channelGestureActive = phase != 2
                    if (phase == 2) session.content.invalidate()
                    return
                }
            }
            override fun handleSystemBack(host: View): Boolean {
                if (session.menu == null) return false
                session.closeMenu(); return true
            }
        }

        private class PanelAdapter(val state: PanelState) : TargetAdapter {
            private val material = TopBarMaterialPolicy(
                role = when (state.role) { 1 -> TopBarRole.CHANNELS; 3 -> TopBarRole.SEARCH; 4 -> TopBarRole.MENU; else -> TopBarRole.ACTION },
                heightDp = 44f, cornerRadiusPercent = if (state.role == 4) 16f else 50f,
                accentColor = 0xFFFB7299.toInt(),
            )
            override val key = "bilibili-home-accessory"
            override val ownsNavigationDrawing = true
            override val ownsNavigationTap get() = state.role != 4
            override val managesHostSiblingOrder = true
            override val ownsHostLayout = true
            override val skipNavigationSnapshot = true
            override val keepNavigationSourceVisible = true
            override val preserveNativeSourceMetrics = true
            override val managesNativeTabVisibility = true
            override val hasPerFrameScene = true
            override val skipBackdropStableDelay = true
            override fun liveBackdropTransform(parent: View, host: View, out: android.graphics.Matrix) =
                state.scene.transform(parent, host, out)
            override val panelHighlightFollowsAllThemes = true
            override val actionOnlyIndices get() = if (state.role == 1) setOf(0) else setOf(0)
            override fun createNavigationState(context: Context): AppNavigationState = state
            override fun normalizeConfig(config: GlassConfig) = material.apply(config)
            override val indicatorInteractionPolicy get() = material.indicatorInteractionPolicy
        }
    }
}

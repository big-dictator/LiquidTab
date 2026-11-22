package io.github.offlineglass.hook.adapters.bilibili_in

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.FrameLayout
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
import io.github.offlineglass.hook.adapters.bilibili.BILIBILI_HOME_TOP_CLEARANCE_DP
import io.github.offlineglass.hook.adapters.bilibili.BilibiliHomeGlassScene
import io.github.offlineglass.hook.adapters.bilibili.BilibiliSearchFeedback
import io.github.offlineglass.hook.adapters.home_glass.ComposeHomeBinding
import io.github.offlineglass.hook.adapters.home_glass.HomeEdgeColorGradient
import io.github.offlineglass.hook.adapters.home_glass.HomePanelHalo
import io.github.offlineglass.hook.adapters.home_glass.HomeSemanticSpec
import io.github.offlineglass.hook.adapters.home_glass.HomeTopGradientVeil
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.glassHostBottomMarginPx
import io.github.offlineglass.hook.glassHostHeightPx
import io.github.offlineglass.hook.glassHostWidthPx
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** International native home semantics and geometry; all optical drawing uses the existing glass host. */
internal object BilibiliInHomeLayoutHooks {
    private val sessions = WeakHashMap<Activity, Session>()
    private val blockedChrome = WeakHashMap<View, Session>()
    private val refreshFeeds = WeakHashMap<View, WeakReference<View>>()
    private val refreshQueries = HashSet<Method>()
    private val channelMeasure = TopBarTabMeasure()
    private val channels = listOf("推荐", "热门")
    private val semantics = HomeSemanticSpec(channels, listOf("avatar", "头像"), listOf("个人主页", "个人中心"),
        listOf("Search Icon", "搜索图标"), listOf("搜索"))

    fun nativeBehaviorChanged(params: Any, value: Any?) {
        sessions.values.forEach { it.nativeBehaviorChanged(params, value) }
    }

    fun install(loader: ClassLoader) {
        BilibiliInFrameInsets.install()
        runCatching { BilibiliInHotListPadding.install(loader) }
            .onFailure { XposedBridge.log("LiquidTab international Hot list hook: $it") }
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // One identity lookup, only for this adapter's verified hidden native header.
                if (blockedChrome[param.thisObject]?.ownsChrome == true) param.result = false
            }
        })
        for (event in listOf("onPostResume", "onStop", "onDestroy"))
            XposedBridge.hookAllMethods(Activity::class.java, event, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.packageName != BilibiliInAdapter.PACKAGE_NAME) return
                    when (event) {
                        "onPostResume" -> {
                            attach(activity)
                            activity.window.decorView.post { if (activity.hasWindowFocus()) attach(activity) }
                            activity.window.decorView.postDelayed({ if (!activity.isDestroyed && activity.hasWindowFocus()) attach(activity) }, 300L)
                        }
                        "onStop" -> sessions[activity]?.pause()
                        else -> sessions.remove(activity)?.dispose()
                    }
                }
            })
        runCatching {
            XposedBridge.hookAllMethods(loader.loadClass("tv.danmaku.bili.home.page.BaseHomeFrameFragment"), "onViewCreated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.args.firstOrNull() as? View ?: return
                    view.post {
                        var context = view.context
                        while (context is android.content.ContextWrapper && context !is Activity) {
                            val next = context.baseContext
                            if (next === context) return@post
                            context = next
                        }
                        (context as? Activity)?.let(::attach)
                    }
                }
            })
        }.onFailure { XposedBridge.log("LiquidTab international optional home fragment: $it") }
    }

    private fun attach(activity: Activity) {
        if (activity.isDestroyed || activity.isFinishing) return
        val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        fun group(name: String) = content.findViewById<ViewGroup>(content.resources.getIdentifier(name, "id", activity.packageName))
        val page = group("primary_multi_page_layout") ?: return
        if (group("top_bar_collapsible") == null || group("top_bar_fixed") == null) return
        val old = sessions[activity]
        if (old?.page === page) { old.resume(); return }
        old?.dispose()
        Session(activity, content, page).also { sessions[activity] = it; it.resume() }
    }

    fun openSearch(root: View?): Boolean {
        val session = sessions.values.firstOrNull { it.content.rootView === root?.rootView } ?: return false
        return session.search()
    }

    private class Overlay(context: Context) : FrameLayout(context), AdapterOwnedOverlay
    private data class Geometry(val view: View, val params: ViewGroup.LayoutParams, val height: Int,
        val top: Int, val bottomPadding: Int, val access: BilibiliInBehaviorAccess?, var behavior: Any?)
    private data class ListPadding(val top: Int, val bottom: Int, val clip: Boolean, var appliedTop: Int, var appliedBottom: Int)
    private data class Refresh(val view: View, val feed: View, val setter: Method, val original: Any?)

    private class Session(val activity: Activity, val content: FrameLayout, val page: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
        private val ids = HashMap<String, Int>()
        private fun find(name: String): View? = content.findViewById(ids.getOrPut(name) { content.resources.getIdentifier(name, "id", activity.packageName) })
        private val overlay = Overlay(activity).apply { clipChildren = false; clipToPadding = false }
        private val top = HomeTopGradientVeil(activity, page)
        private val fade = HomeEdgeColorGradient(false)
        private val bottom = View(activity).apply { background = fade; isClickable = false }
        private val halo = HomePanelHalo(activity)
        private val panels = ArrayList<GlassHostLayout>()
        private var binding: ComposeHomeBinding? = null
        private val nativeAlpha = WeakHashMap<View, Float>()
        private val geometries = ArrayList<Geometry>()
        private val headerBehaviors = WeakHashMap<View, Geometry>()
        private val paddings = WeakHashMap<ViewGroup, ListPadding>()
        private val parentClipping = WeakHashMap<ViewGroup, Boolean>()
        private var refresh: Refresh? = null
        private var queryRefresh: View? = null
        private var refreshIndicator: BilibiliInRefreshIndicator? = null
        private var cachedFeed: ViewGroup? = null
        private var pagerCandidate: View? = null
        private var pagerProbeAt = 0L
        private var pager: ViewGroup? = null
        private var pagerCurrentItem: Method? = null
        private var selectedChannel = -1
        private var bottomHost: GlassHostLayout? = null
        private val searchFeedback = BilibiliSearchFeedback(wholeView = true)
        private var foreground = true
        private var listening = false
        private var ready = false
        private var failures = 0
        private var retryAfter = 0L
        private var lastConfig = 0L
        private var frameConfiguredAt = 0L
        private var frameConfigured: View? = null
        private var frameEnabled = false
        private var config = HookConfigReader.read(activity, activity.packageName)
        private val location = IntArray(2)
        private val origin = IntArray(2)
        private val visible = Rect()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        private var textKey = ""
        private var textWidth = 0f
        private var displayText = ""
        private var bindingDiagnostic = ""
        var active = false; private set
        var ownsChrome = false; private set

        fun resume() {
            foreground = true; failures = 0; retryAfter = 0
            if (!listening) {
                listening = true
                overlay.addView(top, FrameLayout.LayoutParams(-1, 1))
                overlay.addView(bottom, FrameLayout.LayoutParams(-1, 1))
                overlay.addView(halo, FrameLayout.LayoutParams(-1, -1))
                content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
                content.viewTreeObserver.addOnPreDrawListener(this)
            }
            content.invalidate()
        }
        fun pause() {
            searchFeedback.reset()
            foreground = false; active = false; overlay.visibility = View.GONE
            panels.forEach { it.animate().cancel(); it.scaleX = 1f; it.scaleY = 1f }
            top.release()
        }
        override fun onPreDraw(): Boolean {
            if (!foreground || failures >= 3 || SystemClock.uptimeMillis() < retryAfter) return true
            runCatching { update() }.onFailure {
                ready = false; retryAfter = SystemClock.uptimeMillis() + 500L
                XposedBridge.log("LiquidTab international home: $it")
                if (failures == 0) runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-intl-home-error.txt").writeText(it.stackTraceToString()) }
                if (++failures < 3) content.postDelayed({ if (foreground) content.invalidate() }, 500L)
            }
            // Home readiness never blocks the window, native list, or bottom-bar animation.
            return true
        }
        private fun dark() = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        private fun update() {
            val now = SystemClock.uptimeMillis()
            if (now - lastConfig >= 500L) { config = HookConfigReader.read(activity, activity.packageName); lastConfig = now }
            val enabled = config.enabled && config.bilibiliTopBar
            (page.parent as? View)?.let { frame ->
                if (frameConfigured !== frame || frameEnabled != enabled || now-frameConfiguredAt >= 1000L) {
                    frameConfigured?.takeIf { it !== frame }?.let { BilibiliInFrameInsets.release(it) }
                    BilibiliInFrameInsets.configure(frame, enabled)
                    frameConfigured = frame; frameEnabled = enabled; frameConfiguredAt = now
                }
            }
            if (!enabled) { active = false; ready = false; restoreGeometry(); restoreChrome(); overlay.visibility = View.GONE; return }
            active = page.isAttachedToWindow && page.isShown && page.getGlobalVisibleRect(visible) &&
                visible.width() >= content.width * .8f && activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            if (!active) { restoreGeometry(); overlay.visibility = View.GONE; top.restoreSourceClipping(); return }
            val header = find("top_bar_collapsible") as? ViewGroup ?: return
            val strip = find("top_bar_fixed") as? ViewGroup ?: return
            // Once verified, visual ownership survives temporary semantics/source replacement.
            for (source in listOfNotNull(find("app_bar"), find("main_top_layout"), header, strip)) {
                nativeAlpha.getOrPut(source) { source.alpha }
                if (source.alpha != 0f) source.alpha = 0f
                blockedChrome[source] = this
                // The header and its scrolling sibling must relinquish nested-scroll together.
                // Detaching only the sibling makes native AppBar motion compete with our origin.
                val p = source.layoutParams
                var saved = headerBehaviors[source]
                if (saved?.params !== p) {
                    val access = BilibiliInBehaviorAccess.resolve(p)
                    val behavior = access?.get(p)
                    if (access != null) {
                        access.observeNativeWrites()
                        saved = Geometry(source, p, p.height, source.top, source.paddingBottom, access, behavior)
                        headerBehaviors[source] = saved
                    }
                }
                if (saved?.access?.get(p) != null) { saved.access.set(p, null); source.requestLayout() }
            }
            ownsChrome = true
            var native = binding
            if (native == null || native.header !== header || native.strip !== strip) {
                native = ComposeHomeBinding(header, strip, semantics); binding = native; ready = false
            }
            if (!ready || native.needsRefresh(now)) {
                ready = native.refresh(dark())
                if (native.diagnostic != bindingDiagnostic) {
                    bindingDiagnostic = native.diagnostic
                    runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-intl-home-binding.txt").writeText(bindingDiagnostic) }
                }
                if (!ready) {
                    // Material/previous valid host stays; stale callbacks cannot receive clicks.
                    if (panels.isEmpty()) return
                }
            }
            updateSelectedChannel(native)
            if (panels.isEmpty()) {
                repeat(3) { role ->
                    val state = PanelState(this, role)
                    val host = TopBarChannelHost.createPanel(activity, config, if (role == 1) 2 else 1,
                        nativeSelectionReliable = true, navigationSource = page, sliderEnabled = role == 1,
                        adapterOverride = PanelAdapter(state), isChannel = role == 1).apply {
                        elevation = 0f; clipChildren = false; clipToPadding = false
                        contentDescription = listOf("个人中心", "推荐、热门", "搜索")[role]
                    }
                    panels += host; overlay.addView(host, FrameLayout.LayoutParams(1, 1))
                }
                runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-intl-home.txt")
                    .writeText("compose channels=${native.tabs.map { it.title.text }}; native actions; shared glass\n") }
            }
            takeGeometry()
            overlay.visibility = View.VISIBLE
            content.getLocationInWindow(origin)
            val d = content.resources.displayMetrics.density
            val h = (44*d).roundToInt(); val circle = (40*d).roundToInt(); val gap = (12*d).roundToInt()
            if (bottomHost?.isAttachedToWindow != true) bottomHost = (0 until content.childCount)
                .map { content.getChildAt(it) }.filterIsInstance<GlassHostLayout>().firstOrNull()
            val bar = bottomHost?.takeIf { it.width > 0 && it.height > 0 }
            val searchWidth = glassHostWidthPx(content, 5, config, d).coerceAtMost(content.width)
            val searchLeft = (content.width - searchWidth) / 2
            val barTop: Int
            if (bar != null) {
                bar.getLocationInWindow(location); barTop = location[1] - origin[1]
            } else {
                barTop = content.height - glassHostBottomMarginPx(content, config) - glassHostHeightPx(d, config, activity.packageName)
            }
            // Freeform captions own their own input region even when statusBars
            // is zero. Keep panels, feed padding and refresh below the same edge.
            val systemTop = content.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or
                WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar())?.top ?: 0
            val y = (systemTop-origin[1]).coerceAtLeast(0)
            val sizing = channelMeasure.update(channels, d, 14f * activity.resources.displayMetrics.scaledDensity)
            panels[1].updateTopBarTabs(sizing)
            val maxChannelWidth = (content.width - 2 * (circle + gap)).coerceAtLeast(1)
            val tabWidth = minOf(sizing?.desiredWidth?.roundToInt() ?: (180*d).roundToInt(), maxChannelWidth)
            val channelLeft = (content.width - tabWidth) / 2
            val avatarLeft = (channelLeft - gap - circle).coerceAtLeast(0)
            top.setAppearance(config.bilibiliSolidTopGradient, dark()); fade.setDark(dark())
            place(top, 0, 0, content.width, y+h+((if (config.bilibiliSolidTopGradient) 64 else 12)*d).roundToInt())
            place(panels[0], avatarLeft, y+(h-circle)/2, circle, circle)
            place(panels[1], channelLeft, y, tabWidth, h)
            val searchTop = if (config.bilibiliInDetachedSearch) barTop else barTop-gap-h
            if (config.bilibiliInDetachedSearch) searchFeedback.reset()
            panels[2].visibility = if (config.bilibiliInDetachedSearch) View.GONE else View.VISIBLE
            place(panels[2], searchLeft, searchTop, searchWidth, h)
            val fadeTop = (searchTop-h/2-gap).coerceAtLeast(0)
            bottom.visibility = if (config.bilibiliBottomColorGradient) View.VISIBLE else View.GONE
            place(bottom, 0, fadeTop, content.width, content.height-fadeTop)
            halo.bind(panels, config.cornerSmoothing, dark(), config.liquidGlassEnabled)
            val topBarBottom = origin[1] + y + h
            val feedTop = topBarBottom + (BILIBILI_HOME_TOP_CLEARANCE_DP * d).roundToInt()
            val feed = safeInsets(feedTop, searchTop+origin[1], d)
            feed?.let {
                top.bindFeed(it); bindRefresh(it)
                refreshIndicator?.update(topBarBottom + (24f * d).roundToInt(), d)
            }
            top.updateFrame()
        }

        private fun takeGeometry() {
            val frame = page.parent as? View ?: return
            for (view in listOf(frame, page)) {
                var state = geometries.firstOrNull { it.view === view && it.params === view.layoutParams }
                if (state == null) {
                    val p = view.layoutParams
                    val access = BilibiliInBehaviorAccess.resolve(p)
                    access?.observeNativeWrites()
                    state = Geometry(view, p, p.height, view.top, view.paddingBottom, access, access?.get(p))
                    geometries += state
                }
                if (state.access?.get(state.params) != null) { state.access.set(state.params, null); view.requestLayout() }
                if (view === frame && view.top != 0) view.offsetTopAndBottom(-view.top)
                if (content.height > 0 && view.layoutParams.height != content.height)
                    view.layoutParams = view.layoutParams.apply { height = content.height }
                if (view === frame && view.paddingBottom != 0)
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, 0)
            }
        }
        private fun safeInsets(topWindow: Int, bottomWindow: Int, density: Float): ViewGroup? {
            fun pagerRecycler(view: View): Boolean {
                val parent = view.parent as? View ?: return false
                // ViewPager2 owns an internal RecyclerView. A RecyclerView
                // placed directly in the original ViewPager is a content page.
                return generateSequence(parent.javaClass as Class<*>?) { it.superclass }.any {
                    it.name == "androidx.viewpager2.widget.ViewPager2"
                }
            }
            fun resolve(view: View): ViewGroup? {
                if (!view.isShown) return null
                val list = view is AbsListView || generateSequence(view.javaClass as Class<*>?) { it.superclass }.any { it.name == "androidx.recyclerview.widget.RecyclerView" }
                // An outer pager RecyclerView is not the vertical feed. Prefer its nested native list.
                if (view !is AbsListView && view is ViewGroup)
                    for (i in 0 until view.childCount) resolve(view.getChildAt(i))?.let { return it }
                if (list && view is ViewGroup && !pagerRecycler(view) && view.height > 0 &&
                    view.getGlobalVisibleRect(visible) && visible.width() >= content.width*.8f) return view
                return null
            }
            val feed = cachedFeed?.takeIf { !pagerRecycler(it) && it.isAttachedToWindow && it.isShown && it.getGlobalVisibleRect(visible) && visible.width() >= content.width*.8f }
                ?: resolve(pager ?: page)?.also { cachedFeed = it }
            // Only one native feed may own the safe area. A retained pager or
            // a previous channel must never keep padding around the new list.
            val stale = paddings.entries.iterator()
            while (stale.hasNext()) {
                val (view, old) = stale.next()
                if (view === feed) continue
                if (view.paddingTop == old.appliedTop && view.paddingBottom == old.appliedBottom)
                    view.setPadding(view.paddingLeft, old.top, view.paddingRight, old.bottom)
                view.clipToPadding = old.clip
                stale.remove()
            }
            if (feed == null) { cachedFeed = null; return null }
            val layoutTop = layoutTopInWindow(feed)
            val bottomInset = layoutTop + feed.height - bottomWindow + (8*density).roundToInt()
            applyListPadding(feed, topWindow - layoutTop, bottomInset)
            if (feed.clipToPadding) feed.clipToPadding = false
            var parent = feed.parent
            while (parent is ViewGroup) {
                if (!parentClipping.containsKey(parent)) parentClipping[parent] = parent.clipToPadding
                if (parent.clipToPadding) parent.clipToPadding = false
                if (parent === content) break
                parent = parent.parent
            }
            return feed
        }
        private fun layoutTopInWindow(view: View): Int {
            var top = origin[1]
            var child = view
            while (child !== content) {
                val parent = child.parent as? View ?: break
                top += child.top - parent.scrollY
                child = parent
            }
            return top
        }
        private fun applyListPadding(view: ViewGroup, top: Int, bottom: Int) {
            val state = paddings.getOrPut(view) {
                ListPadding(view.paddingTop, view.paddingBottom, view.clipToPadding, view.paddingTop, view.paddingBottom)
            }
            val t = maxOf(state.top, top)
            val b = maxOf(state.bottom, bottom)
            if (view.paddingTop != t || view.paddingBottom != b) view.setPadding(view.paddingLeft, t, view.paddingRight, b)
            state.appliedTop = t
            state.appliedBottom = b
        }
        private fun bindRefresh(feed: View) {
            if (refresh?.feed === feed && refresh?.view?.isAttachedToWindow == true) return
            if (queryRefresh?.let { refreshFeeds[it]?.get() === feed && it.isAttachedToWindow } == true) return
            restoreRefresh()
            var ancestor = feed.parent as? View
            while (ancestor != null && ancestor !== content) {
                val target = ancestor
                fun validCallback(type: Class<*>) = type.isInterface && type.methods.any { method ->
                    method.returnType == Boolean::class.javaPrimitiveType && method.parameterCount in 1..2 &&
                        method.parameterTypes.all { t -> View::class.java.isAssignableFrom(t) }
                }
                val setter = target.javaClass.methods.firstOrNull { it.name == "setOnChildScrollUpCallback" && it.parameterCount == 1 }
                    ?: target.javaClass.methods.filter { it.returnType == Void.TYPE && it.parameterCount == 1 && validCallback(it.parameterTypes[0]) }.singleOrNull()
                if (setter != null) {
                    val type = setter.parameterTypes[0]
                    val method = type.methods.firstOrNull { it.returnType == Boolean::class.javaPrimitiveType && it.parameterCount in 1..2 && it.parameterTypes.all { t -> View::class.java.isAssignableFrom(t) } }
                    if (method == null) {
                        // R8 can erase the callback interface and specialize its caller to one concrete implementation.
                        // Verify the native refresh contract, then bind its unique non-property scroll-up query.
                        val query = target.javaClass.methods.firstOrNull { it.name == "canChildScrollUp" && it.parameterCount == 0 }
                            ?: target.javaClass.declaredMethods.filter {
                                it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType &&
                                    !it.name.startsWith("is") && !it.name.startsWith("has") && !it.isSynthetic
                            }.singleOrNull()
                        if (query != null) {
                            if (refreshQueries.add(query)) {
                            // The native query is final/small and may already be AOT-inlined into these callers.
                            // Deoptimize only the three verified gesture entry points, never the list or renderer.
                            val deoptimized = target.javaClass.declaredMethods.filter { it.name in setOf("onInterceptTouchEvent", "onTouchEvent", "onNestedScroll") }
                                .map { it.name to XposedBridge.framework().deoptimize(it) }
                            runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-intl-refresh-callers.txt").writeText(deoptimized.toString()) }
                            XposedBridge.hookMethod(query, object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    val actual = refreshFeeds[param.thisObject]?.get()?.takeIf { it.isAttachedToWindow && it.isShown } ?: return
                                    val nativeResult = param.result as? Boolean ?: return
                                    val scrollUp = actual.canScrollVertically(-1)
                                    if (!nativeResult && scrollUp) param.result = true
                                }
                            })
                            }
                            refreshFeeds[target] = WeakReference(feed); queryRefresh = target
                            refreshIndicator = BilibiliInRefreshIndicator(target)
                            runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-intl-refresh.txt").writeText("nativeQuery=${target.javaClass.name}.${query.name}; feed=${feed.javaClass.name}\n") }
                            return
                        }
                        ancestor = target.parent as? View
                        continue
                    }
                    val field = generateSequence(target.javaClass as Class<*>?) { it.superclass }.flatMap { it.declaredFields.asSequence() }
                        .firstOrNull { it.type == type }?.apply { isAccessible = true }
                    val original = field?.get(target)
                    val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, called, args ->
                        when (called.name) {
                            method.name -> (original?.let { runCatching { called.invoke(it, *(args ?: emptyArray())) as? Boolean }.getOrNull() } == true) || feed.canScrollVertically(-1)
                            "equals" -> self === args?.firstOrNull()
                            "hashCode" -> System.identityHashCode(self)
                            "toString" -> "InternationalHomeScrollUp"
                            else -> null
                        }
                    }
                    setter.invoke(target, proxy); refresh = Refresh(target, feed, setter, original)
                    refreshIndicator = BilibiliInRefreshIndicator(target)
                    runCatching { java.io.File(activity.getExternalFilesDir(null), "liquidtab-intl-refresh.txt").writeText("callback=${target.javaClass.name}.${setter.name}; feed=${feed.javaClass.name}; original=${original != null}\n") }
                    return
                }
                ancestor = target.parent as? View
            }
        }
        private fun restoreRefresh() {
            refreshIndicator?.restore(); refreshIndicator = null
            refresh?.let { runCatching { it.setter.invoke(it.view, it.original) } }; refresh = null
            queryRefresh?.let { refreshFeeds.remove(it) }; queryRefresh = null
        }
        private fun restoreGeometry() {
            cachedFeed = null
            restoreRefresh()
            paddings.forEach { (view, old) ->
                if (view.paddingTop == old.appliedTop && view.paddingBottom == old.appliedBottom) view.setPadding(view.paddingLeft, old.top, view.paddingRight, old.bottom)
                view.clipToPadding = old.clip
            }; paddings.clear()
            parentClipping.forEach { (view, original) -> if (view.clipToPadding != original) view.clipToPadding = original }
            parentClipping.clear()
            geometries.asReversed().forEach { old ->
                if (old.view.layoutParams === old.params) {
                    if (old.params.height == content.height) old.view.layoutParams = old.params.apply { height = old.height }
                    // This shared frame may already contain Follow/Messages.
                    // The native destination owns its coordinates, not the old home top.
                    old.view.requestLayout()
                    if (old.view.paddingBottom == 0 && old.bottomPadding != 0) old.view.setPadding(old.view.paddingLeft, old.view.paddingTop, old.view.paddingRight, old.bottomPadding)
                    if (old.behavior != null && old.access?.get(old.params) == null) old.access?.set(old.params, old.behavior)
                }
            }; geometries.clear()
            headerBehaviors.forEach { (view, old) ->
                if (view.layoutParams === old.params && old.behavior != null && old.access?.get(old.params) == null)
                    old.access?.set(old.params, old.behavior)
            }; headerBehaviors.clear()
        }
        fun nativeBehaviorChanged(params: Any, value: Any?) {
            geometries.forEach { if (it.params === params) it.behavior = value }
            headerBehaviors.values.forEach { if (it.params === params) it.behavior = value }
        }
        private fun restoreChrome() {
            nativeAlpha.forEach { (view, alpha) -> if (view.alpha == 0f) view.alpha = alpha; blockedChrome.remove(view) }
            nativeAlpha.clear(); ownsChrome = false
        }
        fun dispose() {
            foreground = false; active = false
            if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnPreDrawListener(this)
            restoreGeometry()
            // Destruction releases ownership; while enabled a replacement must not flash native chrome.
            if (!config.enabled || !config.bilibiliTopBar) restoreChrome() else nativeAlpha.keys.forEach { blockedChrome.remove(it) }
            nativeAlpha.clear(); top.release(); panels.forEach { it.animate().cancel() }
            (page.parent as? View)?.let { BilibiliInFrameInsets.release(it) }
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        fun search(): Boolean = binding?.takeIf { it.header.isAttachedToWindow }?.search?.performClick() == true
        private fun updateSelectedChannel(native: ComposeHomeBinding) {
            val pagerId = ids.getOrPut("pager") { content.resources.getIdentifier("pager", "id", activity.packageName) }
            val candidate = pagerCandidate?.takeIf { it.isAttachedToWindow } ?: run {
                val now = SystemClock.uptimeMillis()
                if (now - pagerProbeAt < 250L) null else {
                    pagerProbeAt = now
                    findUniqueDescendant(page) {
                        it is ViewGroup && it.accessibilityClassName == "androidx.viewpager.widget.ViewPager" &&
                            (it.id == pagerId || runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull() == "pager")
                    }
                }
            }
            if (candidate !== pagerCandidate) {
                pagerCandidate = candidate
                pager = candidate as? ViewGroup
                pagerCurrentItem = pager?.let {
                    runCatching { it.javaClass.getMethod("getCurrentItem") }.getOrNull()
                        ?.takeIf { method -> method.returnType == Int::class.javaPrimitiveType }
                }
            }
            val tabs = native.tabs
            val nativeIndex = pagerCurrentItem?.let { method -> method.invoke(pager) as? Int }
            val fromPager = if (tabs.size == channels.size && tabs.all { it.title.text.toString() in channels })
                tabs.getOrNull(nativeIndex ?: -1) else null
            val tab = fromPager ?: tabs.singleOrNull { it.cell.isSelected || it.title.isSelected }
            val index = tab?.let { channels.indexOf(it.title.text.toString()) } ?: -1
            if (index >= 0 && selectedChannel != index) {
                selectedChannel = index
                panels.getOrNull(1)?.invalidate()
            }
        }
        private fun findUniqueDescendant(root: ViewGroup, accepts: (View) -> Boolean): View? {
            val pending = ArrayDeque<View>()
            pending.add(root)
            var match: View? = null
            var remaining = 128
            while (pending.isNotEmpty() && remaining-- > 0) {
                val view = pending.removeFirst()
                if (accepts(view)) {
                    if (match != null) return null
                    match = view
                } else if (view is ViewGroup) {
                    for (i in 0 until view.childCount) pending.add(view.getChildAt(i))
                }
            }
            return match.takeIf { pending.isEmpty() }
        }
        fun selected(): Int? = selectedChannel.takeIf { it >= 0 }
        fun tap(role: Int, index: Int): Boolean {
            if (!active || !ready) return false
            return when (role) {
                0 -> binding?.avatar?.performClick() == true
                1 -> binding?.tabs?.firstOrNull { it.title.text.toString() == channels.getOrNull(index) }?.cell?.performClick() == true
                else -> search()
            }
        }
        private fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
            val p = view.layoutParams as FrameLayout.LayoutParams
            if (p.leftMargin == x && p.topMargin == y && p.width == w && p.height == h) return
            view.layoutParams = p.apply { leftMargin = x; topMargin = y; width = w; height = h }
        }
        fun draw(canvas: Canvas, frame: AdapterNavigationFrame, role: Int) {
            val d = frame.density
            if (role == 0) {
                val source = binding?.avatar ?: return
                if (source.width <= 0 || source.height <= 0) return
                val size = minOf(frame.width, frame.height)*.8f
                val scale = minOf(size/source.width, size/source.height)
                val save = canvas.save(); canvas.translate((frame.width-source.width*scale)/2, (frame.height-source.height*scale)/2)
                canvas.scale(scale, scale); source.draw(canvas); canvas.restoreToCount(save)
            } else if (role == 1) {
                paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
                paint.textSize = 14*frame.scaledDensity * (frame.topBarTabs?.fitScale(frame.width) ?: 1f)
                channels.forEachIndexed { index, label ->
                    val chosen = index == selectedChannel
                    paint.color = if (chosen) BilibiliInAdapter.ACCENT_COLOR else if (dark()) Color.WHITE else 0xFF202020.toInt()
                    paint.typeface = if (chosen) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    val fm = paint.fontMetrics
                    canvas.drawText(label, frame.channelCenter(index), frame.height/2f-(fm.ascent+fm.descent)/2, paint)
                }
            } else {
                drawSearchGlyph(canvas, 25*d, frame.height/2f, d, dark())
                val source = binding?.searchText
                val word = source?.text?.toString()?.takeIf { it.isNotBlank() } ?: "搜索视频、番剧或up主"
                val width = frame.width-61*d
                textPaint.color = if (dark()) 0xFFBBBBBB.toInt() else 0xFF777777.toInt(); textPaint.textSize = 14*frame.scaledDensity
                if (textKey != word || textWidth != width) {
                    displayText = android.text.TextUtils.ellipsize(word, textPaint, width.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
                    textKey = word; textWidth = width
                }
                val save = canvas.save(); canvas.clipRect(45*d, 0f, frame.width-16*d, frame.height.toFloat())
                val fm = textPaint.fontMetrics
                canvas.drawText(displayText, 45*d, frame.height/2f-(fm.ascent+fm.descent)/2, textPaint); canvas.restoreToCount(save)
            }
        }

        private class PanelState(
            val session: Session,
            val role: Int,
            val scene: BilibiliHomeGlassScene = BilibiliHomeGlassScene(session.page),
        ) : AppNavigationState by scene {
            override fun interceptNavigationTouch(host: View, event: android.view.MotionEvent): Boolean? {
                if (role == 2) session.searchFeedback.touch(host, event)
                return null
            }
            override fun onHostDetached() {
                scene.onHostDetached()
                if (role == 2) session.searchFeedback.reset()
            }
            override fun onHostWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus && role == 2) session.searchFeedback.reset()
            }
            override fun drawPanelEdgeGlow(canvas: Canvas, path: android.graphics.Path, density: Float) {
                if (role == 2) session.searchFeedback.drawLight(canvas, path)
            }
            override fun forceDarkMode(selectedIndex: Int) = session.dark()
            override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true
            override fun suppressRedrawnSource() = false
            override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = if (role == 1) session.selected() else 0
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = session.active && (role != 2 || !session.config.bilibiliInDetachedSearch)
            override fun pauseBackdropWork(host: View) = !session.active || (role == 2 && session.config.bilibiliInDetachedSearch)
            override fun forceNavigationBarImmersed(host: GlassHostLayout) = true
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = session.tap(role, index)
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = session.draw(canvas, frame, role)
        }
        private class PanelAdapter(val state: PanelState) : TargetAdapter {
            private val material = TopBarMaterialPolicy(
                role = when (state.role) { 1 -> TopBarRole.CHANNELS; 2 -> TopBarRole.SEARCH; else -> TopBarRole.ACTION },
                heightDp = 44f, accentColor = BilibiliInAdapter.ACCENT_COLOR,
            )
            override val key = "bilibili-in-home-accessory"
            override val ownsNavigationDrawing = true
            override val ownsNavigationTap = true
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
            override val actionOnlyIndices get() = if (state.role == 1) emptySet() else setOf(0)
            override fun createNavigationState(context: Context): AppNavigationState = state
            // Target-only search routing stays outside the common material policy.
            override fun normalizeConfig(config: GlassConfig) = material.apply(
                if (config.bilibiliInDetachedSearch) config.copy(bilibiliInDetachedSearch = false) else config,
            )
            override val indicatorInteractionPolicy get() = material.indicatorInteractionPolicy
        }
    }
    private val searchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun drawSearchGlyph(canvas: Canvas, cx: Float, cy: Float, d: Float, dark: Boolean) {
        searchPaint.color = if (dark) 0xFFDDDDDD.toInt() else 0xFF333333.toInt()
        searchPaint.style = Paint.Style.STROKE; searchPaint.strokeWidth = 1.8f*d; searchPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(cx-1.25f*d, cy-1.25f*d, 6.5f*d, searchPaint)
        canvas.drawLine(cx+3.4f*d, cy+3.4f*d, cx+8.2f*d, cy+8.2f*d, searchPaint)
    }
}

package io.github.offlineglass.hook.adapters.weibo

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.TextView
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import io.github.offlineglass.hook.topbar.TopBarChannelHost
import io.github.offlineglass.hook.topbar.TopBarTabMeasure
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Home-only chrome; the native header remains the owner of navigation and actions. */
internal object WeiboHomeTopBar {
    private val sessions = WeakHashMap<FrameLayout, WeakReference<Session>>()
    fun observe(owner: GlassHostLayout) {
        val content = owner.parent as? FrameLayout ?: return
        sessions[content]?.get()?.takeIf { !it.stopped }?.let { it.bindOwner(owner); return }
        if (!owner.config.enabled || !owner.config.weiboHomeTopBar || owner.selectedIndex != 0) return
        var context: Context = owner.context
        repeat(12) { if (context !is Activity) context = (context as? ContextWrapper)?.baseContext ?: return }
        val activity = context as? Activity ?: return
        val session = Session(owner, activity, content)
        sessions[content] = WeakReference(session)
        session.start()
    }
    fun dispose(owner: GlassHostLayout?) {
        val content = owner?.parent as? FrameLayout ?: return
        // Replacing a bottom source is not permission to reveal the native home header.
        if (!owner.config.enabled || !owner.config.weiboHomeTopBar || !content.isAttachedToWindow)
            sessions.remove(content)?.get()?.stop()
    }

    private class Overlay(context: Context) : FrameLayout(context), AdapterOwnedOverlay {
        init { clipChildren = false; clipToPadding = false; isClickable = false; isFocusable = false }
    }
    private data class Saved(val alpha: Float, val accessibility: Int, val background: Drawable?,
                             val foreground: Drawable?, val elevation: Float)

    private class Session(owner: GlassHostLayout, val activity: Activity, val content: FrameLayout) : ViewTreeObserver.OnPreDrawListener {
        private var ownerRef = WeakReference(owner)
        val overlay = Overlay(activity)
        val hosts = ArrayList<GlassHostLayout>()
        private val topFade = View(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private var fadeDark: Boolean? = null
        private val viewport = WeiboHomeViewport(activity, content)
        private val originals = LinkedHashMap<View, Saved>()
        var binding: WeiboHomeTopBinding.Binding? = null
        private var owned = false
        var stopped = false
        var active = false
        var config = owner.config
        fun bindOwner(owner: GlassHostLayout) { ownerRef = WeakReference(owner) }
        private var nextBind = 0L
        private var attempts = 0
        private var bindPending = true
        private var feed: WeiboHomeTopBinding.Feed? = null
        private var feedIndex = -1
        private var feedProbeAt = 0L
        private var selected = 0
        private val contentPosition = IntArray(2)
        private val rowRect = Rect()
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val artwork = Array(4) { RenderNode("WeiboHomeAction$it") }
        private var publishDrawable: Drawable? = null
        private var publishBitmap: android.graphics.Bitmap? = null
        private var publishCrop = Rect()
        private var publishNight: Boolean? = null
        private fun drawPublish(drawable: Drawable, canvas: Canvas, width: Int, height: Int) {
            val night = dark()
            if (publishDrawable !== drawable || publishNight != night) {
                val bitmap = android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888)
                val original = Rect(drawable.bounds)
                try { drawable.setBounds(0, 0, 160, 160); drawable.draw(Canvas(bitmap)) }
                finally { drawable.bounds = original }
                var left = 160; var top = 160; var right = -1; var bottom = -1
                val pixels = IntArray(160 * 160); bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 160)
                for (y in 0 until 160) for (x in 0 until 160) if (Color.alpha(pixels[y * 160 + x]) > 8) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
                publishCrop = if (right >= left) Rect(left, top, right + 1, bottom + 1) else Rect(0, 0, 160, 160)
                publishBitmap = bitmap; publishDrawable = drawable; publishNight = night
            }
            publishBitmap?.let { canvas.drawBitmap(it, publishCrop, Rect(0, 0, width, height), ink) }
        }
        private val channelMeasure = TopBarTabMeasure()
        private var lastLabels = listOf("推荐", "关注")
        private var lastColors = listOf(Color.BLACK, Color.GRAY)
        private val layout = ViewTreeObserver.OnGlobalLayoutListener {
            if (binding?.valid() != true) { bindPending = true; attempts = 0 }
            if (feed?.list?.isAttachedToWindow != true) feedProbeAt = 0L
        }
        private val detached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { stop() }
        }
        fun start() {
            overlay.setForceDarkAllowed(false)
            overlay.addView(topFade, FrameLayout.LayoutParams(-1, 1))
            overlay.translationZ = 28f * density()
            content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            content.viewTreeObserver.addOnPreDrawListener(this)
            content.viewTreeObserver.addOnGlobalLayoutListener(layout)
            content.addOnAttachStateChangeListener(detached)
        }
        override fun onPreDraw(): Boolean {
            if (!stopped) runCatching { update() }.onFailure {
                android.util.Log.w("LiquidTab-Weibo", "Home top contract pending: ${it.javaClass.simpleName}")
            }
            return true
        }
        private fun update() {
            val bottom = ownerRef.get() ?: run { active = false; return }
            config = bottom.config
            if (!config.enabled || !config.weiboHomeTopBar) {
                active = false; overlay.visibility = View.GONE
                restoreChannels(); viewport.suspend(); feed = null; restoreNative(); owned = false
                return
            }
            if (bottom.selectedIndex != 0 || !bottom.isShown) {
                active = false; overlay.visibility = View.GONE
                restoreChannels(); viewport.suspend(); feed = null; feedIndex = -1
                return
            }
            // A relaunch draws before window focus; chrome ownership must already hold.
            val now = SystemClock.uptimeMillis()
            if (binding?.valid() != true) {
                binding = null
                if (bindPending && attempts < 8 && now >= nextBind) {
                    attempts++; nextBind = now + 250L
                    binding = WeiboHomeTopBinding.find(content)
                    if (binding != null) { bindPending = false; attempts = 0 }
                }
            }
            val bound = binding
            if (bound != null) { suppress(bound.row); suppressDivider(bound.row) }
            if (bound == null && !owned) {
                WeiboHomeTopProbe.record(activity, "pending: ${WeiboHomeTopBinding.lastReport}")
                overlay.visibility = View.GONE; return
            }
            if (bound != null) {
                if (!bound.row.isShown || !bound.row.getGlobalVisibleRect(rowRect) ||
                    rowRect.width() < bound.row.width * .8f || !WeiboHomeTopBinding.ancestorsVisible(bound.row)) {
                    active = false; overlay.visibility = View.GONE; restoreChannels(); viewport.suspend(); feed = null
                    return
                }
                selected = bound.selection()
                if ((feed == null || feedIndex != selected || feed?.list?.isAttachedToWindow != true) && now >= feedProbeAt) {
                    feedProbeAt = now + 350L
                    WeiboHomeTopBinding.feedBranch(content, bound.row)?.let { resolved ->
                        if (feed?.branch !== resolved.branch) restoreChannels()
                        feed = resolved; feedIndex = selected; viewport.bind(resolved, bound.row)
                    }
                }
                if (feed == null && !owned) {
                    WeiboHomeTopProbe.record(activity, "pending: home header complete; native feed branch unavailable")
                    overlay.visibility = View.GONE; return
                }
                lastLabels = bound.tabs.map { it.text.toString() }
                lastColors = bound.tabs.map { it.currentTextColor }
                suppress(bound.row)
                suppressDivider(bound.row)
                owned = true
            }
            ensureHosts()
            active = true; overlay.visibility = View.VISIBLE
            content.getLocationInWindow(contentPosition)
            val inset = content.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
            val tabs = channelMeasure.update(lastLabels, density(), 16f * activity.resources.displayMetrics.scaledDensity)
            val geometry = WeiboHomeTopPolicy.layout(content.width.toFloat(), density(), inset.toFloat(),
                contentPosition[1].toFloat(), tabs?.desiredWidth ?: 144f * density())
            val night = dark()
            if (fadeDark != night) {
                hosts.forEach { it.refreshAdapterDerivedConfig() }
                val rgb = if (night) 0x0018181B else 0x00FFFFFF
                val colors = IntArray(17) { index ->
                    val t = index / 16f
                    ((160f * (1f - t*t*(3f - 2f*t))).toInt().coerceIn(0, 160) shl 24) or rgb
                }
                topFade.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, colors)
                fadeDark = night
            }
            val fadeHeight = inset + dp(6)
            if (topFade.layoutParams.height != fadeHeight) topFade.layoutParams = FrameLayout.LayoutParams(-1, fadeHeight)
            val top = geometry.top().roundToInt(); val size = geometry.size().roundToInt()
            place(hosts[0], geometry.left().roundToInt(), top, dp(48), size)
            hosts[1].updateTopBarTabs(tabs)
            place(hosts[1], geometry.tabs().roundToInt(), top, geometry.tabWidth().roundToInt(), size)
            place(hosts[2], geometry.reward().roundToInt(), top, size, size)
            place(hosts[3], geometry.publish().roundToInt(), top, size, size)
            hosts[1].updateAdapterSlotCount(2, selected)
            viewport.contentDark = dark()
            viewport.update()
            updateChannels()
            WeiboHomeTopProbe.record(activity, "ready: native channels=2 actions=3 selected=$selected feed=${feed != null} top=$top height=$size ${viewport.report()}")
            hosts.forEach { if (it.visibility != View.VISIBLE) it.visibility = View.VISIBLE }
        }
        private var channelRow: ViewGroup? = null
        private var channelAlpha = 1f
        private var channelOriginalY = 0f
        private val channelPosition = IntArray(2)
        private val channelContainers = ArrayList<ViewGroup>()
        private val channelBackgrounds = LinkedHashMap<View, Drawable>()
        private var channelsHidden = false
        private var channelProbeAt = 0L
        private var offsetSource: View? = null
        private var offsetReader: java.lang.reflect.Method? = null
        private fun updateChannels() {
            val page = feed?.branch as? ViewGroup ?: return
            if (channelRow?.isAttachedToWindow != true && SystemClock.uptimeMillis() >= channelProbeAt) {
                channelProbeAt = SystemClock.uptimeMillis() + 1000L
                val labels = ArrayList<TextView>()
                val queue = ArrayDeque<View>(); queue.add(page)
                var count = 0
                while (queue.isNotEmpty() && count++ < 400) {
                    val v = queue.removeFirst()
                    if (v === feed?.list) continue
                    if (v is TextView && v.text?.toString()?.trim() in setOf("热门", "同城", "实时")) labels.add(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
                }
                if (labels.size == 3 && labels.map { it.text.toString().trim() }.toSet().size == 3) {
                    var parent = labels.first().parent as? ViewGroup
                    while (parent != null && parent !== page) {
                        val candidate = parent
                        fun contains(v: View): Boolean {
                            var p: View? = v
                            repeat(16) { if (p === candidate) return true; p = p?.parent as? View }
                            return false
                        }
                        if (labels.all { contains(it) }) {
                            var whole = candidate
                            var outer = whole.parent as? ViewGroup
                            val nativeList = feed?.list
                            while (outer != null && outer !== page) {
                                var ancestor: View? = nativeList
                                var containsList = false
                                repeat(24) {
                                    if (ancestor === outer) containsList = true
                                    ancestor = ancestor?.parent as? View
                                }
                                if (containsList) break
                                whole = outer
                                outer = whole.parent as? ViewGroup
                            }
                            channelRow = whole; channelAlpha = whole.alpha; channelOriginalY = whole.translationY
                            WeiboHomeTopProbe.record(activity, "channel row bound: ${WeiboHomeTopBinding.name(candidate)} labels=热门/同城/实时")
                            break
                        }
                        parent = candidate.parent as? ViewGroup
                    }
                }
            }
            val row = channelRow ?: return
            if (channelContainers.isEmpty()) {
            val chrome = ArrayDeque<View>(); chrome.add(row)
            var chromeCount = 0
            while (chrome.isNotEmpty() && chromeCount++ < 400) {
                val view = chrome.removeFirst()
                if (view is ViewGroup) {
                    channelContainers.add(view)
                    for (i in 0 until view.childCount) chrome.add(view.getChildAt(i))
                }
            }
            }
            channelContainers.forEach { view -> view.background?.let { channelBackgrounds[view] = it; view.background = null } }
            // Follow the native channel branch's displacement, including its scrolling ancestors.
            // The viewport owns the page translation, so stop before that coordinate root.
            var displacement = 0f
            var node: View? = row
            repeat(24) {
                val current = node
                if (current != null && current !== page) {
                    displacement += current.translationY
                    val parent = current.parent as? View
                    if (parent != null) displacement -= parent.scrollY
                    node = parent
                }
            }
            row.getLocationInWindow(channelPosition)
            val targetTop = hosts.firstOrNull()?.let { it.top + it.height } ?: 0
            val nativeTop = channelPosition[1] - contentPosition[1] - row.translationY
            val desiredY = targetTop - nativeTop.toFloat()
            if (row.translationY != desiredY) row.translationY = desiredY
            viewport.topReservation = targetTop + row.height
            val nativeList = feed?.list
            if (offsetSource !== nativeList) {
                offsetSource = nativeList
                offsetReader = nativeList?.javaClass?.getMethod("computeVerticalScrollOffset")
                    ?.takeIf { it.returnType == Int::class.javaPrimitiveType && it.parameterCount == 0 }
            }
            val scrollOffset = (offsetReader?.invoke(nativeList) as? Int)?.coerceAtLeast(0) ?: 0
            val distance = if (nativeList?.canScrollVertically(-1) != true) 0f else scrollOffset.toFloat()
            val progress = (distance / row.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            val alpha = channelAlpha * (1f - progress)
            row.animate().cancel()
            if (row.alpha != alpha) row.alpha = alpha
            val hidden = progress >= .999f
            if (hidden != channelsHidden) {
                channelsHidden = hidden
                if (hidden) WeiboHomeTopBinding.ownTouch(row) else WeiboHomeTopBinding.releaseTouch(row)
            }
        }
        private fun restoreChannels() {
            channelRow?.let { it.animate().cancel(); it.translationY = channelOriginalY; it.alpha = channelAlpha; WeiboHomeTopBinding.releaseTouch(it) }
            channelBackgrounds.forEach { (view, background) -> view.background = background }
            channelBackgrounds.clear(); channelContainers.clear()
            channelRow = null; channelsHidden = false
        }
        private fun suppress(row: ViewGroup) {
            originals.getOrPut(row) { Saved(row.alpha, row.importantForAccessibility, row.background, row.foreground, row.elevation) }
            val original = originals.getValue(row)
            if (row.background != null && row.background !== original.background)
                originals[row] = original.copy(background = row.background)
            WeiboHomeTopBinding.ownTouch(row)
            if (row.alpha != 0f) row.alpha = 0f
            if (row.background != null) row.background = null
            if (row.foreground != null) row.foreground = null
            if (row.elevation != 0f) row.elevation = 0f
            if (row.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        private fun suppressDivider(row: ViewGroup) {
            val parent = row.parent as? ViewGroup ?: return
            for (i in 0 until parent.childCount) {
                val view = parent.getChildAt(i)
                val p = view.layoutParams as? android.widget.RelativeLayout.LayoutParams ?: continue
                if (view !== row && view is android.widget.ImageView && p.height in 1..dp(1) &&
                    p.getRule(android.widget.RelativeLayout.BELOW) == row.id) {
                    originals.getOrPut(view) { Saved(view.alpha, view.importantForAccessibility, view.background, view.foreground, view.elevation) }
                    if (view.alpha != 0f) view.alpha = 0f
                }
            }
        }
        private fun restoreNative() {
            originals.forEach { (v, original) ->
                (v as? ViewGroup)?.let(WeiboHomeTopBinding::releaseTouch)
                v.alpha = original.alpha; v.background = original.background; v.foreground = original.foreground
                v.elevation = original.elevation; v.importantForAccessibility = original.accessibility
            }
            originals.clear()
        }
        private fun ensureHosts() {
            if (hosts.isNotEmpty()) return
            repeat(4) { role ->
                val state = PanelState(this, role)
                val host = TopBarChannelHost.createPanel(activity, config, if (role == 1) 2 else 1,
                    nativeSelectionReliable = false, navigationSource = null,
                    sliderEnabled = role == 1, adapterOverride = PanelAdapter(state), isChannel = role == 1).apply {
                    elevation = 0f; clipChildren = false; clipToPadding = false
                    contentDescription = when (role) { 0 -> "超话"; 1 -> "首页频道：推荐、关注"; 2 -> "红包"; else -> "发布" }
                }
                hosts += host
                overlay.addView(host, FrameLayout.LayoutParams(1, 1))
            }
        }
        private fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
            val p = view.layoutParams as FrameLayout.LayoutParams
            if (p.width != w || p.height != h || p.leftMargin != x || p.topMargin != y) {
                p.width = w; p.height = h; p.leftMargin = x; p.topMargin = y; view.layoutParams = p
            }
        }
        fun tap(role: Int, index: Int): Boolean {
            val b = binding?.takeIf { it.valid() && active } ?: return false
            val target = when (role) { 0 -> b.left; 1 -> b.tabs.getOrNull(index); 2 -> b.reward; else -> b.publish } ?: return false
            return WeiboHomeTopBinding.click(target, b.row)
        }
        fun draw(canvas: Canvas, frame: AdapterNavigationFrame, role: Int) {
            if (role == 1) {
                ink.textSize = 16f * frame.scaledDensity * (frame.topBarTabs?.fitScale(frame.width) ?: 1f)
                val metrics = ink.fontMetrics
                lastLabels.forEachIndexed { index, text ->
                    ink.typeface = Typeface.create("sans-serif", if (index == selected) Typeface.BOLD else Typeface.NORMAL)
                    ink.color = if (dark()) {
                        if (index == selected) Color.WHITE else 0xFFAAAAAA.toInt()
                    } else lastColors.getOrElse(index) { Color.BLACK }
                    val x = frame.channelCenter(index)
                    canvas.drawText(text, x, frame.height * .5f - (metrics.ascent + metrics.descent) * .5f, ink)
                }
                return
            }
            val b = binding?.takeIf { it.valid() }
            val source = when (role) { 0 -> b?.left; 2 -> b?.reward; else -> b?.publish }
            val node = artwork[role]
            if (source != null && source.width > 0 && source.height > 0 && source.isShown) {
                val recording = node.beginRecording(frame.width, frame.height)
                try {
                    val size = (if (role == 3) 40f else if (role == 0) 34f else 30f) * frame.density
                    val scale = size / maxOf(source.width, source.height)
                    val save = recording.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), if (role == 3) 125 else 255)
                    val circular = (source as? TextView)?.takeIf { role == 3 && it.text.isNullOrBlank() }?.let { text ->
                        text.compoundDrawables.filterNotNull().singleOrNull() ?: text.background
                    }
                    if (circular != null) {
                        drawPublish(circular, recording, frame.width, frame.height)
                    } else {
                        recording.translate((frame.width - source.width * scale) * .5f, (frame.height - source.height * scale) * .5f)
                        recording.scale(scale, scale)
                        source.draw(recording)
                    }
                    recording.restoreToCount(save)
                } finally { node.endRecording() }
                node.setPosition(0, 0, frame.width, frame.height)
            }
            if (canvas.isHardwareAccelerated && node.hasDisplayList()) canvas.drawRenderNode(node)
        }
        fun dark(): Boolean = when (config.themeMode) {
            GlassConfig.THEME_LIGHT -> false
            GlassConfig.THEME_DARK -> true
            else -> ownerRef.get()?.isDarkGlass() ?: config.systemDark
        }
        fun selection() = selected
        private fun density() = content.resources.displayMetrics.density
        private fun dp(value: Int) = (value * density()).roundToInt()
        fun stop() {
            if (stopped) return
            stopped = true; active = false
            if (content.viewTreeObserver.isAlive) {
                content.viewTreeObserver.removeOnPreDrawListener(this)
                content.viewTreeObserver.removeOnGlobalLayoutListener(layout)
            }
            content.removeOnAttachStateChangeListener(detached)
            if (content.isAttachedToWindow) {
                restoreChannels(); viewport.suspend(); restoreNative()
            } else {
                // A retiring window may still be used for the system relaunch snapshot.
                // Never re-expose native chrome or reshape that snapshot during detach.
                channelRow?.let(WeiboHomeTopBinding::releaseTouch)
                originals.keys.filterIsInstance<ViewGroup>().forEach(WeiboHomeTopBinding::releaseTouch)
                originals.clear()
            }
            content.post { (overlay.parent as? ViewGroup)?.removeView(overlay); hosts.clear() }
        }

        private class PanelState(val session: Session, val role: Int) : AppNavigationState {
            override val allowsSoftwareBackdropCapture = false
            override val backdropSceneViewsAreDescendants = true
            override fun resolveBackdropScene(host: View): View = session.content
            override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> =
                (0 until session.content.childCount).map { session.content.getChildAt(it) }
                    .filter { it !is GlassHostLayout && it !is AdapterOwnedOverlay && it.visibility == View.VISIBLE && it.alpha > .001f }
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) =
                session.overlay.visibility == View.VISIBLE && session.config.enabled && session.config.weiboHomeTopBar
            override fun pauseBackdropWork(host: View) = !session.active || !session.activity.hasWindowFocus() || !host.isShown
            override fun forceNavigationBarImmersed(host: GlassHostLayout) = true
            override fun resolveAutoDarkMode(host: View) = session.dark()
            override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = if (role == 1) session.selection() else 0
            override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = true
            override fun suppressRedrawnSource() = false
            override fun allowsHostTouch() = session.active
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = session.tap(role, index)
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = session.draw(canvas, frame, role)
        }
        private class PanelAdapter(val state: PanelState) : TargetAdapter {
            override val disablePlatformForceDark = true
            override fun glassSurfaceFillBlendMode(dark: Boolean) = weiboGlassFillBlendMode(dark)
            override val key = "weibo-home-top-${state.role}"
            override val ownsNavigationDrawing = true
            override val ownsNavigationTap = true
            override val managesHostSiblingOrder = true
            override val ownsHostLayout = true
            override val skipNavigationSnapshot = true
            override val hasPerFrameScene = true
            override val skipBackdropStableDelay = true
            override val allowRenderNodeRefresh = true
            override val forceCompositorFrames = true
            override val liveBackdropFramePump = true
            override val panelHighlightFollowsAllThemes = true
            override fun createNavigationState(context: Context) = state
            private val material = TopBarMaterialPolicy(
                role = if (state.role == 1) TopBarRole.CHANNELS else TopBarRole.ACTION,
                iconOnly = state.role != 1, selectedAccent = state.role == 1, accentColor = 0xFFFF8200.toInt())
            override fun normalizeConfig(config: GlassConfig): GlassConfig {
                val result = material.apply(config)
                return if (state.role == 3) result.copy(lightAlpha = 0f, darkAlpha = 0f, solidBarEnabled = false) else result
            }
            override val indicatorInteractionPolicy get() = material.indicatorInteractionPolicy
            override fun restingIndicatorTintScale(config: GlassConfig): Float =
                if (state.role == 1) config.lightAlpha.coerceIn(0f, .8f) else 1f
        }
    }
}

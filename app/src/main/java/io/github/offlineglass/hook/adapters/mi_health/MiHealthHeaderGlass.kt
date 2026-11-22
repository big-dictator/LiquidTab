package io.github.offlineglass.hook.adapters.mi_health

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Color
import android.view.MotionEvent
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import java.lang.ref.WeakReference
import io.github.offlineglass.rendering.IndicatorInteractionLight
import io.github.offlineglass.hook.SpringFloat
import io.github.offlineglass.hook.setBottomBarSquircle

internal data class MiHealthHeaderAction(val view: View, val plus: Boolean)

/** Independent, input-transparent circles. Native/RN child ownership is untouched. */
internal class MiHealthHeaderGlass {
    private var owner: WeakReference<GlassHostLayout>? = null
    private var root: ViewGroup? = null
    private var mount: FrameLayout? = null
    private var overlay: FrameLayout? = null
    private val buttons = ArrayList<Button>()
    private val nativeAlphas = WeakHashMap<View, Float>()
    private var observer: ViewTreeObserver? = null
    private var beforeDraw: ViewTreeObserver.OnPreDrawListener? = null
    private var touchButton: Button? = null
    private var selectedPage = -1
    private var mineBackdrop: ((GlassHostLayout) -> Unit)? = null
    private var lastBind = 0L
    private val rootLocation = IntArray(2)
    private val location = IntArray(2)
    private val layerLocation = IntArray(2)
    private val policy = TopBarMaterialPolicy(TopBarRole.ACTION, heightDp = 44f, iconOnly = true, selectedAccent = false)

    fun update(main: GlassHostLayout, page: Int, controls: List<MiHealthHeaderAction>, scene: View?,
        mineBackdrop: ((GlassHostLayout) -> Unit)? = null) {
        owner = WeakReference(main)
        val parent = main.rootView as? ViewGroup ?: return
        if (root !== parent) { dispose(); owner = WeakReference(main); attach(parent) }
        val pageChanged = selectedPage != page
        selectedPage = page
        this.mineBackdrop = mineBackdrop
        val now = android.os.SystemClock.uptimeMillis()
        if (pageChanged || now - lastBind >= 600L) {
            lastBind = now
            val old = buttons.filter { !it.plus && !it.target.isAttachedToWindow }
            old.forEach { it.restore(); overlay?.removeView(it.panel); buttons.remove(it) }
            val expected = if (page == 3) 2 else 1
            // Roles come from the verified fixed-header binding, not arbitrary screen positions.
            if (scene != null && controls.size == expected && controls.all { it.view.isShown && it.view.width > 0 && it.view.height > 0 }) {
                controls.forEach { action ->
                    val view = action.view
                    val button = buttons.firstOrNull { if (action.plus) it.plus else !it.plus && it.target === view && it.page == page }
                        ?: Button(view, page, scene, main.config, action.plus).also { buttons += it; overlay?.addView(it.panel, FrameLayout.LayoutParams(1, 1)) }
                    button.target = view; button.page = page; button.scene = scene
                    button.suppressOldRing()
                    if (button.config != main.config) { button.config = main.config; button.panel.refreshAdapterDerivedConfig() }
                }
            }
            if (pageChanged) overlay?.postDelayed({ writeDiagnostics() }, 1500L)
        }
        updateGeometry()
    }

    private var lastDiagnostic = ""
    private fun writeDiagnostics() {
        val main = owner?.get() ?: return
        val text = buttons.filter { it.active() }.joinToString("\n") { it.diagnostics() }
        if (text.isEmpty() || text == lastDiagnostic) return
        lastDiagnostic = text
        val folder = main.context.getExternalFilesDir(null) ?: return
        Thread({ runCatching { java.io.File(folder, "liquidtab-header-diagnostics.txt").writeText(text) } }, "MiHealthGlassDiagnostics").apply { isDaemon = true }.start()
    }

    private fun attach(parent: ViewGroup) {
        // Same ordinary native container and live RenderNode path as the bottom host.
        // Never mutate RN/Yoga child lists or create an independent Surface.
        val content = parent.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        root = parent
        mount = content
        installTouchObserver()
        owners[parent] = WeakReference(this)
        val layer = object : FrameLayout(parent.context) {
            // Feedback is forwarded by the decor observer. Business input must
            // continue to the native sibling, rather than hit this visual layer.
            override fun dispatchTouchEvent(event: MotionEvent) = false
        }.apply {
            clipChildren = false; clipToPadding = false; isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        content.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = layer
        val listener = ViewTreeObserver.OnPreDrawListener {
            updateGeometry()
            buttons.forEach { button ->
                if (button.active()) {
                    button.suppressOldRing()
                    if (button.scene?.isDirty == true || button.target.isDirty) button.panel.invalidate()
                }
            }
            true
        }
        parent.viewTreeObserver.let { if (it.isAlive) { it.addOnPreDrawListener(listener); observer = it; beforeDraw = listener } }
    }

    private fun updateGeometry() {
        val parent = mount ?: return
        val layer = overlay ?: return
        // Complete pending companion geometry before this frame is submitted.
        if (layer.isLayoutRequested || layer.width != parent.width || layer.height != parent.height) {
            layer.measure(View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.EXACTLY))
            layer.layout(0, 0, parent.width, parent.height)
        }
        layer.getLocationOnScreen(rootLocation)
        buttons.forEach { button ->
            val visible = button.displayed()
            button.updateVisibility(visible)
            // Retain the last valid bounds during fade-out; the native page
            // may already be detached or translated out by its pager.
            if (!visible || !button.active()) return@forEach
            val target = button.target
            target.getLocationOnScreen(location)
            val side = (44f * target.resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val x = location[0] - rootLocation[0] + (target.width - side) / 2
            val y = location[1] - rootLocation[1] + (target.height - side) / 2
            val params = button.panel.layoutParams as FrameLayout.LayoutParams
            if (params.width != side || params.height != side || params.leftMargin != x || params.topMargin != y) {
                params.width = side; params.height = side; params.leftMargin = x; params.topMargin = y
                button.panel.layoutParams = params
                // Overlay has no Yoga or native parent layout transaction to wait for.
                button.panel.measure(View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY))
                button.panel.layout(x, y, x + side, y + side)
                button.panel.invalidate()
            }
        }
    }

    private inner class Button(var target: View, var page: Int, var scene: View?, var config: GlassConfig, val plus: Boolean) {
        private val main get() = owner?.get()
        private var frames = 0
        private var feedbackEvents = 0
        private var feedbackValue = 0f
        private var feedbackGeneration = 0
        private var downAt = 0L
        private val light = IndicatorInteractionLight()
        private val lightBounds = android.graphics.RectF()
        private val lightPath = android.graphics.Path()
        private val feedback: SpringFloat by lazy {
            SpringFloat(0f, 300f, .5f, .001f) { value, _ ->
                feedbackValue = value
                val scale = 1f + (4f / 48f) * value.coerceIn(-.2f, 1.2f)
                panel.scaleX = scale; panel.scaleY = scale
                panel.invalidate()
            }
        }
        fun observeFeedback(event: MotionEvent) {
            feedbackEvents++
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    feedbackGeneration++; downAt = android.os.SystemClock.uptimeMillis()
                    feedback.animateTo(1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val generation = ++feedbackGeneration
                    val delay = if (event.actionMasked == MotionEvent.ACTION_UP)
                        (downAt + 60L - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L) else 0L
                    panel.postDelayed({ if (generation == feedbackGeneration) feedback.animateTo(0f) }, delay)
                    panel.postDelayed({ writeDiagnostics() }, delay + 600L)
                }
            }
        }
        private var shown = false
        var transitioning = false
            private set
        fun updateVisibility(visible: Boolean) {
            if (shown == visible) return
            shown = visible
            panel.animate().cancel()
            if (plus) {
                transitioning = false
                panel.alpha = if (visible) 1f else 0f
                panel.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                return
            }
            transitioning = true
            panel.visibility = View.VISIBLE
            panel.animate().alpha(if (visible) 1f else 0f).setDuration(200L)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction {
                    transitioning = false
                    if (!shown) {
                        panel.visibility = View.INVISIBLE
                    }
                }.start()
        }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        fun suppressOldRing() {
            nativeAlphas.putIfAbsent(target, target.alpha)
            if (target.alpha != 0f) target.alpha = 0f
        }
        fun restore() { feedbackGeneration++; feedback.cancel(); panel.animate().cancel(); nativeAlphas.remove(target)?.let { target.alpha = it } }
        fun diagnostics(): String = "page=$page role=${if (plus) "add" else "message"} target=${target.javaClass.name} scene=${scene?.javaClass?.name} " +
            "usable=${panel.hasUsableBackdrop()} node=${panel.liveBackdropNodeForAdapter.hasDisplayList()} ${panel.adapterOpticalStateForDiagnostics} " +
            "liquid=${panel.config.liquidGlassEnabled} solid=${panel.config.solidBarEnabled} opacity=${panel.config.darkAlpha} blur=${panel.config.blurRadius} " +
            "hostId=${System.identityHashCode(panel)} frames=$frames feedbackEvents=$feedbackEvents feedback=$feedbackValue shown=${panel.isShown} focus=${panel.hasWindowFocus()} root=${panel.rootView.javaClass.name}:${panel.rootView.width}x${panel.rootView.height} bounds=${panel.left},${panel.top},${panel.width},${panel.height}"
        init { suppressOldRing() }
        fun active() = selectedPage == page && target.isShown && target.isAttachedToWindow && scene?.isShown == true
        fun displayed() = active() || (plus && selectedPage in 0..3 && main?.isShown == true)
        private val state = object : AppNavigationState {
            override val allowsSoftwareBackdropCapture = false
            override fun resolveBackdropScene(host: View): View? = scene
            override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View>? = scene?.let { listOf(it) }
            override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = true
            override fun suppressRedrawnSource() = false
            override fun allowsHostTouch() = true
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = displayed() || transitioning
            // The original event continues through the native window exactly once.
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = true
            override fun pauseBackdropWork(host: View) = !active() || !host.hasWindowFocus()
            override fun forceLiveBackdropFrame(selectedIndex: Int) = true
            override fun onLiveBackdropRecorded(selectedIndex: Int) {
                if (page == 3 && active()) mineBackdrop?.invoke(panel)
            }
            override fun resolveAutoDarkMode(host: View): Boolean? = main?.adapterContentOrSystemDark()
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
                if (!displayed() && !transitioning) return
                frames++
                if (frames == 1) panel.postDelayed({ writeDiagnostics() }, 100L)
                lightBounds.set(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
                lightPath.setBottomBarSquircle(lightBounds, frame.height / 2f, config.cornerSmoothing)
                val lighting = canvas.save()
                canvas.clipPath(lightPath)
                light.inner(canvas, lightBounds, feedbackValue.coerceIn(0f, 1f))
                canvas.restoreToCount(lighting)
                if (plus) {
                    ink.color = if (frame.darkGlass) Color.WHITE else Color.BLACK
                    ink.strokeWidth = 2f * frame.density
                    val radius = 9f * frame.density
                    val cx = frame.width / 2f; val cy = frame.height / 2f
                    canvas.drawLine(cx - radius, cy, cx + radius, cy, ink)
                    canvas.drawLine(cx, cy - radius, cx, cy + radius, ink)
                } else {
                    val save = canvas.save()
                    canvas.translate((frame.width - target.width) / 2f, (frame.height - target.height) / 2f)
                    target.draw(canvas)
                    canvas.restoreToCount(save)
                }
            }
        }
        private val adapter = object : TargetAdapter {
            override val key = "mi-health-header-action"
            override val actionOnlyIndices = setOf(0)
            override val indicatorInteractionPolicy get() = policy.indicatorInteractionPolicy
            override val pressedIndicatorScale = 1f + 4f / 48f
            override val ownsNavigationDrawing = true
            override val ownsNavigationTap = true
            override val managesHostSiblingOrder = true
            override val skipNavigationSnapshot = true
            override val allowsSystemBackgroundBlur = false
            override val reuseOpticalBlur = true
            override val ownsHostLayout = true
            override val allowRenderNodeRefresh = true
            override val skipBackdropStableDelay = true
            override val outerPanelPressScale = 1f + 4f / 48f
            private val global = Matrix()
            override fun normalizeConfig(config: GlassConfig) = policy.apply(this@Button.config)
            override fun createNavigationState(context: Context) = state
            override fun liveBackdropTransform(parent: View, host: View, out: Matrix): Boolean {
                out.reset(); global.reset()
                parent.transformMatrixToGlobal(out); host.transformMatrixToGlobal(global)
                if (!global.invert(global)) return false
                out.setConcat(global, out); return true
            }
        }
        val panel: GlassHostLayout = GlassHostLayout(target.context, policy.apply(config), 1,
            navigationSource = null, sliderEnabled = false, adapterOverride = adapter).apply {
            isClickable = true; isFocusable = false; elevation = 0f
            alpha = 0f; visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
    }

    private fun observeTouch(event: MotionEvent) {
        val layer = overlay ?: return
        val decor = root ?: return
        decor.getLocationOnScreen(location)
        layer.getLocationOnScreen(layerLocation)
        val x = event.x + location[0] - layerLocation[0]
        val y = event.y + location[1] - layerLocation[1]
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchButton = buttons.firstOrNull { button ->
                val panel = button.panel
                button.active() && x >= panel.left && x < panel.right &&
                    y >= panel.top && y < panel.bottom
            }
        }
        val button = touchButton ?: return
        val copy = MotionEvent.obtain(event)
        copy.offsetLocation(x - event.x - button.panel.left, y - event.y - button.panel.top)
        try { button.observeFeedback(copy) } finally { copy.recycle() }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) touchButton = null
    }

    companion object {
        private val owners = WeakHashMap<ViewGroup, WeakReference<MiHealthHeaderGlass>>()
        private var touchHookInstalled = false
        private fun installTouchObserver() {
            if (touchHookInstalled) return
            XposedBridge.hookMethod(ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = owners[param.thisObject as? ViewGroup]?.get() ?: return
                    val event = param.args.firstOrNull() as? MotionEvent ?: return
                    runCatching { owner.observeTouch(event) }
                }
            })
            touchHookInstalled = true
        }
    }

    fun dispose() {
        val listener = beforeDraw
        observer?.let { if (it.isAlive && listener != null) it.removeOnPreDrawListener(listener) }
        beforeDraw = null; observer = null
        root?.let { owners.remove(it) }
        touchButton = null
        buttons.forEach { it.restore() }
        nativeAlphas.forEach { (view, alpha) -> view.alpha = alpha }
        nativeAlphas.clear()
        overlay?.removeAllViews()
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        buttons.clear(); overlay = null; mount = null; root = null; mineBackdrop = null; selectedPage = -1; lastBind = 0L
    }
}

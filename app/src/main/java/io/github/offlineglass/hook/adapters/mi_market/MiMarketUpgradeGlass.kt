package io.github.offlineglass.hook.adapters.mi_market


import android.app.Activity
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.ProgressBar
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.hook.adapters.*
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Native update controls keep their listeners, visibility, text and download state.
 * Only their material is replaced; the window compositor supplies the backdrop. */
internal class MiMarketUpgradeGlass private constructor(private val activity: Activity) :
    ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener, ViewTreeObserver.OnScrollChangedListener {
    companion object {
        private val owners = WeakHashMap<Activity, MiMarketUpgradeGlass>()
        private val materialOwners = WeakHashMap<View, java.lang.ref.WeakReference<MiMarketUpgradeGlass>>()
        private val sourceOwners = WeakHashMap<View, java.lang.ref.WeakReference<MiMarketUpgradeGlass>>()
        private val layoutOwners = WeakHashMap<View, java.lang.ref.WeakReference<MiMarketUpgradeGlass>>()
        fun install() {
            XposedBridge.hookAllMethods(View::class.java, "setLayoutParams", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val owner = layoutOwners[view]?.get()?.takeIf { it.guardingMaterial() } ?: return
                    val native = param.args[0] as? ViewGroup.MarginLayoutParams ?: return
                    val above = (native as? android.widget.RelativeLayout.LayoutParams)
                        ?.getRule(android.widget.RelativeLayout.ABOVE) ?: 0
                    if (native.bottomMargin == 0 && above == 0) return
                    // UpdateAllViewController.checkForUpdateAll writes 60dp on
                    // every state callback. Intercept before measure/layout;
                    // undoing it in preDraw already lets one shortened feed frame
                    // reach the window Surface and the optical readback.
                    owner.oldParams[view] = owner.copyParams(native)
                    val replacement = owner.copyParams(native) as ViewGroup.MarginLayoutParams
                    replacement.bottomMargin = 0
                    (replacement as? android.widget.RelativeLayout.LayoutParams)
                        ?.removeRule(android.widget.RelativeLayout.ABOVE)
                    param.args[0] = replacement
                }
            })
            // Native state/skin transitions can write material after our pre-draw
            // listener. Guard only the exact footer views claimed by this adapter.
            XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val owner = materialOwners[view]?.get()?.takeIf { it.guardingMaterial() } ?: return
                    (param.args[0] as? Drawable)?.let { owner.backgrounds[view] = it }
                    param.args[0] = null
                }
            })
            for (method in arrayOf("setAlpha")) {
                XposedBridge.hookAllMethods(View::class.java, method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val owner = sourceOwners[view]?.get()?.takeIf { it.guardingMaterial() } ?: return
                        if ((param.args[0] as? Float) != 0f) owner.alphas[view] = param.args[0] as Float
                        param.args[0] = 0f
                    }
                })
            }
            XposedBridge.hookAllMethods(ProgressBar::class.java, "setProgressDrawable", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? ProgressBar ?: return
                    val owner = sourceOwners[view]?.get()?.takeIf { it.guardingMaterial() } ?: return
                    if (param.args[0] !== owner.transparentProgress) {
                        owner.progressDrawables[view] = param.args[0] as? Drawable
                        param.args[0] = owner.transparentProgress
                    }
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name != "com.xiaomi.market.ui.UpdateListActivity") return
                    if (!owners.containsKey(activity)) {
                        owners[activity] = MiMarketUpgradeGlass(activity).also { it.attach() }
                    }
                }
            })
        }
    }
    private val root get() = activity.findViewById<ViewGroup>(android.R.id.content)
    private fun guardingMaterial() = !disposing && panel?.config?.enabled != false
    private var panel: GlassHostLayout? = null
    private val backdrop = MiMarketNavigationState(activity)
    private val backgrounds = WeakHashMap<View, Drawable?>()
    private val footerForegrounds = WeakHashMap<View, Drawable>()
    private val alphas = WeakHashMap<View, Float>()
    private val progressDrawables = WeakHashMap<ProgressBar, Drawable?>()
    private val oldParams = WeakHashMap<View, ViewGroup.LayoutParams>()
    private val translations = WeakHashMap<View, Pair<Float, Float>>()
    private val clipping = WeakHashMap<ViewGroup, Boolean>()
    private val colors = WeakHashMap<Drawable, Int>()
    private val padding = WeakHashMap<ViewGroup, IntArray>()
    private var target: View? = null
    private var footerVisible = false
    private val position = IntArray(2)
    private val rootPosition = IntArray(2)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val transparentProgress = ColorDrawable(Color.TRANSPARENT)
    private val clip = Path()
    private val textInk = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ambientInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private val ambientPath = Path()
    // Same feathered circular front and longitudinal fade as Spotify's player.
    private val progressShader = RuntimeShader("""
        uniform float2 size;
        uniform float progress;
        layout(color) uniform half4 theme;
        half4 main(float2 p) {
            float h = max(size.y, 1.0);
            float radius = h * 0.5;
            float played = size.x * progress;
            float y = clamp(p.y - radius, -radius, radius);
            float arc = sqrt(max(radius * radius - y * y, 0.0));
            float distance = p.x - (played - radius + arc);
            float mask = 1.0 - smoothstep(-h * 0.4, h * 0.7, distance);
            mask = mix(mask, 1.0, smoothstep(0.97, 1.0, progress));
            mask *= smoothstep(0.0, radius * 0.3, played);
            float along = clamp(p.x / max(played, 1.0), 0.0, 1.0);
            float light = mix(0.85, 0.18, smoothstep(0.0, 1.0, along));
            half alpha = theme.a * mask * light;
            return half4(theme.rgb * alpha, alpha);
        }
    """.trimIndent())
    private val progressInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = progressShader }
    private var motionUntil = 0L
    private val views = HashMap<String, java.lang.ref.WeakReference<View>>()
    private var listRoot: View? = null
    private val scrollers = ArrayList<java.lang.ref.WeakReference<ViewGroup>>()
    private var signature = ""
    private var ready = false
    private var dirty = true
    private var disposing = false
    private var geometryEpoch = 0L
    private var geometryKey = ""
    private val copyBarrier = MiMarketFrameBarrier()
    private val handler = Handler(Looper.getMainLooper())
    private fun id(name: String) = activity.resources.getIdentifier(name, "id", "com.xiaomi.market")
    private fun find(name: String): View? {
        views[name]?.get()?.takeIf { it.isAttachedToWindow }?.let { return it }
        return id(name).takeIf { it != 0 }?.let { root.findViewById<View>(it) }?.also {
            views[name] = java.lang.ref.WeakReference(it)
        }
    }
    private fun attach() {
        root.viewTreeObserver.addOnPreDrawListener(this)
        root.viewTreeObserver.addOnScrollChangedListener(this)
        root.addOnAttachStateChangeListener(this)
    }
    override fun onScrollChanged() {
        motionUntil = android.os.SystemClock.uptimeMillis() + 250L
        panel?.let { glass ->
            if (!disposing && glass.isShown) {
                glass.enableOpticalSurfaceRendererForAdapter()
            }
        }
    }
    private fun clearBackground(view: View?) {
        view ?: return
        if (!backgrounds.containsKey(view)) backgrounds[view] = view.background
        materialOwners[view] = java.lang.ref.WeakReference(this)
        if (view.background != null) view.background = null
    }

    private fun clearFooterDecoration(view: View?) {
        view ?: return
        clearBackground(view)
        view.foreground?.let {
            footerForegrounds[view] = it
            view.foreground = null
        }
    }
    override fun onPreDraw(): Boolean {
        if (disposing) return true
        val config = panel?.config ?: HookConfigReader.read(activity, "com.xiaomi.market")
        if (!config.enabled) { footerVisible = false; restore(); panel?.visibility = View.GONE; return true }
        val progress = find("update_all_progress")
        val button = find("update_all_button")
        // The controller owns update_all_panel (mNoUpdateHideView). Its outer
        // visibility frame can remain VISIBLE after all updates have completed.
        val footerPanel = find("update_all_panel")
        val footerFrame = find("update_all_visibility_frame")
        footerVisible = footerPanel?.isShown == true && footerFrame?.isShown == true
        // The controller hides one native child before showing the other.
        // Preserve the optical surface through that handoff; the footer owns
        // visibility, not either replaceable source control.
        val next = progress?.takeIf { it.isShown } ?: button?.takeIf { it.isShown }
            ?: target?.takeIf { footerVisible && it.isAttachedToWindow }
        if (next == null || !footerVisible || root.width <= 0 || root.height <= 0) {
            panel?.let { it.alpha = 0f; it.visibility = View.GONE; it.syncOpticalVisibilityCompanions() }
            if (!footerVisible) { target = null; signature = "" }
            return true
        }
        val rebound = panel == null || panel?.visibility == View.GONE
        val sourceChanged = target !== next
        target = next
        if (sourceChanged) dirty = true
        if (rebound) { ready = false; dirty = true }
        // One physical-screen inset on all three exposed sides. Translate the
        // native hit target as well, rather than leaving an invisible old button.
        val gap = (30f * activity.resources.displayMetrics.density).toInt()
        val buttonHeight = (50f * activity.resources.displayMetrics.density).toInt()
        root.getLocationInWindow(rootPosition)
        for (view in listOfNotNull(button, progress)) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            if (!oldParams.containsKey(view)) oldParams[view] = copyParams(params)
            val width = (root.width - gap*2).coerceAtLeast(1)
            if (params.width != width || params.height != buttonHeight || params.leftMargin != 0 || params.rightMargin != 0) {
                if (params.height != buttonHeight && view is ProgressBar) {
                    // Market caches its text baseline after the first draw.
                    // Recompute it for the new native control height.
                    runCatching { view.javaClass.getDeclaredField("normalTextY").apply {
                        isAccessible = true
                    }.setFloat(view, -1f) }
                }
                params.width = width; params.height = buttonHeight; params.leftMargin = 0; params.rightMargin = 0
                view.layoutParams = params
            }
            translations.getOrPut(view) { view.translationX to view.translationY }
            view.getLocationInWindow(position)
            view.translationX += rootPosition[0] + gap - position[0]
            view.translationY += rootPosition[1] + root.height - gap - view.height - position[1]
            var ancestor = view.parent as? ViewGroup
            while (ancestor != null) {
                clipping.getOrPut(ancestor) { ancestor.clipChildren }
                ancestor.clipChildren = false
                if (ancestor === root) break
                ancestor = ancestor.parent as? ViewGroup
            }
        }
        clearFooterDecoration(footerPanel)
        clearFooterDecoration(footerFrame)
        // The legacy upgrade layout puts bottom_tab_bg_div on an unnamed
        // LinearLayout between these two semantic containers. The V2 panel
        // is a direct child and owns its background itself.
        val footerShell = footerPanel?.parent as? android.widget.LinearLayout
        if (footerShell != null && footerShell.parent === footerFrame) {
            clearFooterDecoration(footerShell)
        }
        clearBackground(button); clearBackground(progress)
        for (view in listOfNotNull(button, progress)) {
            if (!alphas.containsKey(view)) alphas[view] = view.alpha
            sourceOwners[view] = java.lang.ref.WeakReference(this)
            if (view.alpha != 0f) view.alpha = 0f
        }
        (progress as? ProgressBar)?.let {
            if (!progressDrawables.containsKey(it)) progressDrawables[it] = it.progressDrawable
            if (it.progressDrawable !== transparentProgress) it.progressDrawable = transparentProgress
        }
        // The native list frame reserves the footer height. Remove that reservation;
        // preserve the list's native padding.
        find("list_view_marge_bottom_frame")?.let { list ->
            layoutOwners[list] = java.lang.ref.WeakReference(this)
            val p = list.layoutParams
            if (p is ViewGroup.MarginLayoutParams && p.bottomMargin != 0) {
                if (!oldParams.containsKey(list)) oldParams[list] = copyParams(p)
                p.bottomMargin = 0; list.layoutParams = p
            }
            if (p is android.widget.RelativeLayout.LayoutParams && p.getRule(android.widget.RelativeLayout.ABOVE) != 0) {
                if (!oldParams.containsKey(list)) oldParams[list] = copyParams(p)
                p.removeRule(android.widget.RelativeLayout.ABOVE); list.layoutParams = p
            }
            fun collect(view: View) {
                if (view is ViewGroup && (view.javaClass.name.contains("RecyclerView") || view is android.widget.ListView)) {
                    scrollers.add(java.lang.ref.WeakReference(view))
                } else if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
            if (listRoot !== list || scrollers.none { it.get()?.isAttachedToWindow == true }) {
                listRoot = list; scrollers.clear(); collect(list)
            }
            for (reference in scrollers) reference.get()?.let { view ->
                    val old = padding.getOrPut(view) { intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom, if(view.clipToPadding) 1 else 0) }
                    // Avoid adding an empty footer-sized band to the viewport.
                    val bottom = old[3]
                    if (view.paddingBottom != bottom) view.setPadding(old[0], old[1], old[2], bottom)
                    view.clipToPadding = false
            }
        }
        val glass = panel ?: GlassHostLayout(activity, config, 1, nativeSelectionReliable = true,
            navigationSource = null, sliderEnabled = false, adapterOverride = MaterialAdapter()).also {
            panel = it
            it.forceHasOverlappingRendering(false)
            it.isClickable = false
            root.addView(it, FrameLayout.LayoutParams(1, 1))
        }
        // Never resize the Surface to a newly visible native child's stale
        // measured dimensions. Both native states share this fixed geometry.
        val x = gap
        val y = root.height - gap - buttonHeight
        val width = (root.width - gap*2).coerceAtLeast(1)
        val newGeometry = "$x:$y:$width:$buttonHeight"
        if (geometryKey != newGeometry) {
            geometryKey = newGeometry
            geometryEpoch++
            copyBarrier.reset()
            dirty = true
        }
        val p = glass.layoutParams as FrameLayout.LayoutParams
        if (p.width != width || p.height != buttonHeight || p.leftMargin != x || p.topMargin != y) {
            p.width = width; p.height = buttonHeight; p.gravity = Gravity.TOP or Gravity.LEFT
            p.leftMargin = x; p.topMargin = y; glass.layoutParams = p
        }
        glass.visibility = View.VISIBLE
        glass.alpha = 1f
        glass.syncOpticalVisibilityCompanions()
        if (rebound) {
            backdrop.invalidateBackdropHost(glass)
            glass.enableOpticalSurfaceRendererForAdapter()
            glass.postInvalidateOnAnimation()
        }
        val state = (next as? ProgressBar)?.let { "${it.progress}:${it.max}:${field(it,"progressText")}:${field(it,"defaultColor")}" }
        val key = "${System.identityHashCode(next)}:$state:${(next as? android.widget.TextView)?.text}:${next.isPressed}:${next.isEnabled}"
        // Hidden native views are not drawn by their parent, so their dirty flag
        // need not clear. Use semantic changes rather than redrawing forever.
        if (key != signature) { signature = key; dirty = true; glass.postInvalidateOnAnimation() }
        return true
    }
    private fun copyParams(p: ViewGroup.MarginLayoutParams): ViewGroup.LayoutParams = when(p) {
        is android.widget.RelativeLayout.LayoutParams -> android.widget.RelativeLayout.LayoutParams(p)
        is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(p)
        is android.widget.LinearLayout.LayoutParams -> android.widget.LinearLayout.LayoutParams(p)
        else -> ViewGroup.MarginLayoutParams(p)
    }
    private val fields = HashMap<Pair<Class<*>, String>, java.lang.reflect.Field?>()
    private fun field(view: View, name: String): Any? = runCatching {
        fields.getOrPut(view.javaClass to name) {
            runCatching { view.javaClass.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
        }?.get(view)
    }.getOrNull()
    private fun nativeColor(view: View): Int {
        (field(view, "defaultColor") as? Int)?.let { return it }
        val original = backgrounds[view] ?: return 0xFF00A878.toInt()
        colors[original]?.let { return it }
        val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        val bounds = Rect(original.bounds)
        original.setBounds(0, 0, 3, 3); original.draw(Canvas(bitmap)); original.bounds = bounds
        val color = bitmap.getPixel(1, 1); bitmap.recycle()
        colors[original] = color
        return color
    }
    private inner class State : AppNavigationState by backdrop {
        override val readyForDrawing = true
        override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) = Unit
        override fun refreshNavigationSource(host: View) = false
        override fun retainNavigationWhenNativeRowHidden(selectedIndex: Int) = true
        override fun allowsOpticalSurfacePipeline() = false
        override fun maintainsCompositorSamplingWithoutSurface() = false
        override fun resolveBackdropScene(host: View): View? = listRoot?.takeIf(::isSafeMarketBackdrop)
        override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> =
            listOfNotNull(listRoot?.takeIf(::isSafeMarketBackdrop))
        override fun skipLiveBackdrop(selectedIndex: Int) = listRoot?.let(::isSafeMarketBackdrop) != true
        override fun contentMotionHoldUntil() = motionUntil
        // PixelCopy callbacks already run on UI: publish and render in that same
        // callback instead of waiting for another traversal of a busy update list.
        override fun deferOpticalCopyRenderToUi() = false
        // The shared vsync probe is the only copy driver. A callback must not
        // start a free-running copy loop or overwrite a buffer still being drawn.
        override fun overlapsOpticalCopyAndRender() = false
        override fun onOpticalSurfaceCopyCompleted(root: View, nextCopy: () -> Unit) = Unit
        override fun opticalCaptureEpoch() = geometryEpoch
        override fun scheduleOpticalSurfaceCopy(root: View, copy: () -> Unit) =
            copyBarrier.schedule(root, geometryEpoch, { geometryEpoch }, copy)
        override fun drawAdditionalAmbientShadow(canvas: Canvas, dark: Boolean, alphaScale: Float) {
            val glass = panel ?: return
            val density = activity.resources.displayMetrics.density
            val rect = RectF(0f, 0f, glass.width.toFloat(), glass.height.toFloat())
            ambientPath.setBottomBarSquircle(rect, rect.height()/2f, glass.config.cornerSmoothing)
            val save = canvas.save()
            canvas.clipOutPath(ambientPath)
            ambientInk.setShadowLayer(12f*density, 0f, 0f,
                if (dark) Color.argb((40*alphaScale).toInt().coerceIn(0,255), 220, 245, 237)
                else Color.argb((46*alphaScale).toInt().coerceIn(0,255), 0, 0, 0))
            canvas.drawPath(ambientPath, ambientInk)
            canvas.restoreToCount(save)
        }
        override fun pauseBackdropWork(host: View) = !footerVisible
        override fun pauseOpticalSurfaceProbe() = !footerVisible
        override fun onHostWindowFocusChanged(hasFocus: Boolean) {
            if (hasFocus) { ready = false; dirty = true; panel?.let { backdrop.invalidateBackdropHost(it); it.postInvalidateOnAnimation() } }
        }
        override fun onHostFrame(host: View) {
            // Native update/pause/completion swaps may stop the probe while the
            // Surface itself survives. Re-arm its coalesced probe on traversal;
            // policy equality alone cannot tell whether sampling is running.
            val glass = host as? GlassHostLayout ?: return
            if (footerVisible && allowsOpticalSurfacePipeline() && !glass.opticalSurfaceRunningForAdapter) {
                glass.enableOpticalSurfaceRendererForAdapter()
            }
        }
        override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
            density: Float, barHeightPx: Int, surfaceColor: () -> Int) = Unit
        override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = 0
        override fun suppressRedrawnSource() = false
        override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = footerVisible
        override fun opticalSurfaceVisibleDuringTransition(hostVisibility: Int, hostAlpha: Float) =
            footerVisible && hostVisibility == View.VISIBLE && hostAlpha > 0f
        override fun allowsHostTouch() = false // Touch goes directly to the unmodified native control.
        override fun drawsNavigationContent() = true
        override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = true
        override fun onOpticalBackdropChanged() {
            backdrop.onOpticalBackdropChanged()
            dirty = true
            // The shared copy callback submits this published frame immediately.
        }
        override fun onOpticalBackdropCaptured(barSource: Rect?) = Unit
        override fun onOpticalSurfaceFrameInvalidated() {
            ready = false; dirty = true; geometryEpoch++; copyBarrier.reset()
            backdrop.onOpticalSurfaceFrameInvalidated()
        }
        override fun onOpticalRendererConfigurationChanged() { ready = false; dirty = true; panel?.let(backdrop::invalidateBackdropHost) }
        override fun onOpticalSurfaceFrameRendered(): Boolean { val first = !ready; ready = true; dirty = false; return first }
        override fun shouldQueueOpticalRenderFromUi(animating: Boolean): Boolean? = if (animating || dirty || !ready) null else false
        override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
            val view = target ?: return
            val config = panel?.config ?: return
            val rect = RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
            clip.setBottomBarSquircle(rect, rect.height()/2f, config.cornerSmoothing)
            val save = canvas.save(); canvas.clipPath(clip)
            val color = nativeColor(view)
            ink.color = config.withSurfaceOpacity(color)
            ink.alpha = (ink.alpha * if(view is ProgressBar) .20f else 1f).toInt()
            canvas.drawRect(rect, ink)
            if (view is ProgressBar && view.max > 0) {
                val fraction = (view.progress.toFloat() / view.max).coerceIn(0f, 1f)
                progressShader.setFloatUniform("size", rect.width(), rect.height())
                progressShader.setFloatUniform("progress", fraction)
                progressShader.setColorUniform("theme", config.withSurfaceOpacity(color))
                canvas.drawRect(rect, progressInk)
            }
            // Read native semantics without replaying its alpha-zero RenderNode
            // or its cached baseline (which belongs to the original height).
            val textView = view as? android.widget.TextView
            val nativePaint = (field(view, "paint") as? Paint) ?: textView?.paint
            val text = (field(view, "progressText") as? String) ?: textView?.text?.toString().orEmpty()
            if (nativePaint != null && text.isNotEmpty()) {
                textInk.set(nativePaint)
                textInk.shader = null
                textInk.alpha = 255
                textInk.textAlign = Paint.Align.CENTER
                val baseline = rect.centerY() - (textInk.ascent() + textInk.descent())/2f
                textInk.color = textView?.currentTextColor ?: color
                canvas.drawText(text, rect.centerX(), baseline, textInk)
                if (view is ProgressBar && view.max > 0) {
                    val end = rect.width() * (view.progress.toFloat()/view.max).coerceIn(0f,1f)
                    val feather = rect.height() * .2f
                    textInk.shader = LinearGradient(end-feather, 0f, end+feather, 0f,
                        (field(view, "changeColor") as? Int) ?: Color.WHITE, color, Shader.TileMode.CLAMP)
                    canvas.drawText(text, rect.centerX(), baseline, textInk)
                    textInk.shader = null
                }
            }
            canvas.restoreToCount(save)
        }
    }
    private inner class MaterialAdapter : TargetAdapter {
        override val key = "mi-market-upgrade"
        override val ownsNavigationDrawing = true
        override val ownsNavigationTap = true
        override val skipNavigationSnapshot = true
        override val managesNativeTabVisibility = true
        override val managesHostSiblingOrder = true
        override val ownsHostLayout = true
        override val ownsOpticalSurfaceLayout = true
        override val usesDirectOpticalBackdropMode = true
        override val usesOpticalSurfacePipeline = true
        override val countsOpticalCompositorCopies = true
        override val keepsOpticalSurfaceDuringBarAnimation = true
        override val deferCompanionRemovalOnDetach = true
        override val allowsSystemBackgroundBlur = false
        override val reuseOpticalBlur = true
        override val reuseSurfaceBlurDuringBarAnimation = true
        override val releaseOutlineClipping = true
        override val observeWindowTouches = true
        override fun onWindowTouch(host: View, event: MotionEvent) {
            motionUntil = android.os.SystemClock.uptimeMillis() +
                if (event.actionMasked == MotionEvent.ACTION_UP) 1800L else 300L
        }
        override val opticalSurfaceOverflowDp = 48f
        override fun surfaceOpacityScale(config: GlassConfig) = 0f // Native green only, no theme-white/black tint.
        override fun normalizeConfig(config: GlassConfig) = config.copy(hiddenMask = 0, detachedPublish = false, cornerRadiusPercent = 50f)
        override fun createNavigationState(context: android.content.Context): AppNavigationState = State()
        override fun opticalSurfaceLayout(contentWidth: Int, contentHeight: Int, hostLeft: Int, hostTop: Int,
            hostWidth: Int, hostHeight: Int, overflow: Int) = OpticalSurfaceLayout(hostWidth+overflow*2,
            hostHeight+overflow*2, hostLeft-overflow, hostTop-overflow, 0, 0, Gravity.TOP or Gravity.LEFT)
        override fun opticalCaptureGeometry(rootWidth: Int, rootHeight: Int, left: Int, top: Int,
            width: Int, height: Int, padding: Int): OpticalCaptureGeometry? {
            val full = MiMarketAdapter.opticalCaptureGeometry(rootWidth,rootHeight,left,top,width,height,padding) ?: return null
            // Background only: half-resolution readback quarters transfer and
            // bitmap comparison work. Glass geometry and text stay full-size.
            val w = (full.targetWidth + 1)/2
            val h = (full.targetHeight + 1)/2
            return OpticalCaptureGeometry(full.sourceRect, w, h, Rect(0,0,w,h))
        }
    }
    private fun restore() {
        // Unregister before restoring, so our own guard cannot swallow restoration.
        backgrounds.keys.forEach { materialOwners.remove(it) }
        alphas.keys.forEach { sourceOwners.remove(it) }
        oldParams.keys.forEach { layoutOwners.remove(it) }
        backgrounds.forEach { (v, bg) -> v.background = bg }; backgrounds.clear()
        footerForegrounds.forEach { (view, drawable) -> view.foreground = drawable }
        footerForegrounds.clear()
        alphas.forEach { (v, a) -> v.alpha = a }; alphas.clear()
        progressDrawables.forEach { (v, d) -> v.progressDrawable = d }; progressDrawables.clear()
        oldParams.forEach { (v, p) -> v.layoutParams = p }; oldParams.clear()
        translations.forEach { (v, p) -> v.translationX = p.first; v.translationY = p.second }; translations.clear()
        clipping.forEach { (v, clipped) -> v.clipChildren = clipped }; clipping.clear()
        padding.forEach { (v, p) -> v.setPadding(p[0],p[1],p[2],p[3]); v.clipToPadding = p[4] != 0 }; padding.clear()
    }
    override fun onViewAttachedToWindow(v: View) = Unit
    override fun onViewDetachedFromWindow(v: View) {
        disposing = true
        v.viewTreeObserver.removeOnPreDrawListener(this); v.removeOnAttachStateChangeListener(this)
        v.viewTreeObserver.removeOnScrollChangedListener(this)
        copyBarrier.reset()
        owners.remove(activity)
        handler.post { restore(); panel?.let { (it.parent as? ViewGroup)?.removeView(it) }; panel = null
            views.clear(); scrollers.clear(); listRoot = null; backdrop.dispose() }
    }
}

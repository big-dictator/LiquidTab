package io.github.offlineglass.hook.adapters.bilibili

import io.github.offlineglass.rendering.setFloatUniformExact

import android.animation.ValueAnimator
import android.app.Activity
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.*
import io.github.offlineglass.hook.compat.*
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.NativeBackdropBlur
import io.github.offlineglass.rendering.PostureGlass
import java.util.WeakHashMap

/** Shared skin for the native floating playlist entry in both Bilibili editions. */
internal object BilibiliPlaylistGlassHooks {
    private const val PAGE = "com.bilibili.ship.theseus.playlist.UnitedPlaylistActivity"
    private val owners = WeakHashMap<Activity, Owner>()
    fun install(packageName: String = "tv.danmaku.bili") {
        for (event in listOf("onPostResume", "onWindowFocusChanged", "onStop", "onDestroy")) {
            XposedBridge.hookAllMethods(Activity::class.java, event, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.packageName != packageName || activity.javaClass.name != PAGE) return
                    when (event) {
                        "onStop", "onDestroy" -> owners.remove(activity)?.stop()
                        else -> if (param.args.firstOrNull() != false &&
                            HookConfigReader.startupSnapshot(packageName)?.enabled == true
                        ) owners.getOrPut(activity) { Owner(activity) }.start()
                    }
                }
            })
        }
        XposedBridge.hookAllMethods(View::class.java, "setSystemUiVisibility", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (owners.values.any { it.ownsBottomInsets && it.root === param.thisObject })
                    param.args[0] = (param.args[0] as Int) or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                owners[param.thisObject as? Activity]?.skin?.touch(param.args.firstOrNull() as? MotionEvent ?: return)
            }
        })
    }
    private class Owner(val activity: Activity) : ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener {
        val root = activity.window.decorView as ViewGroup
        var active = false
        val ownsBottomInsets: Boolean get() = active && button != null
        var skin: Skin? = null
        private var button: View? = null
        private var margin = 0
        private var nativeTranslationX = 0f
        private var introPage: View? = null
        private var introDirty = true
        private var binding: Binding? = null
        private var bindingDirty = true
        private var navigationBackground: View? = null
        private var navigationBackgroundAlpha = 1f
        private var navigationBackgroundDirty = true
        private var statusTop = -1
        private val backgrounds = WeakHashMap<View, Drawable?>()
        private val textColors = WeakHashMap<android.widget.TextView, android.content.res.ColorStateList>()
        private val iconFilters = WeakHashMap<android.widget.ImageView, ColorFilter?>()
        private val white = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        private var backing: View? = null
        private var backingAlpha = 1f
        private val flags = root.systemUiVisibility
        private val navColor = activity.window.navigationBarColor
        private val navDivider = activity.window.navigationBarDividerColor
        private val contrast = activity.window.isNavigationBarContrastEnforced
        private var originalPadding: IntArray? = null
        private data class Binding(val host: ViewGroup, val pager: ViewGroup, val backing: View,
            val title: ViewGroup, val total: android.widget.TextView, val arrow: android.widget.ImageView)
        private val overlay = object : Drawable() {
            override fun draw(canvas: Canvas) { skin?.shadow(canvas, root) }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(filter: ColorFilter?) = Unit
            @Deprecated("Deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        private fun find(name: String, scope: View = root): View? =
            scope.findViewById(root.resources.getIdentifier(name, "id", activity.packageName))
        private fun bind(): Binding? {
            binding?.let {
                if (it.host.isAttachedToWindow && it.pager.isAttachedToWindow && it.backing.parent === it.host.parent &&
                    it.title.parent === it.host && it.total.isAttachedToWindow && it.arrow.isAttachedToWindow
                ) return it
            }
            if (!bindingDirty) return null
            bindingDirty = false
            val host = find("floating_title") as? ViewGroup ?: return null
            val pager = find("pager") as? ViewGroup ?: return null
            val backing = find("bg_floating") ?: return null
            val title = find("title_layout", host) as? ViewGroup ?: return null
            val total = find("total", title) as? android.widget.TextView ?: return null
            val arrow = find("arrow", title) as? android.widget.ImageView ?: return null
            if (backing.parent !== host.parent || title.parent !== host ||
                total.parent !== arrow.parent
            ) return null
            // The sampled pager must be independent of the floating entry.
            fun ancestor(view: View, of: View): Boolean {
                var current = of.parent
                while (current is View) {
                    if (current === view) return true
                    current = current.parent
                }
                return false
            }
            if (host === pager || ancestor(pager, host) || ancestor(host, pager)) return null
            return Binding(host, pager, backing, title, total, arrow).also { binding = it }
        }
        fun start() {
            if (!active) {
                active = true
                root.viewTreeObserver.addOnGlobalLayoutListener(this)
                root.viewTreeObserver.addOnPreDrawListener(this)
                root.overlay.add(overlay)
            }
            bindingDirty = true
            root.invalidate()
        }
        override fun onGlobalLayout() {
            bindingDirty = true
            introDirty = true
            navigationBackgroundDirty = true
        }
        override fun onPreDraw(): Boolean {
            if (!root.hasWindowFocus()) return true
            if (activity.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT ||
                root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true ||
                BilibiliDetailWindowMode.windowed(activity)
            ) { restore(); restoreWindow(); return true }
            val bound = bind() ?: run { restore(); restoreWindow(); return true }
            val host = bound.host
            val source = bound.pager
            if (button !== host || skin?.pager !== source) {
                restore()
                button = host
                margin = (host.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                nativeTranslationX = host.translationX
                skin = Skin(host, source, activity.packageName) { overlay.invalidateSelf() }
            }
            val content = activity.findViewById<View>(android.R.id.content) ?: return true
            val location = IntArray(2); content.getLocationInWindow(location)
            val topInset = root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
            if (statusTop < 0) statusTop = maxOf(location[1] + content.paddingTop, topInset)
            else statusTop = maxOf(statusTop, topInset)
            if (originalPadding == null) originalPadding = intArrayOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
            val gap = root.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())?.bottom ?: 0
            val targetFlags = root.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            if (root.systemUiVisibility != targetFlags) root.systemUiVisibility = targetFlags
            if (activity.window.navigationBarColor != Color.TRANSPARENT) activity.window.navigationBarColor = Color.TRANSPARENT
            if (activity.window.navigationBarDividerColor != Color.TRANSPARENT) activity.window.navigationBarDividerColor = Color.TRANSPARENT
            if (activity.window.isNavigationBarContrastEnforced) activity.window.isNavigationBarContrastEnforced = false
            if (activity.packageName == "tv.danmaku.bili") runCatching {
                val stub = XposedHelpers.callMethod(root, "getDecorViewImmersiveStub")
                if (stub != null && XposedHelpers.callMethod(stub, "isForceImmersive") != true) {
                    XposedHelpers.callMethod(stub, "setForceImmersiveNavBar", true)
                    XposedHelpers.callMethod(root, "updateColorViews", null, false)
                    root.requestApplyInsets()
                }
            }
            if (activity.packageName != "tv.danmaku.bili") {
                if (navigationBackgroundDirty) {
                    navigationBackgroundDirty = false
                    val id = root.resources.getIdentifier("navigationBarBackground", "id", "android")
                    val background = if (id != 0) root.findViewById<View>(id)?.takeIf { it.parent === root } else null
                    if (background !== navigationBackground) {
                        navigationBackground?.let { if (it.alpha == 0f) it.alpha = navigationBackgroundAlpha }
                        navigationBackground = background
                        navigationBackgroundAlpha = background?.alpha ?: 1f
                    }
                }
                navigationBackground?.let { if (it.alpha != 0f) it.alpha = 0f }
            }
            val top = maxOf(originalPadding!![1], statusTop - location[1])
            if (content.paddingTop != top || content.paddingBottom != 0)
                content.setPadding(content.paddingLeft, top, content.paddingRight, 0)
            val pager = source
            run {
                if (introDirty && introPage?.parent !== pager) {
                    introDirty = false
                    var budget = 128
                    fun containsIntro(view: View, depth: Int): Boolean {
                        if (depth > 8 || --budget < 0) return false
                        if (view.javaClass.name == "com.bilibili.ship.theseus.united.widget.UnitedRecyclerView") return true
                        return view is ViewGroup && (0 until view.childCount).any { containsIntro(view.getChildAt(it), depth + 1) }
                    }
                    introPage = (0 until pager.childCount).map { pager.getChildAt(it) }.singleOrNull { containsIntro(it, 0) }
                    skin?.source = introPage ?: pager
                }
                introPage?.let { intro ->
                    skin?.source = intro
                    val shift = intro.left + intro.translationX - pager.scrollX
                    val translation = nativeTranslationX + shift
                    if (host.translationX != translation) host.translationX = translation
                }
            }
            val lp = host.layoutParams as? ViewGroup.MarginLayoutParams
            // The content gains the navigation strip; compensate this button
            // exactly once so its native screen position stays unchanged.
            val bottomMargin = margin + gap + (8f * root.resources.displayMetrics.density).toInt()
            if (lp != null && lp.bottomMargin != bottomMargin) { lp.bottomMargin = bottomMargin; host.layoutParams = lp }
            if (backing == null) { backing = bound.backing; backingAlpha = bound.backing.alpha }
            backing?.let { if (it.alpha != 0f) it.alpha = 0f }
            for (view in listOf(host, bound.title)) {
                if (!backgrounds.containsKey(view)) backgrounds[view] = view.background
                val target = if (view === host) skin else null
                if (view.background !== target) view.background = target
            }
            bound.total.let {
                textColors.getOrPut(it) { it.textColors }
                if (it.currentTextColor != Color.WHITE) it.setTextColor(Color.WHITE)
            }
            bound.arrow.let {
                if (!iconFilters.containsKey(it)) iconFilters[it] = it.colorFilter
                if (it.colorFilter !== white) it.colorFilter = white
            }
            overlay.setBounds(0, 0, root.width, root.height)
            skin?.update()
            overlay.invalidateSelf()
            return true
        }
        private fun restore() {
            skin?.release(); skin = null
            backgrounds.forEach { (view, background) -> view.background = background }; backgrounds.clear()
            textColors.forEach { (view, color) -> view.setTextColor(color) }; textColors.clear()
            iconFilters.forEach { (view, filter) -> view.colorFilter = filter }; iconFilters.clear()
            backing?.let { if (it.alpha == 0f) it.alpha = backingAlpha }; backing = null
            button?.let { host -> (host.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.bottomMargin != margin) { lp.bottomMargin = margin; host.layoutParams = lp }
            }; host.translationX = nativeTranslationX }; button = null; introPage = null; introDirty = true
        }
        private fun restoreWindow() {
            if (originalPadding == null) return
            navigationBackground?.let { if (it.alpha == 0f) it.alpha = navigationBackgroundAlpha }
            navigationBackground = null
            navigationBackgroundDirty = true
            root.systemUiVisibility = flags
            activity.window.navigationBarColor = navColor; activity.window.navigationBarDividerColor = navDivider
            activity.window.isNavigationBarContrastEnforced = contrast
            originalPadding?.let { p -> activity.findViewById<View>(android.R.id.content)?.setPadding(p[0], p[1], p[2], p[3]) }
            originalPadding = null; statusTop = -1
        }
        fun stop() {
            active = false
            if (root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
            root.overlay.remove(overlay)
            restore()
            restoreWindow()
            binding = null
        }
    }
    private class Skin(val host: View, val pager: View, private val packageName: String, val invalidateShadow: () -> Unit) : Drawable() {
        var source = pager
        private val node = RenderNode("Bili-playlist-glass")
        private val ambient = RenderNode("Bili-playlist-shadow")
        private val lens = PostureGlass.shader(GlassHostLayout.ROUNDED_RECT_REFRACTION_SHADER)
        private val path = Path(); private val rect = RectF(); private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glassEdge = io.github.offlineglass.rendering.NativeGlassEdge()
        private val matrix = Matrix(); private val inverse = Matrix()
        private var key = ""
        @Volatile private var config = HookConfigReader.startupSnapshot(packageName) ?: GlassConfig()
        private var readAt = 0L
        private var animation: ValueAnimator? = null
        private var pressed = false
        private var flash = 0f
        fun update() {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - readAt > 1000) {
                readAt = now
                val context = host.context.applicationContext
                worker.execute { config = HookConfigReader.read(context, packageName).normalized() }
            }
            invalidateSelf()
        }
        override fun draw(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated || bounds.isEmpty) return
            val config = this.config
            val w = bounds.width(); val h = bounds.height(); val dp = host.resources.displayMetrics.density
            val dark = host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val radius = h / 2f; val pad = NativeBackdropBlur.padding(minOf(config.blurRadius, 8f) * dp, 28f * dp * config.refractionPaddingScale).toInt()
            val width = w + 2 * pad; val height = h + 2 * pad
            rect.set(0f, 0f, w.toFloat(), h.toFloat()); path.setBottomBarSquircle(rect, radius, -1f)
            PostureGlass.sync(lens, host, config.postureEffectsEnabled && config.liquidGlassEnabled && !config.solidBarEnabled,
                config.liquidViscosity, config.flowHorizontalStrength, config.flowVerticalStrength)
            val geometry = "$width:$height:$dark:${config.hashCode()}"
            if (key != geometry || PostureGlass.effectChanged(lens)) {
                key = geometry
                lens.setFloatUniform("size", w.toFloat(), h.toFloat()); lens.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
                lens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                lens.setFloatUniform("safeSampleBounds", .5f, .5f, width - .5f, height - .5f)
                lens.setFloatUniform("refractionHeight", 12f * dp); lens.setFloatUniform("refractionAmount", -24f * dp * config.refractionScale)
                lens.setFloatUniform("depthEffect", 0f); lens.setFloatUniform("detachedGeometry", 0f, 0f, 0f)
                for (name in listOf("unifiedDropletPilot", "directionalRimPilot", "monotoneFlowPilot", "continuousShape")) lens.setFloatUniform(name, 0f)
                lens.setFloatUniformExact("flowingLiquidEnabled", 1f); lens.setFloatUniformExact("flowMotionEnabled", 1f)
                val blur = NativeBackdropBlur.effect(minOf(config.blurRadius, 8f) * dp)
                node.setRenderEffect(if (config.liquidGlassEnabled && !config.solidBarEnabled)
                    RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), blur) else blur)
                ambient.setPosition(-pad, -pad, w + pad, h + pad)
                val shade = ambient.beginRecording(); shade.translate(pad.toFloat(), pad.toFloat())
                paint.color = if (dark) 0x20FFFFFF else 0x38000000; paint.style = Paint.Style.FILL; shade.drawPath(path, paint); ambient.endRecording()
                ambient.setRenderEffect(RenderEffect.createBlurEffect(8f * dp, 8f * dp, Shader.TileMode.DECAL))
                invalidateShadow()
            }
            if (!config.solidBarEnabled) {
                node.setPosition(0, 0, width, height)
                val recording = node.beginRecording(); recording.drawColor(if (dark) 0xFF17171A.toInt() else Color.WHITE)
                matrix.reset(); inverse.reset(); source.transformMatrixToGlobal(matrix); host.transformMatrixToGlobal(inverse)
                if (inverse.invert(inverse)) {
                    matrix.postConcat(inverse); matrix.postTranslate(pad.toFloat(), pad.toFloat()); recording.concat(matrix)
                    recording.clipRect(0, 0, source.width, source.height); source.draw(recording)
                }
                node.endRecording()
                val saved = canvas.save(); canvas.clipPath(path); canvas.translate(-pad.toFloat(), -pad.toFloat()); canvas.drawRenderNode(node); canvas.restoreToCount(saved)
            }
            paint.shader = null; paint.style = Paint.Style.FILL
            paint.color = if (config.solidBarEnabled) (if (dark) 0xFF202023.toInt() else Color.WHITE) else (if (dark) 0x70202023 else 0x80FFFFFF.toInt())
            canvas.drawPath(path, paint)
            if (config.liquidGlassEnabled && !config.solidBarEnabled) {
                if (config.outlineEnabled) glassEdge.draw(canvas, path, dp, dark, (if (dark) .42f else .6f) * 2f * config.darkBarHighlightStrength)
            } else {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = .7f * dp
                paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0x90FFFFFF.toInt(), 0x15FFFFFF, 0x55FFFFFF), null, Shader.TileMode.CLAMP)
                canvas.drawPath(path, paint); paint.shader = null; paint.style = Paint.Style.FILL
            }
            if (flash > 0f) { paint.color = Color.argb((flash * 65).toInt(), 255, 255, 255); canvas.drawPath(path, paint) }
        }
        fun shadow(canvas: Canvas, root: View) {
            if (!host.isShown || !ambient.hasDisplayList()) return
            matrix.reset(); inverse.reset(); host.transformMatrixToGlobal(matrix); root.transformMatrixToGlobal(inverse)
            if (!inverse.invert(inverse)) return
            matrix.postConcat(inverse)
            val saved = canvas.save(); canvas.concat(matrix); canvas.clipOutPath(path); canvas.drawRenderNode(ambient); canvas.restoreToCount(saved)
        }
        fun touch(event: MotionEvent) {
            val pos = IntArray(2); host.getLocationOnScreen(pos)
            val inside = host.isShown && event.rawX >= pos[0] && event.rawX < pos[0] + host.width && event.rawY >= pos[1] && event.rawY < pos[1] + host.height
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (inside) { pressed = true; animate(true) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pressed) { pressed = false; animate(false) }
                MotionEvent.ACTION_MOVE -> if (pressed && !inside) { pressed = false; animate(false) }
            }
        }
        private fun animate(down: Boolean) {
            animation?.cancel(); val from = host.scaleX
            animation = ValueAnimator.ofFloat(from, if (down) 1.025f else 1f).apply {
                duration = if (down) 120 else 300
                interpolator = if (down) android.view.animation.DecelerateInterpolator() else android.view.animation.OvershootInterpolator(.7f)
                addUpdateListener { host.scaleX = it.animatedValue as Float; host.scaleY = host.scaleX; flash = if (down) it.animatedFraction else 1f - it.animatedFraction; invalidateSelf(); invalidateShadow() }
                start()
            }
        }
        fun release() { animation?.cancel(); host.scaleX = 1f; host.scaleY = 1f; PostureGlass.track(host, false); node.discardDisplayList(); ambient.discardDisplayList() }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: ColorFilter?) = Unit
        @Deprecated("Deprecated") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "BiliPlaylistConfig").apply { isDaemon = true } }
}

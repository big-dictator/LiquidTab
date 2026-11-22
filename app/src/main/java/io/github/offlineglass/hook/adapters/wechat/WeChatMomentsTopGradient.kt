package io.github.offlineglass.hook.adapters.wechat

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** Moments owns native scrolling/title/buttons; only its top frost material is replaced. */
internal object WeChatMomentsTopGradient {
    private val states = WeakHashMap<View, State>()
    private val backgrounds = WeakHashMap<View, State>()
    private val lastBind = WeakHashMap<Activity, Long>()
    private var writing = false
    private lateinit var activityType: Class<*>
    private lateinit var frostType: Class<*>
    private lateinit var feedType: Class<*>
    private lateinit var overlayType: Class<*>
    private lateinit var headerType: Class<*>
    private lateinit var toolbarType: Class<*>

    private class State(binding: WeChatMomentsBindingContract.Binding<View>, title: android.widget.TextView) {
        val title = WeakReference(title)
        val frost = WeakReference(binding.frost())
        val header = WeakReference(binding.header())
        val source = WeakReference(binding.source())
        val originals = WeakHashMap<View, Drawable?>()
        val material = WeChatTopBlurMaterial(binding.frost(), binding.header(), Color.BLACK,
            bottomExtensionDp = 24f, blurRadiusScale = 1.4f)
        val gradient = material.drawable(intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT), status = true)
        var enabled = false
        var target = 0f
        var opacity = 0f
        var lastConfig = 0L
        var lastColor = 0
        var lastHeight = 0
        private val rootPosition = IntArray(2)
        private val headerPosition = IntArray(2)
        private val frostPosition = IntArray(2)
        private var drawOffsetX = 0f
        private var drawOffsetY = 0f
        var observer: ViewTreeObserver? = null
        val animator = ValueAnimator().apply {
            interpolator = LinearInterpolator()
            addUpdateListener {
                opacity = it.animatedValue as Float
                frost.get()?.invalidate()
            }
        }
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            val view = frost.get()
            if (view != null && view.isShown) {
                if (android.os.SystemClock.uptimeMillis() - lastConfig > 1000) configure()
                if (enabled) {
                    geometry()
                    this.title.get()?.let { animateTo(it.alpha) }
                }
            }
            true
        }

        init {
            material.bind(binding.source())
            val pending = ArrayDeque<Pair<View, Int>>()
            pending += binding.toolbar() to 0
            originals[binding.header()] = original(binding.header())
            var count = 0
            while (pending.isNotEmpty() && count++ < 96) {
                val (view, depth) = pending.removeFirst()
                if (view === binding.toolbar() || (view.width >= binding.toolbar().width * .85f &&
                        view.height >= binding.toolbar().height * .85f)) originals[view] = original(view)
                if (view is ViewGroup && depth < 4) for (i in 0 until view.childCount) pending += view.getChildAt(i) to depth + 1
            }
            originals.keys.forEach { backgrounds[it] = this }
        }

        private fun original(view: View): Drawable? = backgrounds[view]?.let {
            if (it.originals.containsKey(view)) it.originals[view] else view.background
        } ?: view.background

        fun valid(): Boolean = source.get()?.parent === frost.get() && header.get()?.isAttachedToWindow == true

        fun attach() {
            val view = frost.get() ?: return
            if (observer?.isAlive == true) observer?.removeOnPreDrawListener(preDraw)
            observer = view.viewTreeObserver.also { it.addOnPreDrawListener(preDraw) }
            configure()
            geometry()
        }

        fun detach() {
            animator.cancel()
            if (observer?.isAlive == true) observer?.removeOnPreDrawListener(preDraw)
            observer = null
            material.enabled = false
        }

        fun configure() {
            val view = frost.get() ?: return
            lastConfig = android.os.SystemClock.uptimeMillis()
            val config = HookConfigReader.read(view.context, "com.tencent.mm")
            val topEnabled = config.enabled && config.wechatTopBarReconstruction
            material.enabled = topEnabled && config.wechatTopBlurGradient
            if (enabled != topEnabled) {
                enabled = topEnabled
                writing = true
                try { originals.forEach { (layer, background) -> layer.background = if (enabled) null else background } }
                finally { writing = false }
                if (!enabled) { animator.cancel(); opacity = 0f }
                else animateTo(target)
                view.invalidate()
            }
        }

        fun animateTo(value: Float) {
            val next = value.coerceIn(0f, 1f)
            if (next == target && (animator.isRunning || abs(opacity - next) < .001f)) return
            target = next
            if (!enabled || !frost.get().let { it?.isAttachedToWindow == true && it.isShown }) return
            animator.cancel()
            if (abs(target - opacity) < .001f) return
            animator.setFloatValues(opacity, target)
            animator.duration = (180f * abs(target - opacity)).toLong().coerceAtLeast(1L)
            animator.start()
        }

        fun geometry() {
            val view = frost.get() ?: return
            val bar = header.get() ?: return
            view.rootView.getLocationInWindow(rootPosition)
            bar.getLocationInWindow(headerPosition)
            view.getLocationInWindow(frostPosition)
            drawOffsetX = (rootPosition[0] - frostPosition[0]).toFloat()
            drawOffsetY = (rootPosition[1] - frostPosition[1]).toFloat()
            val height = (headerPosition[1] - rootPosition[1] + bar.height +
                material.extensionPx(view.resources.displayMetrics.density)).coerceAtLeast(1)
            val tint = XposedHelpers.callMethod(view, "getFrostedColor") as Int
            val color = Color.rgb(Color.red(tint), Color.green(tint), Color.blue(tint))
            if (lastColor != color || lastHeight != height) {
                lastColor = color; lastHeight = height
                material.color = color
                gradient.colors = intArrayOf((200 shl 24) or (color and 0xffffff),
                    (100 shl 24) or (color and 0xffffff), color and 0xffffff)
            }
            gradient.setBounds(0, 0, bar.width, height)
        }

        fun draw(canvas: Canvas) {
            val view = frost.get() ?: return
            if (!enabled || opacity <= 0f || !view.isShown) return
            val saved = canvas.save()
            canvas.translate(drawOffsetX, drawOffsetY)
            // One alpha layer fades tint AND all blur levels together.
            // At full opacity, avoid allocating/compositing an extra GPU layer.
            val alpha = if (opacity < 1f) canvas.saveLayerAlpha(0f, 0f, gradient.bounds.right.toFloat(),
                gradient.bounds.bottom.toFloat(), (opacity * 255).toInt()) else -1
            gradient.draw(canvas)
            if (alpha >= 0) canvas.restoreToCount(alpha)
            canvas.restoreToCount(saved)
        }
    }

    private fun bind(activity: Activity): State? {
        if (!activityType.isInstance(activity)) return null
        val root = activity.window.decorView
        val previous = states.entries.firstOrNull { it.key.rootView === root && it.key.isAttachedToWindow }?.value
        if (previous?.valid() == true) return previous
        val now = android.os.SystemClock.uptimeMillis()
        if (now - (lastBind[activity] ?: 0L) < 400) return previous
        lastBind[activity] = now
        val views = ArrayList<View>()
        val pending = ArrayDeque<View>(); pending += root
        while (pending.isNotEmpty() && views.size < 256) {
            val view = pending.removeFirst(); views += view
            if (feedType.isInstance(view) || view is android.widget.AbsListView) continue
            if (view is ViewGroup) for (i in 0 until view.childCount) pending += view.getChildAt(i)
        }
        if (pending.isNotEmpty()) return null
        val binding = WeChatMomentsBindingContract.resolve(views, { it.parent as? View },
            frostType::isInstance, feedType::isInstance, overlayType::isInstance,
            headerType::isInstance, toolbarType::isInstance) ?: return null
        val title = binding.toolbar().findViewById<View>(android.R.id.text1) as? android.widget.TextView ?: return null
        val state = State(binding, title)
        previous?.detach()
        previous?.frost?.get()?.let { if (it !== binding.frost()) states.remove(it) }
        states[binding.frost()] = state
        binding.frost().addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = state.attach()
            override fun onViewDetachedFromWindow(v: View) = state.detach()
        })
        state.target = title.alpha
        state.attach()
        android.util.Log.i("LiquidTabWeChatTop", "Moments gradient bound to native frost/feed/header contract")
        return state
    }

    private fun owner(view: View): Activity? {
        var context = view.context
        repeat(12) {
            if (context is Activity) return context
            context = (context as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    fun install(loader: ClassLoader) {
        activityType = XposedHelpers.findClass("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI", loader)
        frostType = XposedHelpers.findClass("com.tencent.mm.ui.FrostedContentView", loader)
        feedType = XposedHelpers.findClass("com.tencent.mm.plugin.sns.ui.improve.view.ImproveRecyclerView", loader)
        overlayType = XposedHelpers.findClass("androidx.appcompat.widget.ActionBarOverlayLayout", loader)
        headerType = XposedHelpers.findClass("androidx.appcompat.widget.ActionBarContainer", loader)
        toolbarType = XposedHelpers.findClass("androidx.appcompat.widget.Toolbar", loader)
        // Native onScrolled writes the same 0..1 progress to android.R.id.text1
        // via MMActivity.setTitleAlpha after updating its frost. Read that public
        // View property at pre-draw, so AOT inlining of the tiny setter is harmless.
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activityType.isInstance(activity)) activity.window.decorView.post { bind(activity)?.configure() }
            }
        })
        XposedBridge.hookAllMethods(frostType, "dispatchDraw", object : XC_MethodHook() {
            private val saved = ThreadLocal.withInitial { ArrayDeque<Pair<View, Int>>() }
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val state = states[view]?.takeIf { it.valid() } ?: owner(view)?.let(::bind) ?: return
                if (!state.enabled) return
                val height = XposedHelpers.callMethod(view, "getTopBlurAreaHeight") as Int
                checkNotNull(saved.get()).addLast(view to height)
                // Leave native enable/colour/progress/bottom frost intact. Only
                // remove its uniform TOP paint pass, for this draw call alone.
                XposedHelpers.callMethod(view, "setTopBlurAreaHeight", 0)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val stack = saved.get() ?: return
                val entry = stack.lastOrNull()?.takeIf { it.first === param.thisObject } ?: return
                stack.removeLast()
                XposedHelpers.callMethod(entry.first, "setTopBlurAreaHeight", entry.second)
                states[entry.first]?.draw(param.args[0] as Canvas)
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setBackgroundDrawable", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (writing) return
                val view = param.thisObject as? View ?: return
                val state = backgrounds[view] ?: return
                state.originals[view] = param.args[0] as? Drawable
                if (state.enabled) param.args[0] = null
            }
        })
    }
}

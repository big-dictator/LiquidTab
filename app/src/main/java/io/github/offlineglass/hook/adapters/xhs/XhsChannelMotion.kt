package io.github.offlineglass.hook.adapters.xhs

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.PathInterpolator
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** One transform for the native content and glass sibling; native close completion is retained. */
internal class XhsChannelMotion(private val anchor: View) {
    private data class Native(val view: View, val animation: Animation, val listener: Animation.AnimationListener, val close: Boolean)
    private var pending: Native? = null
    private var animator: ValueAnimator? = null
    private var group: ViewGroup? = null
    private var material: GlassHostLayout? = null
    private var category: View? = null
    private var waitingTree: ViewTreeObserver? = null
    private var waiting: ViewTreeObserver.OnPreDrawListener? = null
    // Pending open is still unscaled: layout must finish before geometry is frozen.
    val running: Boolean get() = animator != null
    fun intercept(view: View, animation: Animation, listener: Animation.AnimationListener): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        // Native close owns the category reference for detach; open owns only
        // its onStart callback. This survives listener/package renaming.
        val close = listener.javaClass.declaredFields.any { it.type.isInstance(view) }
        clearWait()
        animator?.removeAllListeners(); animator?.cancel(); animator = null
        pending = Native(view, animation, listener, close)
        category = view; group = parent
        view.clearAnimation()
        listener.onAnimationStart(animation)
        parent.background = null
        if (!close) { parent.alpha = 0f; parent.scaleX = 1f; parent.scaleY = 1f }
        if (close && material?.isAttachedToWindow == true) begin()
        return true
    }
    fun bind(view: View, host: GlassHostLayout) {
        material = host
        if (pending?.view === view && animator == null && waiting == null) {
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (pending?.view !== view || !view.isAttachedToWindow) { clearWait(); return true }
                    val parent = group ?: return true
                    if (parent.isLayoutRequested || view.isLayoutRequested || host.isLayoutRequested || host.width <= 0 || host.height <= 0)
                        return true
                    clearWait()
                    begin()
                    return true
                }
            }
            waiting = listener; waitingTree = view.viewTreeObserver
            waitingTree?.addOnPreDrawListener(listener)
            view.invalidate()
        }
    }
    private fun clearWait() {
        waiting?.let { listener -> waitingTree?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        waiting = null; waitingTree = null
    }
    private fun begin() {
        val native = pending ?: return
        val parent = group ?: return
        if (!parent.isAttachedToWindow) { restore(); return }
        pending = null
        val origin = IntArray(2); val target = IntArray(2)
        anchor.getLocationInWindow(origin)
        parent.getLocationInWindow(target)
        // Read the unscaled parent's layout origin rather than its transformed bounds.
        val parentParent = parent.parent as? View
        parentParent?.getLocationInWindow(target)
        parent.pivotX = origin[0] + anchor.width / 2f - target[0] - parent.left
        parent.pivotY = origin[1] + anchor.height / 2f - target[1] - parent.top
        val startScale = if (native.close) parent.scaleX else .08f
        val startAlpha = parent.alpha
        val endScale = if (native.close) .08f else 1f
        val endAlpha = if (native.close) 0f else 1f
        val next = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (native.close) 180L else 240L
            interpolator = if (native.close) AccelerateInterpolator(1.3f) else PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                parent.scaleX = startScale + (endScale - startScale) * t
                parent.scaleY = parent.scaleX
                parent.alpha = startAlpha + (endAlpha - startAlpha) * t
                material?.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animator = null
                    native.listener.onAnimationEnd(native.animation)
                    material?.invalidate()
                }
            })
        }
        animator = next
        next.start()
    }
    fun restore() {
        clearWait()
        animator?.removeAllListeners(); animator?.cancel(); animator = null; pending = null
        group?.let { it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f }
        group = null; category = null; material = null
    }
    companion object {
        fun install(owner: (View) -> XhsChannelMotion?) {
            val listenerField = runCatching {
                Animation::class.java.declaredFields.single {
                    it.type == Animation.AnimationListener::class.java
                }.apply { isAccessible = true }
            }.getOrElse {
                android.util.Log.w("LiquidTabXhsTop", "platform animation listener contract unavailable", it)
                return
            }
            XposedBridge.hookAllMethods(View::class.java, "startAnimation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (view.javaClass.name != "com.xingin.xhs.homepage.explorefeed.category.panel.CategoryViewView") return
                    val animation = param.args.firstOrNull() as? Animation ?: return
                    val listener = listenerField.get(animation) as? Animation.AnimationListener ?: return
                    if (owner(view)?.intercept(view, animation, listener) == true) param.result = null
                }
            })
        }
    }
}

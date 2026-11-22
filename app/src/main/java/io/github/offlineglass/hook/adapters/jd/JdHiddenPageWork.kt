package io.github.offlineglass.hook.adapters.jd

import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** JD 16.1 page-scoped lifecycle repair. Never stop a shared JS engine/player. */
internal object JdHiddenPageWork {
    private class Page(fragment: Any) {
        val fragment = WeakReference(fragment)
        var pausedByUs = false
    }
    private val pages = WeakHashMap<Any, Page>()
    private val hookedContexts = mutableSetOf<Class<*>>()
    private val carousels = WeakHashMap<View, Carousel>()
    private var pauses = 0
    private var resumes = 0
    private var blockedResumes = 0
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    private var reconcileQueued = false
    private fun scheduleReconcile() {
        if (reconcileQueued) return
        reconcileQueued = true
        mainHandler.post {
            reconcileQueued = false
            // One Fragment transaction can hide/show several nested fragments.
            // Reconcile the final state once instead of repeatedly reflecting
            // over every page and carousel inside the transaction itself.
            reconcilePages()
            carousels.values.toList().forEach { it.update() }
        }
    }
    fun status() = "hiddenPauses=$pauses hiddenResumes=$resumes blockedPageResumes=$blockedResumes carouselWatchers=${carousels.size}"
    private fun main() = Looper.myLooper() == Looper.getMainLooper()
    private fun call(value: Any, name: String): Any? = runCatching { XposedHelpers.callMethod(value, name) }.getOrNull()

    private fun isTabTree(fragment: Any): Boolean {
        var current: Any? = fragment
        repeat(8) {
            val value = current ?: return false
            var type: Class<*>? = value.javaClass
            while (type != null) {
                if (type.name == "com.jingdong.common.unification.navigationbar.JDTabFragment") return true
                type = type.superclass
            }
            current = call(value, "getParentFragment")
        }
        return false
    }
    private fun hidden(fragment: Any): Boolean {
        var current: Any? = fragment
        repeat(8) {
            val value = current ?: return false
            if (call(value, "isHidden") == true) return true
            current = call(value, "getParentFragment")
        }
        return false
    }
    private fun foreground(fragment: Any) = call(fragment, "isResumed") == true && !hidden(fragment)

    private fun bindContext(fragment: Any, context: Any) {
        if (!isTabTree(fragment)) return
        if (pages[context]?.fragment?.get() !== fragment) pages[context] = Page(fragment)
        var type: Class<*>? = context.javaClass
        while (type != null) {
            val declared = type.declaredMethods.any { it.name == "onResume" && it.parameterCount == 1 && !java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            if (declared) {
                if (hookedContexts.add(type)) XposedBridge.hookAllMethods(type, "onResume", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!main() || param.args.size != 1) return
                        val state = pages[param.thisObject] ?: return
                        val owner = state.fragment.get() ?: return
                        if (!foreground(owner)) {
                            // reloadBundle's completion can call onResume without
                            // the isResumed check present in preload consumption.
                            param.result = null
                            if (!state.pausedByUs) runCatching { XposedHelpers.callMethod(param.thisObject, "onPause", param.args[0]) }
                            state.pausedByUs = true
                            blockedResumes++
                        } else state.pausedByUs = false
                    }
                })
                return
            }
            type = type.superclass
        }
    }

    private fun reconcilePages() {
        for ((context, state) in pages) {
            val fragment = state.fragment.get() ?: continue
            val activity = call(fragment, "getActivity") ?: continue
            if (!state.pausedByUs && hidden(fragment) && call(fragment, "isResumed") == true) {
                if (runCatching { XposedHelpers.callMethod(context, "onPause", activity) }.isSuccess) {
                    state.pausedByUs = true; pauses++
                }
            } else if (state.pausedByUs && foreground(fragment)) {
                if (runCatching { XposedHelpers.callMethod(context, "onResume", activity) }.isSuccess) {
                    state.pausedByUs = false; resumes++
                }
            }
        }
    }

    private class Carousel(view: View) : View.OnAttachStateChangeListener,
        ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnWindowFocusChangeListener {
        private val view = WeakReference(view)
        private var observer: WeakReference<ViewTreeObserver>? = null
        private var pausedByUs = false
        private var updating = false
        fun watch() {
            val value = view.get() ?: return
            value.addOnAttachStateChangeListener(this)
            if (value.isAttachedToWindow) onViewAttachedToWindow(value) else update()
        }
        fun update(detaching: Boolean = false) {
            val value = view.get() ?: return
            if (updating) return
            updating = true
            try {
                val running = call(value, "isCarouselRunning") == true
                val paused = call(value, "isCarouselPaused") == true
                if (!running) { pausedByUs = false; return }
                if (detaching || !value.isAttachedToWindow || !value.isShown || !value.hasWindowFocus()) {
                    if (!paused && runCatching { XposedHelpers.callMethod(value, "pauseCarousel") }.isSuccess) {
                        pausedByUs = true; pauses++
                    }
                } else if (pausedByUs) {
                    pausedByUs = false
                    if (paused && runCatching { XposedHelpers.callMethod(value, "resumeCarousel") }.isSuccess) resumes++
                }
            } finally { updating = false }
        }
        private fun unobserve() {
            observer?.get()?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalLayoutListener(this); it.removeOnWindowFocusChangeListener(this)
            }
            observer = null
        }
        override fun onViewAttachedToWindow(v: View) {
            unobserve()
            val tree = v.viewTreeObserver
            observer = WeakReference(tree)
            tree.addOnGlobalLayoutListener(this); tree.addOnWindowFocusChangeListener(this)
            update()
        }
        // During this callback Android may still report isAttachedToWindow=true.
        override fun onViewDetachedFromWindow(v: View) { unobserve(); update(detaching = true) }
        // All carousels share the window observer. A mount/layout burst should
        // reconcile once, not repeat reflection for each listener in each pass.
        override fun onGlobalLayout() = scheduleReconcile()
        override fun onWindowFocusChanged(hasFocus: Boolean) = update()
    }

    fun install(loader: ClassLoader) {
        runCatching { Class.forName("com.jingdong.app.mall.bundle.taromodule.BaseTaroFragment", false, loader) }.getOrNull()?.let { taro ->
            val contextField = runCatching { taro.getDeclaredField("taroPageContext").apply { isAccessible = true } }.getOrNull()
            XposedBridge.hookAllMethods(taro, "beforeBundleLoad", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (main()) param.args.firstOrNull()?.let { bindContext(param.thisObject, it) }
                }
            })
            XposedBridge.hookAllMethods(taro, "onResume", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!main()) return
                    runCatching { contextField?.get(param.thisObject) }.getOrNull()?.let { bindContext(param.thisObject, it) }
                }
            })
        }
        runCatching { Class.forName("androidx.fragment.app.Fragment", false, loader) }.getOrNull()?.let { fragment ->
            XposedBridge.hookAllMethods(fragment, "onHiddenChanged", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!main() || !isTabTree(param.thisObject)) return
                    scheduleReconcile()
                }
            })
        }
        runCatching { Class.forName("com.jd.lib.un.business.widget.JDImageCarouselView", false, loader) }.getOrNull()?.let { carousel ->
            for (name in listOf("startCarousel", "resumeCarousel")) XposedBridge.hookAllMethods(carousel, name, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!main()) return
                    val view = param.thisObject as? View ?: return
                    (carousels[view] ?: Carousel(view).also { carousels[view] = it; it.watch() }).update()
                }
            })
        }
    }
}

package io.github.offlineglass.hook.adapters.jd

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassInstaller
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.targets.TargetSpec
import java.util.WeakHashMap

/** Bind the actual row before its first draw, instead of the scanner's 650ms retry. */
internal object JdNavigationBootstrap {
    private val pending = WeakHashMap<View, Boolean>()
    fun install(loader: ClassLoader, spec: TargetSpec) {
        val type = JdNavigationBinding.initialize(loader) ?: return
        val onClick = runCatching { type.getMethod("onClick", View::class.java) }.getOrNull()
        if (onClick?.returnType == Void.TYPE) XposedBridge.hookMethod(onClick, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val row = JdNavigationBinding.resolve(param.thisObject as? ViewGroup) ?: return
                if (param.args.firstOrNull() === row.slots[JdNavigationBinding.BROWSE].touch &&
                    HookConfigReader.read(row.view.context, spec.packageName).enabled) param.result = null
            }
        })
        val setter = runCatching {
            val callback = Class.forName("${type.packageName}.INavigationShow", false, loader)
            type.getDeclaredMethod("setNavigationButton", List::class.java, callback, Activity::class.java)
                .also { check(it.returnType == Void.TYPE) }
        }.onFailure { XposedBridge.log("LiquidTab JD early navigation binding unavailable: $it") }.getOrNull() ?: return
        XposedBridge.hookMethod(setter, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val row = param.thisObject as? ViewGroup ?: return
                JdNavigationBinding.invalidate(row)
                if (JdNavigationBinding.resolve(row) == null || pending.put(row, true) != null) return
                fun arm() {
                    val observer = row.viewTreeObserver
                    val listener = object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (observer.isAlive) observer.removeOnPreDrawListener(this)
                            pending.remove(row)
                            var context: Context? = row.context
                            repeat(12) { if (context is ContextWrapper && context !is Activity)
                                context = (context as ContextWrapper).baseContext }
                            val activity = context as? Activity ?: return true
                            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return true
                            val binding = JdNavigationBinding.find(content)?.takeIf { it.view === row } ?: return true
                            if (GlassInstaller.hasActiveNavigationBinding(activity, row)) return true
                            val config = HookConfigReader.read(activity, spec.packageName)
                            if (!config.enabled) return true
                            val installed = GlassInstaller.install(activity, row, binding.slots.size, config, spec)
                            if (installed) row.rootView.postInvalidateOnAnimation()
                            return !installed
                        }
                    }
                    observer.addOnPreDrawListener(listener)
                }
                if (row.isAttachedToWindow) arm() else row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { row.removeOnAttachStateChangeListener(this); arm() }
                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
        })
    }
}

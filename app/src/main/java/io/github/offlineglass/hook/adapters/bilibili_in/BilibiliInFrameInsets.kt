package io.github.offlineglass.hook.adapters.bilibili_in

import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Removes only the verified scrolling-behavior compensation, never IME or unrelated view insets. */
internal object BilibiliInFrameInsets {
    private data class State(var enabled: Boolean, var compensation: Int, var original: Int, var behaviorClass: Class<*>? = null)
    private val frames = WeakHashMap<View, State>()
    private val hooked = HashSet<Method>()
    private val measuring = ThreadLocal<View?>()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        XposedBridge.hookAllMethods(View::class.java, "setPadding", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size != 4 || measuring.get() !== param.thisObject) return
                val state = frames[param.thisObject] ?: return
                if (!state.enabled || state.compensation <= 0 || param.args[3] != state.compensation) return
                state.original = state.compensation
                param.args[3] = 0
            }
        })
    }

    fun configure(frame: View, enabled: Boolean) {
        val inset = frame.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        val state = frames.getOrPut(frame) { State(enabled, inset, frame.paddingBottom) }
        if (!enabled) {
            if (state.enabled && frame.paddingBottom == 0 && state.original > 0)
                frame.setPadding(frame.paddingLeft, frame.paddingTop, frame.paddingRight, state.original)
            state.enabled = false
            return
        }
        state.enabled = true; state.compensation = inset
        val p = frame.layoutParams
        val behavior = BilibiliInBehaviorAccess.resolve(p)?.get(p)
        if (behavior != null && state.behaviorClass !== behavior.javaClass) {
            state.behaviorClass = behavior.javaClass
            val method = behavior.javaClass.methods.firstOrNull {
                it.name == "onMeasureChild" && it.parameterCount == 6 && it.returnType == Boolean::class.javaPrimitiveType &&
                    ViewGroup::class.java.isAssignableFrom(it.parameterTypes[0]) && View::class.java.isAssignableFrom(it.parameterTypes[1]) &&
                    it.parameterTypes.drop(2).all { type -> type == Int::class.javaPrimitiveType }
            }
            if (method != null && hooked.add(method)) {
                val stack = ThreadLocal.withInitial { ArrayList<View?>() }
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        stack.get()!!.add(measuring.get())
                        measuring.set((param.args.getOrNull(1) as? View)?.takeIf { frames[it]?.enabled == true })
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val values = stack.get()!!
                        measuring.set(if (values.isEmpty()) null else values.removeAt(values.lastIndex))
                    }
                })
            }
        }
        if (inset > 0 && frame.paddingBottom == inset) {
            state.original = inset
            frame.setPadding(frame.paddingLeft, frame.paddingTop, frame.paddingRight, 0)
        }
    }

    fun release(frame: View) { configure(frame, false); frames.remove(frame) }
}

package io.github.offlineglass.hook.adapters.mi_health

import android.graphics.RenderNode
import android.view.View
import android.widget.EdgeEffect
import android.widget.ScrollView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Mirrors the platform's actual HWUI stretch vector; never changes native scroll physics.
 * For pixels above a bottom-stretched viewport, AOSP StretchEffect.cpp maps y to y-diff,
 * where diff=height/(1+abs(vector))-height. Thus the matching draw translation is diff.
 * Evidence: current ReactScrollView.draw -> ScrollView.draw -> EdgeEffect -> RenderNode.stretch.
 */
internal object MiHealthNativeStretch {
    private val watches = WeakHashMap<RenderNode, Watch>()
    private var installed = false
    private val nodeField = runCatching { View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true } }.getOrNull()
    private val bottomField = runCatching { ScrollView::class.java.getDeclaredField("mEdgeGlowBottom").apply { isAccessible = true } }.getOrNull()

    class Watch(scroll: ScrollView, owner: View) {
        private val scroll = WeakReference(scroll)
        private val owner = WeakReference(owner)
        private var vector = 0f
        fun set(value: Float) {
            if (vector == value) return
            vector = value; owner.get()?.invalidate()
        }
        fun translation(): Float {
            val view = scroll.get() ?: return 0f
            val bottom = runCatching { bottomField?.get(view) as? EdgeEffect }.getOrNull()
            if (bottom?.isFinished == true) set(0f)
            if (vector >= 0f) return 0f
            return MiHealthStretchMath.bottomTranslation(view.height.toFloat(), vector)
        }
        fun detach() { watches.entries.removeAll { it.value === this }; set(0f) }
    }
    fun watch(scroll: ScrollView, owner: View): Watch? = runCatching {
        install()
        val node = nodeField?.get(scroll) as? RenderNode ?: return null
        Watch(scroll, owner).also { watches[node] = it }
    }.getOrNull()

    private fun install() {
        if (installed) return
        val stretch = RenderNode::class.java.getDeclaredMethod("stretch", Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        XposedBridge.hookMethod(stretch, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                watches[param.thisObject as? RenderNode]?.set((param.args[1] as? Float) ?: 0f)
            }
        })
        runCatching { XposedBridge.hookMethod(RenderNode::class.java.getDeclaredMethod("clearStretch"), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { watches[param.thisObject as? RenderNode]?.set(0f) }
        }) }
        installed = true
    }
}

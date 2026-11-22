package io.github.offlineglass.hook.adapters.xhs

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One structural scene for all home materials. Live native RenderNodes remain the input. */
internal class XhsBackdropScene(private val root: ViewGroup, private val excluded: Set<View>, private val structureChanged: () -> Unit) {
    private var content: ViewGroup? = null
    private var failed = false
    private var released = false
    private val graph = XhsSceneGraph(object : XhsSceneGraph.Access<View> {
        override fun count(view: View) = (view as? ViewGroup)?.childCount ?: 0
        override fun child(view: View, index: Int): View = (view as ViewGroup).getChildAt(index)
        override fun blocked(view: View) = view in excluded || view is GlassHostLayout || view is AdapterOwnedOverlay
        override fun visible(view: View) = view.visibility == View.VISIBLE && view.alpha > .001f
        override fun watch(view: View) { if (view is ViewGroup) owners[view] = WeakReference(this@XhsBackdropScene) }
    })
    fun changed(view: View) { graph.changed(view) }
    private fun structuralChange(view: View) { graph.changed(view); structureChanged() }
    fun reset() { content = null; failed = false; graph.reset(null) }
    fun views(): List<View> {
        if (released || failed || !observeStructure()) return emptyList()
        val next = root.findViewById<ViewGroup>(android.R.id.content) ?: root
        if (next !== content) { content = next; graph.reset(next) }
        return runCatching { graph.scene() }.getOrElse {
            failed = true
            android.util.Log.w("LiquidTabXhsScene", "unsafe/incomplete topology rejected", it)
            emptyList()
        }
    }
    fun release() {
        released = true; reset()
        owners.entries.removeAll { it.value.get() == null || it.value.get() === this }
    }
    companion object {
        private val owners = WeakHashMap<ViewGroup, WeakReference<XhsBackdropScene>>()
        private var observed: Boolean? = null
        private fun observeStructure(): Boolean {
            observed?.let { return it }
            val handles = ArrayList<XC_MethodHook.Unhook>()
            val ok = runCatching {
                // Platform structural dispatch also covers subclasses which do
                // not call super.onViewAdded/onViewRemoved. No drawing hooks.
                val signatures = listOf(
                    "dispatchViewAdded" to arrayOf(View::class.java),
                    "dispatchViewRemoved" to arrayOf(View::class.java),
                    "bringChildToFront" to arrayOf(View::class.java),
                    "attachViewToParent" to arrayOf(View::class.java, Int::class.javaPrimitiveType!!, ViewGroup.LayoutParams::class.java),
                    "detachViewFromParent" to arrayOf(View::class.java),
                    "detachViewFromParent" to arrayOf(Int::class.javaPrimitiveType!!),
                    "detachViewsFromParent" to arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
                    "detachAllViewsFromParent" to emptyArray(),
                )
                for ((name, parameters) in signatures) {
                    val method = ViewGroup::class.java.getDeclaredMethod(name, *parameters)
                    handles += XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? ViewGroup ?: return
                            owners[view]?.get()?.structuralChange(view)
                        }
                    })
                }
            }.onFailure {
                handles.forEach { it.unhook() }
                android.util.Log.w("LiquidTabXhsScene", "platform structure observation unavailable", it)
            }.isSuccess
            if (ok) android.util.Log.i("LiquidTabXhsScene", "incremental topology observation ready")
            observed = ok
            return ok
        }
    }
}

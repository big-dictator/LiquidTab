package io.github.offlineglass.hook.adapters.spotify

import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Cache scene topology across frames; RenderNodes still supply live pixels. */
internal object SpotifyBackdropScene {
    private val entries=WeakHashMap<ViewGroup,Entry>()
    private var installed=false
    private var hierarchySignals=false
    private fun installHierarchySignals() {
        if(installed)return
        installed=true
        fun hook(name: String): Boolean {
            val method=ViewGroup::class.java.declaredMethods.singleOrNull {
                it.name==name && it.returnType==Void.TYPE && it.parameterTypes.contentEquals(arrayOf(View::class.java))
            } ?: return false
            XposedBridge.hookMethod(method,object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    var node=param.thisObject as? View
                    var depth=0
                    while(node!=null && depth++<64) {
                        if(node is ViewGroup)entries[node]?.invalidateTopology()
                        node=node.parent as? View
                    }
                }
            })
            return true
        }
        hierarchySignals=runCatching {
            val added=hook("dispatchViewAdded")
            val removed=hook("dispatchViewRemoved")
            added && removed
        }.getOrDefault(false)
        if(!hierarchySignals)android.util.Log.w("SpotifyGlass","framework hierarchy notifications unavailable; retain layout-driven scene invalidation")
    }
    fun views(root: ViewGroup): List<View> {
        installHierarchySignals()
        return entries.getOrPut(root) { Entry(root) }.views()
    }
    fun release(root: ViewGroup?) { if(root!=null)entries.remove(root)?.dispose() }
    private class Entry(root: ViewGroup): ViewTreeObserver.OnGlobalLayoutListener {
        private val root=WeakReference(root)
        private var observer=WeakReference<ViewTreeObserver>(null)
        private var dirty=true
        private var frontier=emptyList<WeakReference<View>>()
        private var frameKey=Long.MIN_VALUE
        private var visible=WeakReference<List<View>>(null)
        fun invalidateTopology() { dirty=true;frameKey=Long.MIN_VALUE }
        override fun onGlobalLayout() {
            // Dimensions/animated button widths do not change the scene tree.
            // Keep the original safe path only when framework events are absent.
            if(!hierarchySignals)dirty=true
            frameKey=Long.MIN_VALUE
        }
        fun dispose() {
            observer.get()?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
            frontier=emptyList();visible.clear();observer.clear()
        }
        fun views(): List<View> {
            val parent=root.get() ?: return emptyList()
            val tree=parent.viewTreeObserver
            if(observer.get()!==tree && tree.isAlive) {
                observer.get()?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
                tree.addOnGlobalLayoutListener(this);observer=WeakReference(tree);dirty=true;frameKey=Long.MIN_VALUE
            }
            if(dirty) {
                val found=ArrayList<View>()
                fun collect(view: View): Boolean {
                    if(view is AdapterOwnedOverlay || view is GlassHostLayout)return true
                    val start=found.size
                    var split=false
                    if(view is ViewGroup)for(i in 0 until view.childCount)split=collect(view.getChildAt(i)) || split
                    if(!split) { while(found.size>start)found.removeAt(found.lastIndex);found+=view }
                    return split
                }
                for(i in 0 until parent.childCount)collect(parent.getChildAt(i))
                frontier=found.map { WeakReference(it) };dirty=false
            }
            val key=parent.drawingTime
            if(frameKey==key)visible.get()?.let { return it }
            val result=frontier.mapNotNull { ref -> ref.get()?.takeIf { view ->
                var node: View?=view
                var visible=view.isAttachedToWindow
                while(visible && node!=null && node!==parent) {
                    visible=node.visibility==View.VISIBLE && node.alpha>.001f
                    node=node.parent as? View
                }
                visible && node===parent
            } }
            frameKey=key;visible=WeakReference(result)
            return result
        }
    }
}

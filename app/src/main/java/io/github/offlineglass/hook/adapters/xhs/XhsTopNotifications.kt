package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 9.51 Hera notification positioning; preserve its own translation and drag controller. */
internal object XhsTopNotifications {
    private class Record(val card: WeakReference<ViewGroup>, var nativeTop: Int)
    private val boundaries = WeakHashMap<View, Int>()
    private val records = WeakHashMap<View, Record>()
    private val position = IntArray(2)
    fun install(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.xingin.hera.notification.NotifyDragView", false, loader)
            val notifyPosition = type.getMethod("getNotifyPosition")
            fun register(view: View, nativeLayout: Boolean) {
                if ((notifyPosition.invoke(view) as? Enum<*>)?.name != "TOP") return
                // Native constructor adds its getNotifyView() result as the
                // unique direct card. Do not call the factory a second time.
                val owner = view as? ViewGroup ?: return
                val card = (0 until owner.childCount).map(owner::getChildAt).filterIsInstance<ViewGroup>()
                    .singleOrNull()?.takeIf { it.parent === owner } ?: return
                val lp = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                val record = records.getOrPut(view) { Record(WeakReference(card), lp.topMargin) }
                if (nativeLayout) record.nativeTop = lp.topMargin
                apply(view, record)
            }
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    runCatching { register(view, false) }
                    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) { runCatching { register(view, false) } }
                        override fun onViewDetachedFromWindow(v: View) = Unit
                    })
                }
            })
        }.onFailure { android.util.Log.w("LiquidTabXhsTop", "native notification positioning unavailable", it) }
    }
    fun update(root: View, bottom: Int) {
        root.getLocationInWindow(position)
        boundaries[root] = bottom + position[1]
        records.forEach { (view, record) -> apply(view, record) }
    }
    fun restore(root: View) {
        if (boundaries.remove(root) == null) return
        records.forEach { (view, record) -> apply(view, record) }
    }
    private fun activityRoot(context: Context): View? {
        var current = context
        repeat(12) {
            if (current is Activity) return (current as Activity).window?.decorView
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }
    private fun apply(view: View, record: Record) {
        val card = record.card.get() ?: return
        val lp = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val floor = boundaries[view.rootView] ?: activityRoot(view.context)?.let { boundaries[it] }
        val top = if (floor != null && view.isAttachedToWindow) {
            view.getLocationInWindow(position)
            maxOf(record.nativeTop, floor - position[1])
        } else record.nativeTop
        if (lp.topMargin != top) { lp.topMargin = top; card.layoutParams = lp }
    }
}

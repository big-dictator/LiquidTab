package io.github.offlineglass.hook.adapters.weibo

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Home-owned pull gesture plus native adapter notifications, never private refresh symbols. */
internal class WeiboHomeRefresh(private val page: View, private val list: ViewGroup) {
    companion object {
        private val pages = WeakHashMap<View, WeakReference<WeiboHomeRefresh>>()
        private val lists = WeakHashMap<View, WeakReference<WeiboHomeRefresh>>()
        private val adapters = WeakHashMap<Any, WeakReference<WeiboHomeRefresh>>()
        private val hooked = HashSet<java.lang.reflect.Method>()
        fun touch(view: Any, event: MotionEvent) {
            pages[view]?.get()?.onTouch(event)
            lists[view]?.get()?.onListTouch(event)
        }
    }
    private var adapter: Any? = null
    private var startY = 0f
    private var startX = 0f
    private var tracking = false
    private var dragging = false
    private var expires = 0L
    private var dataChanged = false
    private var queued = false
    private var closed = false
    private var lastEventTime = Long.MIN_VALUE
    private var lastDownTime = Long.MIN_VALUE
    private var initialTop = false
    private var touched = false
    fun requestInitialTop() {
        if (touched || closed) return
        initialTop = true
        changed()
    }
    private val pullThreshold = ViewConfiguration.get(page.context).scaledTouchSlop * 2
    private var pullContainer: ViewGroup? = null
    private var interceptionBridge: java.lang.reflect.Method? = null
    private var upwardHandoff = false
    private var listReceivedDown = false
    private var pullGestureUntil = 0L
    private val pagePosition = IntArray(2)
    private val listPosition = IntArray(2)
    private val releaseHandoff = Runnable {
        pullContainer?.let { container -> interceptionBridge?.invoke(container, false) }
        upwardHandoff = false
    }
    private val resetTask = Runnable { applyReset() }
    private fun cancelQueuedReset() {
        if (queued) list.removeCallbacks(resetTask)
        queued = false
    }
    private val traceFile = java.io.File(list.context.getExternalFilesDir(null), "liquidtab-weibo-refresh-touch.txt")
    private val tracing = traceFile.isFile
    private var traceCount = 0
    private fun trace(stage: String, action: Int = -1) {
        if (!tracing || traceCount >= 80 || action == MotionEvent.ACTION_MOVE || !traceFile.isFile) return
        traceCount++
        runCatching { traceFile.appendText("${SystemClock.uptimeMillis()} $stage action=$action listTop=${list.top} y=${list.translationY} dragging=$dragging queued=$queued initial=$initialTop\n") }
    }
    init {
        pages[page] = WeakReference(this); lists[list] = WeakReference(this)
        val dispatch = page.javaClass.getMethod("dispatchTouchEvent", MotionEvent::class.java)
        if (dispatch.declaringClass != ViewGroup::class.java && hooked.add(dispatch)) {
            XposedBridge.hookMethod(dispatch, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    pages[param.thisObject]?.get()?.onTouch(param.args[0] as MotionEvent)
                }
            })
        }
        var ancestor = list.parent as? ViewGroup
        repeat(12) {
            val candidate = ancestor
            if (candidate != null && candidate !== page) {
                if (WeiboHomeTopBinding.name(candidate) == "flow_stream_pull_down") {
                    val method = runCatching { candidate.javaClass.getMethod("onRequestDisallowInterceptTouchEvent", Boolean::class.javaPrimitiveType) }.getOrNull()
                    val enable = runCatching { candidate.javaClass.getMethod("setEnable", Boolean::class.javaPrimitiveType) }.getOrNull()
                    if (method?.returnType == Void.TYPE && enable?.returnType == Void.TYPE) {
                        pullContainer = candidate; interceptionBridge = method
                    }
                    ancestor = null
                } else ancestor = candidate.parent as? ViewGroup
            }
        }
        trace("bound bridge=${pullContainer != null} dispatch=${dispatch.declaringClass.name}")
    }
    private fun onListTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) listReceivedDown = true
        trace("list", event.actionMasked)
    }
    fun sync() {
        val current = list.javaClass.getMethod("getAdapter").invoke(list) ?: return
        if (adapter === current) return
        adapter?.let { adapters.remove(it) }
        adapter = current; adapters[current] = WeakReference(this)
        // Public RecyclerView.Adapter contract; inherited final methods resolve to the base owner.
        val methods = listOf(
            current.javaClass.getMethod("notifyDataSetChanged"),
            current.javaClass.getMethod("notifyItemRangeInserted", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            current.javaClass.getMethod("notifyItemRangeChanged", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            current.javaClass.getMethod("notifyItemRangeChanged", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java)
        )
        methods.forEach { method -> if (hooked.add(method)) XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                adapters[param.thisObject]?.get()?.changed()
            }
        }) }
        if (initialTop) changed()
    }
    private fun onTouch(event: MotionEvent) {
        // The override can call the platform superclass with the same event.
        if (event.eventTime == lastEventTime && event.downTime == lastDownTime) return
        lastEventTime = event.eventTime; lastDownTime = event.downTime
        trace("page", event.actionMasked)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listReceivedDown = false
                list.removeCallbacks(releaseHandoff)
                if (upwardHandoff) releaseHandoff.run()
                touched = true; initialTop = false
                cancelQueuedReset()
                startY = event.y; startX = event.x; dragging = true
                tracking = !list.canScrollVertically(-1)
                expires = 0L; dataChanged = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - startY
                if (!upwardHandoff && dy < -pullThreshold && -dy > kotlin.math.abs(event.x - startX) && (list.top > 0 || SystemClock.uptimeMillis() < pullGestureUntil) && pullContainer != null) {
                    interceptionBridge?.invoke(pullContainer, true)
                    upwardHandoff = true
                    // The native pull wrapper consumed DOWN while rebounding. Give the
                    // list a matching start before it receives the ordinary forwarded MOVE.
                    page.getLocationInWindow(pagePosition); list.getLocationInWindow(listPosition)
                    if (!listReceivedDown) {
                        val down = MotionEvent.obtain(event)
                        try {
                            down.action = MotionEvent.ACTION_DOWN
                            down.offsetLocation((pagePosition[0] - listPosition[0]).toFloat(), (pagePosition[1] - listPosition[1]).toFloat() - dy)
                            list.dispatchTouchEvent(down)
                        } finally { down.recycle() }
                    }
                    trace("upward handoff")
                }
                if (tracking && dy > pullThreshold && dy > kotlin.math.abs(event.x - startX)) {
                    expires = SystemClock.uptimeMillis() + 30_000L
                    pullGestureUntil = SystemClock.uptimeMillis() + 3_000L
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; resetIfReady()
                if (upwardHandoff) list.post(releaseHandoff)
            }
        }
    }
    private fun changed() {
        trace("data")
        if (!initialTop && expires <= SystemClock.uptimeMillis()) return
        val current = adapter ?: return
        if ((current.javaClass.getMethod("getItemCount").invoke(current) as? Int ?: 0) <= 0) return
        dataChanged = true; resetIfReady()
    }
    private fun resetIfReady() {
        if (closed || dragging || queued || !dataChanged || (!initialTop && expires <= SystemClock.uptimeMillis())) return
        queued = true
        if (!list.post(resetTask)) queued = false
    }
    private fun applyReset() {
            trace("reset")
            queued = false
            if (closed || dragging || !list.isAttachedToWindow || !dataChanged || (!initialTop && expires <= SystemClock.uptimeMillis())) return
            dataChanged = false; expires = 0L
            val manager = list.javaClass.getMethod("getLayoutManager").invoke(list)
            val method = manager?.javaClass?.methods?.singleOrNull {
                it.name == "scrollToPositionWithOffset" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            }
            if (method != null) method.invoke(manager, 0, 0)
            else list.javaClass.getMethod("scrollToPosition", Int::class.javaPrimitiveType).invoke(list, 0)
            WeiboHomeTopProbe.record(list.context, "home pull refresh: native data changed; restored first item with safe padding=${list.paddingTop}")
    }
    fun close() {
        closed = true; cancelQueuedReset(); list.removeCallbacks(releaseHandoff)
        if (upwardHandoff) releaseHandoff.run()
        pages.remove(page); lists.remove(list); adapter?.let { adapters.remove(it) }; adapter = null
    }
}

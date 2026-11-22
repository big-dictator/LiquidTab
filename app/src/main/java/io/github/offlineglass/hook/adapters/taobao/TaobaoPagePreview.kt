package io.github.offlineglass.hook.adapters.taobao

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay

/** Transparent input gate. Keep the REAL current page during initial feedback;
 * never insert a blank/screenshot page or copy a hybrid engine render tree. */
internal class TaobaoPagePreview(private val host: GlassHostLayout) {
    var routeDiagnostic: () -> String = { "" }
    var target = -1
        private set
    private var shield: View? = null
    private var generation = 0
    private var tree: ViewTreeObserver? = null
    private var draw: ViewTreeObserver.OnPreDrawListener? = null
    private var committed: Runnable? = null
    private var started = 0L
    private var animationCommitted = 0L
    private var nativeStarted = 0L
    private var dispatched = 0L
    private var fallback = false
    private val retire = Runnable { fallback = true; finish() }

    private class InputShield(host: GlassHostLayout) : View(host.context), AdapterOwnedOverlay {
        init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onTouchEvent(event: MotionEvent): Boolean = true
    }

    fun begin(index: Int): Boolean {
        val parent = host.parent as? FrameLayout ?: return false
        val view = shield ?: InputShield(host).also {
            shield = it
            parent.addView(it, parent.indexOfChild(host).coerceAtLeast(0),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        target = index
        started = SystemClock.uptimeMillis()
        animationCommitted = 0L; nativeStarted = 0L; dispatched = 0L; fallback = false
        view.visibility = View.VISIBLE
        host.postInvalidateOnAnimation()
        return true
    }

    fun feedbackFrameCommitted() { animationCommitted = SystemClock.uptimeMillis() }
    fun nativeStarting() { nativeStarted = SystemClock.uptimeMillis() }

    fun nativeDispatched(routeReady: () -> Boolean) {
        if (target < 0) return
        val token = generation
        dispatched = SystemClock.uptimeMillis()
        tree = host.rootView.viewTreeObserver
        draw = ViewTreeObserver.OnPreDrawListener {
            if (!routeReady()) return@OnPreDrawListener true
            draw?.let { tree?.takeIf { observer -> observer.isAlive }?.removeOnPreDrawListener(it) }
            draw = null
            committed = Runnable { host.post {
                if (token == generation) finish()
            } }
            tree?.takeIf { it.isAlive }?.registerFrameCommitCallback(committed!!)
            true
        }
        tree?.addOnPreDrawListener(draw!!)
        host.rootView.postInvalidateOnAnimation()
        // This bounds only a transparent touch shield, never a blank visual.
        host.postDelayed(retire, 800L)
    }

    fun finish() {
        if (target >= 0 && dispatched > 0L) runCatching {
            val folder = host.context.getExternalFilesDir(null) ?: return@runCatching
            java.io.File(folder, "liquidtab-tab-transition.json").writeText(org.json.JSONObject()
                .put("pid", android.os.Process.myPid()).put("target", target)
                .put("mode", "live-page-no-screenshot").put("cacheBytes", 0)
                .put("route", routeDiagnostic()).put("feedbackCommitMs", animationCommitted - started)
                .put("nativeWaitMs", nativeStarted - animationCommitted)
                .put("nativeClickMs", dispatched - nativeStarted)
                .put("handoffMs", SystemClock.uptimeMillis() - dispatched).put("fallback", fallback).toString())
        }
        generation++
        target = -1
        host.removeCallbacks(retire)
        draw?.let { tree?.takeIf { observer -> observer.isAlive }?.removeOnPreDrawListener(it) }
        committed?.let { tree?.takeIf { observer -> observer.isAlive }?.unregisterFrameCommitCallback(it) }
        draw = null; committed = null; tree = null
        shield?.visibility = View.GONE
        host.postInvalidateOnAnimation()
    }

    fun clear(dispose: Boolean = false) {
        finish()
        shield?.let { (it.parent as? ViewGroup)?.removeView(it) }
        shield = null
    }
}

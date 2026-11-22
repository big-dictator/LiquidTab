package io.github.offlineglass.hook.adapters.meituan_main

import android.os.SystemClock
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.GlassHostLayout

/** Native routing starts only after the complete liquid spring has committed.
 * Meituan-specific click targets stay here; the shared host owns animation. */
internal class MeituanMainTabTransition(
    private val host: GlassHostLayout,
    private val preview: MeituanMainPagePreview,
    private val selected: (ViewGroup) -> Int?,
) {
    private var generation = 0
    private var pending: Runnable? = null
    private var observer: ViewTreeObserver? = null
    private var committed: Runnable? = null
    private var requested = -1
    val pendingTarget: Int? get() = requested.takeIf { it >= 0 && preview.target >= 0 }

    fun dispatch(row: ViewGroup?, index: Int, count: Int): Boolean {
        if (row == null || count != MeituanMainTabContract.SLOT_COUNT ||
            index !in 0 until count || index == MeituanMainTabContract.VIDEO) return false
        val target = MeituanMainNativeTabs.bind(row)?.cells?.firstOrNull { it.role == index }?.button
            ?.takeIf { it.isAttachedToWindow && it.isEnabled } ?: return false
        cancel()
        if (!preview.begin(index)) return false
        requested = index
        val token = generation
        val started = SystemClock.uptimeMillis()
        var waitCommit = false
        var settledCommitted = false
        lateinit var submit: Runnable
        submit = Runnable {
            if (token != generation) return@Runnable
            if (!host.hasWindowFocus() || !target.isAttachedToWindow || !host.isAttachedToWindow) {
                cancel(); return@Runnable
            }
            if (host.adapterNavigationAnimationRunning) {
                settledCommitted = false
                host.postOnAnimation(submit)
                return@Runnable
            }
            if (!settledCommitted) {
                if (!waitCommit) {
                    waitCommit = true
                    observer = host.viewTreeObserver
                    committed = Runnable { host.post {
                        if (token == generation) {
                            committed = null; observer = null
                            waitCommit = false; settledCommitted = true
                            submit.run()
                        }
                    } }
                    observer?.registerFrameCommitCallback(committed!!)
                    host.postInvalidateOnAnimation()
                }
                return@Runnable
            }
            preview.nativeWillDispatch()
            Trace.beginSection("LiquidTab.Meituan.nativeTabClick")
            val clickStart = SystemClock.uptimeMillis()
            val accepted = try { target.performClick() } catch (_: Exception) { false }
                finally { Trace.endSection() }
            val clickMs = SystemClock.uptimeMillis() - clickStart
            android.util.Log.i("MeituanCache", "target=$index animationWindowMs=${clickStart-started} nativeClickMs=$clickMs")
            pending = null
            if (accepted) preview.nativeDispatched { selected(row) == index }
            else { requested = -1; preview.finish() }
            host.postInvalidateOnAnimation()
        }
        pending = submit
        // Two animation callbacks let the host start this tap's spring before
        // the first settled-state query; no fixed time or frame-count cutoff.
        host.postOnAnimation { if (token == generation) host.postOnAnimation(submit) }
        return true
    }

    fun cancel() {
        generation++
        pending?.let(host::removeCallbacks); pending = null
        committed?.let { observer?.takeIf { tree -> tree.isAlive }?.unregisterFrameCommitCallback(it) }
        committed = null; observer = null; requested = -1
        if (preview.target >= 0) preview.finish()
        preview.nativeWillDispatch()
    }
}

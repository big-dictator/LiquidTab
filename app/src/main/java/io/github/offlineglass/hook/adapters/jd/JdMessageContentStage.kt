package io.github.offlineglass.hook.adapters.jd

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedInterface
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException

/** Message pilot: leave routing/network/framework creation native; stage UI snapshots only. */
internal object JdMessageContentStage {
    private fun mark(name: String) {
        if (android.os.Trace.isEnabled()) {
            android.os.Trace.beginSection(name)
            android.os.Trace.endSection()
        }
    }
    private inline fun <T> traced(name: String, action: () -> T): T {
        val enabled = android.os.Trace.isEnabled()
        if (enabled) android.os.Trace.beginSection(name)
        try { return action() } finally { if (enabled) android.os.Trace.endSection() }
    }
    private class Update(val page: WeakReference<Any>, val view: WeakReference<View>,
        val valid: (Any, View) -> Boolean, val replay: (Any) -> Unit)
    private val batch = JdContentBatch<Update>()
    private var host = WeakReference<GlassHostLayout>(null)
    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private var observer: ViewTreeObserver? = null
    private var commitArmed = false
    private var frameCommit: Runnable? = null
    private val handler = android.os.Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private val bound = HashSet<Class<*>>()

    fun request(owner: GlassHostLayout, index: Int) {
        cancel()
        if (index != 2 || !owner.config.enabled) return
        mark("LiquidTab.JD.stage.request")
        host = WeakReference(owner)
        val token = batch.begin(SystemClock.uptimeMillis())
        timeout = Runnable { if (token == batch.generation()) {
            mark("LiquidTab.JD.stage.release.timeout")
            drain(token)
        } }.also { handler.postDelayed(it, 800L) }
    }
    fun cancel(owner: GlassHostLayout? = null) {
        if (owner != null && host.get() !== owner) return
        unobserve()
        batch.cancel()
        host.clear()
    }
    private fun unobserve() {
        frameCommit?.let { if (observer?.isAlive == true) observer?.unregisterFrameCommitCallback(it) }
        frameCommit = null
        listener?.let { if (observer?.isAlive == true) observer?.removeOnPreDrawListener(it) }
        listener = null; observer = null; commitArmed = false
        timeout?.let(handler::removeCallbacks); timeout = null
    }
    private fun live(): GlassHostLayout? = host.get()?.takeIf {
        it.config.enabled && it.isAttachedToWindow && it.hasWindowFocus()
    }
    private fun drain(token: Long) {
        if (token != batch.generation()) return
        val owner = host.get()
        // Turning the adapter off releases pending UI work through native,
        // rather than leaving an already-visible page without its response.
        if (owner == null || !owner.isAttachedToWindow || !owner.hasWindowFocus()) { cancel(); return }
        unobserve()
        val updates = batch.take(token)
        for (update in updates) {
            if (token != batch.generation() || host.get() !== owner || !owner.isAttachedToWindow || !owner.hasWindowFocus()) break
            val page = update.page.get() ?: continue
            val view = update.view.get() ?: continue
            if (!view.isAttachedToWindow || !view.isShown || !update.valid(page, view)) continue
            traced("LiquidTab.JD.stage.native.replay") { update.replay(page) }
        }
    }
    private fun watch(owner: GlassHostLayout, token: Long) {
        if (listener != null) return
        observer = owner.viewTreeObserver
        listener = ViewTreeObserver.OnPreDrawListener {
            if (token != batch.generation() || host.get() !== owner || !owner.isAttachedToWindow || !owner.hasWindowFocus()) cancel(owner)
            else if (!owner.config.enabled) drain(token)
            else if (!owner.adapterNavigationAnimationRunning && !commitArmed) {
                commitArmed = true
                val release = Runnable {
                    if (token == batch.generation()) {
                        commitArmed = false
                        frameCommit = null
                        if (live() === owner && !owner.adapterNavigationAnimationRunning) {
                            mark("LiquidTab.JD.stage.release.commit")
                            drain(token)
                        }
                    }
                }
                // Let the actual resting bar frame commit, no fixed delay.
                runCatching {
                    if (owner.isHardwareAccelerated && observer?.isAlive == true) {
                        frameCommit = Runnable { owner.post(release) }.also { observer?.registerFrameCommitCallback(it) }
                    } else owner.postOnAnimation(release)
                }.onFailure {
                    XposedBridge.log("LiquidTab JD content-stage frame callback unavailable: $it")
                    drain(token)
                }
            }
            true
        }.also { observer?.addOnPreDrawListener(it) }
        owner.postInvalidateOnAnimation()
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val fragment = Class.forName("androidx.fragment.app.Fragment", false, loader)
            val tab = Class.forName("com.jingdong.common.unification.navigationbar.JDTabFragment", false, loader)
            val getActivity = fragment.getMethod("getActivity")
            val getView = fragment.getMethod("getView")
            val framework = XposedBridge.framework()
            framework.hook(fragment.getDeclaredMethod("performAttach"))
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    if (Looper.myLooper() == Looper.getMainLooper()) runCatching {
                        var type: Class<*>? = chain.thisObject.javaClass
                        var page: Class<*>? = null
                        repeat(16) {
                            if (type?.name == "com.jd.lib.message.messagecenter3.MessageCenterFragment") page = type
                            type = type?.superclass
                        }
                        val actual = page
                        if (actual != null && actual !in bound && bound.size < 4) {
                            val model = Class.forName("com.jd.lib.message.messagecenter3.model.MessageItemModel", false, actual.classLoader)
                            val method = JdMessageContentContract.resolve(actual, tab, model)
                            val origin = framework.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN)
                            framework.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { call ->
                                mark("LiquidTab.JD.stage.arrival")
                                if (Looper.myLooper() == Looper.getMainLooper() && host.get()?.config?.enabled == false && !batch.isEmpty())
                                    drain(batch.generation())
                                val owner = if (Looper.myLooper() == Looper.getMainLooper()) live() else null
                                val view = if (owner != null) getView.invoke(call.thisObject) as? View else null
                                val now = SystemClock.uptimeMillis()
                                if (owner == null || view == null || !view.isAttachedToWindow || !view.isShown ||
                                    getActivity.invoke(call.thisObject) !== owner.activityForAdapter() || call.getArg(0) == null) {
                                    mark(if (owner == null) "LiquidTab.JD.stage.direct.noOwner" else "LiquidTab.JD.stage.direct.notMounted")
                                    traced("LiquidTab.JD.stage.native.direct") { call.proceed() }
                                }
                                else {
                                    val token = batch.generation()
                                    if (!batch.open(now) || (!owner.adapterNavigationAnimationRunning && batch.isEmpty())) {
                                        if (!batch.isEmpty()) drain(token)
                                        mark(if (!batch.open(now)) "LiquidTab.JD.stage.direct.closed" else "LiquidTab.JD.stage.direct.animationDone")
                                        traced("LiquidTab.JD.stage.native.direct") { call.proceed() }
                                    } else {
                                        val args = arrayOf<Any?>(call.getArg(0), call.getArg(1))
                                        val update = Update(WeakReference(call.thisObject), WeakReference(view),
                                            { target, root -> getView.invoke(target) === root && getActivity.invoke(target) === owner.activityForAdapter() },
                                            { target ->
                                                try { origin.invoke(target, *args) }
                                                catch (error: InvocationTargetException) { throw (error.targetException ?: error) }
                                            })
                                        if (!batch.offer(token, update)) {
                                            mark("LiquidTab.JD.stage.release.overflow")
                                            drain(token) // overflow preserves native FIFO, then current call
                                            call.proceed()
                                        } else {
                                            mark("LiquidTab.JD.stage.deferred")
                                            watch(owner, token)
                                            null
                                        }
                                    }
                                }
                            }
                            bound.add(actual)
                            mark("LiquidTab.JD.stage.bound")
                        }
                    }.onFailure {
                        mark("LiquidTab.JD.stage.binding.failed")
                        XposedBridge.log("LiquidTab JD content-stage capability unavailable: $it")
                    }
                    chain.proceed()
                }
        }.onFailure { XposedBridge.log("LiquidTab JD content-stage lifecycle unavailable: $it") }
    }
}

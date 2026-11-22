package io.github.offlineglass.hook.adapters.x

import android.app.Activity
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.rendering.LiquidMotion
import io.github.offlineglass.rendering.PostureGlass
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** X reply composer only. Preserve the native editor, gestures and dynamic layout. */
internal object XReplyComposerGlass {
    private const val TAG="OfflineGlassXReply"
    private var bridge: XComposeBridge?=null
    private var activityRef: WeakReference<Activity>?=null
    private var active=false
    private val controllers=WeakHashMap<View,Controller>()
    private val nodes=WeakHashMap<Any,Controller>()
    private data class Observed(val coordinate: WeakReference<Any>,val width: Float,val height: Float)
    private val observed=WeakHashMap<Any,Observed>()
    private val boundCoordinates=WeakHashMap<Any,WeakReference<Any>>()
    private var scheduled=false
    private var lastError=0L

    fun install(bindings: XBindingResolver.Bindings) {
        val binding=XComposeBridge(bindings)
        XReplyPageSource(binding) // Validate all source contracts before installing any hooks.
        bridge=binding
        val surface=requireNotNull(bindings.methods["replySurface"]) { "reply surface semantic scope missing" }
        val stateType=surface.parameterTypes.single { it.name.startsWith("dev.chrisbanes.haze.") }
        val stateIndex=surface.parameterTypes.indices.single { surface.parameterTypes[it]==stateType }
        hook(surface,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) { p.args[stateIndex]?.let(binding::rememberReplyState) }
        })
        XposedBridge.hookAllMethods(Activity::class.java,"onPostResume",object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val activity=p.thisObject as? Activity ?: return
                if(activity.packageName!=XAdapter.PACKAGE_NAME) return
                onHostReady(activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java,"onDestroy",object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val activity=p.thisObject as? Activity ?: return
                if(activity===activityRef?.get()) { controllers.values.toList().forEach { it.close() };controllers.clear();nodes.clear();observed.clear();binding.resetScene() }
            }
        })
        bindings.methods["replyComposer"]?.let { method -> hook(method,object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { if(nodes.isEmpty()) schedule() }
        }) }
        data class Pass(val hook: XC_MethodHook.MethodHookParam,val scope: Any,val frame: XComposeBridge.Frame,
                        val coordinate: Any,var controller: Controller?=null,var shape: XComposeBridge.Shape?=null,
                        var painted: Boolean=false,var outerClip: Int=-1) {
            val node: Any get() = hook.thisObject
        }
        val passes=ThreadLocal.withInitial { ArrayDeque<Pass>() }
        val saved=WeakHashMap<XC_MethodHook.MethodHookParam,Pair<android.graphics.Canvas,Int>>()
        hook(binding.drawMethod,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if(!active) return
                val scope=p.args.firstOrNull() ?: return
                runCatching {
                    if(!binding.ownsReply(p.thisObject)) return
                    val frame=binding.frame(scope) ?: return
                    if(!frame.canvas.isHardwareAccelerated) return
                    val coordinate=binding.coordinator(p.thisObject) ?: return
                    val pass=Pass(p,scope,frame,coordinate)
                    passes.get()?.addLast(pass)
                    observed[p.thisObject]=Observed(WeakReference(coordinate),frame.width,frame.height)
                    val rect=binding.bounds(coordinate) ?: return
                    val controller=uniqueController(binding,p.thisObject,rect,frame.width,frame.height)
                    if(controller==null) { if(controllers.isEmpty()) schedule();return }
                    nodes[p.thisObject]=controller;boundCoordinates[p.thisObject]=WeakReference(coordinate)
                    val config=HookConfigReader.read(controller.anchor.context,XAdapter.PACKAGE_NAME)
                    if(!config.enabled || !config.backdropCapture) return
                    val shape=binding.shape(p.thisObject,scope,frame)
                    pass.controller=controller
                    pass.shape=shape
                    val contour=controller.material.prepareContour(frame,shape)
                    pass.outerClip=frame.canvas.save()
                    frame.canvas.clipPath(contour)
                }.onFailure(::failure)
            }
            override fun afterHookedMethod(p: MethodHookParam) {
                val stack=passes.get() ?: return
                if(stack.lastOrNull()?.hook===p) {
                    val pass=stack.removeLast()
                    if(pass.outerClip>=0) pass.frame.canvas.restoreToCount(pass.outerClip)
                }
            }
        })
        // Material is inserted immediately before native foreground content.
        // The original Haze draw and its try/finally lifecycle always run normally.
        hook(binding.contentMethod,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val pass=passes.get()?.lastOrNull() ?: return
                if(pass.painted || pass.scope!==p.thisObject || !binding.isDrawing(p.thisObject,pass.node)) return
                val node=pass.node
                try {
                    val frame=binding.frame(p.thisObject) ?: return
                    // A renderer may record its foreground into a different layer.
                    // Do not put a backdrop into that recording or redraw its content.
                    if(frame.canvas!==pass.frame.canvas || !frame.canvas.isHardwareAccelerated) return
                    val coordinate=binding.coordinator(node) ?: return
                    val sameGeometry=coordinate===pass.coordinate && frame.size==pass.frame.size
                    val controller=(if(sameGeometry) pass.controller else null) ?: run {
                        if(!binding.ownsReply(node)) return
                        observed[node]=Observed(WeakReference(coordinate),frame.width,frame.height)
                        val rect=binding.bounds(coordinate) ?: return
                        val found=uniqueController(binding,node,rect,frame.width,frame.height) ?: return
                        nodes[node]=found;boundCoordinates[node]=WeakReference(coordinate)
                        found
                    }
                    val shape=(if(sameGeometry) pass.shape else null) ?: binding.shape(node,p.thisObject,frame)
                    pass.painted=true
                    if(!controller.render(node,coordinate,frame,shape)) return
                    if(sameGeometry && pass.outerClip>=0) return
                    val save=frame.canvas.save()
                    frame.canvas.clipPath(controller.material.contour)
                    saved[p]=frame.canvas to save
                } catch(error: Throwable) { failure(error) }
            }
            override fun afterHookedMethod(p: MethodHookParam) {
                saved.remove(p)?.let { (canvas,save) -> canvas.restoreToCount(save) }
            }
        })
    }
    fun onHostReady(activity: Activity) {
        if(bridge==null || activity.packageName!=XAdapter.PACKAGE_NAME || activity.isDestroyed) return
        activityRef=WeakReference(activity)
        // Do not wait for another lifecycle callback when hooks were installed
        // after resume. The visible editor semantics establish the reply scope.
        XPageSemantics.request(activity.window.decorView)
    }
    fun onDetailChanged(enabled: Boolean) {
        // Decompose hints can arrive out of order during expansion/navigation.
        // Visible semantic ownership and native coordinate lifetime are decisive.
        if(enabled) active=true
        schedule()
    }
    private fun schedule() { activityRef?.get()?.window?.decorView?.let(XPageSemantics::request) }
    private fun uniqueController(binding: XComposeBridge,node: Any,rect: RectF,w: Float,h: Float): Controller? {
        val coordinate=observed[node]?.coordinate?.get()
        nodes[node]?.let { current ->
            if(current.anchor.isAttachedToWindow && current.anchor.isShown &&
                boundCoordinates[node]?.get()===coordinate && binding.ownsReply(node)) return current
            nodes.remove(node);boundCoordinates.remove(node)
        }
        val controller=controllers.values.singleOrNull { it.anchor.isAttachedToWindow && it.anchor.isShown && it.matches(rect,w,h) } ?: return null
        val owners=observed.entries.filter { (candidate,seen) ->
            val coordinate=seen.coordinate.get() ?: return@filter false
            val bounds=binding.bounds(coordinate) ?: return@filter false
            binding.ownsReply(candidate) && controller.matches(bounds,seen.width,seen.height)
        }
        return controller.takeIf { owners.size==1 && owners.single().key===node }
    }
    fun onSemantics(anchor: View,target: RectF?) {
        val binding=bridge ?: return
        if(target==null) { controllers[anchor]?.acceptTarget(null);return }
        active=true
        controllers.getOrPut(anchor) { Controller(anchor,binding) }.acceptTarget(target)
    }
    private fun failure(error: Throwable) {
        val now=SystemClock.uptimeMillis()
        if(now-lastError<2000) return
        lastError=now
        android.util.Log.w(TAG,"Native composer retained",error)
        val context=activityRef?.get() ?: return
        runCatching { java.io.File(context.getExternalFilesDir(null),"liquidtab-x-reply.txt")
            .writeText(error.stackTraceToString().take(6000)) }
    }
    private fun hook(method: java.lang.reflect.Method,callback: XC_MethodHook) {
        XposedBridge::class.java.getMethod("hookMethod",java.lang.reflect.Member::class.java,XC_MethodHook::class.java)
            .invoke(null,method,callback)
    }
    private class Controller(val anchor: View,private val bridge: XComposeBridge) {
        val source=XReplyPageSource(bridge)
        val material=XReplyMaterial(anchor,source,bridge)
        var target: RectF?=null
        private var lastReport=""
        private var lastReportAt=0L
        private var lastPose=Long.MIN_VALUE
        private var moving=false
        private var lastConfigHash=0
        private var renderOutcome="awaiting native draw"
        private var observer: ViewTreeObserver?=null
        private var missingSince=0L
        private val confirmMissing=Runnable {
            if(missingSince!=0L && SystemClock.uptimeMillis()-missingSince>=350L) releaseBindings()
        }
        private val preDraw=ViewTreeObserver.OnPreDrawListener {
            if(active && target!=null) {
                if(!material.syncExterior()) invalidate()

                val cfg=HookConfigReader.read(anchor.context,XAdapter.PACKAGE_NAME)
                if(lastConfigHash!=cfg.hashCode()) { lastConfigHash=cfg.hashCode();invalidate() }
                val enabled=cfg.enabled && cfg.postureEffectsEnabled
                val revision=PostureGlass.revision(anchor,enabled)
                val motion=LiquidMotion.sample(anchor,cfg.liquidViscosity,enabled)
                if(revision!=lastPose || motion.active || moving) invalidate()
                lastPose=revision;moving=motion.active
            }
            true
        }
        private val attachment=object: View.OnAttachStateChangeListener {
            override fun onViewDetachedFromWindow(view: View) { clear();detachObserver() }
            override fun onViewAttachedToWindow(view: View) { attachObserver();XPageSemantics.request(anchor) }
        }
        init {
            anchor.addOnAttachStateChangeListener(attachment)
            attachObserver()
            XPageSemantics.request(anchor)
        }
        private fun attachObserver() {
            val current=anchor.viewTreeObserver
            if(observer===current) return
            detachObserver();observer=current

            current.addOnPreDrawListener(preDraw)
        }
        private fun detachObserver() {
            observer?.takeIf { it.isAlive }?.let {

                it.removeOnPreDrawListener(preDraw)
            }
            observer=null
        }
        fun matches(rect: RectF,w: Float,h: Float): Boolean {
            val t=target ?: return false
            if(abs(rect.left-t.left)>=4f || abs(rect.top-t.top)>=4f || abs(rect.width()-t.width())>=4f ||
                abs(w-rect.width())>=4f || abs(h-rect.height())>=4f) return false
            // Accessibility bounds may be clipped by the IME/animated parent;
            // the verified native surface still owns its full measured height.
            // Ownership/state and uniqueness are checked by the caller as well.
            return abs(rect.height()-t.height())<4f ||
                (rect.bottom>=t.bottom && t.height()>=rect.height()*.5f)
        }
        fun render(node: Any,coordinate: Any,frame: XComposeBridge.Frame,shape: XComposeBridge.Shape): Boolean {
            val rendered=material.draw(frame.canvas,node,coordinate,frame,shape)
            renderOutcome="rendered=$rendered; ${material.diagnostic}"
            report("target=$target; rendered=$rendered; nativeBordersRemoved=${XReplyNativeBorder.removed}; footerMasksRemoved=${XReplyBackdropMask.removed}; ${material.diagnostic}; ${source.diagnostic}")
            return rendered
        }
        fun invalidate() { nodes.entries.filter { it.value===this }.forEach { runCatching { bridge.invalidate(it.key) } } }
        fun acceptTarget(found: RectF?) {
            // The native binding may survive a semantic rebuild, but a root-overlay
            // decoration must never survive the visible editor that owns it.
            material.setExteriorVisible(found!=null)
            if(found==null) {
                if(missingSince==0L) {
                    missingSince=SystemClock.uptimeMillis()
                    anchor.postDelayed(confirmMissing,350L)
                }
                return
            }
            val changed=target!=found || missingSince!=0L
            missingSince=0L;anchor.removeCallbacks(confirmMissing)
            target=found
            // Reject duplicate native draw owners instead of selecting by order.
            val candidates=observed.entries.toList().mapNotNull { (node,seen) ->
                if(!bridge.ownsReply(node)) return@mapNotNull null
                val coordinate=seen.coordinate.get() ?: return@mapNotNull null
                val bounds=bridge.bounds(coordinate) ?: return@mapNotNull null
                if(matches(bounds,seen.width,seen.height)) node to coordinate else null
            }
            if(candidates.size==1) {
                val (node,coordinate)=candidates.single()
                if(nodes[node]!==this || boundCoordinates[node]?.get()!==coordinate) {
                    nodes[node]=this;boundCoordinates[node]=WeakReference(coordinate)
                    anchor.post { invalidate();anchor.invalidate() }
                }
            } else if(candidates.size>1) {
                candidates.forEach { (node,_) ->
                    if(nodes.remove(node)!=null) runCatching { bridge.invalidate(node) }
                    boundCoordinates.remove(node)
                }
            }
            val evidence=if(found!=null && candidates.isEmpty()) observed.entries.take(6).joinToString { (node,seen) ->
                "${seen.width.toInt()}x${seen.height.toInt()}/${seen.coordinate.get()?.let(bridge::bounds)}/owned=${bridge.ownsReply(node)}"
            } else ""
            report("semantics target=$found; nativeCandidates=${candidates.size}; bound=${nodes.values.count { it===this }}; $renderOutcome; observed=[$evidence]; source=${source.diagnostic}")
            if(changed) invalidate()
        }
        private fun report(text: String) {
            val now=SystemClock.uptimeMillis()
            if(text==lastReport || now-lastReportAt<2000L) return
            lastReportAt=now
            lastReport=text
            runCatching { java.io.File(anchor.context.getExternalFilesDir(null),"liquidtab-x-reply.txt").writeText(text) }
        }
        fun clear() {
            anchor.removeCallbacks(confirmMissing);missingSince=0L
            releaseBindings()
            source.clear()
            observed.clear()
        }
        private fun releaseBindings() {
            val mine=nodes.entries.filter { it.value===this }.map { it.key }
            mine.forEach { runCatching { bridge.invalidate(it) };nodes.remove(it);boundCoordinates.remove(it) }
            target=null;renderOutcome="no visible reply editor";material.close()
        }
        fun close() {
            anchor.removeOnAttachStateChangeListener(attachment)

            clear()
            detachObserver()
        }
    }
}


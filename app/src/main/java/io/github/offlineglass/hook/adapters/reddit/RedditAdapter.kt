package io.github.offlineglass.hook.adapters.reddit

import android.app.Activity
import android.graphics.Canvas
import android.graphics.RenderNode
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.targets.TargetSpec
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal object RedditAdapter : TargetAdapter {
    override val key = "reddit"
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val skipNavigationSnapshot = true
    override val nativeSelectionReliable = true
    override val usesContentDarkMode = true
    override val recheckNavigationBindingOnLayout = true
    override val drawsNavigationInOptics = true
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = true
    override val foregroundNavigationOverSelection = true
    override val hasPerFrameScene = true
    private val sessions = WeakHashMap<Activity, Session>()
    private var expandedFraction = 1f
    private var expansion: RedditExpansion? = null
    private fun sampleExpansion() {
        expansion?.let { state -> runCatching { state.value() }.onSuccess { expandedFraction=it }
            .onFailure { XposedBridge.log("[LiquidTab][Reddit] expansion state rejected: $it"); expansion=null } }
    }
    override fun installHooks(param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        runCatching {
            val screen = param.classLoader.loadClass("com.reddit.launch.bottomnav.BottomNavScreen")
            // APK call-chain evidence: the screen's sole interface float callback
            // receives the feed's native expandedFraction and drives its Animatable.
            val callback = screen.declaredMethods.filter { m ->
                m.returnType == Void.TYPE && m.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType)) &&
                    screen.interfaces.any { api -> api.methods.any { it.name == m.name && it.parameterTypes.contentEquals(m.parameterTypes) } }
            }.singleOrNull() ?: error("native expanded-fraction callback ambiguous")
            XposedBridge.hookMethod(callback, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (expansion == null) expansion=RedditExpansion.bind(param.thisObject)
                    sessions.values.forEach { it.requestVisibilityFrame() }
                }
            })
            XposedBridge.hookAllConstructors(screen, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    expansion=RedditExpansion.bind(param.thisObject)
                    check(expansion != null) { "native expansion state missing or ambiguous" }
                    sampleExpansion()
                }
            })
        }.onFailure { XposedBridge.log("[LiquidTab][Reddit] native visibility contract failed: $it") }
    }

    override fun beforeNavigationScan(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        sessions.getOrPut(activity) { Session(content) }.refresh()
    }
    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? =
        sessions.values.filter { it.content.rootView === root.rootView && it.ready }
            .singleOrNull()?.let { NavigationCandidate(it.proxy, it.binding!!.tabs.size, 1000) }
    override fun acceptsNavigationSource(navigation: ViewGroup) = navigation is Source
    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? =
        (group as? Source)?.session?.binding?.tabs?.size
    override fun createNavigationState(context: android.content.Context): AppNavigationState = State()

    private class Source(val session: Session) : FrameLayout(session.content.context) {
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS; isClickable = false }
    }
    private class Artwork(val layout: Any, var node: RenderNode = RenderNode("reddit-native-tab")) {
        var pending = RenderNode("reddit-native-tab-pending")
        var image: android.graphics.Bitmap? = null
        var recorded = false
        var requested = true
    }
    private class Session(val content: ViewGroup) {
        val proxy = Source(this)
        private val semantics = RedditSemantics()
        var binding: RedditSemantics.Binding? = null; private set
        var active = false
        private var ownsNativeVisuals = false
        private var available = true
        private var lastRefresh = 0L
        private var lastReport = ""
        private var artworkTheme = content.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        private var maskFailureReported=false
        private var maskStatus: Boolean? = null
        val nativeTravelHeight get() = binding?.bounds?.height()
        private fun ensureNativeMask(b: RedditSemantics.Binding): Boolean {
            val result=runCatching { RedditNativeLayer.bind(b.maskLayout,b.root.javaClass,b.draw.parameterTypes) }.getOrDefault(false)
            if(maskStatus!=result) {
                maskStatus=result
                runCatching { java.io.File(content.context.getExternalFilesDir(null),"liquidtab-reddit-layer.txt")
                    .writeText("pid=${android.os.Process.myPid()} bound=$result masked=${RedditNativeLayer.isMasked(b.maskLayout)}\n") }
            }
            if(!result && !maskFailureReported) {
                maskFailureReported=true
                XposedBridge.log("[LiquidTab][Reddit] native composite layer contract unavailable")
            }
            return result
        }
        private var artworks = emptyList<Artwork>()
        private val hooks = ArrayList<XC_MethodHook.Unhook>()
        private var recording = false
        private var captureArtwork: (() -> Unit)? = null
        private var host: java.lang.ref.WeakReference<View>? = null
        val ready get() = expansion != null && binding != null && proxy.isAttachedToWindow && binding!!.owner.isShown &&
            artworks.size == binding!!.tabs.size && artworks.all { it.recorded }
        init {
            proxy.visibility = View.INVISIBLE
            content.addView(proxy, FrameLayout.LayoutParams(1, 1))
            // The first scanner callback may run before Compose has completed
            // layout. Resolve once immediately before the first drawable frame,
            // rather than leaving native chrome visible until the delayed retry.
            content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if(content.viewTreeObserver.isAlive)content.viewTreeObserver.removeOnPreDrawListener(this)
                    if(binding==null)refresh(true)
                    return true
                }
            })
            content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    clear(); (proxy.parent as? ViewGroup)?.removeView(proxy)
                    sessions.entries.removeAll { it.value === this@Session }
                }
            })
        }
        fun refresh(force: Boolean = false) {
            val now = SystemClock.uptimeMillis()
            if (!force && now - lastRefresh < 350L) return
            if (!force && binding?.owner?.let { !it.isDirty && !it.isLayoutRequested } == true && now-lastRefresh < 2000L) return
            lastRefresh = now
            if (!HookConfigReader.read(content.context, "com.reddit.frontpage").enabled) { deactivate(); return }
            // Native alpha/height change without changing tab identities or artwork.
            // Clipped accessibility bounds during that animation are not a rebind.
            if (ownsNativeVisuals && expandedFraction < .999f && binding?.owner?.isAttachedToWindow == true) return
            val owners = ArrayList<View>()
            var visited = 0
            fun visit(view: View, depth: Int) {
                if (depth > 20 || visited++ >= 1600 || view === proxy || view is GlassHostLayout || !view.isShown) return
                if (view.javaClass.methods.any { it.name == "getSemanticsOwner" && it.parameterCount == 0 }) owners += view
                else if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
            }
            visit(content, 0)
            val matches = owners.mapNotNull(semantics::read)
            val next = matches.singleOrNull()
            val report = if (next == null) "native fallback: ${if (matches.size > 1) "multiple bottom bars" else semantics.failure}" else "verified ${next.tabs.size} native tabs"
            if (report != lastReport) {
                lastReport = report
                XposedBridge.log("[LiquidTab][Reddit] $report")
                runCatching { java.io.File(content.context.getExternalFilesDir(null), "liquidtab-reddit-binding.txt")
                    .writeText("pid=${android.os.Process.myPid()} owners=${owners.size} matches=${matches.size}\n$report\n") }
            }
            if (next == null) {
                // A native scroll collapse can temporarily remove semantics/bounds.
                // Keep the already-verified native draw mask through that transition.
                if (binding?.owner?.isAttachedToWindow == true && ownsNativeVisuals) {
                    available=false
                    return
                }
                clear(); return
            }
            available=true
            val previous = binding
            val unchanged = previous != null && previous.owner === next.owner && previous.bar.layout === next.bar.layout &&
                previous.maskLayout === next.maskLayout &&
                previous.tabs.map { it.entry.layout } == next.tabs.map { it.entry.layout }
            if (!unchanged) {
                // Acquire the new native visual branch before releasing any old
                // binding. Artwork/host readiness must never expose native pixels.
                if(!ensureNativeMask(next))return
                clear(restoreNative=false)
                binding = next
                ownsNativeVisuals=true
                artworks = next.tabs.map { Artwork(it.entry.layout) }
                if (!install(next)) { clear(); return }
                captureArtwork?.invoke()
                next.invalidateLayers.invoke(null, next.root)
                next.owner.invalidate()
            } else {
                val theme=content.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                next.tabs.forEachIndexed { i, tab ->
                    if (previous!!.tabs[i].revision != tab.revision || previous.bounds != next.bounds || artworkTheme != theme) artworks[i].requested = true
                    if (previous.bounds.width() != next.bounds.width() || previous.bounds.height() != next.bounds.height()) artworks[i].recorded = false
                }
                binding = next
                if(ownsNativeVisuals) ensureNativeMask(next)
                artworkTheme=theme
                if (artworks.any { it.requested }) {
                    captureArtwork?.invoke()
                }
            }
            val pos = IntArray(2).also(content::getLocationOnScreen)
            val b = next.bounds
            val lp = proxy.layoutParams as FrameLayout.LayoutParams
            if (lp.width != b.width() || lp.height != b.height() || lp.leftMargin != b.left-pos[0] || lp.topMargin != b.top-pos[1]) {
                lp.width=b.width();lp.height=b.height();lp.leftMargin=b.left-pos[0];lp.topMargin=b.top-pos[1]
                proxy.layoutParams=lp
            }
            proxy.visibility = if (artworks.all { it.recorded }) View.VISIBLE else View.INVISIBLE
        }
        fun attach(host: View) {
            if (active && this.host?.get() === host) return
            if(binding?.let(::ensureNativeMask)!=true)return
            this.host = java.lang.ref.WeakReference(host); active = true; ownsNativeVisuals=true
            binding?.let { it.invalidateLayers.invoke(null,it.root); it.owner.invalidate() }
        }
        fun requestVisibilityFrame() { host?.get()?.postInvalidateOnAnimation(); content.invalidate() }
        fun releaseHost() {
            active=false;host=null
            // Host recreation is not permission to display native chrome. Only
            // an explicit disabled config or the window teardown releases it.
            if(!HookConfigReader.read(content.context,"com.reddit.frontpage").enabled)deactivate()
        }
        fun deactivate() {
            if (!active && host == null && !ownsNativeVisuals) return
            active = false; ownsNativeVisuals=false; host = null
            binding?.let { RedditNativeLayer.clear(it.maskLayout) }
            RedditNativeLayer.restoreRetired()
            binding?.let { it.invalidateLayers.invoke(null,it.root);it.owner.invalidate() }
        }
        fun allowsNavigation() = available && ready && expandedFraction > 0f
        private fun clear(restoreNative: Boolean = true) {
            binding?.let { if(restoreNative) RedditNativeLayer.clear(it.maskLayout) else RedditNativeLayer.retire(it.maskLayout) }
            if(restoreNative)RedditNativeLayer.restoreRetired()
            active = false
            ownsNativeVisuals=false
            hooks.forEach { it.unhook() }; hooks.clear()
            captureArtwork=null
            artworks.forEach { it.node.discardDisplayList();it.pending.discardDisplayList();it.image=null }; artworks = emptyList()
            binding?.owner?.invalidate(); binding = null
            proxy.visibility = View.INVISIBLE
        }
        private fun install(b: RedditSemantics.Binding): Boolean = runCatching {
            val snapshot=android.graphics.HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
                RenderNode::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply { isAccessible=true }
            val nativeCanvasField = b.canvasField
            val wrapper = b.canvasType.getConstructor().newInstance()
            var normalizeFirstTranslation = false
            val normalize=object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (recording && normalizeFirstTranslation && param.thisObject === wrapper) {
                        // LayoutNode.draw starts with its parent's placement offset.
                        // The retained image is local to this native tab, independent
                        // of which ancestor Compose graphics layer is recording it.
                        normalizeFirstTranslation = false
                        param.result = null
                    }
                }
            }
            captureArtwork = capture@ {
                val current=binding ?: return@capture
                val normalizeHandle=XposedBridge.hookMethod(b.translate,normalize)
                try {
                for ((index,art) in artworks.withIndex()) {
                    if(!art.requested)continue
                    val rect=current.tabs[index].bounds
                    art.pending.setPosition(0,0,rect.width(),rect.height())
                    val canvas=art.pending.beginRecording(rect.width(),rect.height())
                    var success=false
                    recording=true;normalizeFirstTranslation=true
                    try {
                        nativeCanvasField.set(wrapper,canvas)
                        current.draw.invoke(art.layout,wrapper,null)
                        success=true
                    } catch(error: Throwable) {
                        XposedBridge.log("[LiquidTab][Reddit] independent artwork contract failed: $error")
                    } finally {
                        nativeCanvasField.set(wrapper,Canvas())
                        recording=false;art.pending.endRecording()
                    }
                    if(!success)continue
                    val image=runCatching { snapshot.invoke(null,art.pending,rect.width(),rect.height()) as? android.graphics.Bitmap }.getOrNull() ?: continue
                    art.image=image
                    val old=art.node;art.node=art.pending;art.pending=old
                    art.recorded=true;art.requested=false
                }
                } finally { normalizeHandle.unhook() }
                host?.get()?.invalidate()
            }
            true
        }.onFailure { XposedBridge.log("[LiquidTab][Reddit] draw binding rejected: $it") }.getOrDefault(false)
        fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
            val b = binding ?: return
            if (!ready || !canvas.isHardwareAccelerated) return
            val scale = minOf(frame.height.toFloat()/b.bounds.height(), frame.slotWidth/(b.bounds.width().toFloat()/b.tabs.size))
            frame.enabledIndices.forEachIndexed { visual, index ->
                val tab = b.tabs.getOrNull(index) ?: return@forEachIndexed
                val artwork = artworks.getOrNull(index) ?: return@forEachIndexed
                val cx = (visual + .5f)*frame.slotWidth + 4f*frame.density
                val save = canvas.save()
                canvas.translate(cx, frame.height*.5f)
                canvas.scale(scale, scale)
                canvas.translate(-tab.bounds.width()*.5f, -tab.bounds.height()*.5f)
                canvas.clipRect(0, 0, tab.bounds.width(), tab.bounds.height())
                artwork.image?.let { canvas.drawBitmap(it,0f,0f,null) }
                canvas.restoreToCount(save)
            }
        }
        fun click(index: Int): Boolean {
            refresh(true) // Never dispatch a callback retained across native screen reconstruction.
            if (!available || !ready || expandedFraction <= 0f) return false
            val tab = binding?.tabs?.getOrNull(index) ?: return false
            return semantics.click(tab).also { if (it) { lastRefresh=0; content.post { refresh(true) } } }
        }
    }
    private class State : AppNavigationState {
        private var session: Session? = null
        override val readyForDrawing get() = session?.ready == true
        override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) { session=(source as? Source)?.session }
        override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
                                   density: Float, barHeightPx: Int, surfaceColor: () -> Int) {
            // prepare() runs on the draw path. Bind before the visibility decision so a
            // newly created, initially hidden host can reach its first draw at all.
            session = (source as? Source)?.session ?: session
            sampleExpansion()
            session?.let { s -> s.refresh(); if (!enabled) s.deactivate() else if (s.ready) s.attach(host) }
            host.alpha=expandedFraction
            // Native placement uses round((1-fraction) * native measured height),
            // not the reconstructed capsule's different height (verified fallback DEX).
            host.translationY=((1f-expandedFraction)*(session?.nativeTravelHeight ?: host.height)).roundToInt().toFloat()
        }
        override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? =
            session?.binding?.tabs?.indexOfFirst { it.entry.selected == true }?.takeIf { it >= 0 }
        override fun pageAllowsNavigation(root: View?): Boolean = session?.allowsNavigation() == true
        override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) { session?.draw(canvas,frame) }
        override fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame,
                                    selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
            val mix=frame.navigationOpticalMix.coerceIn(0f,1f)
            if(mix<=0f || selectionPath.isEmpty) { drawSource();return true }
            val outside=canvas.save()
            canvas.clipOutPath(selectionPath)
            drawSource()
            canvas.restoreToCount(outside)
            if(mix<1f) {
                val inside=canvas.save()
                canvas.clipPath(selectionPath)
                canvas.saveLayerAlpha(0f,0f,frame.width.toFloat(),frame.height.toFloat(),((1f-mix)*255f).toInt())
                drawSource()
                canvas.restoreToCount(inside)
            }
            return true
        }
        override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = session?.click(index) == true
        override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true
        override fun onHostDetached() { session?.releaseHost() }
        override fun dispose() { session?.releaseHost(); session=null }
    }
}


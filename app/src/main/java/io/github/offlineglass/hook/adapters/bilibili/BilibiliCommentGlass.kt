package io.github.offlineglass.hook.adapters.bilibili


import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.glassHostBottomMarginPx
import io.github.offlineglass.hook.symmetricGlassSideMarginPx
import io.github.offlineglass.hook.setBottomBarSquircle
import io.github.offlineglass.rendering.KYANT_LENS_SHADER
import io.github.offlineglass.rendering.NativeBackdropBlur
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/** A bridge into the host's Compose ABI; native text, semantics and clicks stay in Compose. */
internal object BilibiliCommentGlass {
    private val owners = WeakHashMap<View, Owner>()
    private val composing = ThreadLocal<Owner?>()
    private var bridge: Bridge? = null
    private var sceneBaseColor = 0xFF17181A.toInt()

    fun available(): Boolean = bridge != null
    fun isRegisteredView(view: View): Boolean = owners.containsKey(view)

    fun pageColor(packed: Long) { sceneBaseColor = (packed ushr 32).toInt() or Color.BLACK }

    fun foregroundColor(): Long? {
        val view = composing.get()?.view?.get() ?: return null
        val dark = view.resources.configuration.uiMode and 0x30 == 0x20
        return (if (dark) Color.WHITE else Color.BLACK).toLong() shl 32
    }

    fun viewport(view: View, modifier: Any, continueBehindInput: Boolean = true, listKey: Any? = null): Any {
        val api = bridge ?: return modifier
        val owner = owners.getOrPut(view) { Owner(view, api) }
        owner.continueBehindInput = continueBehindInput
        return owner.decorateScene(modifier, listKey)
    }

    fun replyViewport(view: View, modifier: Any, extent: Int,
        wrapHeight: Method, requiredHeight: Method, top: Any, listKey: Any? = null): Any {
        val api = bridge ?: return modifier
        val owner = owners.getOrPut(view) { Owner(view, api) }
        owner.continueBehindInput = false
        return owner.decorateScene(modifier, listKey)
    }

    fun viewportHeight(view: View, container: View): Int {
        val api = bridge ?: return container.height
        return owners.getOrPut(view) { Owner(view, api) }.viewportHeight(container)
    }

    fun begin(args: Array<Any?>) {
        val api = bridge ?: return
        runCatching {
            val view = XposedHelpers.callMethod(args[8], api.consumeName, api.localView) as View
            val owner = owners.getOrPut(view) { Owner(view, api) }
            composing.set(owner)
            val density = view.resources.displayMetrics.density
            val side = symmetricGlassSideMarginPx(view, density) / density
            val bottom = glassHostBottomMarginPx(view) / density
            // Draw outside the padding as well, including the gesture-side gap.
            val nativeModifier = args[4] ?: api.modifier.getField("Companion").get(null)
            var continuedScene = owner.decorateInputScene(requireNotNull(nativeModifier))
            val inMainContainer = generateSequence(view as View?) { it.parent as? View }.any {
                it.id != View.NO_ID && runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull() == "comment_container"
            }
            if (!inMainContainer) {
                // Replies use a native Column with a weighted list and input sibling.
                // Reserve zero for the input, but bottom-align its normal unbounded
                // child above that zero-height slot. The real list now fills beneath
                // it; no duplicated scene or list measurement feedback is required.
                val size = api.modifier.classLoader.loadClass("androidx.compose.foundation.layout.SizeKt")
                val required = size.declaredMethods.single { it.name.startsWith("requiredHeight-") && it.parameterTypes.size == 2 }
                val wrap = size.declaredMethods.single { it.name == "wrapContentHeight" && it.parameterTypes.size == 3 }
                val alignment = api.modifier.classLoader.loadClass("androidx.compose.ui.Alignment").getField("Companion").get(null)
                val bottomAlignment = XposedHelpers.callMethod(alignment, "getBottom")
                val companion = api.modifier.getField("Companion").get(null)
                val floating = wrap.invoke(null, required.invoke(null, companion, 0f), bottomAlignment, true)
                continuedScene = api.modifier.getMethod("then", api.modifier).invoke(floating, continuedScene)
                owner.continueBehindInput = false
            }
            args[4] = api.padding.invoke(null, continuedScene, side, 0f, side, bottom)
            // Modifier participates in the host's normal change detection.
            args[9] = (args[9] as Int) and 24576.inv()
        }.onFailure { XposedBridge.log("[LiquidTab/BilibiliCommentGlass] composition: $it") }
    }

    fun end() = composing.remove()

    fun install(loader: ClassLoader, international: Boolean = false) {
        runCatching {
            val api = Bridge(loader, international)
            bridge = api
            if (international) return
            val binding = BilibiliCommentBinding.current ?: error("Comment call graph unavailable")
            XposedBridge.hookMethod(binding.capsule, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = composing.get() ?: return
                    if (param.args.size != 3 || (param.args[0] as? Number)?.toFloat() != 16f || param.result == null) return
                    runCatching {
                        // The space belongs to the weighted native box, so hit regions remain separate.
                        val inset = api.padding.invoke(null, param.result, 0f, 0f, 8f, 0f)
                        param.result = owner.text.decorate(inset)
                    }.onFailure { XposedBridge.log("[LiquidTab/BilibiliCommentGlass] text: $it") }
                }
            })
            XposedBridge.hookMethod(binding.emoji, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val owner = composing.get() ?: return
                    if (param.args.size != 4 || param.args[0] != 44f || param.result == null ||
                        !api.modifier.isInstance(param.result)) return
                    param.result = owner.emoji.decorate(param.result)
                }
            })
            val padding = XposedHelpers.findClass("androidx.compose.foundation.layout.PaddingKt", loader)
            val rowPadding = padding.declaredMethods.single {
                it.name.startsWith("padding-") && it.name.endsWith("\$default") &&
                    it.parameterTypes.contentEquals(arrayOf(api.modifier, Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType, Any::class.java))
            }
            XposedBridge.hookAllMethods(padding, rowPadding.name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (composing.get() == null || param.args.size != 7 || param.args[1] != 12f || param.args[5] != 10) return
                    // Shared outer margins replace the host's asymmetric 12/0 dp row padding.
                    param.args[1] = 0f
                    param.args[3] = 0f
                }
            })
            val size = XposedHelpers.findClass("androidx.compose.foundation.layout.SizeKt", loader)
            val minHeight = size.declaredMethods.single {
                it.name.startsWith("heightIn-") && it.name.endsWith("\$default") && it.parameterTypes.size == 5
            }
            XposedBridge.hookAllMethods(size, minHeight.name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (composing.get() != null && param.args.size == 5 && param.args[1] == 30f) param.args[1] = 44f
                }
            })
            // Native click availability is preserved, but the foreground must remain fully
            // white/black and the glass must not expose sharp text from beneath an alpha layer.
            XposedBridge.hookAllMethods(XposedHelpers.findClass("androidx.compose.ui.draw.AlphaKt", loader), "alpha", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (composing.get() != null && param.args.size == 2) param.result = param.args[0]
                }
            })
            // Our continuous glass path owns clipping; native 16 dp clipping would cut off the
            // enlargement animation and reintroduce a different corner curve on the text box.
            XposedBridge.hookAllMethods(XposedHelpers.findClass("androidx.compose.ui.draw.ClipKt", loader), "clip", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (composing.get() != null && param.args.size == 2) param.result = param.args[0]
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name !in setOf("com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity",
                            "com.bilibili.ship.theseus.playlist.UnitedPlaylistActivity")) return
                    val event = param.args.firstOrNull() as? MotionEvent ?: return
                    owners.values.toList().forEach { owner ->
                        if (owner.view.get()?.rootView === activity.window.decorView) owner.touch(event)
                    }
                }
            })
        }.onFailure {
            bridge = null
            XposedBridge.log("[LiquidTab/BilibiliCommentGlass] unsupported ABI: $it")
            val error = it.stackTraceToString()
            XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? Activity)?.getExternalFilesDir(null)
                        ?.resolve("liquidtab-comment-glass-error.txt")?.writeText(error)
                }
            })
        }
    }

    internal fun decorateInternational(modifier: Any, circular: Boolean): Any? {
        val owner = composing.get() ?: return null
        val spaced = if (circular) modifier else owner.api.padding.invoke(null, modifier, 0f, 0f, 8f, 0f)
        return (if (circular) owner.emoji else owner.text).decorate(spaced)
    }
    internal fun internationalView(composer: Any): View? {
        val api = bridge ?: return null
        return XposedHelpers.callMethod(composer, api.consumeName, api.localView) as? View
    }
    internal fun touchActivity(activity: Activity, event: MotionEvent) {
        owners.values.toList().forEach { if (it.view.get()?.rootView === activity.window.decorView) it.touch(event) }
    }
    private class Bridge(loader: ClassLoader, val international: Boolean) {
        val consumeName = if (international) "r" else "consume"
        val contextName = if (international) "c0" else "getDrawContext"
        val canvasName = if (international) "a" else "getCanvas"
        val sizeName = if (international) "e" else "getSize-"
        val drawContentName = if (international) "C1" else "drawContent"
        val setCanvasName = if (international) "f" else "setCanvas"
        val modifier = XposedHelpers.findClass(if (international) "androidx.compose.ui.j" else "androidx.compose.ui.Modifier", loader)
        val function = XposedHelpers.findClass("kotlin.jvm.functions.Function1", loader)
        val unit = XposedHelpers.findClass(if (international) "Gy1.q" else "kotlin.Unit", loader).getField(if (international) "a" else "INSTANCE").get(null)
        val localView = if (international) XposedHelpers.findClass("androidx.compose.ui.platform.J", loader).getDeclaredField("f").get(null)
            else XposedHelpers.findClass("androidx.compose.ui.platform.AndroidCompositionLocals_androidKt", loader).getDeclaredMethod("getLocalView").invoke(null)
        val padding: Method = XposedHelpers.findClass(if (international) "d0.w0" else "androidx.compose.foundation.layout.PaddingKt", loader)
            .declaredMethods.single { if (international) it.name == "l" && it.parameterTypes.size == 5 else
                it.name.startsWith("padding-") && it.parameterTypes.contentEquals(arrayOf(modifier,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)) }
        val draw: Method = XposedHelpers.findClass(if (international) "I0.l" else "androidx.compose.ui.draw.DrawModifierKt", loader)
            .declaredMethods.single { it.name == (if (international) "c" else "drawWithContent") && it.parameterTypes.size == 2 }
        val position: Method = XposedHelpers.findClass(if (international) "X0.c0" else "androidx.compose.ui.layout.OnGloballyPositionedModifierKt", loader)
            .declaredMethods.single { it.name == (if (international) "a" else "onGloballyPositioned") && it.parameterTypes.size == 2 }
        val canvas: Method = XposedHelpers.findClass(if (international) "L0.z" else "androidx.compose.ui.graphics.AndroidCanvas_androidKt", loader)
            .declaredMethods.single { it.name == (if (international) "a" else "getNativeCanvas") && it.parameterTypes.size == 1 }
        private val composeCanvasClass = if (international) XposedHelpers.findClass("L0.y", loader) else null
        private val composeCanvas: Method? = if (international) null else XposedHelpers.findClass("androidx.compose.ui.graphics.AndroidCanvas_androidKt", loader)
            .declaredMethods.single { it.name == "Canvas" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java)) }
        private val state: Method = XposedHelpers.findClass(if (international) "androidx.compose.runtime.u1" else "androidx.compose.runtime.SnapshotStateKt", loader)
            .methods.single { if (international) it.name == "h" && it.parameterTypes.size == 1 else it.name == "mutableStateOf\$default" && it.parameterTypes.size == 4 }
        fun newState(): Any = if (international) state.invoke(null, 0L) else state.invoke(null, 0L, null, 2, null)
        fun wrapCanvas(canvas: Canvas): Any = if (international) composeCanvasClass!!.getDeclaredConstructor().newInstance().also { composeCanvasClass.getDeclaredField("a").apply { isAccessible = true }.set(it, canvas) }
            else composeCanvas!!.invoke(null, canvas)
        fun context(scope: Any): Any = XposedHelpers.callMethod(scope, contextName)
        fun size(context: Any): Long = context.javaClass.methods.first { (if (international) it.name == sizeName else it.name.startsWith(sizeName)) && it.parameterTypes.isEmpty() }.invoke(context) as Long
        fun callback(block: (Any) -> Unit): Any = Proxy.newProxyInstance(function.classLoader, arrayOf(function)) { proxy, method, args ->
            when (method.name) {
                "invoke" -> { block(args!![0]); unit }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "BilibiliCommentGlass"
                else -> null
            }
        }
    }

    private class Owner(view: View, val api: Bridge) {
        val view = WeakReference(view)
        val text = Button(this, false)
        val emoji = Button(this, true)
        private var scene = RenderNode("BilibiliCommentScene")
        private val storyCommentScene = !api.international && generateSequence(view) { it.parent as? View }.any {
            it.id != View.NO_ID && runCatching {
                it.resources.getResourceEntryName(it.id)
            }.getOrNull() == "story_comment_container"
        }
        private val storyScenes = ArrayList<StoryCommentScene>()
        val sceneBounds = RectF()
        var continueBehindInput = true
        private val replyNativeHeight = api.newState()
        private var replyOuterTop = Float.NaN
        private val replyOuterBounds = RectF()
        private var replySizeListenerAdded = false
        private val replySizeReset = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                XposedHelpers.callMethod(replyNativeHeight, "setValue", 0L)
        }
        private val replyOuterPosition = api.callback { coordinates ->
            updateBounds(coordinates, this.view, replyOuterBounds, api.international)
            val measured = replyOuterBounds.height().roundToInt()
            val previous = (XposedHelpers.callMethod(replyNativeHeight, "getValue") as Number).toInt()
            if (replyOuterTop.isFinite() && kotlin.math.abs(replyOuterTop - replyOuterBounds.top) > 1f && previous > 0) {
                XposedHelpers.callMethod(replyNativeHeight, "setValue", 0L)
            } else if (previous == 0 && measured > 0) {
                // Capture only the unextended pass. Never feed our own enlarged
                // child back as a new base height on subsequent layout callbacks.
                XposedHelpers.callMethod(replyNativeHeight, "setValue", measured.toLong())
            }
            replyOuterTop = replyOuterBounds.top
        }
        fun extendReply(original: Any, extent: Int, wrapHeight: Method, requiredHeight: Method, top: Any): Any {
            if (!replySizeListenerAdded) {
                view.get()?.addOnLayoutChangeListener(replySizeReset)
                replySizeListenerAdded = true
            }
            val nativeHeight = (XposedHelpers.callMethod(replyNativeHeight, "getValue") as Number).toInt()
            val companion = api.modifier.getField("Companion").get(null)
            // The outer position reports the parent's original constrained size.
            // Only its unbounded child receives nativeHeight + footer extension.
            var outer = requireNotNull(api.position.invoke(null, companion, replyOuterPosition))
            if (nativeHeight > 0) {
                outer = requireNotNull(wrapHeight.invoke(null, outer, top, true))
                val density = view.get()?.resources?.displayMetrics?.density ?: 1f
                outer = requireNotNull(requiredHeight.invoke(null, outer, (nativeHeight + extent) / density))
            }
            return requireNotNull(api.modifier.getMethod("then", api.modifier).invoke(outer, original))
        }

        private val viewportSizeState = api.newState()
        private var viewportContainer: WeakReference<View>? = null
        private val viewportSizeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateViewportSize()
        }
        private fun updateViewportSize() {
            val height = viewportContainer?.get()?.height ?: return
            if (height > 0) XposedHelpers.callMethod(viewportSizeState, "setValue", height.toLong())
        }
        fun viewportHeight(container: View): Int {
            if (viewportContainer?.get() !== container) {
                viewportContainer?.get()?.removeOnLayoutChangeListener(viewportSizeListener)
                viewportContainer = WeakReference(container)
                container.addOnLayoutChangeListener(viewportSizeListener)
            }
            updateViewportSize()
            // Recompose only when the measured available area actually changes.
            return (XposedHelpers.callMethod(viewportSizeState, "getValue") as Number).toInt()
        }
        var config = HookConfigReader.read(view.context, view.context.packageName)
        private val ambientHost = view.rootView as ViewGroup
        private val ambientLayer = object : View(view.context) {
            init { setWillNotDraw(false); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
            override fun onDraw(canvas: Canvas) {
                val saved = canvas.save()
                text.excludeInterior(canvas, ambientHost)
                emoji.excludeInterior(canvas, ambientHost)
                text.drawAmbient(canvas, ambientHost)
                emoji.drawAmbient(canvas, ambientHost)
                canvas.restoreToCount(saved)
            }
        }
        private var ambientVisible = false
        init {
            ambientHost.overlay.add(ambientLayer)
            ambientHost.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!view.isAttachedToWindow) {
                        if (storyCommentScene) {
                            storyScenes.clear()
                            scene.discardDisplayList()
                        }
                        viewportContainer?.get()?.removeOnLayoutChangeListener(viewportSizeListener)
                        view.removeOnLayoutChangeListener(replySizeReset)
                        ambientHost.overlay.remove(ambientLayer)
                        if (ambientHost.viewTreeObserver.isAlive) ambientHost.viewTreeObserver.removeOnPreDrawListener(this)
                    } else {
                        if (ambientLayer.width != ambientHost.width || ambientLayer.height != ambientHost.height) {
                            ambientLayer.layout(0, 0, ambientHost.width, ambientHost.height)
                            ambientLayer.invalidate()
                        }
                        if (ambientVisible != view.isShown) {
                            ambientVisible = view.isShown
                            ambientLayer.invalidate()
                        }
                        if (ambientVisible && !api.international) refreshScenePosition()
                    }
                    return true
                }
            })
        }
        fun invalidateAmbient() { ambientLayer.invalidate() }
        private var configTime = 0L
        private val recordScene = api.callback { scope -> recordScene(scope, scene) }

        private fun recordScene(scope: Any, target: RenderNode) {
            val context = api.context(scope)
            val original = XposedHelpers.callMethod(context, api.canvasName)
            val canvas = api.canvas.invoke(null, original) as Canvas
            val size = api.size(context)
            val width = Float.fromBits((size shr 32).toInt()).roundToInt()
            val height = Float.fromBits(size.toInt()).roundToInt()
            if (!canvas.isHardwareAccelerated || width <= 0 || height <= 0) {
                XposedHelpers.callMethod(scope, api.drawContentName)
            } else {
                // Record ONLY the list branch once, before its sibling input is drawn.
                // No window capture, recursive View.draw(), or self-sampling feedback.
                target.setPosition(0, 0, width, height)
                val recording = target.beginRecording(width, height)
                try {
                    recording.drawColor(sceneBaseColor)
                    XposedHelpers.callMethod(context, api.setCanvasName, api.wrapCanvas(recording))
                    XposedHelpers.callMethod(scope, api.drawContentName)
                } finally {
                    XposedHelpers.callMethod(context, api.setCanvasName, original)
                    target.endRecording()
                }
                canvas.drawRenderNode(target)
                // The sibling input draw observer must follow the final scroll
                // recording too, even when its own composition is unchanged.
                invalidateSceneReaders()
            }
        }
        private val sceneRevision = api.newState()
        private var sceneVersion = 0L
        private fun invalidateSceneReaders() {
            XposedHelpers.callMethod(sceneRevision, "setValue", ++sceneVersion)
        }

        private var sceneCoordinates: WeakReference<Any>? = null
        private val scenePositionFrame = RectF()
        private val windowOrigin = IntArray(2)
        private var localToWindow: Method? = null

        private fun refreshScenePosition() {
            val coordinates = sceneCoordinates?.get() ?: return
            val root = view.get()?.rootView ?: return
            // Compose can move a graphics layer during overscroll without dispatching
            // onGloballyPositioned. Read the actual layer origin on native frames.
            val method = localToWindow?.takeIf { it.declaringClass.isInstance(coordinates) } ?:
                coordinates.javaClass.methods.singleOrNull {
                    it.name.startsWith("localToWindow-") &&
                        it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
                }?.also { localToWindow = it } ?: return
            val packed = runCatching { method.invoke(coordinates, 0L) as Long }.getOrNull() ?: return
            root.getLocationOnScreen(windowOrigin)
            val x = Float.fromBits((packed ushr 32).toInt()) + windowOrigin[0]
            val y = Float.fromBits(packed.toInt()) + windowOrigin[1]
            if (!x.isFinite() || !y.isFinite() || sceneBounds.isEmpty) return
            sceneBounds.offsetTo(x, y)
            if (scenePositionFrame != sceneBounds) {
                scenePositionFrame.set(sceneBounds)
                invalidateSceneReaders()
            }
        }
        private val inputBounds = RectF()
        private val inputPosition = api.callback { coordinates -> updateBounds(coordinates, this.view, inputBounds, api.international) }
        private val continueScene = api.callback { scope ->
            XposedHelpers.callMethod(sceneRevision, "getValue")
            val context = api.context(scope)
            val canvas = api.canvas.invoke(null, XposedHelpers.callMethod(context, api.canvasName)) as Canvas
            val size = api.size(context)
            val width = Float.fromBits((size shr 32).toInt())
            val height = Float.fromBits(size.toInt())
            if (continueBehindInput && canvas.isHardwareAccelerated && scene.hasDisplayList() && !sceneBounds.isEmpty && !inputBounds.isEmpty) {
                val saved = canvas.save()
                canvas.clipRect(0f, 0f, width, height)
                canvas.translate(sceneBounds.left - inputBounds.left, sceneBounds.top - inputBounds.top)
                canvas.drawRenderNode(scene)
                canvas.restoreToCount(saved)
            }
            XposedHelpers.callMethod(scope, api.drawContentName)
        }
        fun decorateInputScene(modifier: Any): Any = requireNotNull(api.position.invoke(null,
            api.draw.invoke(null, modifier, continueScene), inputPosition))

        private val scenePosition = api.callback { coordinates ->
            updateBounds(coordinates, this.view, sceneBounds, api.international)
            if (!api.international) {
                sceneCoordinates = WeakReference(coordinates)
                if (scenePositionFrame != sceneBounds) {
                    scenePositionFrame.set(sceneBounds)
                    invalidateSceneReaders()
                }
            }
        }
        fun decorateScene(modifier: Any, listKey: Any? = null): Any {
            if (!storyCommentScene) {
                return requireNotNull(api.position.invoke(null, api.draw.invoke(null, modifier, recordScene), scenePosition))
            }
            if (listKey == null) return modifier
            // Primary and replies can occupy the same AndroidComposeView
            // during a native transition. A recorded draw references the node,
            // so a node overwritten by replies also changes the primary draw.
            storyScenes.removeAll { it.key.get() == null }
            val list = storyScenes.firstOrNull { it.key.get() === listKey }
                ?: StoryCommentScene(listKey).also { storyScenes.add(it) }
            return requireNotNull(api.position.invoke(null, api.draw.invoke(null, modifier, list.draw), list.position))
        }

        private inner class StoryCommentScene(listKey: Any) {
            val key = WeakReference(listKey)
            private val node = RenderNode("BilibiliStoryCommentList")
            private val bounds = RectF()
            private var coordinates: WeakReference<Any>? = null
            val position = api.callback {
                updateBounds(it, view, bounds)
                coordinates = WeakReference(it)
            }
            val draw = api.callback { scope ->
                scene = node
                sceneBounds.set(bounds)
                sceneCoordinates = coordinates
                recordScene(scope, node)
            }
        }

        fun capture(): RenderNode? {
            if (!api.international) XposedHelpers.callMethod(sceneRevision, "getValue")
            val source = view.get() ?: return null
            if (SystemClock.uptimeMillis() - configTime > 1000) {
                config = HookConfigReader.read(source.context, source.context.packageName)
                configTime = SystemClock.uptimeMillis()
            }
            return scene.takeIf { it.hasDisplayList() }
        }
        fun touch(event: MotionEvent) { text.touch(event); emoji.touch(event) }
    }

    private class Button(private val owner: Owner, private val circular: Boolean) {
        private val bounds = RectF()
        private val node = RenderNode("BilibiliCommentButton")
        private val ambientNode = RenderNode("BilibiliCommentButtonAmbient")
        private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val lens = io.github.offlineglass.rendering.PostureGlass.shader(KYANT_LENS_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val outline = Path()
        private val glassEdge = io.github.offlineglass.rendering.NativeGlassEdge()
        private val shape = RectF()
        private var down = false
        private var press = 0f
        private var glow = 0f
        private var glowSpread = 0f
        private var touchX = .5f
        private var touchY = .5f
        private val drawState = owner.api.newState()
        private var drawVersion = 0L
        private var animator: ValueAnimator? = null
        private var effectSignature = ""
        private var ambientSignature = ""
        private var diagnosticWritten = false
        private val drawCallback = owner.api.callback { scope ->
                runCatching { draw(scope) }.onFailure {
                    if (!diagnosticWritten) {
                        diagnosticWritten = true
                        owner.view.get()?.context?.getExternalFilesDir(null)?.resolve("liquidtab-comment-glass-error.txt")?.writeText(it.stackTraceToString())
                    }
                    XposedHelpers.callMethod(scope, owner.api.drawContentName)
                }
        }
        private val positionCallback = owner.api.callback { coordinates ->
            updateBounds(coordinates, owner.view, bounds, owner.api.international)
            owner.invalidateAmbient()
        }
        fun decorate(modifier: Any?): Any = requireNotNull(owner.api.position.invoke(null,
            owner.api.draw.invoke(null, modifier, drawCallback), positionCallback))

        fun touch(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (bounds.contains(event.rawX, event.rawY) && owner.view.get()?.isShown == true) {
                    touchX = ((event.rawX - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                    touchY = ((event.rawY - bounds.top) / bounds.height()).coerceIn(0f, 1f)
                    down = true; animate(1f, false)
                }
                MotionEvent.ACTION_MOVE -> if (down && !bounds.contains(event.rawX, event.rawY)) { down = false; animate(0f, false) }
                MotionEvent.ACTION_UP -> if (down) { down = false; animate(0f, true) }
                MotionEvent.ACTION_CANCEL -> if (down) { down = false; animate(0f, false) }
            }
        }
        private fun animate(target: Float, click: Boolean) {
            animator?.cancel()
            val from = press
            if (click) glow = 1f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (target == 1f) 130L else 420L
                interpolator = if (target == 1f) DecelerateInterpolator(1.5f) else LinearInterpolator()
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    press = if (target == 1f) from + (1f - from) * fraction
                        else if (fraction >= 1f) 0f else from * exp(-6f * fraction) * cos(12f * fraction)
                    if (click) { glow = (1f - fraction) * (1f - fraction); glowSpread = fraction }
                    else glow = 0f
                    // Read by the native draw observer below: invalidates the Compose layer itself,
                    // rather than just its View, so cached drawing cannot freeze the animation.
                    XposedHelpers.callMethod(drawState, "setValue", ++drawVersion)
                    owner.invalidateAmbient()
                }
                start()
            }
        }
        private fun ambientMatrix(parent: View): Matrix {
            val origin = IntArray(2)
            parent.getLocationOnScreen(origin)
            val scale = 1f + press * if (circular) .075f else .035f
            return Matrix().apply {
                setScale(scale, scale, bounds.width() / 2f, bounds.height() / 2f)
                postTranslate(bounds.left - origin[0], bounds.top - origin[1])
            }
        }
        fun excludeInterior(canvas: Canvas, parent: View) {
            if (bounds.isEmpty || owner.view.get()?.isShown != true) return
            val exterior = Path()
            path.transform(ambientMatrix(parent), exterior)
            canvas.clipOutPath(exterior)
        }
        fun drawAmbient(canvas: Canvas, parent: View) {
            if (!canvas.isHardwareAccelerated || bounds.isEmpty || owner.view.get()?.isShown != true || !ambientNode.hasDisplayList()) return
            val saved = canvas.save()
            canvas.concat(ambientMatrix(parent))
            owner.view.get()?.let { poseView ->
                io.github.offlineglass.rendering.PostureGlass.shadow(canvas, poseView,
                    RectF(0f,0f,bounds.width(),bounds.height())) { canvas.drawRenderNode(ambientNode) }
            }
            canvas.restoreToCount(saved)
        }
        private fun draw(scope: Any) {
            XposedHelpers.callMethod(drawState, "getValue")
            val source = owner.view.get() ?: return
            val context = owner.api.context(scope)
            val canvas = owner.api.canvas.invoke(null, XposedHelpers.callMethod(context, owner.api.canvasName)) as Canvas
            val size = owner.api.size(context)
            val width = Float.fromBits((size shr 32).toInt())
            val height = Float.fromBits(size.toInt())
            if (width <= 0f || height <= 0f) { XposedHelpers.callMethod(scope, owner.api.drawContentName); return }
            val density = source.resources.displayMetrics.density
            val radius = min(width, height) / 2f
            val dark = source.resources.configuration.uiMode and 0x30 == 0x20
            val scene = owner.capture()
            val saved = canvas.save()
            try {
                val scale = 1f + press * if (circular) .075f else .035f
                canvas.scale(scale, scale, width / 2f, height / 2f)
                shape.set(0f, 0f, width, height)
                if (circular) { path.reset(); path.addOval(shape, Path.Direction.CW) }
                else path.setBottomBarSquircle(shape, radius, owner.config.cornerSmoothing)
                val ambientPad = (24f * density).roundToInt()
                val ambientKey = "$width/$height/$dark/${owner.config.cornerSmoothing}"
                if (ambientKey != ambientSignature) {
                    val w = width.roundToInt(); val h = height.roundToInt()
                    ambientNode.setPosition(-ambientPad, -ambientPad, w + ambientPad, h + ambientPad)
                    val recording = ambientNode.beginRecording(w + ambientPad * 2, h + ambientPad * 2)
                    recording.translate(ambientPad.toFloat(), ambientPad.toFloat())
                    ambientPaint.strokeWidth = (if (dark) 2.5f else 3f) * density
                    ambientPaint.color = if (dark) 0x38FFFFFF else 0x28000000
                    recording.drawPath(path, ambientPaint)
                    ambientNode.endRecording()
                    val blur = (if (dark) 6f else 7f) * density
                    ambientNode.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
                    ambientSignature = ambientKey
                    owner.invalidateAmbient()
                }
                if (scene != null && canvas.isHardwareAccelerated && !bounds.isEmpty) {
                    val blurRadius = owner.config.blurRadius * .8f * density
                    val pad = NativeBackdropBlur.padding(blurRadius, 26f * density).roundToInt()
                    val w = width.roundToInt(); val h = height.roundToInt()
                    node.setPosition(-pad, -pad, w + pad, h + pad)
                    val recording = node.beginRecording(w + pad * 2, h + pad * 2)
                    recording.drawColor(sceneBaseColor)
                    recording.translate(pad - bounds.left + owner.sceneBounds.left, pad - bounds.top + owner.sceneBounds.top)
                    recording.drawRenderNode(scene)
                    node.endRecording()
                    val postureView = owner.view.get()
                    postureView?.let { io.github.offlineglass.rendering.PostureGlass.sync(
                        lens, it, owner.config.postureEffectsEnabled && owner.config.liquidGlassEnabled && !owner.config.solidBarEnabled,
                        owner.config.liquidViscosity,
                        owner.config.flowHorizontalStrength, owner.config.flowVerticalStrength,
                    ) }
                    val signature = "$w/$h/$pad/${owner.config.blurRadius}/${owner.config.liquidGlassEnabled}:${owner.config.refractionStrength}"
                    val postureChanged = io.github.offlineglass.rendering.PostureGlass.effectChanged(lens)
                    if (signature != effectSignature || postureChanged) {
                        lens.setFloatUniform("size", width, height)
                        lens.setFloatUniform("offset", -pad.toFloat(), -pad.toFloat())
                        lens.setFloatUniform("cornerRadii", radius, radius, radius, radius)
                        lens.setFloatUniform("refractionHeight", min(height / 2f, 24f * density))
                        lens.setFloatUniform("refractionAmount", -24f * density * owner.config.refractionScale)
                        lens.setFloatUniform("depthEffect", 0f)
                        val blur = NativeBackdropBlur.effect(blurRadius)
                        node.setRenderEffect(if (owner.config.liquidGlassEnabled)
                            RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"), blur) else blur)
                        effectSignature = signature
                    }
                    val clip = canvas.save(); canvas.clipPath(path); canvas.clipRect(shape)
                    canvas.drawRenderNode(node); canvas.restoreToCount(clip)
                }
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = if (dark) Color.argb((owner.config.darkAlpha * 255f).toInt(), 33, 31, 38)
                    else Color.argb((owner.config.lightAlpha * 255f).toInt(), 255, 255, 255)
                canvas.drawPath(path, paint)
                if (owner.config.liquidGlassEnabled && !owner.config.solidBarEnabled) {
                    if (owner.config.outlineEnabled) glassEdge.draw(canvas, path, density, dark, (if (dark) .42f else .6f) * 2f * owner.config.darkBarHighlightStrength)
                } else {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = density * .7f
                paint.shader = LinearGradient(0f, 0f, width, height,
                    intArrayOf(0xB3FFFFFF.toInt(), 0x18FFFFFF, 0x60FFFFFF), null, Shader.TileMode.CLAMP)
                shape.inset(.35f * density, .35f * density)
                if (circular) { outline.reset(); outline.addOval(shape, Path.Direction.CW) }
                else outline.setBottomBarSquircle(shape, radius - .35f * density, owner.config.cornerSmoothing)
                canvas.drawPath(outline, paint)
                paint.shader = null; paint.style = Paint.Style.FILL
                }
                if (press > 0f || glow > 0f) {
                    val highlight = ((max(press, 0f) * .20f + glow * .38f) * 255).toInt().coerceIn(0, 255)
                    paint.color = Color.WHITE
                    paint.shader = RadialGradient(width * touchX, height * touchY,
                        max(height, width * (.22f + glowSpread * .55f)),
                        intArrayOf(Color.argb(highlight, 255, 255, 255), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                    canvas.drawPath(path, paint)
                    paint.shader = null
                }
                XposedHelpers.callMethod(scope, owner.api.drawContentName)
            } finally { canvas.restoreToCount(saved) }
        }
    }

    private fun updateBounds(coordinates: Any, reference: WeakReference<View>, bounds: RectF, international: Boolean = false) {
        val packed = coordinates.javaClass.methods.first {
            (if (international) it.name == "D" else it.name.startsWith("localToWindow-")) && it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
        }.invoke(coordinates, 0L) as Long
        val size = coordinates.javaClass.methods.first { (if (international) it.name == "a" else it.name.startsWith("getSize-")) && it.parameterTypes.isEmpty() }
            .invoke(coordinates) as Long
        val view = reference.get() ?: return
        val location = IntArray(2)
        view.rootView.getLocationOnScreen(location)
        val x = Float.fromBits((packed shr 32).toInt()) + location[0]
        val y = Float.fromBits(packed.toInt()) + location[1]
        bounds.set(x, y, x + (size shr 32).toInt(), y + size.toInt())
    }
}

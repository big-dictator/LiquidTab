package io.github.offlineglass.hook.adapters.bilibili

import android.app.Activity
import android.content.res.Configuration
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap

/** Extend only the inspected 9.15 player branch, keeping the native controller allocation. */
internal object BilibiliStoryVideoViewport {
    private const val ACTIVITY = "com.bilibili.video.story.StoryVideoActivity"
    private val sessions = WeakHashMap<Activity, Session>()
    private var traceFile: java.io.File? = null
    private fun trace(event: String) {
        runCatching {
            val file = traceFile ?: return
            if (file.length() > 65536) file.writeText("")
            file.appendText("${android.os.SystemClock.uptimeMillis()} $event\n")
        }
    }
    private fun storyActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current.takeIf { activity ->
                generateSequence(activity.javaClass as Class<*>?) { it.superclass }.any { it.name == ACTIVITY }
            }
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return null
    }
    fun install(loader: ClassLoader) {
        BilibiliStoryTransitionReveal.install(loader)
        // Chronos can render into a sibling Texture while the legacy render
        // layer itself has no SurfaceTexture. Native capture then returns
        // without invoking its callback, leaving transition covers empty.
        val captureOwners = mutableSetOf<Pair<Class<*>, String>>()
        val renderContract = loader.loadClass("tv.danmaku.render.core.IVideoRenderLayer")
        fun bindCapture(layer: Any) {
          if (layer !is android.view.TextureView || !renderContract.isInstance(layer)) return
          runCatching {
            val legacy = layer.javaClass
            for (method in listOf("takeVideoCapture", "takeVideoCaptureEffectively")) {
                val contract = renderContract.methods.single { it.name == method }
                val actual = legacy.getMethod(method, *contract.parameterTypes)
                if (java.lang.reflect.Modifier.isAbstract(actual.modifiers)) continue
                if (!captureOwners.add(actual.declaringClass to method)) continue
                XposedBridge.hookMethod(actual, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val layer = param.thisObject as? android.view.TextureView ?: return
                        if (layer.isAvailable || android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
                        var context: Context = layer.context
                        while (context is ContextWrapper && context !is Activity) {
                            val base = context.baseContext
                            if (base === context) return
                            context = base
                        }
                        val activity = context as? Activity ?: return
                        if (storyActivity(activity) == null && activity.javaClass.name !=
                            "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity") return
                        fun source(view: View): android.view.TextureView? {
                            if (view is android.view.TextureView && view !== layer && view.isShown && view.isAvailable &&
                                view.javaClass.name == "com.bilibili.cron.TextureInternalView") return view
                            if (view is ViewGroup) for (i in 0 until view.childCount)
                                source(view.getChildAt(i))?.let { return it }
                            return null
                        }
                        val videoId = activity.resources.getIdentifier(
                            if (storyActivity(activity) != null) "video_view" else "video_container", "id", activity.packageName)
                        val video = activity.findViewById<View>(videoId) ?: return
                        val bitmap = source(video)?.bitmap ?: return
                        trace("transitionCapture ${bitmap.width}x${bitmap.height} $method")
                        if (method == "takeVideoCaptureEffectively") param.result = bitmap
                        else {
                            val callback = param.args.firstOrNull() ?: return
                            XposedHelpers.callMethod(callback, "onResult", bitmap)
                            param.result = null
                        }
                    }
                })
            }
          }.onFailure { XposedBridge.log("LiquidTab story transition capture: $it") }
        }
        val layerTypes = loader.loadClass("tv.danmaku.render.core.IVideoRenderLayer\$Type")
        val texture = layerTypes.getField("TypeTextureView").get(null)
        val textureExternal = layerTypes.getField("TypeTextureViewWithExternalRender").get(null)
        val surfaceExternal = layerTypes.getField("TypeSurfaceViewWithExternalRender").get(null)
        val surface = layerTypes.getField("TypeSurfaceView").get(null)
        val ownedLayers = WeakHashMap<Any, Any>()
        val factory = loader.loadClass("tv.danmaku.render.core.VideoRenderLayerFactory")
        fun storyCreation() = Thread.currentThread().stackTrace.any {
            it.className.startsWith("com.bilibili.video.story.player.") || it.className == ACTIVITY
        }
        XposedBridge.hookAllMethods(factory, "create", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size != 1 || !storyCreation()) return
                param.args[0] = if (param.args[0] === surfaceExternal || param.args[0] === textureExternal) textureExternal else texture
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                param.result?.let(::bindCapture)
                if (!storyCreation()) return
                val layer = param.result ?: return
                val actual = XposedHelpers.callMethod(factory.cast(param.thisObject), "type", layer) ?: return
                if (actual === texture || actual === textureExternal) ownedLayers[layer] = actual
                trace("create ${System.identityHashCode(layer)} ${layer.javaClass.name} $actual")
            }
        })
        // Creation and reuse must agree. The native detail/fullscreen receiver
        // can request Surface for a Texture layer that we deliberately created.
        // Preserve its external-render contract and all other native checks.
        XposedBridge.hookAllMethods(factory, "create", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                installReuseCheck(param.thisObject.javaClass)
            }
            private val installed = mutableSetOf<Class<*>>()
            private fun installReuseCheck(type: Class<*>) {
                val implementation = generateSequence(type as Class<*>?) { it.superclass }.firstOrNull { candidate ->
                    candidate.declaredMethods.any { it.name == "match" && !java.lang.reflect.Modifier.isAbstract(it.modifiers) }
                } ?: return
                if (!installed.add(implementation)) return
                XposedBridge.hookAllMethods(implementation, "match", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size != 2) return
                        val actual = ownedLayers[param.args[1]] ?: return
                        trace("match ${System.identityHashCode(param.args[1])} requested=${param.args[0]} actual=$actual")
                        if ((actual === texture && param.args[0] === surface) ||
                            (actual === textureExternal && param.args[0] === surfaceExternal))
                            param.args[0] = actual
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.size == 2 && ownedLayers.containsKey(param.args[1]))
                            trace("matchResult ${System.identityHashCode(param.args[1])} ${param.result}")
                    }
                })
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view is android.view.TextureView && storyActivity(view.context) != null)
                    trace("textureAttach ${System.identityHashCode(view)} ${view.javaClass.name} ${view.width}x${view.height}")
                val name = view.javaClass.name
                if (name != "com.bilibili.video.story.view.StoryViewPager" &&
                    name != "com.bilibili.video.story.action.StoryController" &&
                    name != "com.bilibili.video.story.action.StoryLiveController") return
                val activity = storyActivity(view.context) ?: return
                sessions.getOrPut(activity) { Session(activity) }.sizeTransitionLayer(view)
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "onDetachedFromWindow", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? android.view.TextureView ?: return
                if (storyActivity(view.context) != null)
                    trace("textureDetach ${System.identityHashCode(view)} available=${view.isAvailable}")
            }
        })
        val frame = loader.loadClass("com.bilibili.video.story.view.StoryVideoFrameLayout")
        val reserved = frame.declaredFields.single {
            java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
        }.apply { isAccessible = true }
        val transitionTime = frame.declaredFields.single { it.type == Long::class.javaPrimitiveType }.apply { isAccessible = true }
        val measuring = ThreadLocal.withInitial { java.util.ArrayDeque<Pair<View, Long>>() }
        XposedBridge.hookAllMethods(frame, "onMeasure", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                val activity = storyActivity(view.context) ?: return
                val session = sessions.getOrPut(activity) { Session(activity) }
                val target = session.prepare(view) ?: return
                // The native one-second orientation cache can force the previous
                // height even for EXACTLY specs. Suspend it only in this measure.
                measuring.get().addLast(view to transitionTime.getLong(view))
                transitionTime.setLong(view, 0L)
                // Native onMeasure subtracts VIDEO_CONTAINER_BOTTOM in portrait.
                // Compensate its input only for the verified player branch.
                param.args[1] = View.MeasureSpec.makeMeasureSpec(target + reserved.getInt(null).coerceAtLeast(0), View.MeasureSpec.EXACTLY)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val stack = measuring.get()
                if (stack.peekLast()?.first === param.thisObject) {
                    val saved = stack.removeLast()
                    transitionTime.setLong(saved.first, saved.second)
                }
                if (stack.isEmpty()) measuring.remove()
            }
        })
        // Run after Bilibili's focus override, which can reset its own insets
        // after Activity.onWindowFocusChanged has already returned.
        XposedBridge.hookAllMethods(loader.loadClass(ACTIVITY), "onWindowFocusChanged", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.firstOrNull() != true) return
                val activity = param.thisObject as? Activity ?: return
                sessions.getOrPut(activity) { Session(activity) }.start()
            }
        })
        for (event in listOf("onResume", "onPostResume", "onStop", "onDestroy")) {
            XposedBridge.hookAllMethods(Activity::class.java, event, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name.startsWith("com.bilibili.ship.theseus.detail.") || storyActivity(activity) != null) {
                        if (traceFile == null) traceFile = java.io.File(activity.getExternalFilesDir(null), "liquidtab-video-transition.txt")
                        trace("$event ${activity.javaClass.simpleName} orientation=${activity.resources.configuration.orientation}")
                    }
                    if (storyActivity(activity) == null) return
                    when (event) {
                        "onResume", "onPostResume" -> sessions.getOrPut(activity) { Session(activity) }.start()
                        "onStop" -> sessions[activity]?.stop()
                        else -> sessions.remove(activity)?.destroy()
                    }
                }
            })
        }
        XposedBridge.hookAllMethods(Activity::class.java, "onConfigurationChanged", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val config = param.args.firstOrNull() as? Configuration ?: return
                if (storyActivity(activity) != null && config.orientation != Configuration.ORIENTATION_PORTRAIT)
                    sessions[activity]?.restoreBeforeConfiguration()
            }
        })
    }
    private class Session(private val activity: Activity) : ViewTreeObserver.OnPreDrawListener {
        private val root = activity.window.decorView as ViewGroup
        private val players = WeakHashMap<View, Boolean>()
        private val clips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
        private val scrims = WeakHashMap<View, Float>()
        private val danmakuGlass = BilibiliStoryDanmakuGlass(activity, root)
        private val transitionHeights = WeakHashMap<View, Int>()
        private val videoId = root.resources.getIdentifier("video_view", "id", activity.packageName)
        private val pageId = root.resources.getIdentifier("story_page_container", "id", activity.packageName)
        private val controllerId = root.resources.getIdentifier("video_controller", "id", activity.packageName)
        private val feedId = root.resources.getIdentifier("feed_videos", "id", activity.packageName)
        private val pagerId = root.resources.getIdentifier("story_view_pager", "id", activity.packageName)
        private val liveBottomGroupId = root.resources.getIdentifier("story_live_bottom_group", "id", activity.packageName)
        private val scrimIds = setOf("story_ctrl_bg_top", "story_ctrl_bg_bottom").map {
            root.resources.getIdentifier(it, "id", activity.packageName)
        }.filter { it != 0 }.toSet()
        private var listening = false
        private var scanScrims = true
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { scanScrims = true }
        private var enabled = true
        private var geometryApplied = false
        private val navigationColor = activity.window.navigationBarColor
        private val navigationDivider = activity.window.navigationBarDividerColor
        private val navigationContrast = activity.window.isNavigationBarContrastEnforced
        private val statusColor = activity.window.statusBarColor
        private val statusContrast = activity.window.isStatusBarContrastEnforced
        fun start() {
            enabled = true
            if (!listening) {
                listening = true
                root.viewTreeObserver.addOnPreDrawListener(this)
                root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            }
            scanScrims = true
            // Background stop restores native dimensions. A resumed root can
            // reuse identical MeasureSpecs and bypass onMeasure unless these
            // exact video branches are explicitly marked for measurement.
            fun rebind(view: View) {
                if (view.id == pagerId || view.id == controllerId) sizeTransitionLayer(view)
                if (view.javaClass.name == "com.bilibili.video.story.view.StoryVideoFrameLayout") {
                    view.forceLayout()
                    view.requestLayout()
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) rebind(view.getChildAt(i))
            }
            root.findViewById<View>(pageId)?.let(::rebind)
            root.requestLayout()
            root.invalidate()
        }
        fun prepare(player: View): Int? {
            if (!enabled) return null
            if (root.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT ||
                root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
                return null
            }
            if (player.javaClass.name != "com.bilibili.video.story.view.StoryVideoFrameLayout") return null
            val parent = player.parent as? ViewGroup ?: return null
            val sharedPlayer = player.id == videoId && parent.id == pageId
            val poster = player.id == View.NO_ID && parent.javaClass == android.widget.FrameLayout::class.java &&
                (0 until parent.childCount).any { parent.getChildAt(it).id == controllerId } &&
                generateSequence(parent.parent as? View) { it.parent as? View }.any { it.id == feedId }
            if (!sharedPlayer && !poster) return null
            val origin = IntArray(2)
            player.getLocationInWindow(origin)
            val windowHeight = root.height.takeIf { it > 0 } ?: activity.windowManager.currentWindowMetrics.bounds.height()
            // A recycled item's window Y changes during the swipe. Its poster
            // must use the shared player's viewport, not remaining screen space.
            val pageOrigin = IntArray(2)
            root.findViewById<View>(pageId)?.getLocationInWindow(pageOrigin)
            val target = windowHeight - if (poster) pageOrigin[1] else origin[1]
            if (target <= 0) return null
            players[player] = true
            geometryApplied = true
            if (poster) return target
            // Do not resize the activity/page/pager: all native controls retain
            // their original coordinates. Only permit the player to overflow.
            var branch: ViewGroup? = parent
            while (branch != null) {
                clips.getOrPut(branch) { branch.clipChildren to branch.clipToPadding }
                if (branch.clipChildren) branch.clipChildren = false
                if (branch.clipToPadding) branch.clipToPadding = false
                branch = branch.parent as? ViewGroup
            }
            if (activity.window.navigationBarColor != Color.TRANSPARENT) activity.window.navigationBarColor = Color.TRANSPARENT
            if (activity.window.navigationBarDividerColor != Color.TRANSPARENT) activity.window.navigationBarDividerColor = Color.TRANSPARENT
            if (activity.window.isNavigationBarContrastEnforced) activity.window.isNavigationBarContrastEnforced = false
            if (activity.window.statusBarColor != Color.TRANSPARENT) activity.window.statusBarColor = Color.TRANSPARENT
            if (activity.window.isStatusBarContrastEnforced) activity.window.isStatusBarContrastEnforced = false
            return target
        }
        fun sizeTransitionLayer(view: View) {
            if (!enabled || root.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT ||
                root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) return
            val nativeHeight = root.findViewById<View>(android.R.id.content)?.height?.takeIf { it > 0 }
                ?: (activity.windowManager.currentWindowMetrics.bounds.height() -
                    (root.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0))
            val target = when (view.id) {
                pagerId -> root.height.takeIf { it > 0 } ?: activity.windowManager.currentWindowMetrics.bounds.height()
                controllerId -> nativeHeight
                else -> return
            }
            val params = view.layoutParams ?: return
            transitionHeights.getOrPut(view) { params.height }
            geometryApplied = true
            if (params.height != target) view.layoutParams = params.apply { height = target }
        }
        override fun onPreDraw(): Boolean {
            if (root.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT ||
                root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
                restore(); return true
            }
            fun removeScrims(view: View) {
                if (view.id == controllerId || view.id == pagerId) sizeTransitionLayer(view)
                // The live controller layout also has an unnamed, solid bottom
                // filler, separate from its named gradient scrims. Identify it
                // by the inspected sibling/bottom geometry, never by color alone.
                val parent = view.parent as? ViewGroup
                val liveBottomFiller = view.javaClass == View::class.java && view.id == View.NO_ID &&
                    parent?.javaClass?.name == "com.bilibili.video.story.action.StoryLiveController" &&
                    liveBottomGroupId != 0 && view.height > 0 && view.left == 0 &&
                    view.width == parent.width && view.bottom == parent.height &&
                    parent.findViewById<View>(liveBottomGroupId)?.bottom == view.top
                if (view.id in scrimIds || liveBottomFiller) {
                    scrims.getOrPut(view) { view.alpha }
                    if (view.alpha != 0f) view.alpha = 0f
                    return
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) removeScrims(view.getChildAt(index))
            }
            if (scanScrims) {
                scanScrims = false
                root.findViewById<View>(pageId)?.let(::removeScrims)
                root.findViewById<View>(pageId)?.let { danmakuGlass.scan(it, root.findViewById(videoId)) }
            }
            scrims.keys.forEach { if (it.alpha != 0f) it.alpha = 0f }
            root.findViewById<View>(videoId)?.let(::prepare)
            danmakuGlass.update()
            return true
        }
        private fun restore() {
            if (!geometryApplied) return
            geometryApplied = false
            players.keys.toList().forEach { it.requestLayout() }
            players.clear()
            danmakuGlass.restore()
            transitionHeights.forEach { (view, height) ->
                if (view.layoutParams.height != height) view.layoutParams = view.layoutParams.apply { this.height = height }
            }
            transitionHeights.clear()
            scrims.forEach { (view, alpha) -> if (view.alpha == 0f) view.alpha = alpha }
            scrims.clear()
            clips.forEach { (view, original) -> view.clipChildren = original.first; view.clipToPadding = original.second }
            clips.clear()
            activity.window.navigationBarColor = navigationColor
            activity.window.navigationBarDividerColor = navigationDivider
            activity.window.isNavigationBarContrastEnforced = navigationContrast
            activity.window.statusBarColor = statusColor
            activity.window.isStatusBarContrastEnforced = statusContrast
        }
        fun stop() {
            enabled = false
            if (listening && root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
            if (listening && root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            listening = false
            // A fullscreen destination can still be sharing this renderer.
            // Do not resize/reclip the outgoing video during Activity handoff.
            danmakuGlass.restore()
        }
        fun restoreBeforeConfiguration() { restore() }
        fun destroy() { stop(); restore() }
    }
}

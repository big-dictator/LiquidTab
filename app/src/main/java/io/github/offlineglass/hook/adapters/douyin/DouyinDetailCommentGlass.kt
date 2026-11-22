package io.github.offlineglass.hook.adapters.douyin

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import java.io.File
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Native DetailActivity accessory, separate from homepage navigation/elevation.
 * 40.8: CommentBottomBarComponent delegates to CommentInputFragment; xvd is
 * the bottom-manager overlay. Bind its real fragment view, never obfuscated IDs.
 */
internal object DouyinDetailCommentGlass {
    private val sessions = WeakHashMap<View, Session>()
    private val materials = WeakHashMap<GlassHostLayout, WeakReference<ViewGroup>>()
    private val observedComments = WeakHashMap<ViewGroup, Boolean>()
    private var installed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A detach callback runs inside ViewGroup's fixed-count child traversal.
     * Never mutate that child array recursively; a View.post on a detached view
     * can also wait forever for reattachment, so use the main Looper directly.
     */
    private fun retire(panel: GlassHostLayout?) {
        panel ?: return
        val parent = panel.parent as? ViewGroup ?: return
        mainHandler.post {
            if (panel.parent === parent) parent.removeView(panel)
            materials.remove(panel)
        }
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        installed = true
        runCatching {
            val type = Class.forName("com.ss.android.ugc.aweme.comment.ui.CommentInputFragment", false, loader)
            val method = type.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
            XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java, XC_MethodHook::class.java)
                .invoke(null, method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val root = param.args[0] as? ViewGroup ?: return
                        val activity = activity(root.context) ?: return
                        if (!isDetailContext(activity)) return
                        observeComment(root)
                    }
                })
        }.onFailure { android.util.Log.w("OfflineGlassDouyin", "detail comment entry unavailable", it) }
        installBottomCorners(loader)
        installSearchBar(loader)
    }

    private fun installBottomCorners(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.ss.android.ugc.aweme.feed.adaption.ContainerCornerComponent", false, loader)
            // Decompiled 40.8 has both RelativeLayout and MeasureOnce layout
            // branches. The component's named bottom accessors cover both and
            // the lazy-creation branch, without touching its top corners.
            for (name in listOf("ensureBottomLeftView", "ensureBottomRightView",
                "getBottomLeftViewIfNeeded", "getBottomRightViewIfNeeded")) {
                XposedBridge.hookAllMethods(type, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val image = param.result as? ImageView ?: return
                        if (!isDetailContext(image.context)) return
                        if (image.imageAlpha != 0) {
                            image.imageAlpha = 0
                            android.util.Log.i("OfflineGlassDouyin", "detail bottom corner suppressed: $name")
                        }
                    }
                })
            }
        }.onFailure { android.util.Log.w("OfflineGlassDouyin", "detail bottom corner entry unavailable", it) }
    }

    private fun installSearchBar(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.ss.android.ugc.aweme.search.entrance.mainstruct.detailpage.SearchBarViewHolder", false, loader)
            // LIZ(View, FeedParam, boolean) binds et_search_kw and the capsule.
            // Resolve by signature instead of depending on its obfuscated name.
            val bind = type.declaredMethods.single { method ->
                method.parameterTypes.let { it.size == 3 && it[0] == View::class.java &&
                    it[1].name == "com.ss.android.ugc.aweme.feed.param.FeedParam" && it[2] == Boolean::class.javaPrimitiveType }
            }
            val keywordField = type.declaredFields.singleOrNull {
                TextView::class.java.isAssignableFrom(it.type)
            }?.apply { isAccessible = true }
            XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java, XC_MethodHook::class.java)
                .invoke(null, bind, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val root = param.args[0] as? ViewGroup ?: return
                        val owner = activity(root.context) ?: return
                        if (!isDetailContext(owner)) return
                        val id = root.resources.getIdentifier("et_search_kw", "id", owner.packageName)
                        val keyword = runCatching { keywordField?.get(param.thisObject) as? View }.getOrNull()
                            ?: if (id != 0) root.findViewById<View>(id) else null
                        keyword ?: return
                        var group = keyword.parent as? ViewGroup
                        while (group != null && group !== root && group.childCount < 3) group = group.parent as? ViewGroup
                        val capsule = group?.takeIf { it !== root } ?: return
                        SearchMaterial.observe(capsule, owner)
                    }
                })
        }.onFailure { android.util.Log.w("OfflineGlassDouyin", "detail search entry unavailable", it) }
    }

    /** Component hooks above prove the accessory identity. Match the detail
     * route family, not one activity implementation or an APK version: both
     * legacy DetailActivity and server-selected UltraDetailActivity use them.
     */
    fun isDetailContext(context: Context): Boolean {
        var type: Class<*>? = activity(context)?.javaClass ?: return false
        repeat(16) {
            val current = type ?: return false
            if (current.name.startsWith("com.ss.android.ugc.aweme.detail.")) return true
            type = current.superclass
        }
        return false
    }

    private fun observeComment(root: ViewGroup) {
        if (observedComments.put(root, true) != true) {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) { root.post { bindComment(root) } }
                override fun onViewDetachedFromWindow(view: View) = Unit
            })
        }
        root.post { bindComment(root) }
    }

    private fun bindComment(root: ViewGroup) {
        if (!root.isAttachedToWindow || sessions.containsKey(root)) return
        val owner = activity(root.context) ?: return
        if (!isDetailContext(owner)) return
        val config = HookConfigReader.read(root.context, owner.packageName)
        if (!config.enabled) return
        val session = Session(root, owner, config)
        if (session.start()) sessions[root] = session
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(12) {
            if (current is Activity) return current as Activity
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }

    private fun findEdit(root: View, depth: Int = 0): EditText? {
        if (root is EditText) return root
        if (depth > 12 || root !is ViewGroup) return null
        for (i in 0 until root.childCount.coerceAtMost(64)) findEdit(root.getChildAt(i), depth + 1)?.let { return it }
        return null
    }

    private class MaterialAdapter(private val state: AppNavigationState, private val clearTint: Boolean = false) : TargetAdapter {
        override val key = "douyin-detail-comment"
        override val ownsNavigationDrawing = true
        override val skipNavigationSnapshot = true
        override val keepNavigationSourceVisible = true
        override val preserveNativeSourceMetrics = true
        override val managesNativeTabVisibility = true
        override val hasPerFrameScene = true
        override val continuousFramePump = true
        override val requestedFrameRateMax = 120f
        override val skipBackdropStableDelay = true
        override val forceCompositorFrames = true
        // Replay the selected native page branches as GPU scene input.
        override val allowRenderNodeRefresh = true
        override val panelHighlightFollowsAllThemes = true
        override fun normalizeConfig(config: GlassConfig) = config.copy(detachedPublish = false, hiddenMask = 0,
            themeMode = GlassConfig.THEME_DARK,
            lightAlpha = if (clearTint) 0f else config.lightAlpha,
            darkAlpha = if (clearTint) 0f else config.darkAlpha)
        override fun glassSurfaceFillColor(dark: Boolean, selectedIndex: Int, defaultColor: Int) =
            if (clearTint) Color.TRANSPARENT else defaultColor
        override fun createNavigationState(context: Context): AppNavigationState = state
    }

    /** Each accessory owns its own live/fallback handoff. A panel first drawn
     * before the native texture is ready must not retain that flat fallback
     * forever merely because its outer RenderNode already has a display list.
     */
    private abstract class MaterialState : AppNavigationState {
        protected abstract val materialHost: GlassHostLayout?
        protected abstract val materialTexture: TextureView?
        private var recordedTexture = WeakReference<TextureView>(null)
        private var recordedLive = false
        private var recordedVideoEpoch = Int.MIN_VALUE
        private var recordedWidth = 0
        private var recordedHeight = 0
        private var recordedBitmap: android.graphics.Bitmap? = null
        private var probeFrames = 0
        override val allowsSoftwareBackdropCapture = false
        override val backdropSceneViewsAreDescendants = true
        final override fun resolveBackdropScene(host: View): View? =
            activity(host.context)?.findViewById<ViewGroup>(android.R.id.content)

        final override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View>? {
            val host = materialHost ?: return emptyList()
            val scene = resolveBackdropScene(host) as? ViewGroup ?: return emptyList()
            val excluded = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
            val paths = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<View, Boolean>())
            for ((panel, target) in materials) {
                if (panel.rootView !== host.rootView) continue
                for (view in listOfNotNull(panel, target.get())) {
                    excluded.add(view)
                    var ancestor = view.parent as? View
                    while (ancestor != null) {
                        paths.add(ancestor)
                        if (ancestor === scene) break
                        ancestor = ancestor.parent as? View
                    }
                }
            }
            val sources = ArrayList<View>()
            fun collect(view: View, depth: Int) {
                if (depth > 32 || view in excluded || view is GlassHostLayout ||
                    view.visibility != View.VISIBLE || view.alpha <= .001f) return
                if (view in paths && view is ViewGroup) {
                    for (index in 0 until view.childCount) collect(view.getChildAt(index), depth + 1)
                } else if (view.width > 0 && view.height > 0) sources.add(view)
            }
            // Walk only ancestor paths to our two controls; ordinary native
            // branches stay whole GPU nodes, including background and video.
            collect(scene, 0)
            return sources
        }
        override fun beforeHostDraw(host: View, source: ViewGroup?): Boolean {
            // One bounded sample after initial submission, not a per-frame log.
            if (probeFrames < 24 && ++probeFrames == 24) {
                val h = Rect(); val v = Rect()
                val texture = materialTexture
                host.getGlobalVisibleRect(h)
                texture?.getGlobalVisibleRect(v)
                val owner = activity(host.context)
                val kind = if (this is SearchMaterial) "search" else "comment"
                val summary = "time=${System.currentTimeMillis()} activity=${owner?.javaClass?.name} role=$kind live=${materialHost?.liveBackdropActiveForAdapter} " +
                    "texture=${texture?.let(System::identityHashCode)} available=${texture?.isAvailable}\n" +
                    "host=$h video=$v scale=${texture?.scaleX},${texture?.scaleY} " +
                    "sameRoot=${texture?.rootView === host.rootView} sourceShown=${source?.isShown}\n"
                if (owner != null) Thread { runCatching {
                    File(owner.getExternalFilesDir(null), "dy_detail_${kind}_scene.txt").writeText(summary)
                } }.start()
            }
            return true
        }
        override fun outerBackdropNeedsRecord(backdrop: android.graphics.Bitmap?, width: Int, height: Int,
                                             hasDisplayList: Boolean): Boolean =
            !hasDisplayList || recordedLive != (materialHost?.liveBackdropActiveForAdapter == true) ||
                recordedTexture.get() !== materialTexture || recordedVideoEpoch != (videoBackdrop()?.epoch ?: -1) ||
                recordedBitmap !== backdrop || recordedWidth != width || recordedHeight != height
        override fun onOuterBackdropRecorded(backdrop: android.graphics.Bitmap?, width: Int, height: Int) {
            recordedLive = materialHost?.liveBackdropActiveForAdapter == true
            recordedTexture = WeakReference(materialTexture)
            recordedVideoEpoch = videoBackdrop()?.epoch ?: -1
            recordedBitmap = backdrop; recordedWidth = width; recordedHeight = height
        }
    }

    /** Native search capsule is skinned in place; its entire hit hierarchy stays native. */
    private class SearchMaterial(private val target: ViewGroup, private val owner: Activity,
                                 private val frame: FrameLayout) : MaterialState(), View.OnAttachStateChangeListener {
        companion object {
            private val active = WeakHashMap<View, SearchMaterial>()
            private val observed = WeakHashMap<ViewGroup, Boolean>()
            fun observe(target: ViewGroup, owner: Activity) {
                if (observed.put(target, true) != true) {
                    target.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) { target.post { attach(target, owner) } }
                        override fun onViewDetachedFromWindow(view: View) = Unit
                    })
                    target.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        // Do not insert into a parent while it is traversing
                        // its native child array during layout.
                        if (!active.containsKey(target)) target.post { attach(target, owner) }
                    }
                }
                target.post { attach(target, owner) }
            }
            fun attach(target: ViewGroup, owner: Activity) {
                if (active.containsKey(target) || !target.isAttachedToWindow || target.width <= 0 ||
                    target.height !in 1..(100f * target.resources.displayMetrics.density).toInt()) return
                var node: View? = target.parent as? View
                while (node != null && node !is FrameLayout) node = node.parent as? View
                val frame = node as? FrameLayout ?: return
                val config = HookConfigReader.read(owner, owner.packageName)
                if (!config.enabled) return
                SearchMaterial(target, owner, frame).also { active[target] = it; it.start(config) }
            }
        }
        private var panel: GlassHostLayout? = null
        private var currentTexture = WeakReference<TextureView>(null)
        override val materialHost get() = panel
        override val materialTexture get() = currentTexture.get()
        private val location = IntArray(2)
        private val origin = IntArray(2)
        private val backgrounds = LinkedHashMap<View, Drawable?>()
        private var stopped = false
        private fun start(config: GlassConfig) {
            val glass = GlassHostLayout(owner, config, 1, nativeSelectionReliable = false,
                navigationSource = target, sliderEnabled = false,
                adapterOverride = MaterialAdapter(this, clearTint = true)).apply {
                isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = 0f
            }
            panel = glass
            // Above the video, immediately below the native search/header branch.
            // Inserting at index zero would put the material behind the video.
            var branch: View = target
            while (branch.parent is View && branch.parent !== frame) branch = branch.parent as View
            val index = frame.indexOfChild(branch)
            if (index < 0) { active.remove(target); return }
            frame.addView(glass, index, FrameLayout.LayoutParams(target.width, target.height, Gravity.TOP or Gravity.LEFT))
            materials[glass] = WeakReference(target)
            target.addOnAttachStateChangeListener(this)
            update()
            android.util.Log.i("OfflineGlassDouyin", "detail search material attached ${target.width}x${target.height}; tint=0")
            val summary = "time=${System.currentTimeMillis()} search=${target.width}x${target.height} tint=0 native-input=true\n"
            Thread { runCatching { File(owner.getExternalFilesDir(null), "dy_detail_search_status.txt").writeText(summary) } }.start()
        }
        private fun update() {
            val glass = panel ?: return
            if (stopped || target.width <= 0 || target.height <= 0) return
            updateTexture(glass)
            // Only the capsule's own background hierarchy, not the back button
            // or other top-bar siblings. Never modify native text/icon pixels.
            fun clear(view: View, depth: Int) {
                if (depth > 4) return
                if (!backgrounds.containsKey(view)) backgrounds[view] = view.background
                if (view.background != null) view.background = null
                if (view is ViewGroup) for (i in 0 until view.childCount) clear(view.getChildAt(i), depth + 1)
            }
            clear(target, 0)
            target.getLocationInWindow(location); frame.getLocationInWindow(origin)
            val x = location[0] - origin[0]; val y = location[1] - origin[1]
            val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
            if (lp.width != target.width || lp.height != target.height || lp.leftMargin != x || lp.topMargin != y ||
                lp.gravity != (Gravity.TOP or Gravity.LEFT)) {
                lp.width = target.width; lp.height = target.height; lp.leftMargin = x; lp.topMargin = y
                lp.bottomMargin = 0; lp.gravity = Gravity.TOP or Gravity.LEFT
                glass.layoutParams = lp
            }
            val visible = target.isShown && target.alpha > .001f
            if (glass.visibility != (if (visible) View.VISIBLE else View.INVISIBLE))
                glass.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
        override fun onHostFrame(host: View) = update()
        override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
                                   density: Float, barHeightPx: Int, surfaceColor: () -> Int) = update()
        private fun updateTexture(host: View) {
            val current = DouyinPlayerTextureHooks.detailTexture(host)
            if (currentTexture.get() !== current) currentTexture = WeakReference(current)
        }
        override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true
        override fun suppressRedrawnSource() = false
        override fun drawsNavigationContent() = false
        override fun forceDarkMode(selectedIndex: Int) = true
        override fun forceNavigationBarImmersed(host: GlassHostLayout) = false
        override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = 0
        override fun allowsHostTouch() = false
        override fun pauseBackdropWork(host: View) = !target.isShown
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            if (stopped) return
            stopped = true
            target.removeOnAttachStateChangeListener(this)
            retire(panel)
            panel = null
            backgrounds.forEach { (node, bg) -> if (node.background == null) node.background = bg }
            active.remove(target)
        }
    }

    private class Session(val root: ViewGroup, val activity: Activity, val initialConfig: GlassConfig) :
        MaterialState(), ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private var input: ViewGroup? = null
        private var parent: FrameLayout? = null
        private var panel: GlassHostLayout? = null
        private var texture = WeakReference<TextureView>(null)
        override val materialHost get() = panel
        override val materialTexture get() = texture.get()
        private val location = IntArray(2)
        private val origin = IntArray(2)
        private val visible = Rect()
        private val backgrounds = LinkedHashMap<View, Drawable?>()
        private data class Clip(val children: Boolean, val padding: Boolean, val outline: Boolean)
        private val clips = WeakHashMap<ViewGroup, Clip>()
        private class PageClip(val original: Rect?) { val applied = Rect() }
        private val pageClips = WeakHashMap<View, PageClip>()
        private val pageMatrix = Matrix()
        private val inversePageMatrix = Matrix()
        private val pageViewport = RectF()
        private val pageBounds = Rect()
        private val currentClip = Rect()
        private class MediaFit(view: TextureView) {
            val scaleX = view.scaleX; val scaleY = view.scaleY
            val pivotX = view.pivotX; val pivotY = view.pivotY
            val translationX = view.translationX; val translationY = view.translationY
            var appliedScale = Float.NaN; var appliedX = Float.NaN; var appliedY = Float.NaN
        }
        // The comment fragment can outlive many recycled video pages. These
        // records must not keep the old player views/surfaces alive.
        private val mediaFits = WeakHashMap<TextureView, MediaFit>()
        private var written = false
        private var stopped = false
        private val sampler = DouyinVideoSampler(activity) {}

        fun start(): Boolean {
            val edit = findEdit(root) ?: return false
            val group = edit.parent as? ViewGroup ?: return false
            var ancestor: View? = group
            while (ancestor != null && ancestor !is FrameLayout) ancestor = ancestor.parent as? View
            val frame = ancestor as? FrameLayout ?: return false
            input = group
            parent = frame
            val glass = GlassHostLayout(activity, initialConfig, 1, nativeSelectionReliable = false,
                navigationSource = group, sliderEnabled = false, adapterOverride = MaterialAdapter(this)).apply {
                isClickable = false; isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = 0f
            }
            panel = glass
            frame.addView(glass, 0, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
            materials[glass] = WeakReference(group)
            root.addOnAttachStateChangeListener(this)
            root.viewTreeObserver.addOnPreDrawListener(this)
            update()
            return true
        }

        override fun onPreDraw(): Boolean { update(); return true }
        override fun onHostFrame(host: View) { updateGeometry() }
        override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
                                   density: Float, barHeightPx: Int, surfaceColor: () -> Int) { updateGeometry() }
        override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true
        override fun suppressRedrawnSource() = false
        override fun drawsNavigationContent() = false
        override fun forceDarkMode(selectedIndex: Int) = true
        override fun forceNavigationBarImmersed(host: GlassHostLayout) = false
        override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = 0
        override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) = sampler.request(host, pageAllowed, false)
        override fun videoBackdrop() = sampler.snapshot()
        override fun allowsHostTouch() = false
        override fun pauseBackdropWork(host: View) = !root.isShown
        override fun onHostDetached() { sampler.dispose() }

        private fun clear(view: View) {
            if (!backgrounds.containsKey(view)) backgrounds[view] = view.background
            if (view.background != null) view.background = null
        }
        private fun unclip(group: ViewGroup) {
            if (!clips.containsKey(group)) clips[group] = Clip(group.clipChildren, group.clipToPadding, group.clipToOutline)
            if (group.clipChildren) group.clipChildren = false
            if (group.clipToPadding) group.clipToPadding = false
            if (group.clipToOutline) group.clipToOutline = false
        }
        private fun updateGeometry() {
            val target = input ?: return
            val frame = parent ?: return
            val glass = panel ?: return
            if (target.width <= 0 || target.height <= 0) return
            target.getLocationInWindow(location); frame.getLocationInWindow(origin)
            val x = location[0] - origin[0]; val y = location[1] - origin[1]
            val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
            if (lp.width != target.width || lp.height != target.height || lp.leftMargin != x || lp.topMargin != y ||
                lp.gravity != (Gravity.TOP or Gravity.LEFT)) {
                lp.width = target.width; lp.height = target.height
                lp.leftMargin = x; lp.topMargin = y; lp.bottomMargin = 0
                lp.gravity = Gravity.TOP or Gravity.LEFT
                glass.layoutParams = lp
            }
        }
        private fun update() {
            if (stopped || !root.isAttachedToWindow) return
            val target = input ?: return
            val glass = panel ?: return
            // Keep native editor, buttons, listeners and all positions intact.
            var node: View? = target
            while (node != null) {
                clear(node)
                if (node === root) break
                node = node.parent as? View
            }
            // Bottom manager and native footer shells are overlays, not content.
            node = root.parent as? View
            repeat(10) {
                val current = node ?: return@repeat
                if (current.height in 1..(target.height * 2)) clear(current)
                node = current.parent as? View
            }
            updateGeometry()
            val current = DouyinPlayerTextureHooks.detailTexture(glass)
            if (current !== texture.get()) {
                texture = WeakReference(current)
                // Rebind the retained scene in this draw, not after a probe timer.
                glass.clearLiveBackdropForAdapter()
                glass.invalidate()
            }
            val media = texture.get() ?: return
            val decor = activity.window.decorView
            // Cached fragments can remain attached outside the viewport.
            if (!target.getGlobalVisibleRect(visible)) return
            decor.getWindowVisibleDisplayFrame(visible)
            // Do not expand while IME/sheets resize the viewport.
            if (decor.height - visible.bottom > decor.height / 4) return
            var branch: View? = media.parent as? View
            while (branch is ViewGroup) { unclip(branch); branch = branch.parent as? View }
            fitMedia(media, decor)
            containCachedPages(media, decor, target)
            if (activity.window.navigationBarColor != Color.TRANSPARENT) activity.window.navigationBarColor = Color.TRANSPARENT
            if (activity.window.navigationBarDividerColor != Color.TRANSPARENT) activity.window.navigationBarDividerColor = Color.TRANSPARENT
            if (activity.window.isNavigationBarContrastEnforced) activity.window.isNavigationBarContrastEnforced = false
            if (!written) {
                written = true
                val summary = "time=${System.currentTimeMillis()} activity=${activity.javaClass.name} dark=true mode=fit-center\n" +
                    "input=${target.javaClass.name} ${target.width}x${target.height}\n" +
                    "video=${media.javaClass.name} native=${media.width}x${media.height} fit=${media.scaleX},${media.scaleY} decor=${decor.width}x${decor.height}\n"
                android.util.Log.i("OfflineGlassDouyin", summary)
                // One write per accessory, never a per-frame diagnostic loop.
                Thread { runCatching { File(activity.getExternalFilesDir(null), "dy_detail_comment_status.txt").writeText(summary) } }.start()
            }
        }
        private fun restorePageClip(view: View) {
            val saved = pageClips.remove(view) ?: return
            if (view.getClipBounds(currentClip) && currentClip == saved.applied) view.clipBounds = saved.original
        }

        private fun containCachedPages(media: TextureView, decor: View, target: View) {
            // Only the visible player's page may overflow into the native footer.
            // Clip cached siblings to the fixed content viewport, rather than
            // enabling pager clipping (which would also cut off the current video).
            target.getLocationOnScreen(location)
            var footerTop = location[1]
            var shell = target.parent as? View
            while (shell != null && shell !== decor) {
                if (shell.height > target.height * 2) break
                shell.getLocationOnScreen(location)
                footerTop = minOf(footerTop, location[1])
                shell = shell.parent as? View
            }
            var page: View = media
            var ancestor = media.parent as? ViewGroup
            while (ancestor != null && ancestor !== decor) {
                if (ancestor.javaClass.simpleName.contains("ViewPager")) {
                    restorePageClip(page)
                    ancestor.getLocationOnScreen(origin)
                    for (index in 0 until ancestor.childCount) {
                        val sibling = ancestor.getChildAt(index)
                        if (DouyinProfilePages.contains(sibling)) {
                            restorePageClip(sibling)
                            continue
                        }
                        if (sibling === page || sibling !is ViewGroup ||
                            sibling.width < ancestor.width / 2 || sibling.height < ancestor.height / 2) continue
                        pageMatrix.reset()
                        sibling.transformMatrixToGlobal(pageMatrix)
                        if (!pageMatrix.invert(inversePageMatrix)) continue
                        pageViewport.set(origin[0].toFloat(), origin[1].toFloat(),
                            (origin[0] + ancestor.width).toFloat(), footerTop.toFloat())
                        if (pageViewport.isEmpty) continue
                        inversePageMatrix.mapRect(pageViewport)
                        // Round inward so the adjacent page cannot leak one pixel.
                        pageBounds.set(kotlin.math.ceil(pageViewport.left).toInt(),
                            kotlin.math.ceil(pageViewport.top).toInt(),
                            kotlin.math.floor(pageViewport.right).toInt(),
                            kotlin.math.floor(pageViewport.bottom).toInt())
                        val saved = pageClips.getOrPut(sibling) { PageClip(sibling.clipBounds?.let { Rect(it) }) }
                        saved.original?.let { if (!pageBounds.intersect(it)) pageBounds.setEmpty() }
                        if (!sibling.getClipBounds(currentClip) || currentClip != pageBounds) sibling.clipBounds = pageBounds
                        saved.applied.set(pageBounds)
                    }
                }
                page = ancestor
                ancestor = ancestor.parent as? ViewGroup
            }
        }
        private fun fitMedia(media: TextureView, decor: View) {
            if (media.width <= 0 || media.height <= 0) return
            val nativeParent = media.parent as? View ?: return
            val saved = mediaFits.getOrPut(media) { MediaFit(media) }
            // Preserve the player's own texture matrix and aspect. Never change
            // just its height or force center-crop. A uniform fit shows the
            // complete native renderer; non-screen ratios remain letterboxed.
            val scale = minOf(decor.width.toFloat() / media.width, decor.height.toFloat() / media.height)
            var page: View = media
            var ancestor = media.parent as? View
            while (ancestor != null && ancestor !== decor) {
                if (ancestor.javaClass.simpleName.contains("ViewPager")) break
                page = ancestor
                ancestor = ancestor.parent as? View
            }
            if (ancestor == null || ancestor === decor) page = decor
            page.getLocationInWindow(location)
            nativeParent.getLocationInWindow(origin)
            // Keep the page's live swipe displacement. Centering in the global
            // window instead would pin the video while its pager was moving.
            val x = location[0] + (decor.width - media.width * scale) * .5f - origin[0] - media.left
            val y = location[1] + (decor.height - media.height * scale) * .5f - origin[1] - media.top
            if (media.pivotX != 0f) media.pivotX = 0f
            if (media.pivotY != 0f) media.pivotY = 0f
            if (media.scaleX != scale) media.scaleX = scale
            if (media.scaleY != scale) media.scaleY = scale
            if (media.translationX != x) media.translationX = x
            if (media.translationY != y) media.translationY = y
            saved.appliedScale = scale; saved.appliedX = x; saved.appliedY = y
        }
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            if (stopped) return
            stopped = true
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
            root.removeOnAttachStateChangeListener(this)
            retire(panel)
            panel = null
            sampler.dispose()
            backgrounds.forEach { (v, bg) -> if (v.background == null) v.background = bg }
            clips.forEach { (v, clip) -> v.clipChildren = clip.children; v.clipToPadding = clip.padding; v.clipToOutline = clip.outline }
            pageClips.forEach { (v, saved) ->
                if (v.getClipBounds(currentClip) && currentClip == saved.applied) v.clipBounds = saved.original
            }
            pageClips.clear()
            mediaFits.forEach { (v, saved) ->
                if (v.scaleX == saved.appliedScale && v.scaleY == saved.appliedScale &&
                    v.translationX == saved.appliedX && v.translationY == saved.appliedY) {
                    v.scaleX = saved.scaleX; v.scaleY = saved.scaleY
                    v.pivotX = saved.pivotX; v.pivotY = saved.pivotY
                    v.translationX = saved.translationX; v.translationY = saved.translationY
                }
            }
            sessions.remove(root)
        }
    }
}

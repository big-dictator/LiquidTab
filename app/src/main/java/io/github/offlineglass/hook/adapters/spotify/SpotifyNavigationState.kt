package io.github.offlineglass.hook.adapters.spotify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.ViewGroup
import android.view.View
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.localizeNavigationLabel

/** Resize native Compose artwork after capture; no dependency on virtual View nodes. */
internal class SpotifyNavigationState : AppNavigationState {
    private val resourceIds = HashMap<String, Int>()
    private val navigationLabels = arrayOf("主页", "搜索", "音乐库", "Premium", "创建")
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val labelMetrics = Paint.FontMetrics()
    private val createPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val detachedCreatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var detachedCreate = false
    private val iconPaints = android.util.LruCache<Int, Paint>(4)
    private fun iconPaint(color: Int): Paint = iconPaints[color] ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = android.graphics.BlendModeColorFilter(color, android.graphics.BlendMode.SRC_IN)
        iconPaints.put(color, this)
    }
    private fun resourceId(view: View, name: String): Int = resourceIds.getOrPut(name) {
        view.resources.getIdentifier(name, "id", "com.spotify.music")
    }
    private var chromePassDone = false
    private var chromePassDrawingTime = Long.MIN_VALUE
    private var chromePassSource = java.lang.ref.WeakReference<ViewGroup>(null)
    companion object {
        // Survives host replacement during rotation; updated from the same
        // measured transform used to render portrait native artwork.
        private var portraitArtworkScale: Float? = null
        private val immersiveContent = java.util.WeakHashMap<View, Boolean>()
        private var paddingHookInstalled = false
        private fun protectContentPadding(content: View) {
            if (!paddingHookInstalled) {
                io.github.offlineglass.hook.compat.XposedBridge.hookAllMethods(View::class.java, "setPadding",
                    object : io.github.offlineglass.hook.compat.XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (immersiveContent.containsKey(view) &&
                                view.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                                param.args[3] = 0
                            }
                        }
                    })
                paddingHookInstalled = true
            }
            immersiveContent[content] = true
        }
    }
    override fun forceDarkMode(selectedIndex: Int) = true
    private val drawerVisibleBounds = Rect()
    override fun pageAllowsNavigation(root: View?): Boolean? {
        root ?: return null
        val id = resourceId(root, "sidedrawer_compose_view")
        val drawer = if (id != 0) root.findViewById<View>(id) else null
        // ax41 creates the native side drawer under this stable resource ID.
        // Check visible geometry too: a closed drawer may remain attached offscreen.
        return drawer == null || !drawer.isShown || drawer.alpha <= 0.001f ||
            !drawer.getGlobalVisibleRect(drawerVisibleBounds) || drawerVisibleBounds.isEmpty
    }
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.TRANSPARENT
    }
    override fun drawPanelEdgeGlow(canvas: Canvas, path: android.graphics.Path, density: Float) {
        // Use the same zero-offset blurred silhouette as the ambient shadow.
        // The transparent fill and outside clip preserve the clear glass interior.
        edgeGlowPaint.setShadowLayer(18f * density, 0f, 0f,
            android.graphics.Color.argb(14, 220, 235, 255))
        val saved = canvas.save()
        canvas.clipOutPath(path)
        canvas.drawPath(path, edgeGlowPaint)
        canvas.restoreToCount(saved)
    }
    override fun resolveBackdropScene(host: View): View? {
        // vertical_main.xml: page content belongs to the coordinator's fragment
        // subtree. Sample it directly, excluding sibling bottom chrome/overlays.
        val id = resourceId(host, "fragment_container")
        return if (id != 0) host.rootView.findViewById<View>(id)?.takeIf {
            it.isAttachedToWindow && it.isShown &&
                it.javaClass.name == "com.spotify.mainlayout.ui.view.containers.MainLayoutFragmentContainerView"
        } else null
    }
    override fun backdropSceneBaseColor(scene: View, fallback: Int) = 0xFF121212.toInt()
    // Compose can update text/scroll display lists without replacing the parent
    // RenderNode. Refresh the scene wrapper so the effect samples that update.
    override fun forceLiveBackdropFrame(selectedIndex: Int) = true
    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus || !chromeEnabled) return
        chromePassDone = false
        sourceRef?.get()?.let { source ->
            suppressNativeChrome(source)
            contentBottomPadding.keys.forEach { content ->
                content.requestApplyInsets()
                content.requestLayout()
                content.invalidate()
            }
            source.rootView.invalidate()
        }
    }
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true
    override fun drawForeground(canvas: Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || selectionPath.isEmpty) { drawSource(); return true }
        // Split by geometry every frame, never by selected tab/index: an icon
        // crossing the boundary immediately regains its normal outside portion.
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(outside)
        if (mix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), ((1f - mix) * 255f).toInt())
            drawSource()
            canvas.restoreToCount(inside)
        }
        return true
    }
    private var pageIndex = 0
    private var createPending = false
    private var createSeen = false
    private var createRequestedAt = 0L

    override fun onNavigationSelectionRequested(index: Int, slotCount: Int, now: Long): Boolean {
        if (index == 4) {
            createPending = !detachedCreate
            createSeen = false
            createRequestedAt = now
        } else {
            pageIndex = index
            createPending = false
        }
        return false
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int {
        val native = SpotifyNativeTabs.indices()
        if (pageIndex == 3 || pageIndex !in native) pageIndex = native.firstOrNull { it != 3 && it != 4 } ?: 0
        if (!SpotifyNativeTabs.createVisible) createPending = false
        if (detachedCreate) return pageIndex.coerceIn(0, slotCount - 1)
        if (!createPending) return pageIndex.coerceIn(0, slotCount - 1)
        val root = source?.rootView ?: return pageIndex
        val id = resourceId(root, "fragment_overlay_container")
        val overlay = if (id != 0) root.findViewById<ViewGroup>(id) else null
        val visible = overlay != null && overlay.isShown && overlay.childCount > 0 &&
            (0 until overlay.childCount).any { overlay.getChildAt(it).visibility == View.VISIBLE }
        if (visible) { createSeen = true; return 4 }
        // Allow asynchronous fragment insertion, but restore as soon as an observed
        // popup disappears (back, outside tap, menu choice or native dismissal).
        if (!createSeen && android.os.SystemClock.uptimeMillis() - createRequestedAt < 600L) return 4
        createPending = false
        return pageIndex.coerceIn(0, slotCount - 1)
    }
    private val originalAlpha = java.util.WeakHashMap<View, Float>()
    private val originalVisibility = java.util.WeakHashMap<View, Int>()
    private val contentBottomPadding = java.util.WeakHashMap<View, Int>()
    private val insetTransformers = java.util.WeakHashMap<View, Any>()
    private val navigationWindows = java.util.WeakHashMap<android.view.Window, Pair<Int, Boolean>>()
    private fun clearSystemNavigationSurface(source: View) {
        var context = source.context
        while (context is android.content.ContextWrapper && context !is android.app.Activity) {
            val base = context.baseContext
            if (base === context) break
            context = base
        }
        val window = (context as? android.app.Activity)?.window ?: return
        navigationWindows.getOrPut(window) { window.navigationBarColor to window.isNavigationBarContrastEnforced }
        if (window.navigationBarColor != android.graphics.Color.TRANSPARENT) window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (window.isNavigationBarContrastEnforced) window.isNavigationBarContrastEnforced = false
    }
    private fun removeNativeNavigationInsets(content: View) {
        if (insetTransformers.containsKey(content)) return
        val getter = content.javaClass.methods.firstOrNull { it.name.startsWith("getInsetsTransformer") && it.parameterCount == 0 } ?: return
        val setter = content.javaClass.methods.firstOrNull { it.name.startsWith("setInsetsTransformer") && it.parameterCount == 1 } ?: return
        runCatching {
            val original = getter.invoke(content) ?: return
            val functionType = setter.parameterTypes[0]
            val invoke = functionType.methods.first { it.name == "invoke" && it.parameterCount == 1 }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(functionType.classLoader, arrayOf(functionType)) { instance, method, args ->
                when (method.name) {
                    "invoke" -> {
                        val transformed = invoke.invoke(original, args?.get(0))
                        if (transformed is android.view.WindowInsets && content.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                            val types = android.view.WindowInsets.Type.systemBars()
                            val native = transformed.getInsets(types)
                            // The floating bar must sample content through the entire
                            // former bottom strip, including the gesture inset. Keeping
                            // the physical system inset still crops the page there.
                            val actual = 0
                            android.view.WindowInsets.Builder(transformed)
                                .setInsets(types, android.graphics.Insets.of(native.left, native.top, native.right, actual))
                                .setInsetsIgnoringVisibility(types, android.graphics.Insets.of(native.left, native.top, native.right, actual))
                                .build()
                        } else transformed
                    }
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.get(0)
                    "toString" -> "SpotifySystemInsetsTransformer"
                    else -> method.invoke(original, *(args ?: emptyArray()))
                }
            }
            setter.invoke(content, proxy)
            insetTransformers[content] = original
            content.requestApplyInsets()
        }
    }
    private var chromeEnabled = true
    private val miniPlayerGlass = SpotifyMiniPlayerGlass()
    private val scrollPadding = java.util.WeakHashMap<ViewGroup, Pair<Int, Boolean>>()
    private fun updateScrollSafety(host: View, mini: View?) {
        val root = host.rootView
        val active = pageIndex == 0 || pageIndex == 3
        val name = if (pageIndex == 3) "pdp_content_container" else "recycler_view"
        val scene = resolveBackdropScene(host)
        val id = resourceId(root, name)
        val scroll = if (active && id != 0) scene?.findViewById<ViewGroup>(id) else null
        scrollPadding.keys.toList().filter { it !== scroll }.forEach { view ->
            val old = scrollPadding.remove(view) ?: return@forEach
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, old.first)
            view.clipToPadding = old.second
        }
        scroll ?: return
        val old = scrollPadding.getOrPut(scroll) { scroll.paddingBottom to scroll.clipToPadding }
        val scrollLocation = IntArray(2).also(scroll::getLocationInWindow)
        val coverLocation = IntArray(2).also(host::getLocationInWindow)
        if (mini != null && mini.isShown && mini.alpha > 0.001f && mini.height > 0) {
            val miniLocation = IntArray(2).also(mini::getLocationInWindow)
            coverLocation[1] = minOf(coverLocation[1], miniLocation[1])
        }
        val padding = maxOf(old.first, scrollLocation[1] + scroll.height - coverLocation[1] +
            (8f * host.resources.displayMetrics.density).toInt())
        if (scroll.clipToPadding) scroll.clipToPadding = false
        if (scroll.paddingBottom != padding) scroll.setPadding(scroll.paddingLeft, scroll.paddingTop, scroll.paddingRight, padding)
    }
    private val nativeBackgrounds = java.util.WeakHashMap<View, Long>()
    private val backgroundAccessors = HashMap<Class<*>, Pair<java.lang.reflect.Method, java.lang.reflect.Method>?>()
    private fun clearNativeBackground(view: View) {
        // MainLayoutContentContainerView draws its background inside Compose;
        // View.setBackgroundColor cannot clear that layer. Resolve the public
        // Kotlin value-class accessor by signature, independent of obfuscation.
        val type = view.javaClass
        if (!backgroundAccessors.containsKey(type)) {
            val methods = type.methods
            val getter = methods.firstOrNull { it.name.startsWith("getBackgroundColor") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
            val setter = methods.firstOrNull { it.name.startsWith("setBackgroundColor") && it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE)) }
            backgroundAccessors[type] = if (getter != null && setter != null) getter to setter else null
        }
        val (getter, setter) = backgroundAccessors[type] ?: return
        runCatching {
            val color = getter.invoke(view) as Long
            nativeBackgrounds.getOrPut(view) { color }
            if (color != 0L) setter.invoke(view, 0L)
        }
    }
    private val gradientIds = listOf("bottom_gradient", "bottom_gradient_black_fade", "bottom_gradient_window_insets")
    private var fullArtworkSlotWidth = 0f

    override fun onHostPreDraw(host: View, source: ViewGroup?, selected: () -> Int,
        enabled: Boolean, density: Float, barHeightPx: Int, surfaceColor: () -> Int) {
        chromePassDone = false
        SpotifyNativeTabs.attach(host)
        if (!SpotifyNativeTabs.layoutKnown) {
            // Native chrome ownership is independent of model readiness.
            return
        }
        val landscape = host.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        chromeEnabled = enabled && !landscape
        detachedCreate = (host as? io.github.offlineglass.hook.GlassHostLayout)?.config
            ?.let { it.detachedPublish && SpotifyNativeTabs.createVisible && it.hiddenMask and 16 == 0 } == true
        if (detachedCreate) createPending = false
        if (!chromeEnabled) {
            restoreGradients()
            if (landscape) host.post {
                (host.parent as? ViewGroup)?.removeView(host)
                source?.alpha = 1f
            }
            return
        }
        // Native popup dismissal invalidates its own Compose branch only. The glass
        // host display list can otherwise remain cached with the Create selection.
        val desired = resolveSelectedIndex(source, 5)
        val displayed = (host as? io.github.offlineglass.hook.GlassHostLayout)?.adapterSelectedIndex
        if (desired != displayed) host.invalidate()
        suppressNativeChrome(source)
        (host as? io.github.offlineglass.hook.GlassHostLayout)?.let {
            val fullWidth = io.github.offlineglass.hook.glassHostWidthPx(host.rootView, 5, it.config, density)
            fullArtworkSlotWidth = (fullWidth - 8f * density).coerceAtLeast(1f) / 5f
            val mini = miniPlayerGlass.update(it, resolveBackdropScene(host))
            updateScrollSafety(host, mini)
        }
    }

    override fun suppressNativeChromeEarly(source: ViewGroup?) = suppressNativeChrome(source)

    override fun suppressNativeChrome(source: ViewGroup?) {
        if (!chromeEnabled || source?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) return
        val root = source?.rootView ?: return
        // The shared host calls early/normal suppression several times per frame.
        // One pass still rechecks native changes on every new pre-draw.
        if (chromePassDone && chromePassSource.get() === source && chromePassDrawingTime == source.drawingTime) return
        chromePassDone = true
        chromePassDrawingTime = source.drawingTime
        chromePassSource = java.lang.ref.WeakReference(source)
        clearSystemNavigationSurface(source)
        clearNativeBackground(source)
        // vertical_main.xml keeps the coordinator full-height. MainLayout.onLayout
        // -> evd0.b() shortens its fragment child using the native bar overlap as
        // parent padding. Remove that viewport crop, not the list's scroll padding.
        if (root.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            val id = resourceId(root, "fragment_container")
            val content = if (id != 0) root.findViewById<View>(id) else null
            if (content?.javaClass?.name == "com.spotify.mainlayout.ui.view.containers.MainLayoutFragmentContainerView") {
                removeNativeNavigationInsets(content)
                contentBottomPadding.getOrPut(content) { content.paddingBottom }
                protectContentPadding(content)
                if (content.paddingBottom != 0) content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, 0)
            }
        }
        for (name in gradientIds) {
            val id = resourceId(root, name)
            if (id == 0) continue
            val view = root.findViewById<View>(id) ?: continue
            originalAlpha.getOrPut(view) { view.alpha }
            originalVisibility.getOrPut(view) { view.visibility }
            // Leave constraint anchors and native animation lifecycle intact.
            if (view.visibility == View.VISIBLE) view.visibility = View.INVISIBLE
            if (view.alpha != 0f) view.alpha = 0f
        }
    }

    private fun restoreGradients() {
        chromePassDone = false
        chromePassSource.clear()
        miniPlayerGlass.restore()
        scrollPadding.forEach { (view, old) ->
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, old.first)
            view.clipToPadding = old.second
        }
        scrollPadding.clear()
        contentBottomPadding.keys.forEach { immersiveContent.remove(it) }
        navigationWindows.forEach { (window, original) ->
            window.navigationBarColor = original.first
            window.isNavigationBarContrastEnforced = original.second
        }
        navigationWindows.clear()
        insetTransformers.forEach { (view, transformer) -> runCatching {
            view.javaClass.methods.firstOrNull { it.name.startsWith("setInsetsTransformer") && it.parameterCount == 1 }?.invoke(view, transformer)
            view.requestApplyInsets()
        } }
        insetTransformers.clear()
        contentBottomPadding.forEach { (view, bottom) -> view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom) }
        contentBottomPadding.clear()
        originalVisibility.forEach { (view, visibility) -> view.visibility = visibility }
        originalVisibility.clear()
        nativeBackgrounds.forEach { (view, color) ->
            runCatching { view.javaClass.methods.firstOrNull {
                it.name.startsWith("setBackgroundColor") && it.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE))
            }?.invoke(view, color) }
        }
        nativeBackgrounds.clear()
        originalAlpha.forEach { (view, alpha) -> view.alpha = alpha }
        originalAlpha.clear()
    }

    override fun onHostDetached() {
        restoreGradients()
        sourceRef?.get()?.let {
            if (it.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) it.alpha = 1f
        }
    }
    override fun dispose() = restoreGradients()

    private var sourceRef: java.lang.ref.WeakReference<ViewGroup>? = null
    private val artwork = android.graphics.RenderNode("SpotifyNativeArtwork")
    private var artworkDrawingTime = Long.MIN_VALUE
    private var artworkWidth = 0
    private var artworkHeight = 0

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        sourceRef = source?.let { java.lang.ref.WeakReference(it) }
        artworkDrawingTime = Long.MIN_VALUE
    }

    override fun draw(canvas: Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) {
        if (!frame.hideSelectedForOptics) drawDetachedCreate(canvas, frame)
        val source = sourceRef?.get() ?: return
        if (source.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            return
        }
        if (!canvas.isHardwareAccelerated || source.width <= 0 || source.height <= 0) return
        val w = source.width
        val h = source.height.toFloat()
        val drawingTime = source.drawingTime
        if (drawingTime == 0L || drawingTime != artworkDrawingTime ||
            artworkWidth != w || artworkHeight != source.height || !artwork.hasDisplayList()) {
            val recording = artwork.beginRecording(w, source.height)
            // Direct View.draw does not apply the parent's child alpha. Changing
            // source.alpha here dirties the native Compose tree unnecessarily.
            try { source.draw(recording) } finally { artwork.endRecording() }
            artwork.setPosition(0, 0, w, source.height)
            artworkDrawingTime = drawingTime
            artworkWidth = w
            artworkHeight = source.height
        }
        val nativeEntries = SpotifyNativeTabs.indices()
        val nativeSlotWidth = w.toFloat() / nativeEntries.size
        // Visible slots affect positioning only. Keep artwork at the approved
        // full five-entry scale instead of magnifying it as entries disappear.
        val artworkSlotWidth = fullArtworkSlotWidth.takeIf { it > 0f } ?: frame.slotWidth
        val scale = minOf(artworkSlotWidth / (w / 5f), frame.height / h)
        portraitArtworkScale = scale
        val iconTop = h * .02f
        val iconBottom = h * .60f
        val labelTop = h * .65f
        val labelBottom = h * .91f
        val iconScale = 1.2f * 1.15f
        val iconDrawScale = scale * iconScale * frame.extraScale
        val iconHeight = (iconBottom - iconTop) * iconDrawScale
        // Crop bands include transparent native padding: overlap that padding,
        // rather than adding it to the visible icon-to-label gap.
        val labelCenterOffset = ((labelBottom - labelTop) * .55f - h * .04f) *
            scale * frame.extraScale - .5f * frame.density
        labelPaint.textSize = frame.textSize * frame.scaledDensity * 1.1f * frame.extraScale
        labelPaint.getFontMetrics(labelMetrics)
        val labelHeight = labelMetrics.descent - labelMetrics.ascent
        val contentHeight = if (frame.iconOnly) iconHeight else iconHeight + labelCenterOffset + labelHeight / 2f
        val top = (frame.height - contentHeight) / 2f
        val labelCenter = top + iconHeight + labelCenterOffset
        val labelBaseline = labelCenter - (labelMetrics.ascent + labelMetrics.descent) / 2f
        // The lens samples an accented scene; the foreground is uniformly idle.
        // Geometric clipping, rather than the active page, chooses the visible color.
        val artworkColor = if (frame.hideSelectedForOptics) frame.accentColor else 0xFFB3B3B3.toInt()
        val selectedPaint = iconPaint(artworkColor)
        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            if (index == 4 && !SpotifyNativeTabs.createVisible) return@forEachIndexed
            val nativeIndex = nativeEntries.indexOf(index)
            if (nativeIndex < 0) return@forEachIndexed
            // Include the selected glyph as well: the foreground mask removes
            // its unrefracted copy inside the lens during optical mixing.
            val cx = 4f * frame.density + (visualIndex + .5f) * frame.slotWidth
            val nativeCx = (nativeIndex + .5f) * nativeSlotWidth
            fun label() {
                if (frame.iconOnly) return
                labelPaint.color = artworkColor
                canvas.drawText(frame.localizeNavigationLabel(navigationLabels[index]), cx, labelBaseline, labelPaint)
            }
            fun drawIcon() {
                val saved = canvas.save()
                canvas.translate(cx, top)
                canvas.scale(iconDrawScale, iconDrawScale)
                canvas.translate(-nativeCx, -iconTop)
                canvas.clipRect(nativeIndex * nativeSlotWidth, iconTop, (nativeIndex + 1) * nativeSlotWidth, iconBottom)
                // Tint only this glyph band. A null-bounds layer before the
                // transform allocated the whole bar for every individual icon.
                canvas.saveLayer(nativeIndex * nativeSlotWidth, iconTop,
                    (nativeIndex + 1) * nativeSlotWidth, iconBottom, selectedPaint)
                canvas.drawRenderNode(artwork)
                canvas.restoreToCount(saved)
            }
            if (index == 4) {
                // Create is an action with a native expanding selection/ripple plate.
                // Draw its glyph independently so that plate never becomes artwork.
                val paint = createPaint.apply {
                    color = artworkColor
                    strokeWidth = h * .027f * scale * iconScale
                }
                val size = h * .38f * iconDrawScale
                val cy = top + iconHeight / 2f
                canvas.drawLine(cx - size / 2, cy, cx + size / 2, cy, paint)
                canvas.drawLine(cx, cy - size / 2, cx, cy + size / 2, paint)
                label()
                return@forEachIndexed
            }
            drawIcon()
            label()
        }
    }

    private fun drawDetachedCreate(canvas: Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) {
        if (!SpotifyNativeTabs.createVisible) return
        val layout = frame.barLayout ?: return
        if (layout.actionIndex != 4) return
        val cx = layout.actionLeft + layout.actionSize / 2f
        val cy = frame.height / 2f
        val paint = detachedCreatePaint
        // Same runtime accent as the selected icon mask, not a separate green.
        paint.style = Paint.Style.FILL
        paint.color = (frame.accentColor and 0xFFFFFF) or (70 shl 24)
        canvas.drawCircle(cx, cy, (layout.actionSize / 2f - 0.6f * frame.density).coerceAtLeast(1f), paint)
        val half = minOf(9f * frame.density, layout.actionSize * 0.17f)
        paint.style = Paint.Style.STROKE
        paint.color = android.graphics.Color.WHITE
        paint.strokeWidth = 2.6f * frame.density
        canvas.drawLine(cx - half, cy, cx + half, cy, paint)
        canvas.drawLine(cx, cy - half, cx, cy + half, paint)
        paint.style = Paint.Style.FILL
    }

    /** Horizontal main layout supplies a vertical rail, not five bottom cells.
     * Use independent 24-unit glyphs for all bottom entries, including actions
     * omitted from the native rail. Never scale/crop the rail as bottom artwork.
     */
    private fun drawLandscape(canvas: Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) {
        val metrics = frame.context.resources.displayMetrics
        val portraitWidth = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val source = sourceRef?.get()
        val side = source?.let { io.github.offlineglass.hook.globalGlassBottomGapPx(it) } ?: 0
        val portraitSlotWidth = (portraitWidth - 2f * side - 8f * frame.density) / frame.slotCount
        val nativeHeightId = frame.context.resources.getIdentifier("design_bottom_navigation_height", "dimen", "com.spotify.music")
        val nativeHeight = if (nativeHeightId != 0) frame.context.resources.getDimension(nativeHeightId) else 56f * frame.density
        val portraitScale = portraitArtworkScale ?: minOf(portraitSlotWidth / (portraitWidth / frame.slotCount), frame.height / nativeHeight)
        val size = 24f * frame.density * 1.2f * 1.15f * frame.extraScale * portraitScale
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = frame.textSize * frame.scaledDensity * 1.1f * frame.extraScale
        }
        val labelHeight = if (frame.iconOnly) 0f else text.fontMetrics.descent - text.fontMetrics.ascent
        val gap = if (frame.iconOnly) 0f else 2f * frame.density
        val top = (frame.height - size - gap - labelHeight) / 2f - if (frame.iconOnly) 0f else 3f * frame.density
        val names = listOf("主页", "搜索", "音乐库", "Premium", "创建")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 1.9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        frame.enabledIndices.forEachIndexed { visual, index ->
            if (frame.hideSelectedForOptics && index == frame.selectedIndex) return@forEachIndexed
            val color = if (index == frame.selectedIndex) frame.accentColor else 0xFFB3B3B3.toInt()
            val cx = 4f * frame.density + (visual + .5f) * frame.slotWidth
            val saved = canvas.save()
            canvas.translate(cx - size / 2f, top)
            canvas.scale(size / 24f, size / 24f)
            paint.color = color
            paint.style = Paint.Style.STROKE
            when (index) {
                0 -> {
                    val path = android.graphics.Path().apply {
                        moveTo(3f, 9f); lineTo(12f, 2.5f); lineTo(21f, 9f)
                        lineTo(21f, 21.5f); lineTo(15f, 21.5f); lineTo(15f, 14f)
                        lineTo(9f, 14f); lineTo(9f, 21.5f); lineTo(3f, 21.5f); close()
                    }
                    canvas.drawPath(path, paint)
                }
                1 -> {
                    canvas.drawCircle(10.5f, 10.5f, 7.5f, paint)
                    canvas.drawLine(16f, 16f, 22f, 22f, paint)
                }
                2 -> {
                    canvas.drawLine(4f, 3f, 4f, 21f, paint)
                    canvas.drawLine(9f, 3f, 9f, 21f, paint)
                    val path = android.graphics.Path().apply {
                        moveTo(15f, 3f); lineTo(21f, 6f); lineTo(21f, 21f); lineTo(15f, 21f); close()
                    }
                    if (index == frame.selectedIndex) paint.style = Paint.Style.FILL
                    canvas.drawPath(path, paint)
                }
                3 -> {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(12f, 12f, 11f, paint)
                    paint.style = Paint.Style.STROKE
                    paint.color = 0xFF151515.toInt()
                    for (row in 0..2) {
                        val y = 8f + row * 4f
                        val path = android.graphics.Path().apply {
                            moveTo(6f + row * .7f, y)
                            quadTo(12f, y - 2.1f, 18f - row * .7f, y + 1.1f)
                        }
                        canvas.drawPath(path, paint)
                    }
                }
                4 -> {
                    canvas.drawLine(2f, 12f, 22f, 12f, paint)
                    canvas.drawLine(12f, 2f, 12f, 22f, paint)
                }
            }
            canvas.restoreToCount(saved)
            if (!frame.iconOnly) {
                text.color = color
                canvas.drawText(frame.localizeNavigationLabel(names[index]), cx, top + size + gap - text.fontMetrics.ascent, text)
            }
        }
    }
}



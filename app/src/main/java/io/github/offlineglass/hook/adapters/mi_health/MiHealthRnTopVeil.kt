package io.github.offlineglass.hook.adapters.mi_health


import android.graphics.*
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import android.widget.TextView

/** RN content is fully attached: sample its child without changing Yoga's viewport. */
internal class MiHealthRnTopVeil {
    private var veil: DeviceVeilView? = null
    private var source: ViewGroup? = null
    private var overlayHost: android.widget.FrameLayout? = null
    private var container: ViewGroup? = null
    private var originalOrder = emptyList<View>()
    private var observer: ViewTreeObserver? = null
    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private var lastProbe = 0L
    private var previousGeometry = Long.MIN_VALUE
    private data class Binding(val parent: ViewGroup, val list: ViewGroup, val shell: View)

    fun currentGlassScene(): View? = veil?.takeIf { it.isShown && it.isAttachedToWindow }
    fun currentRawScene(): View? = source?.getChildAt(0)?.takeIf { it.isAttachedToWindow && it.isShown }
    fun currentHeaderTitle(): TextView? = originalOrder.filterIsInstance<TextView>().singleOrNull()
        ?.takeIf { it.isAttachedToWindow && it.parent === container }
    /** Same unclipped RN draw as the accepted veil, before any blur is applied. */
    fun recordMineButtonBackdrop(host: io.github.offlineglass.hook.GlassHostLayout) {
        veil?.recordMineButtonBackdrop(host)
    }
    fun currentHeaderControls(): List<MiHealthHeaderAction> {
        val candidates = LinkedHashMap<View, String>()
        val stack = ArrayDeque<View>(); originalOrder.forEach { stack += it }
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 80) {
            val view = stack.removeLast()
            if (!view.isShown || view is android.widget.HorizontalScrollView) continue
            val node = view.createAccessibilityNodeInfo()
            val buttonRole = node.className?.toString() == "android.widget.Button"
            val label = node.contentDescription?.toString().orEmpty()
            node.recycle()
            if (buttonRole &&
                view.width > 0 && view.height > 0 && view.width <= 64f * view.resources.displayMetrics.density &&
                view.height <= 64f * view.resources.displayMetrics.density) {
                var control = view
                while (control.parent is ViewGroup && (control.parent as View) !in originalOrder) {
                    val parent = control.parent as ViewGroup
                    if (parent.width <= 0 || parent.height <= 0 || parent.width > 64f * parent.resources.displayMetrics.density || parent.height > 64f * parent.resources.displayMetrics.density) break
                    control = parent
                }
                candidates[control] = label
            } else if (view is ViewGroup) for (i in 0 until view.childCount) stack += view.getChildAt(i)
        }
        if (candidates.size == 1) return candidates.keys.map { MiHealthHeaderAction(it, true) }
        if (candidates.size != 2) return emptyList()
        // Verified MessageIcon accessibility key, not a local-coordinate or obfuscated-symbol guess.
        val notificationLabels = setOf("消息通知", "訊息通知", "Notifications", "ئۇچۇر ئۇقتۇرۇشى", "འཕྲིན་ཡིག་བརྡ་ཐོ།")
        val message = candidates.filterValues { it in notificationLabels }.keys.singleOrNull() ?: return emptyList()
        return candidates.keys.map { MiHealthHeaderAction(it, it !== message) }
    }

    fun currentScrollSource(): ViewGroup? = source?.takeIf { it.isShown && it.isAttachedToWindow }

    fun update(root: View, now: Long, forceProbe: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < 33 || (!forceProbe && now - lastProbe < 600L)) return
        lastProbe = now
        val current = source
        if (current?.isShown == true && veil?.isAttachedToWindow == true) return
        val stack = ArrayDeque<View>(); stack += root
        val matches = ArrayList<Binding>()
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (!view.isShown || view === veil) continue
            if (view is ScrollView && isReactScroll(view) && view.childCount == 1 && view.getChildAt(0) is ViewGroup && view.getGlobalVisibleRect(Rect())) {
                val parent = view.parent as? ViewGroup
                if (parent != null && isReactGroup(parent)) {
                    val siblings = (0 until parent.childCount).map { parent.getChildAt(it) }.filter { it !== view && it !== veil }
                    // Verified RN shell: one vertical scroll and a sibling title plus action controls.
                    if (siblings.any { it is TextView } && siblings.any { it is ViewGroup } &&
                        siblings.none { it is ScrollView } && view.top > 0 && view.width >= parent.width * .9f) {
                        matches += Binding(parent, view, view)
                    }
                }
            }
            if (view is ScrollView && isReactScroll(view) && view.childCount == 1 &&
                view.getChildAt(0) is ViewGroup && view.getGlobalVisibleRect(Rect())) {
                var branch: View = view
                var ancestor = view.parent as? ViewGroup
                var pagerSeen = false
                var depth = 0
                while (ancestor != null && depth++ < 10) {
                    if (ancestor.accessibilityClassName?.toString() == "androidx.viewpager.widget.ViewPager") pagerSeen = true
                    if (pagerSeen && isReactGroup(ancestor)) {
                        val siblings = (0 until ancestor.childCount).map { ancestor.getChildAt(it) }
                        if (siblings.any { it is TextView } && siblings.any { it is android.widget.HorizontalScrollView } && branch.top > 0) {
                            matches += Binding(ancestor, view, branch)
                            break
                        }
                    }
                    branch = ancestor
                    ancestor = ancestor.parent as? ViewGroup
                }
            }
            if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i)
        }
        if (stack.isNotEmpty() || matches.size != 1) return
        val binding = matches.single()
        val parent = binding.parent; val scroll = binding.list; val shell = binding.shell
        val extension = if (shell !== scroll) (64f * scroll.resources.displayMetrics.density).toInt() else 0
        val height = shell.top + extension
        val next = DeviceVeilView(scroll.context, scroll, parent, if (shell !== scroll) 1.25f else 1f, shell === scroll)
        if (!next.enableBlur(height)) return
        dispose()
        source = scroll; container = parent; veil = next
        originalOrder = (0 until parent.childCount).map { parent.getChildAt(it) }.filter { it !== shell }
        val host = android.widget.FrameLayout(scroll.context).apply {
            isClickable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            clipChildren = false; clipToPadding = false
        }
        host.addView(next, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val chrome = object : View(scroll.context) {
            override fun onDraw(canvas: Canvas) {
                originalOrder.forEach { child ->
                    if (child.visibility == View.VISIBLE && child.alpha > 0f && child.parent === parent) {
                        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (child.alpha * 255).toInt())
                        canvas.translate(child.left.toFloat(), child.top.toFloat()); canvas.concat(child.matrix)
                        child.draw(canvas); canvas.restoreToCount(save)
                    }
                }
            }
        }
        host.addView(chrome, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun sizeHost() {
            host.measure(View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((shell.top + extension).coerceAtLeast(1), View.MeasureSpec.EXACTLY))
            host.layout(0, 0, parent.width, (shell.top + extension).coerceAtLeast(1))
        }
        sizeHost()
        parent.overlay.add(host); overlayHost = host
        android.util.Log.i("MiHealthRnVeil", "bound scroll=${scroll.javaClass.name} headerHeight=${scroll.top} nativeInsetsPreserved=true")
        previousGeometry = Long.MIN_VALUE
        val beforeDraw = ViewTreeObserver.OnPreDrawListener {
            if (scroll.isShown && next.isAttachedToWindow) {
                if (shell.top > 0 && (host.height != shell.top + extension || host.width != parent.width)) {
                    sizeHost(); next.enableBlur(shell.top + extension)
                }
                if (originalOrder.any { it.isDirty }) chrome.invalidate()
                val geometry = next.sourcePositions()
                if (geometry != previousGeometry || scroll.isDirty || scroll.getChildAt(0)?.isDirty == true) {
                    previousGeometry = geometry; next.invalidate()
                }
            }
            true
        }
        scroll.viewTreeObserver.let { if (it.isAlive) {
            it.addOnPreDrawListener(beforeDraw); observer = it; listener = beforeDraw
        } }
    }

    private fun isReactScroll(view: View): Boolean = hierarchy(view).any { it.name == "com.facebook.react.views.scroll.ReactScrollView" }
    private fun isReactGroup(view: View): Boolean = hierarchy(view).any { it.name == "com.facebook.react.views.view.ReactViewGroup" }
    private fun hierarchy(view: View): Sequence<Class<*>> = generateSequence<Class<*>>(view.javaClass) { it.superclass }

    fun dispose() {
        val callback = listener
        observer?.let { if (it.isAlive && callback != null) it.removeOnPreDrawListener(callback) }
        observer = null; listener = null
        overlayHost?.let { container?.overlay?.remove(it) }
        overlayHost = null
        originalOrder = emptyList(); source = null; container = null; veil = null
    }

    private class DeviceVeilView(
        context: android.content.Context,
        private val recycler: ViewGroup,
        private val container: ViewGroup,
        private val blurRadiusScale: Float,
        private val followNativeStretch: Boolean,
    ) : View(context) {
        private var stretch: MiHealthNativeStretch.Watch? = null
        private val sourceMatrix = Matrix()
        private val inverse = Matrix()
        private val matrixValues = FloatArray(9)
        private val childLocation = IntArray(2)
        private val veilLocation = IntArray(2)
        private val recyclerLocation = IntArray(2)

        init {
            setWillNotDraw(false)
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (followNativeStretch) (recycler as? ScrollView)?.let { stretch = MiHealthNativeStretch.watch(it, this) }
        }
        override fun onDetachedFromWindow() { stretch?.detach(); stretch = null; super.onDetachedFromWindow() }

        fun isSourceShown(): Boolean = recycler.isShown

        fun recordMineButtonBackdrop(host: io.github.offlineglass.hook.GlassHostLayout) {
            // Only the verified direct-scroll Mine shell. Sport keeps its
            // existing nested-pager sampling and visual parameters.
            if (!followNativeStretch || !recycler.isShown) return
            val source = recycler.getChildAt(0) ?: return
            val node = host.liveBackdropNodeForAdapter
            val w = node.width; val h = node.height
            if (w <= 0 || h <= 0) return
            sourceMatrix.reset(); inverse.reset()
            source.transformMatrixToGlobal(sourceMatrix)
            host.transformMatrixToGlobal(inverse)
            if (!inverse.invert(inverse)) return
            sourceMatrix.setConcat(inverse, sourceMatrix)
            val recording = node.beginRecording(w, h)
            try {
                recording.drawColor(if (host.adapterContentOrSystemDark()) Color.BLACK else Color.WHITE)
                recording.translate((w - host.width) / 2f, (h - host.height) / 2f)
                recording.translate(0f, stretch?.translation() ?: 0f)
                recording.concat(sourceMatrix)
                // View.draw bypasses the content-root RenderNode's viewport
                // clip, exactly as onDraw below does for the gradient veil.
                source.draw(recording)
            } finally { node.endRecording() }
        }

        fun sourcePositions(): Long {
            recycler.getChildAt(0)?.getLocationOnScreen(recyclerLocation)
            var result = recycler.childCount.toLong() * 31 + recyclerLocation[1]
            result = result * 31 + recyclerLocation[0]
            result = result * 31 + recycler.scrollY
            result = result * 31 + (stretch?.translation() ?: 0f).toBits()
            recycler.getChildAt(0)?.let { source ->
                sourceMatrix.reset(); source.transformMatrixToGlobal(sourceMatrix)
                sourceMatrix.getValues(matrixValues)
                matrixValues.forEach { result = result * 31 + it.toBits() }
            }
            for (index in 0 until recycler.childCount) {
                val child = recycler.getChildAt(index)
                result = result * 31 + System.identityHashCode(child)
                result = result * 31 + child.top
                result = result * 31 + child.bottom
                result = result * 31 + child.translationY.toBits()
                result = result * 31 + child.alpha.toBits()
            }
            return result
        }

        fun enableBlur(height: Int): Boolean = runCatching {
            val radii = floatArrayOf(32f, 18f, 8f, 2f, 0f)
            var combined: RenderEffect? = null
            radii.forEachIndexed { index, radius ->
                val mask = RuntimeShader(LEVEL_MASK)
                mask.setFloatUniform("veilHeight", height.toFloat())
                mask.setFloatUniform("level", index.toFloat())
                val masking = RenderEffect.createRuntimeShaderEffect(mask, "content")
                val effect = if (radius == 0f) masking else {
                    val pixels = radius * blurRadiusScale * resources.displayMetrics.density
                    RenderEffect.createChainEffect(
                        masking,
                        RenderEffect.createBlurEffect(pixels, pixels, Shader.TileMode.MIRROR),
                    )
                }
                combined = combined?.let { RenderEffect.createBlendModeEffect(it, effect, BlendMode.PLUS) }
                    ?: effect
            }
            setRenderEffect(combined ?: return@runCatching false)
            true
        }.getOrDefault(false)

        override fun onDraw(canvas: Canvas) {
            if (!recycler.isAttachedToWindow) return
            getLocationOnScreen(veilLocation)
            recycler.getChildAt(0)?.getLocationOnScreen(recyclerLocation)
            // The parent fragment fixes the title above the RecyclerView.
            // Keep Xiaomi's screen coordinates. A tall RecyclerView item can
            // remain attached after almost all of it has left the viewport;
            // its hidden upper part must then stop appearing in the title.
            container.background?.let { background ->
                val oldBounds = Rect(background.bounds)
                background.setBounds(0, 0, width, height)
                background.draw(canvas)
                background.setBounds(oldBounds)
            }
            val saved = canvas.save()
            val source = recycler.getChildAt(0)
            if (source != null) {
                sourceMatrix.reset(); inverse.reset()
                source.transformMatrixToGlobal(sourceMatrix)
                transformMatrixToGlobal(inverse)
                if (inverse.invert(inverse)) {
                    sourceMatrix.setConcat(inverse, sourceMatrix)
                    canvas.translate(0f, stretch?.translation() ?: 0f)
                    canvas.concat(sourceMatrix)
                    source.draw(canvas)
                }
            }
            canvas.restoreToCount(saved)
        }
    }

    private companion object {
        const val LEVEL_MASK = """
            uniform shader content;
            uniform float veilHeight;
            uniform float level;
            half4 main(float2 coord) {
                float t = clamp(coord.y / max(veilHeight, 1.0), 0.0, 1.0);
                float position = smoothstep(0.0, 1.0, t) * 4.0;
                float weight = max(1.0 - abs(position - level), 0.0);
                return content.eval(coord) * half(weight);
            }
        """
    }
}

package io.github.offlineglass.hook.adapters.mi_health


import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver

/** Native scrolling-page veil: shared mine/sport rendering, with page-scoped bindings. */
internal class MiHealthScrollTopVeil(
    private val contentAnchorId: String,
    private val headerId: String,
    private val extendViewport: Boolean,
) {
    private var title: ViewGroup? = null
    private var page: ViewGroup? = null
    private var originalTitleBackground: Drawable? = null
    private var parent: ViewGroup? = null
    private var originalClipToPadding: Boolean? = null
    private var veil: DeviceVeilView? = null
    private var scrollObserver: ViewTreeObserver? = null
    private var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var drawObserver: ViewTreeObserver? = null
    private var drawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var lastProbeAt = 0L
    private var rawScene: View? = null
    private var pager: ViewGroup? = null
    private var recyclerView: ViewGroup? = null
    private var originalTopMargin = 0
    private var originalPaddingTop = 0
    private var originalRecyclerClip = true
    private var viewportInsetHeight = -1

    fun currentRawScene(): View? = rawScene?.takeIf { it.isAttachedToWindow && it.isShown }
    fun currentGlassScene(): View? = veil?.takeIf { it.isShown && it.isAttachedToWindow }
    fun currentHeader(): ViewGroup? = title?.takeIf { it.isShown && it.isAttachedToWindow }

    fun update(root: View, now: Long) {
        if (Build.VERSION.SDK_INT < 33 || now - lastProbeAt < 600L) return
        lastProbeAt = now
        veil?.let { current ->
            if (current.isAttachedToWindow && page?.isShown == true && current.isSourceShown()) {
                updateViewportInset(title?.height ?: 0)
                val height = title?.bottom ?: 0
                if (height > 0 && current.layoutParams.height != height) {
                    current.layoutParams = current.layoutParams.apply { this.height = height }
                    current.enableBlur(height)
                }
                current.translationY = -(parent?.paddingTop ?: 0).toFloat()
                return
            }
        }

        val container = findPage(root) ?: return
        val header = (0 until container.childCount).map { container.getChildAt(it) }
            .filterIsInstance<ViewGroup>().singleOrNull { resourceName(it) == headerId } ?: return
        if (header.bottom <= 0 || header.height <= 0) return
        val recycler = findVisibleGroup(container, "scrollView") ?: return
        if (recycler.childCount != 1 || recycler.getChildAt(0) !is ViewGroup) return
        val pager = if (extendViewport) findVisibleGroup(container, "swipe_container") else null
        if (extendViewport && (pager == null || pager.parent !== container || recycler.parent !== pager)) return
        if (!extendViewport && recycler.parent !== container) return
        val next = DeviceVeilView(header.context, recycler, container)
        if (!next.enableBlur(header.bottom)) return
        dispose()
        val params = pager?.layoutParams as? ViewGroup.MarginLayoutParams
        if (extendViewport && params == null) return
        originalTopMargin = params?.topMargin ?: 0
        page = container
        originalPaddingTop = recycler.paddingTop
        originalRecyclerClip = recycler.clipToPadding
        this.pager = pager
        recyclerView = recycler
        updateViewportInset(header.height)
        if (extendViewport) recycler.clipToPadding = false
        rawScene = recycler.getChildAt(0)
        title = header
        originalTitleBackground = header.background
        parent = container
        originalClipToPadding = container.clipToPadding
        veil = next
        container.addView(next, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, header.bottom))
        header.bringToFront()
        container.clipToPadding = false
        next.translationY = -container.paddingTop.toFloat()
        header.background = null
        if (!extendViewport) {
            next.visibility = header.visibility
            next.alpha = header.alpha
        }
        val observer = recycler.viewTreeObserver
        val listener = ViewTreeObserver.OnScrollChangedListener { next.invalidate() }
        if (observer.isAlive) {
            observer.addOnScrollChangedListener(listener)
            scrollObserver = observer
            scrollListener = listener
            // RecyclerView scrolls by offsetting its children; its own scrollY
            // stays at zero, so OnScrollChanged alone misses most frames.
            var previousPositions = Long.MIN_VALUE
            val beforeDraw = ViewTreeObserver.OnPreDrawListener {
                if (!recycler.isShown) return@OnPreDrawListener true
                if (!extendViewport) {
                    if (next.visibility != header.visibility) next.visibility = header.visibility
                    if (next.alpha != header.alpha) next.alpha = header.alpha
                }
                // The native sport title can write a solid background while scrolling.
                // Keep only its text/buttons; the veil owns its material while enabled.
                if (header.background != null) {
                    originalTitleBackground = header.background
                    header.background = null
                }
                val positions = next.sourcePositions()
                if (positions != previousPositions || recycler.isDirty) {
                    previousPositions = positions
                    next.invalidate()
                }
                true
            }
            observer.addOnPreDrawListener(beforeDraw)
            drawObserver = observer
            drawListener = beforeDraw
        }
    }

    private fun updateViewportInset(height: Int) {
        if (height <= 0 || height == viewportInsetHeight) return
        val page = pager ?: return
        val list = recyclerView ?: return
        val params = page.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.topMargin = originalTopMargin - height
        page.layoutParams = params
        list.setPadding(list.paddingLeft, originalPaddingTop + height, list.paddingRight, list.paddingBottom)
        viewportInsetHeight = height
    }

    fun dispose() {
        rawScene = null
        pager?.let { view ->
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = originalTopMargin; view.layoutParams = it
            }
        }
        recyclerView?.let {
            it.setPadding(it.paddingLeft, originalPaddingTop, it.paddingRight, it.paddingBottom)
            it.clipToPadding = originalRecyclerClip
        }
        pager = null; recyclerView = null; viewportInsetHeight = -1
        val observer = scrollObserver
        val listener = scrollListener
        if (observer?.isAlive == true && listener != null) observer.removeOnScrollChangedListener(listener)
        scrollObserver = null
        scrollListener = null
        val beforeDraw = drawListener
        drawObserver?.let { if (it.isAlive && beforeDraw != null) it.removeOnPreDrawListener(beforeDraw) }
        drawObserver = null
        drawListener = null
        veil?.let { (it.parent as? ViewGroup)?.removeView(it) }
        veil = null
        parent?.let { group -> originalClipToPadding?.let { group.clipToPadding = it } }
        parent = null
        originalClipToPadding = null
        title?.let { header -> if (header.background == null) header.background = originalTitleBackground }
        title = null
        page = null
        originalTitleBackground = null
    }

    private fun resourceName(view: View): String? =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    private fun findPage(root: View): ViewGroup? {
        val stack = ArrayDeque<View>(); stack += root
        var visited = 0
        var match: ViewGroup? = null
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (view !is ViewGroup || !view.isShown) continue
            val directIds = (0 until view.childCount).map { resourceName(view.getChildAt(it)) }
            val hasStructure = headerId in directIds &&
                (if (extendViewport) "swipe_container" in directIds else "scrollView" in directIds)
            if (hasStructure && findVisibleGroup(view, contentAnchorId) != null) {
                if (match != null) return null
                match = view
            } else for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i)
        }
        return match
    }

    private fun findVisibleGroup(root: View, name: String): ViewGroup? {
        val stack = ArrayDeque<View>()
        stack += root
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val view = stack.removeLast()
            if (view !is ViewGroup || !view.isShown) continue
            if (runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() == name) return view
            for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index)
        }
        return null
    }

    private class DeviceVeilView(
        context: android.content.Context,
        private val recycler: ViewGroup,
        private val container: ViewGroup,
    ) : View(context) {
        private val childLocation = IntArray(2)
        private val veilLocation = IntArray(2)
        private val recyclerLocation = IntArray(2)

        init {
            setWillNotDraw(false)
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun isSourceShown(): Boolean = recycler.isShown

        fun sourcePositions(): Long {
            recycler.getLocationOnScreen(recyclerLocation)
            var result = recycler.childCount.toLong() * 31 + recyclerLocation[1]
            result = result * 31 + recyclerLocation[0]
            result = result * 31 + recycler.scrollY
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
                    val pixels = radius * resources.displayMetrics.density
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
            recycler.getLocationOnScreen(recyclerLocation)
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
            canvas.translate((recyclerLocation[0] - veilLocation[0]).toFloat(),
                (recyclerLocation[1] - veilLocation[1]).toFloat())
            recycler.draw(canvas)
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

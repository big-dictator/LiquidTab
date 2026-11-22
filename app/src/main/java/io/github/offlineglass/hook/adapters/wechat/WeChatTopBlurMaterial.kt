package io.github.offlineglass.hook.adapters.wechat


import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import java.lang.ref.WeakReference
import kotlin.math.ceil

/** Native content display list only: neither toolbar foreground nor our own overlay is sampled. */
internal class WeChatTopBlurMaterial(
    root: View, header: View, color: Int,
    private val bottomExtensionDp: Float = 0f,
    private val blurRadiusScale: Float = 1f,
    private val pageLocal: Boolean = false,
) {
    private val root = WeakReference(root)
    private val header = WeakReference(header)
    private var source = WeakReference<View>(null)
    private var conversations = WeakReference<android.widget.ListView>(null)
    private val band = RenderNode("WeChatTopGradientBlur")
    private var sceneValid = false
    private val nodes = ArrayList<RenderNode>()
    private val recordedNodes = ArrayList<RenderNode>()
    private var recordedProperties = IntArray(0)
    private var recordedWidth = 0
    private var recordedHeight = 0
    private var recordedDensity = 0f
    private var recordedX = 0
    private var recordedY = 0
    private var cachedEffect: RenderEffect? = null
    private var effectHeight = -1f
    private var effectDensity = -1f
    private val rootPosition = IntArray(2)
    private val parentPosition = IntArray(2)
    private val headerPosition = IntArray(2)
    private val visible = Rect()
    private var failed = false
    var hideMaterial = false
    var color = color
        set(value) { if (field != value) { field = value; sceneValid = false } }
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            failed = false
            if (!value) {
                band.discardDisplayList(); band.setRenderEffect(null)
                cachedEffect = null; sceneValid = false
                nodes.clear(); recordedNodes.clear()
            }
        }

    fun extensionPx(density: Float): Int = ceil(bottomExtensionDp * density).toInt()

    fun bind(view: View) { if (source.get() !== view) { source = WeakReference(view); sceneValid = false } }
    fun bindConversations(view: android.widget.ListView) {
        if (conversations.get() !== view) { conversations = WeakReference(view); sceneValid = false }
    }

    fun drawable(color: IntArray, status: Boolean): GradientDrawable = object : GradientDrawable(Orientation.TOP_BOTTOM, color) {
        override fun draw(canvas: Canvas) {
            if (hideMaterial) return
            if (enabled && !failed && canvas.isHardwareAccelerated) {
                val saved = canvas.save()
                try {
                    canvas.clipRect(bounds)
                    drawBlur(canvas, status)
                } catch (error: Exception) {
                    failed = true
                    android.util.Log.e("LiquidTabWeChatTop", "Top blur render contract failed", error)
                } finally { canvas.restoreToCount(saved) }
            }
            super.draw(canvas)
        }
    }

    private fun drawBlur(canvas: Canvas, status: Boolean) {
        val root = root.get() ?: return
        val header = header.get() ?: return
        val source = source.get() ?: return
        // Chat's native page (including its status material) is animated as a
        // unit. Cancel that common ancestor transform before sampling: using
        // window X here would apply the entry/swipe offset a second time.
        (if (pageLocal) root else root.rootView).getLocationInWindow(rootPosition)
        header.getLocationInWindow(headerPosition)
        val left = headerPosition[0] - rootPosition[0]
        val top = headerPosition[1] - rootPosition[1]
        val density = root.resources.displayMetrics.density
        val height = (top + header.height + extensionPx(density)).coerceAtLeast(1)
        val width = header.width
        if (width <= 0) return
        val list = conversations.get()?.takeIf {
            it.isShown && it.alpha > .01f && it.getGlobalVisibleRect(visible) && visible.width() >= width * .9f
        }
        // ConversationListView's first native header is the entire mini-app
        // desktop, not information-flow content (verified addHeaderView call).
        // Do not sample its fallback backgrounds or the list's onDraw chrome.
        val parent = list ?: (source.parent as? View ?: return)
        parent.getLocationInWindow(parentPosition)
        val x = parentPosition[0] - rootPosition[0] - parent.scrollX
        val y = parentPosition[1] - rootPosition[1] - parent.scrollY
        nodes.clear()
        if (list != null) {
            for (index in 0 until list.childCount) {
                val child = list.getChildAt(index)
                if (child.visibility == View.VISIBLE &&
                    child.javaClass.name != "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer") addNode(child)
            }
        } else addNode(source)
        val changed = !sceneValid || recordedWidth != width || recordedHeight != height ||
            recordedDensity != density || recordedX != x || recordedY != y || !matchesRecordedNodes()
        if (changed || !band.hasDisplayList()) {
            val padding = ceil(96f * blurRadiusScale * density * SCALE).toInt()
            val w = ceil(width * SCALE).toInt()
            val h = ceil(height * SCALE).toInt() + padding * 2
            band.setPosition(0, 0, w, h)
            band.setClipToBounds(true)
            val scaledHeight = height * SCALE
            val scaledDensity = density * SCALE
            // Content nodes can move/rebind without changing the blur kernels.
            // Keep the compiled shader/effect graph until its geometry changes.
            if (cachedEffect == null || effectHeight != scaledHeight || effectDensity != scaledDensity) {
                cachedEffect = effect(scaledHeight, scaledDensity, padding.toFloat())
                effectHeight = scaledHeight; effectDensity = scaledDensity
                band.setRenderEffect(cachedEffect)
            }
            val recording = band.beginRecording(w, h)
            recording.drawColor(color)
            recording.translate(0f, padding.toFloat())
            recording.scale(SCALE, SCALE)
            // RenderNode already carries the source's own position/translation.
            recording.translate(x.toFloat(), y.toFloat())
            nodes.forEach(recording::drawRenderNode)
            band.endRecording()
            recordedWidth = width
            recordedHeight = height
            recordedDensity = density
            recordedX = x
            recordedY = y
            recordNodeProperties()
            sceneValid = true
        }
        val saved = canvas.save()
        if (!status) canvas.translate(-left.toFloat(), -top.toFloat())
        canvas.scale(1f / SCALE, 1f / SCALE)
        canvas.translate(0f, -ceil(96f * blurRadiusScale * density * SCALE).toFloat())
        canvas.drawRenderNode(band)
        canvas.restoreToCount(saved)
    }

    private fun addNode(view: View) {
        val node = nodeField?.get(view) as? RenderNode ?: return
        if (node.hasDisplayList()) nodes.add(node)
    }

    private fun matchesRecordedNodes(): Boolean {
        if (recordedNodes.size != nodes.size) return false
        for (index in nodes.indices) {
            val node = nodes[index]
            val offset = index * 7
            if (recordedNodes[index] !== node ||
                recordedProperties[offset] != node.left ||
                recordedProperties[offset + 1] != node.top ||
                recordedProperties[offset + 2] != node.right ||
                recordedProperties[offset + 3] != node.bottom ||
                recordedProperties[offset + 4] != node.translationX.toBits() ||
                recordedProperties[offset + 5] != node.translationY.toBits() ||
                recordedProperties[offset + 6] != node.alpha.toBits()) return false
        }
        return true
    }

    private fun recordNodeProperties() {
        recordedNodes.clear()
        recordedNodes.addAll(nodes)
        if (recordedProperties.size < nodes.size * 7) recordedProperties = IntArray(nodes.size * 7)
        for (index in nodes.indices) {
            val node = nodes[index]
            val offset = index * 7
            recordedProperties[offset] = node.left
            recordedProperties[offset + 1] = node.top
            recordedProperties[offset + 2] = node.right
            recordedProperties[offset + 3] = node.bottom
            recordedProperties[offset + 4] = node.translationX.toBits()
            recordedProperties[offset + 5] = node.translationY.toBits()
            recordedProperties[offset + 6] = node.alpha.toBits()
        }
    }

    private fun effect(height: Float, density: Float, padding: Float): RenderEffect {
        var combined: RenderEffect? = null
        floatArrayOf(32f, 18f, 8f, 2f).forEachIndexed { index, radius ->
            val mask = RuntimeShader(MASK)
            mask.setFloatUniform("height", height)
            mask.setFloatUniform("padding", padding)
            mask.setFloatUniform("level", index.toFloat())
            val level = RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(mask, "content"),
                RenderEffect.createBlurEffect(radius * blurRadiusScale * density, radius * blurRadiusScale * density, Shader.TileMode.MIRROR),
            )
            combined = combined?.let { RenderEffect.createBlendModeEffect(it, level, BlendMode.PLUS) } ?: level
        }
        return checkNotNull(combined)
    }

    companion object {
        private const val SCALE = .25f
        private val nodeField = runCatching {
            View::class.java.getDeclaredField("mRenderNode").apply { isAccessible = true }
        }.getOrNull()
        private const val MASK = """
uniform shader content;
uniform float height;
uniform float padding;
uniform float level;
half4 main(float2 p) {
    float t = clamp((p.y - padding) / max(height, 1.0), 0.0, 1.0);
    float position = smoothstep(0.0, 1.0, t) * 4.0;
    return content.eval(p) * half(max(1.0 - abs(position - level), 0.0));
}
"""
    }
}

package io.github.offlineglass.hook.adapters.xianyu

import android.graphics.Canvas
import android.graphics.RenderNode
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedHelpers
import android.os.SystemClock
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.GlassHostLayout

/** Projects native tab artwork per slot, including the sibling publish action. */
internal class XianyuNavigationState(private val host: GlassHostLayout) : AppNavigationState {
    private data class Tab(val artwork: View?, val badge: View?)
    private var source: ViewGroup? = null
    private var tabs = emptyList<Tab>()
    private var refreshAt = 0L
    private val origin = IntArray(2)
    private val position = IntArray(2)
    private val pageChrome = XianyuPageChrome()
    private var messagePipelineActive = false
    private var sampledTexture: android.graphics.SurfaceTexture? = null
    private var sampledTimestamp = Long.MIN_VALUE
    private var messageFrameChanged = true
    private var frameWidth = 0
    private var frameHeight = 0
    private val navigationNode = RenderNode("Xianyu-Message-Artwork")
    @Volatile private var artworkSignature = 0L
    private var recordedSignature = Long.MIN_VALUE
    private var recordedFrame: AdapterNavigationFrame? = null

    override val allowsSoftwareBackdropCapture: Boolean get() = host.adapterSelectedIndex != 3

    override fun resolveBackdropScene(host: View): View? =
        if (this.host.adapterSelectedIndex == 3) pageChrome.messageFrameTexture() else null

    override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View>? =
        if (host.adapterSelectedIndex == 3) listOfNotNull(pageChrome.messageFrameTexture()) else null

    override fun skipLiveBackdrop(selectedIndex: Int): Boolean =
        selectedIndex == 3 && pageChrome.messageFrameTexture() == null

    override fun canReuseLiveBackdrop(selectedIndex: Int): Boolean =
        selectedIndex == 3 && !messageFrameChanged

    override fun forceLiveBackdropFrame(selectedIndex: Int): Boolean =
        selectedIndex == 3 && messageFrameChanged

    override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean {
        val message = host.adapterSelectedIndex == 3
        if (message != messagePipelineActive) {
            sampledTexture = null
            sampledTimestamp = Long.MIN_VALUE
            messageFrameChanged = true
            host.invalidateOpticalBackdropForAdapter()
        }
        messagePipelineActive = message
        return false
    }

    fun onMessageTouch(event: android.view.MotionEvent) {
        if (event.actionMasked != android.view.MotionEvent.ACTION_CANCEL) host.invalidate()
    }
    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) {
        // The message compositor renderer also invokes prepare on its worker.
        // Native layout/source discovery stays on the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val now = SystemClock.uptimeMillis()
        if (source === this.source && now < refreshAt) return
        this.source = source
        val shell = source?.let(::shell)
        shell?.let(pageChrome::prepare)
        val row = shell?.let { find(it, "indicator_itmes") } as? ViewGroup
        tabs = (0 until 5).map { index ->
            val group = row?.let { if (index < it.childCount) it.getChildAt(index) else null }
            if (index == 2) Tab(shell?.let { find(it, "post_container") ?: find(it, "post_click") }, null)
            else Tab(group?.let { find(it, "tab") }, group?.let { find(it, "tv_msg_unread") })
        }
        // This is the publish button's white outer shade, not its yellow
        // post_ball_bg artwork. It must not be projected into the glass.
        shell?.let { find(it, "post_container") }?.let {
            if (it.background != null) it.background = null
        }
        refreshAt = now + 250L
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (frame.selectedIndex != 3 || !canvas.isHardwareAccelerated) {
            drawArtwork(canvas, frame)
            return
        }
        // Scrolling changes the backdrop, not the native tab artwork. Retain
        // its display list until native state, Lottie progress, or geometry
        // changes instead of replaying five View trees for every copied strip.
        val signature = artworkSignature
        if (recordedSignature != signature || recordedFrame != frame || !navigationNode.hasDisplayList()) {
            val recording = navigationNode.beginRecording(frame.width, frame.height)
            try { drawArtwork(recording, frame) } finally { navigationNode.endRecording() }
            navigationNode.setPosition(0, 0, frame.width, frame.height)
            recordedSignature = signature
            recordedFrame = frame
        }
        canvas.drawRenderNode(navigationNode)
    }

    private fun drawArtwork(canvas: Canvas, frame: AdapterNavigationFrame) {
        for ((visual, index) in frame.enabledIndices.withIndex()) {
            val tab = tabs.getOrNull(index) ?: continue
            val artwork = tab.artwork?.takeIf { it.width > 0 && it.height > 0 } ?: continue
            val animation = find(artwork, "tab_icon_animation")
                ?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
            val icon = animation ?: find(artwork, "tab_icon")
                ?.takeIf { it.visibility == View.VISIBLE }
            // lottie_animation_view.xml sets an independent white View background.
            // The animation may be inflated on the very first tap, after prepare;
            // clear that one surface before drawing, leaving its Drawable intact.
            animation?.takeIf { Looper.myLooper() == Looper.getMainLooper() }?.let { animation ->
                if (animation.background != null) animation.background = null
            }
            val centerX = 4f * frame.density + (visual + .5f) * frame.slotWidth
            val centerY = frame.height / 2f
            val content = if (frame.iconOnly && index != 2) {
                icon ?: artwork
            } else artwork
            val scale = frame.iconScale * frame.extraScale *
                if (index == 2) (63.36f * frame.density / content.height.coerceAtLeast(1)) else 1f
            val x = centerX - content.width * scale / 2f
            val y = centerY - content.height * scale / 2f
            // Shift artwork only. Badge coordinates below retain the original y.
            if (index == 2) {
                val background = find(artwork, "post_ball_bg")
                val camera = arrayOf("post_ball_main_content", "post_ball_seafood_content", "post_ball_fun_content")
                    .firstNotNullOfOrNull { id -> find(artwork, id)?.takeIf { it.visibility == View.VISIBLE } }
                if (background != null && camera != null) {
                    drawPart(canvas, artwork, background, x, y - 3f * frame.density, scale)
                    drawPart(canvas, artwork, camera, x, y - 3f * frame.density, scale, 1.3f)
                } else drawView(canvas, content, x, y - 3f * frame.density, scale)
            } else if (!frame.iconOnly) {
                // Native onAnimationStart refreshes the static Drawable while
                // Lottie remains visible. Drawing the whole stack projects both.
                // Select one icon layer; project the label independently at its
                // existing native offset, without mutating native visibility.
                icon?.let { drawPart(canvas, artwork, it, x, y - 3f * frame.density, scale, 1.12f) }
                find(artwork, "tab_title")?.takeIf { it.visibility == View.VISIBLE }?.let {
                    drawPart(canvas, artwork, it, x, y - 3f * frame.density, scale)
                }
            } else drawView(canvas, content, x - content.width * scale * .06f,
                y - 3f * frame.density - content.height * scale * .06f, scale * 1.12f)
            tab.badge?.takeIf { it.visibility == View.VISIBLE && it.width > 0 }?.let { badge ->
                content.getLocationInWindow(origin)
                badge.getLocationInWindow(position)
                drawView(canvas, badge, x + (position[0] - origin[0]) * scale,
                    y + (position[1] - origin[1]) * scale, scale)
            }
        }
    }

    private fun drawPart(canvas: Canvas, root: View, part: View, x: Float, y: Float, scale: Float,
                         artworkScale: Float = 1f) {
        root.getLocationInWindow(origin)
        part.getLocationInWindow(position)
        drawView(canvas, part,
            x + (position[0] - origin[0]) * scale - part.width * scale * (artworkScale - 1f) / 2f,
            y + (position[1] - origin[1]) * scale - part.height * scale * (artworkScale - 1f) / 2f,
            scale * artworkScale)
    }

    private fun drawView(canvas: Canvas, view: View, x: Float, y: Float, scale: Float) {
        val save = canvas.save()
        try {
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            // View.draw bypasses the hidden ancestor's alpha, retaining native
            // child visibility, animations, colours and label metrics.
            view.draw(canvas)
        } finally { canvas.restoreToCount(save) }
    }

    override fun onHostFrame(host: View) {
        prepare(this.host.adapterNavigationSource) { _, _ -> }
        pageChrome.update()
        for (tab in tabs) tab.artwork?.let { find(it, "tab_icon_animation") }?.let {
            if (it.background != null) it.background = null
        }
        updateBackdropRendererMode(this.host)
        if (this.host.adapterSelectedIndex == 3) {
            val texture = pageChrome.messageFrameTexture()?.surfaceTexture
            val timestamp = texture?.let { runCatching { it.timestamp }.getOrNull() }
            messageFrameChanged = texture !== sampledTexture || timestamp == null || timestamp <= 0L ||
                timestamp != sampledTimestamp || frameWidth != host.width || frameHeight != host.height
            sampledTexture = texture
            sampledTimestamp = timestamp ?: Long.MIN_VALUE
            frameWidth = host.width
            frameHeight = host.height
            // Consume no pixels on the CPU. The narrow GPU wrapper references
            // only Flutter's TextureView, and is refreshed on producer changes.
            if (messageFrameChanged) this.host.invalidate()
            var signature = 17L
            fun visit(view: View) {
                signature = signature * 31 + System.identityHashCode(view)
                signature = signature * 31 + view.visibility
                signature = signature * 31 + view.alpha.toBits()
                signature = signature * 31 + view.width + view.height + view.left + view.top
                signature = signature * 31 + view.scaleX.toBits() + view.scaleY.toBits()
                signature = signature * 31 + view.translationX.toBits() + view.translationY.toBits()
                if (view is TextView) {
                    signature = signature * 31 + view.text.hashCode()
                    signature = signature * 31 + view.currentTextColor + view.textSize.toBits()
                }
                if (view is ImageView) {
                    signature = signature * 31 + System.identityHashCode(view.drawable)
                    signature = signature * 31 + (view.drawable?.state?.contentHashCode() ?: 0)
                    if (name(view) == "tab_icon_animation" && view.visibility == View.VISIBLE) {
                        val progress = runCatching { XposedHelpers.callMethod(view, "getProgress") as Float }.getOrNull()
                        // Unknown animation implementations remain uncached.
                        signature = signature * 31 + (progress?.toBits()?.toLong() ?: SystemClock.uptimeMillis())
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            for (tab in tabs) { tab.artwork?.let(::visit); tab.badge?.let(::visit) }
            artworkSignature = signature
        }
    }

    override fun dispose() { source = null; tabs = emptyList(); pageChrome.clear() }

    companion object {
        private fun name(view: View): String? = runCatching {
            if (view.id <= 0) return null
            view.resources.getResourceEntryName(view.id)
        }.getOrNull()

        fun shell(source: ViewGroup): ViewGroup {
            var current: View? = source
            while (current is ViewGroup) {
                if (name(current) == "id_indicator" ||
                    current.javaClass.name == "com.taobao.idlefish.maincontainer.MainNavigateTabIndicator") return current
                current = current.parent as? View
            }
            return source
        }

        fun find(root: View, id: String): View? {
            if (name(root) == id) return root
            if (root is ViewGroup) for (i in 0 until root.childCount) {
                find(root.getChildAt(i), id)?.let { return it }
            }
            return null
        }
    }
}

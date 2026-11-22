package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Canvas
import android.graphics.RenderNode
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Shared display list of native player layers. Video pixels stay in the existing TextureView. */
internal class XhsVideoBackdrop(private val changed: () -> Unit) {
    private val scene = RenderNode("XHS-shared-video-backdrop")
    private var sources = emptyList<View>()
    private var textures = emptyList<TextureView>()
    private var geometry = Long.MIN_VALUE
    private var recordedGeometry = Long.MIN_VALUE
    private var recordedNodes = Long.MIN_VALUE
    private val nodes = ArrayList<RenderNode?>()
    private val position = IntArray(2)
    private val origin = IntArray(2)
    private var scanAt = 0L
    private val timestamps = WeakHashMap<SurfaceTexture, Long>()
    private val surfaces = LinkedHashMap<SurfaceTexture, TextureView>()
    private val nextSurfaces = LinkedHashMap<SurfaceTexture, TextureView>()
    private val self = WeakReference(this)
    private var reportedFrame = false
    var observesFrames = false
        private set
    val nativeContentDirty: Boolean get() = sources.any { it.isDirty }

    fun bind(players: List<View>) {
        val now = SystemClock.uptimeMillis()
        if (sources != players || now - scanAt >= 500L) {
            val sourceChanged = sources != players
            if (sourceChanged) sources = players.toList()
            scanAt = now
            val found = ArrayList<TextureView>()
            fun walk(view: View) {
                if (view is TextureView) { found.add(view); return }
                if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            sources.forEach(::walk)
            textures = found
        }
        nextSurfaces.clear()
        observesFrames = textures.isNotEmpty()
        for (texture in textures) {
            val surface = texture.surfaceTexture
            if (surface == null || !texture.isAvailable) { observesFrames = false; continue }
            nextSurfaces[surface] = texture
            if (targets[surface] !== self) targets[surface] = self
            val listener = platformListener(texture)
            if (listener == null || !observe(listener.javaClass)) observesFrames = false
        }
        surfaces.keys.forEach { surface ->
            if (surface !in nextSurfaces) {
                if (targets[surface] === self) targets.remove(surface)
                timestamps.remove(surface)
            }
        }
        surfaces.clear()
        surfaces.putAll(nextSurfaces)
    }

    private fun frame(surface: SurfaceTexture) {
        val texture = surfaces[surface] ?: return
        if (!texture.isAttachedToWindow || !texture.isShown || texture.surfaceTexture !== surface) return
        val timestamp = runCatching { surface.timestamp }.getOrNull() ?: return
        if (timestamps[surface] == timestamp) return
        timestamps[surface] = timestamp
        if (!reportedFrame) {
            reportedFrame = true
            android.util.Log.d("LiquidTabXhsVideo", "Native video frame reached the glass backdrop")
        }
        changed()
    }

    fun updateGeometry(root: View): Long {
        root.getLocationInWindow(origin)
        var signature = root.width.toLong() * 31 + root.height
        for (source in sources) {
            source.getLocationInWindow(position)
            signature = signature * 31 + System.identityHashCode(source)
            signature = signature * 31 + position[0] - origin[0]
            signature = signature * 31 + position[1] - origin[1]
            signature = signature * 31 + source.width
            signature = signature * 31 + source.height
        }
        geometry = signature
        return signature
    }

    fun draw(canvas: Canvas, root: View) {
        if (root.width <= 0 || root.height <= 0) return
        var signature = 0L
        nodes.clear()
        for (source in sources) {
            val node = XhsNoteCapsule.renderNode(source)?.takeIf { it.hasDisplayList() }
            nodes.add(node)
            signature = signature * 31 + System.identityHashCode(node)
        }
        if (recordedGeometry != geometry || recordedNodes != signature || !scene.hasDisplayList()) {
            root.getLocationInWindow(origin)
            scene.setPosition(0, 0, root.width, root.height)
            val recording = scene.beginRecording(root.width, root.height)
            try {
                for ((index, source) in sources.withIndex()) {
                    if (!source.isAttachedToWindow || !source.isShown) continue
                    val node = nodes[index] ?: continue
                    source.getLocationInWindow(position)
                    val saved = recording.save()
                    recording.translate((position[0] - origin[0]).toFloat(), (position[1] - origin[1]).toFloat())
                    recording.clipRect(0, 0, source.width, source.height)
                    recording.translate(-source.x, -source.y)
                    recording.drawRenderNode(node)
                    recording.restoreToCount(saved)
                }
            } finally { scene.endRecording() }
            recordedGeometry = geometry
            recordedNodes = signature
        }
        canvas.drawRenderNode(scene)
    }

    fun release() {
        if (sources.isEmpty() && !scene.hasDisplayList()) return
        targets.entries.removeAll { it.value.get() == null || it.value.get() === this }
        textures = emptyList(); sources = emptyList(); scanAt = 0L; timestamps.clear(); observesFrames = false
        surfaces.clear(); nextSurfaces.clear()
        nodes.clear()
        reportedFrame = false
        scene.discardDisplayList(); recordedGeometry = Long.MIN_VALUE; recordedNodes = Long.MIN_VALUE
    }

    companion object {
        private val targets = WeakHashMap<SurfaceTexture, WeakReference<XhsVideoBackdrop>>()
        private val observed = WeakHashMap<Class<*>, Boolean>()
        private val platformListenerField by lazy {
            runCatching {
                TextureView::class.java.getDeclaredField("mListener").apply {
                    check(TextureView.SurfaceTextureListener::class.java.isAssignableFrom(type))
                    isAccessible = true
                }
            }.onFailure { android.util.Log.w("LiquidTabInject", "XHS platform texture listener unavailable", it) }.getOrNull()
        }
        private fun platformListener(texture: TextureView): TextureView.SurfaceTextureListener? =
            runCatching { platformListenerField?.get(texture) as? TextureView.SurfaceTextureListener }.getOrNull()
        private fun observe(type: Class<*>): Boolean {
            observed[type]?.let { return it }
            val success = runCatching {
                // Preserve the player's listener; observe its platform callback
                // signature rather than its obfuscated implementation name.
                XposedBridge.hookMethod(type.getMethod("onSurfaceTextureUpdated", SurfaceTexture::class.java), object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val surface = param.args.firstOrNull() as? SurfaceTexture ?: return
                        targets[surface]?.get()?.frame(surface)
                    }
                })
            }.onFailure { android.util.Log.w("LiquidTabInject", "XHS native frame callback unavailable; native redraw retained", it) }.isSuccess
            observed[type] = success
            return success
        }
    }
}

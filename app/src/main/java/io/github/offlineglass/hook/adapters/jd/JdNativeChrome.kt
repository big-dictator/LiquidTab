package io.github.offlineglass.hook.adapters.jd

import android.os.SystemClock
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/** JD native bar shell cleanup; never traverses an unrelated app's hierarchy. */
internal class JdNativeChrome(private val host: GlassHostLayout) {
    private var outerShader: RuntimeShader? = null
    private var outerEffectSignature = Long.MIN_VALUE

    fun invalidateOuterEffect() {
        outerEffectSignature = Long.MIN_VALUE
    }

    fun syncOuterEffect(
        view: View,
        liquidGlassEnabled: Boolean,
        targetWidth: Int,
        targetHeight: Int,
        blurRadius: Float,
        cornerRadiusPercent: Float,
        density: Float,
        shaderFactory: () -> RuntimeShader?,
    ) {
        if (!liquidGlassEnabled) {
            view.setRenderEffect(null)
            invalidateOuterEffect()
            view.invalidate()
            return
        }
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        val signature = (width.toLong() shl 32) xor height.toLong() xor
            (blurRadius * 100f).roundToInt().toLong() xor
            ((cornerRadiusPercent * 100f).roundToInt().toLong() shl 12)
        if (signature == outerEffectSignature) return
        val shader = outerShader ?: shaderFactory()?.also { outerShader = it } ?: return
        outerEffectSignature = signature
        val corner = minOf(width, height) * (cornerRadiusPercent / 100f)
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("offset", 0f, 0f)
        shader.setFloatUniform("cornerRadii", corner, corner, corner, corner)
        shader.setFloatUniform("refractionHeight", 24f * density)
        shader.setFloatUniform("refractionAmount", -24f * density)
        shader.setFloatUniform("depthEffect", 0f)
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        view.invalidate()
    }
    private var decorationParent: WeakReference<ViewGroup>? = null
    private var decorationChildCount = -1
    private val decorations = ArrayList<WeakReference<View>>()
    private var lastSuppressedSource: WeakReference<ViewGroup>? = null
    private var lastSuppressAt = Long.MIN_VALUE
    private val sourceLocation = IntArray(2)
    private val chromeLocation = IntArray(2)
    private var resumeGeneration = 0

    fun onWindowResumed(onSettled: () -> Unit = {}) {
        val generation = ++resumeGeneration
        lastSuppressedSource = null
        lastSuppressAt = Long.MIN_VALUE
        // JD restores native chrome during resume without replacing its row.
        // Clear before drawing, then once after its deferred theme/layout work.
        host.navigationSource?.let { source ->
            suppress(source)
            discoverDecorations(source)
            clearRestoredMask(source)
        }
        host.postDelayed({
            if (generation != resumeGeneration || !host.isAttachedToWindow || !host.hasWindowFocus())
                return@postDelayed
            host.navigationSource?.let {
                discoverDecorations(it)
                suppress(it)
                clearRestoredMask(it)
            }
            onSettled()
        }, 240L)
    }

    private fun discoverDecorations(source: ViewGroup) {
        decorations.clear()
        val parent = source.parent as? ViewGroup ?: return
        decorationParent = WeakReference(parent)
        decorationChildCount = parent.childCount
        // Native navigation decorations can draw an ImageView drawable or
        // custom onDraw content. Background clearing cannot suppress those.
        // Search only the local navigation shell; never enter the feed.
        var shell = parent
        repeat(2) { shell = shell.parent as? ViewGroup ?: shell }
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += shell to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 600) {
            val (view, depth) = stack.removeLast()
            if (view === host || view === source || view is GlassHostLayout ||
                view is io.github.offlineglass.hook.adapters.AdapterOwnedOverlay ||
                view is android.view.SurfaceView || view is android.view.TextureView ||
                view is android.webkit.WebView) continue
            val name = view.javaClass.name
            if (name == "com.jingdong.app.mall.navigationbar.BottomCropImage" ||
                name == "com.jingdong.app.mall.navigationbar.refactor.view.AiTabSelectShadow") {
                decorations += WeakReference(view)
                view.alpha = 0f
                continue
            }
            val simple = view.javaClass.simpleName
            if (simple.contains("RecyclerView") || simple.contains("Scroll") || simple.contains("Refresh")) continue
            if (view is ViewGroup && depth < 6) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
    }

    private fun suppressDecorations(source: ViewGroup) {
        val parent = source.parent as? ViewGroup
        if (decorationParent?.get() !== parent || decorationChildCount != parent?.childCount) {
            discoverDecorations(source)
        }
        for (reference in decorations) {
            val view = reference.get() ?: continue
            if (view.isAttachedToWindow && view.alpha != 0f) view.alpha = 0f
        }
    }

    private fun clearRestoredMask(source: ViewGroup) {
        val root = source.rootView
        if (root.height <= 0) return
        source.getLocationInWindow(sourceLocation)
        val barHeight = maxOf(source.height, host.height).coerceAtLeast(1)
        val bandTop = sourceLocation[1] - barHeight
        var shell = source.parent as? ViewGroup
        var hops = 0
        while (shell != null && shell !== root && shell !== host && hops++ < 6) {
            shell.getLocationInWindow(chromeLocation)
            if (shell.width >= root.width * 0.9f &&
                shell.height in 1..(barHeight * 2.2f).toInt() &&
                chromeLocation[1] >= bandTop &&
                chromeLocation[1] + shell.height >= sourceLocation[1]) {
                if (shell.background != null) shell.background = null
                if (shell.backgroundTintList != null) shell.backgroundTintList = null
                if (shell.foreground != null) shell.foreground = null
                if (shell.elevation != 0f) shell.elevation = 0f
                if (shell.translationZ != 0f) shell.translationZ = 0f
            }
            shell = shell.parent as? ViewGroup
        }
    }
    fun suppress(source: ViewGroup?) {

        if (source == null) return
        suppressDecorations(source)

        if (source.alpha != 0f) source.alpha = 0f

        if (source.background != null) source.background = null

        if (source.backgroundTintList != null) source.backgroundTintList = null

        // The host invokes suppression twice in its pre-draw callback. Keep
        // source cleanup immediate, but avoid repeating the same geometry walk.
        val now = SystemClock.uptimeMillis()
        if (lastSuppressedSource?.get() === source && now - lastSuppressAt < 8L) return
        if (lastSuppressedSource?.get() !== source) lastSuppressedSource = WeakReference(source)
        lastSuppressAt = now



        // JD's video cards are composed through a separate Surface, whereas

        // ordinary information-flow cards are regular Views.  Never hide an

        // arbitrary sibling/ancestor from this hot path: on the 新品 channel a

        // broad match can repeatedly fade the regular feed while the video

        // Surface remains stable. Only same-band, bar-sized chrome is valid.

        source.getLocationInWindow(sourceLocation)

        val sourceTop = sourceLocation[1]

        val barHeight = maxOf(source.height, host.height).coerceAtLeast(1)
        val maxChromeHeight = (barHeight * 2.2f).toInt().coerceAtLeast(1)

        val chromeTop = sourceTop - barHeight



        // Hide only sibling backgrounds that actually occupy the native bar

        // band; feed/pager siblings are deliberately left untouched.

        val parent = source.parent as? ViewGroup

        if (parent != null) {

            for (i in 0 until parent.childCount) {

                val sibling = parent.getChildAt(i)

                val location = chromeLocation
                sibling.getLocationInWindow(location)

                val inNativeBarBand = sibling.height in 1..maxChromeHeight &&

                    location[1] >= chromeTop &&

                    location[1] + sibling.height >= sourceTop

                if (sibling !== source && sibling.visibility == View.VISIBLE && inNativeBarBand) {

                    if (sibling.alpha != 0f) sibling.alpha = 0f

                    if (sibling.background != null) sibling.background = null

                    if (sibling.backgroundTintList != null) sibling.backgroundTintList = null

                    if (sibling.foreground != null) sibling.foreground = null

                }

            }

        }



        // An ancestor is safe to suppress only when it is itself a short

        // bottom-bar shell. Larger parents commonly host the information flow.

        var ancestor = parent

        var hops = 0

        while (ancestor != null && ancestor !is GlassHostLayout && hops < 6) {

            val loc = chromeLocation

            ancestor.getLocationOnScreen(loc)

            val isShortBarShell = ancestor.height in 1..maxChromeHeight &&

                loc[1] >= chromeTop && loc[1] + ancestor.height >= sourceTop

            if (isShortBarShell) {

                if (ancestor.alpha != 0f) ancestor.alpha = 0f

                if (ancestor.background != null) ancestor.background = null

                if (ancestor.backgroundTintList != null) ancestor.backgroundTintList = null

                if (ancestor.foreground != null) ancestor.foreground = null

                if (ancestor.elevation > 0f) ancestor.elevation = 0f

                if (ancestor.translationZ > 0f) ancestor.translationZ = 0f

            }

            ancestor = ancestor.parent as? ViewGroup

            hops++

        }



    }

    fun reset() {
        resumeGeneration++
        decorationParent = null
        decorationChildCount = -1
        decorations.clear()
        lastSuppressedSource = null
        lastSuppressAt = Long.MIN_VALUE
    }
}


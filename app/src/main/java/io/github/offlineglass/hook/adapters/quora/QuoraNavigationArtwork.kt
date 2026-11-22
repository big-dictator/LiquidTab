package io.github.offlineglass.hook.adapters.quora

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame

/** Two independent themed state-list drawables; native View selection is never mutated. */
internal class QuoraNavigationArtwork {
    private data class Icon(val view: ImageView, val original: Drawable,
        val idle: Drawable, val active: Drawable, val tint: android.content.res.ColorStateList?)
    private var source: ViewGroup? = null
    private var icons = emptyList<Icon>()
    private var badges = emptyList<View>()
    private var nextProbe = 0L
    var gestureActive=false
    private val handoffPaint=android.graphics.Paint().apply { blendMode=android.graphics.BlendMode.PLUS }
    private class DrawRects { val icon=Rect();val badge=Rect() }
    private val drawRects=ThreadLocal.withInitial { DrawRects() }
    val ready: Boolean get() = source != null && icons.size == source?.childCount

    fun prepare(row: ViewGroup?) {
        if (source === row) return
        source = row; icons = emptyList(); badges = emptyList(); nextProbe = 0
        refresh()
    }

    private fun refresh() {
        val row = source ?: return
        val now = SystemClock.uptimeMillis()
        if (now < nextProbe) return
        nextProbe = now + 200
        val found = ArrayList<ImageView>()
        val decorations = ArrayList<View>()
        val queue = java.util.ArrayDeque<Pair<View, Int>>()
        queue.add(row to 0)
        var budget = 128
        while (queue.isNotEmpty() && budget-- > 0) {
            val (view, depth) = queue.removeFirst()
            when (QuoraAdapter.name(view)) {
                "icon" -> if (view is ImageView) found.add(view)
                "badge", "badge_dot" -> decorations.add(view)
            }
            if (view is ViewGroup && depth < 6) {
                for (i in 0 until view.childCount) queue.add(view.getChildAt(i) to depth + 1)
            }
        }
        if (queue.isNotEmpty() || found.size != row.childCount) { icons = emptyList(); return }
        val updated = ArrayList<Icon>()
        for (view in found) {
            val drawable = view.drawable ?: run { icons = emptyList(); return }
            val cached = icons.firstOrNull { it.view === view && it.original === drawable && it.tint == view.imageTintList }
            if (cached != null) { updated.add(cached); continue }
            val constant = drawable.constantState ?: run { icons = emptyList(); return }
            fun clone(selected: Boolean): Drawable = constant.newDrawable(view.resources).mutate().apply {
                state = intArrayOf(android.R.attr.state_enabled,
                    if (selected) android.R.attr.state_selected else -android.R.attr.state_selected)
                view.imageTintList?.let { setTintList(it) }
                jumpToCurrentState()
            }
            updated.add(Icon(view, drawable, clone(false), clone(true), view.imageTintList))
        }
        icons = updated; badges = decorations
    }

    fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        refresh()
        val mix=frame.navigationOpticalMix.coerceIn(0f,1f)
        if(!gestureActive&&!frame.hideSelectedForOptics&&mix>0f&&mix<1f) {
            // During release, hand the foreground back continuously rather
            // than showing all-idle until the last frame and then snapping.
            val combined=canvas.saveLayer(0f,0f,frame.width.toFloat(),frame.height.toFloat(),null)
            val normal=canvas.saveLayerAlpha(0f,0f,frame.width.toFloat(),frame.height.toFloat(),((1f-mix)*255).toInt())
            drawPass(canvas,frame.copy(navigationOpticalMix=0f));canvas.restoreToCount(normal)
            // Add weighted sources inside an isolated row so overlapping
            // strokes retain opacity instead of dimming at the midpoint.
            handoffPaint.alpha=(mix*255).toInt()
            val idle=canvas.saveLayer(0f,0f,frame.width.toFloat(),frame.height.toFloat(),handoffPaint)
            drawPass(canvas,frame);canvas.restoreToCount(idle)
            canvas.restoreToCount(combined)
        } else drawPass(canvas,frame)
    }

    private fun drawPass(canvas: Canvas, frame: AdapterNavigationFrame) {
        val row = source ?: return
        if (!ready || row.width <= 0 || row.height <= 0) return
        val nativeSlot = row.width.toFloat() / frame.slotCount
        val scale = minOf(frame.slotWidth / nativeSlot, frame.height.toFloat() / row.height)
        val h = row.height.toFloat()
        val scratch=drawRects.get()
        frame.enabledIndices.forEachIndexed { visual, index ->
            val icon = icons.getOrNull(index) ?: return@forEachIndexed
            val selected = QuoraNavigationContract.selectedArtwork(frame.navigationOpticalMix,
                frame.hideSelectedForOptics, index, frame.selectedIndex)
            val drawable = if (selected) icon.active else icon.idle
            val saved = canvas.save()
            val cx = 4f * frame.density + (visual + .5f) * frame.slotWidth
            canvas.translate(cx, frame.height / 2f)
            canvas.scale(scale * frame.extraScale, scale * frame.extraScale)
            canvas.translate(-(index + .5f) * nativeSlot, -h / 2f)
            val rect = scratch.icon.apply { set(0, 0, icon.view.width, icon.view.height) }
            row.offsetDescendantRectToMyCoords(icon.view, rect)
            val imageSave = canvas.save()
            canvas.translate(rect.exactCenterX(), rect.exactCenterY())
            canvas.scale(icon.view.scaleX, icon.view.scaleY)
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val dh = drawable.intrinsicHeight.coerceAtLeast(1)
            val fit = minOf(icon.view.width.toFloat() / w, icon.view.height.toFloat() / dh)
            canvas.scale(fit, fit)
            canvas.translate(-w / 2f, -dh / 2f)
            drawable.setBounds(0, 0, w, dh)
            drawable.alpha = (icon.view.alpha * 255f).toInt().coerceIn(0, 255)
            drawable.draw(canvas)
            canvas.restoreToCount(imageSave)
            // Badges retain their own native colors and visibility in both roles.
            for (badge in badges) {
                if (!badge.isShown || badge.width <= 0) continue
                val b = scratch.badge.apply { set(0, 0, badge.width, badge.height) }
                row.offsetDescendantRectToMyCoords(badge, b)
                if ((b.exactCenterX() / nativeSlot).toInt() != index) continue
                val bs = canvas.save(); canvas.translate(b.left.toFloat(), b.top.toFloat())
                badge.draw(canvas); canvas.restoreToCount(bs)
            }
            canvas.restoreToCount(saved)
        }
    }

    fun drawForeground(canvas: Canvas, frame: AdapterNavigationFrame, path: Path, draw: () -> Unit): Boolean {
        val mix = frame.navigationOpticalMix.coerceIn(0f, 1f)
        if (mix <= 0f || path.isEmpty) { draw(); return true }
        val outside = canvas.save(); canvas.clipOutPath(path); draw(); canvas.restoreToCount(outside)
        if (mix < 1f) {
            val inside = canvas.save(); canvas.clipPath(path)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), ((1f-mix)*255).toInt())
            draw(); canvas.restoreToCount(inside)
        }
        return true
    }
}

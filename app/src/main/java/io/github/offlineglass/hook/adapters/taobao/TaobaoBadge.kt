package io.github.offlineglass.hook.adapters.taobao

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference
import kotlin.math.max

/** Message count is refracted with its native slot but keeps its own orange fill. */
internal class TaobaoBadge {
    private data class Snapshot(
        val value: String, val centerX: Float, val centerY: Float,
        val halfWidth: Float, val halfHeight: Float, val textSize: Float,
        val pivotY: Float,
    )
    @Volatile private var snapshot: Snapshot? = null
    private var cachedRoot: WeakReference<View>? = null
    private var cachedBadge: WeakReference<TextView>? = null
    private var lastValidLayoutAt = 0L
    private val countPattern = Regex("\\d{1,3}\\+?")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5000.toInt()
        style = Paint.Style.FILL
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** UI pre-draw publishes one complete state; the optical worker never reads Views. */
    fun update(host: GlassHostLayout, source: ViewGroup?): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val now = SystemClock.uptimeMillis()
        if (source == null || source.width <= 0 || source.height <= 0) return unavailable(now)
        val transform = host.adapterTabTransform(2) ?: return publish(null)
        val badge = findBadge(source) ?: return unavailable(now)
        val value = badge.text?.toString()?.trim().orEmpty()
        // Child visibility and count are authoritative even though its native parent is transparent.
        if (badge.visibility != View.VISIBLE || value == "0" || !countPattern.matches(value)) {
            lastValidLayoutAt = now
            return publish(null)
        }
        // A positive, attached count awaiting layout is not a cleared notification.
        // Retain its last geometry until this same UI reader sees the completed layout.
        if (badge.width <= 0 || badge.height <= 0 || badge.isLayoutRequested) return false
        val scale = transform[0]
        val dx = transform[1]
        val dy = transform[2]
        val sourceScale = transform[3]
        val density = transform[4]
        // Source-local layout coordinates do not inherit transient window/scroll translations.
        var x = 0f
        var y = 0f
        var current: View = badge
        while (current !== source) {
            x += current.left
            y += current.top
            current = current.parent as? View ?: return unavailable(now)
        }
        val inset = 1.5f * density / sourceScale
        val nativeRect = RectF(
            x - inset, y - inset, x + badge.width + inset, y + badge.height + inset,
        )
        val centerX = dx + nativeRect.centerX() * scale
        val centerY = dy + nativeRect.centerY() * scale
        val nativeWidth = (nativeRect.width() - 3f * density / scale).coerceAtLeast(1f)
        val nativeHeight = (nativeRect.height() - 3f * density / scale).coerceAtLeast(1f)
        val halfWidth = max(nativeWidth * scale / 2f, 8.5f * density)
        val halfHeight = max(nativeHeight * scale / 2f, 8.5f * density)
        lastValidLayoutAt = now
        return publish(Snapshot(value, centerX, centerY, halfWidth, halfHeight,
            badge.textSize * scale, host.height / 2f))
    }

    private fun unavailable(now: Long): Boolean =
        if (now - lastValidLayoutAt > 500L) publish(null) else false

    private fun publish(next: Snapshot?): Boolean {
        if (snapshot == next) return false
        snapshot = next
        return true
    }

    @Synchronized fun draw(canvas: Canvas, contentScale: Float) {
        val state = snapshot ?: return
        val rect = RectF(state.centerX - state.halfWidth, state.centerY - state.halfHeight,
            state.centerX + state.halfWidth, state.centerY + state.halfHeight)
        val saved = canvas.save()
        canvas.scale(contentScale.coerceAtLeast(0.01f), contentScale.coerceAtLeast(0.01f),
            state.centerX, state.pivotY)
        canvas.drawRoundRect(rect, state.halfHeight, state.halfHeight, fill)
        text.textSize = state.textSize
        val baseline = state.centerY - (text.ascent() + text.descent()) / 2f
        canvas.drawText(state.value, state.centerX, baseline, text)
        canvas.restoreToCount(saved)
    }

    private fun findBadge(source: ViewGroup): TextView? {
        val messageRoot = source.getChildAt(2) ?: return null
        cachedBadge?.get()?.takeIf { cachedRoot?.get() === messageRoot && it.parent === messageRoot }
            ?.let { return it }
        // Confirmed in 10.66.30: this field is the count, distinct from mTitleView.
        val native = runCatching {
            messageRoot.javaClass.getDeclaredField("mMessageView").apply { isAccessible = true }
                .get(messageRoot) as? TextView
        }.getOrNull()
        if (native != null && native.parent === messageRoot) return cache(messageRoot, native)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += messageRoot to 0
        var best: TextView? = null
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is TextView) {
                val value = view.text?.toString()?.trim().orEmpty()
                if (countPattern.matches(value) && view.width > 0 && view.height > 0) {
                    if (best == null || view.width * view.height < best.width * best.height) best = view
                }
            }
            if (view is ViewGroup && depth < 6) {
                for (index in view.childCount - 1 downTo 0) stack += view.getChildAt(index) to depth + 1
            }
        }
        return best?.let { cache(messageRoot, it) }
    }

    private fun cache(root: View, view: TextView): TextView {
        cachedRoot = WeakReference(root)
        cachedBadge = WeakReference(view)
        return view
    }

    fun dispose() {
        snapshot = null
        cachedRoot = null
        cachedBadge = null
        lastValidLayoutAt = 0L
    }
}

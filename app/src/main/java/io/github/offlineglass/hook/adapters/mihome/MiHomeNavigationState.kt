package io.github.offlineglass.hook.adapters.mihome

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState

/** Mi Home's APK-owned icon views and labels, drawn without scaling its native row. */
internal class MiHomeNavigationState : AppNavigationState {
    private var activeHost: io.github.offlineglass.hook.GlassHostLayout? = null
    private var items: List<View> = emptyList()
    private val edgeMask = android.graphics.Path()
    private val edgeBounds = RectF()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        blendMode = android.graphics.BlendMode.DST_OUT
    }

    override fun drawSelectionBackdrop(
        canvas: Canvas, path: android.graphics.Path, selectedIndex: Int, drawContent: () -> Unit,
    ): Boolean {
        if (selectedIndex != 0) return false
        path.computeBounds(edgeBounds, true)
        edgeBounds.inset(-1f, -1f)
        edgeMask.set(path)
        edgeMask.fillType = android.graphics.Path.FillType.INVERSE_WINDING
        // Erase outside with fractional coverage rather than hard-clipping the RenderNode.
        val layer = canvas.saveLayer(edgeBounds, null)
        try {
            drawContent()
            canvas.drawPath(edgeMask, edgePaint)
        } finally {
            canvas.restoreToCount(layer)
        }
        return true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean,
         density: Float, barHeightPx: Int,
        surfaceColor: () -> Int,
    ) {
        activeHost = host as? io.github.offlineglass.hook.GlassHostLayout
        val navigation = findBottomNavigation(source, host.rootView) ?: return
        // The native indicator may wrap its tab views; prefer its own ordered list.
        val reflected = runCatching {
            de.robv.android.xposed.XposedHelpers.callMethod(navigation, "getTabViewList") as? List<*>
        }.getOrNull()?.filterIsInstance<View>().orEmpty()
        if (reflected.size in 4..5) {
            items = reflected
            return
        }
        val fresh = ArrayList<View>(5)
        for (index in 0 until navigation.childCount) {
            val child = navigation.getChildAt(index)
            if (readField(child, "mImageView") is View || readField(child, "mAnimView") is View) fresh += child
        }
        if (fresh.size in 4..5) items = fresh
    }

    override fun suppressNativeChrome(source: ViewGroup?) {
        activeHost?.suppressNativeMiHomeBottomBar(source)
    }

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        (host as? io.github.offlineglass.hook.GlassHostLayout)?.adjustMiHomeBottomActions()
    }

    // Runs on the root pre-draw even while the bar is GONE; no stale host or visibility timer.
    override fun hostPageAllowsNavigation(
        host: io.github.offlineglass.hook.GlassHostLayout, root: View?,
    ): Boolean = !host.isMiHomeDeviceEditMode()

    override fun dispose() { activeHost = null; items = emptyList() }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val item = items.getOrNull(index)
        val clicked = item?.performClick() == true
        val switched = clicked || findBottomNavigation(source, host.rootView)?.let { navigation ->
            runCatching {
                de.robv.android.xposed.XposedHelpers.callMethod(navigation, "setCurrentItem", index)
                true
            }.getOrDefault(false)
        } == true
        if (switched) host.postInvalidateOnAnimation()
        return switched
    }

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? {
        val navigation = findBottomNavigation(source, source?.rootView ?: return null) ?: return null
        return runCatching {
            (de.robv.android.xposed.XposedHelpers.callMethod(navigation, "getSelectedTabIndex") as? Number)?.toInt()
        }.getOrNull()?.takeIf { it in 0 until slotCount }
    }

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        if (frame.slotCount !in 4..5) return
        val iconSize = 26f * 1.265f * frame.density
        val iconCenterY = 20f * frame.density
        val labelBaseline = 45.5f * frame.density
        val horizontalPadding = 4f * frame.density
        val labels = if (frame.slotCount == 4) LABELS_FOUR else LABELS_FIVE
        val normalColor = if (frame.darkGlass) 0xFFE5E5E5.toInt() else 0xFF8A8A8A.toInt()
        labelPaint.textSize = 11f * frame.scaledDensity
        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            val item = items.getOrNull(index)
            val centerX = horizontalPadding + (visualIndex + .5f) * frame.slotWidth
            canvas.save(); canvas.scale(frame.iconScale * frame.extraScale, frame.iconScale * frame.extraScale,
                centerX, frame.height / 2f)
            val image = item?.let { readField(it, "mImageView") } as? View
            val animation = item?.let { readField(it, "mAnimView") } as? View
            val icon = animation?.takeIf { it.visibility == View.VISIBLE && it.width > 0 && it.height > 0 }
                ?: image?.takeIf { it.width > 0 && it.height > 0 }
            if (icon != null) {
                val destination = RectF(centerX - iconSize / 2f, iconCenterY - iconSize / 2f,
                    centerX + iconSize / 2f, iconCenterY + iconSize / 2f)
                val save = canvas.save(); canvas.translate(destination.left, destination.top)
                canvas.scale(destination.width() / icon.width.coerceAtLeast(1),
                    destination.height() / icon.height.coerceAtLeast(1))
                val alpha = icon.alpha
                try {
                    icon.alpha = 1f
                    icon.draw(canvas)
                } finally {
                    icon.alpha = alpha
                    canvas.restoreToCount(save)
                }
            }
            val text = item?.let { readField(it, "mText") } as? TextView
            val label = text?.text?.toString()?.trim().orEmpty().ifEmpty { labels.getOrElse(index) { "" } }
            labelPaint.color = text?.currentTextColor ?: if (index == frame.selectedIndex) 0xFF2587E8.toInt() else normalColor
            canvas.drawText(label, centerX, labelBaseline, labelPaint)
            canvas.restore()
        }
    }

    private fun findBottomNavigation(source: ViewGroup?, root: View): ViewGroup? {
        var ancestor: View? = source
        repeat(6) {
            if (ancestor?.javaClass?.name == BOTTOM_NAV_CLASS) return ancestor as? ViewGroup
            ancestor = ancestor?.parent as? View
        }
        val stack = ArrayDeque<Pair<View, Int>>(); stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view is ViewGroup && view.javaClass.name == BOTTOM_NAV_CLASS) return view
            if (view is ViewGroup && depth < 12) for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
        }
        return null
    }

    private fun readField(instance: Any, name: String): Any? = runCatching {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let { field ->
                field.isAccessible = true; return field.get(instance)
            }
            type = type.superclass
        }
        null
    }.getOrNull()

    private companion object {
        const val BOTTOM_NAV_CLASS = "com.xiaomi.smarthome.newui.buttomtab.TabPageIndicatorNew"
        val LABELS_FOUR = arrayOf("米家", "智能", "产品", "我的")
        val LABELS_FIVE = arrayOf("米家", "智能", "产品", "商城", "我的")
    }
}

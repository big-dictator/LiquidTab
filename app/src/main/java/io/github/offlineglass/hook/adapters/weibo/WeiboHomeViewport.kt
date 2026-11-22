package io.github.offlineglass.hook.adapters.weibo

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup

/** Home-owned geometry only: keep native toolbar semantics measured, extend its feed behind it. */
internal class WeiboHomeViewport(private val activity: Activity, private val root: ViewGroup) {
    var topReservation = 0
    var contentDark = false
    private var nativeListBackground: android.graphics.drawable.Drawable? = null
    private val canvasPaint = android.graphics.Paint()
    private var offsetReader: java.lang.reflect.Method? = null
    private var contentStart = 0
    private var contentColor = Color.WHITE
    private val listSurface = object : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) {
            canvasPaint.color = contentColor
            canvas.drawRect(bounds.left.toFloat(), (bounds.top + contentStart).toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), canvasPaint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Drawable opacity") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
    private var initialPositionApplied = false
    private var body: View? = null
    private var refresh: WeiboHomeRefresh? = null
    private var originalHeight = 0
    private var originalY = 0f
    private var bodyPaddingTop = 0
    private var pager: ViewGroup? = null
    private var pagerPaddingTop = 0
    private var originalBelow = 0
    private var ownsBelow = false
    private var list: ViewGroup? = null
    private var listPaddingTop = 0
    private var listClip = true
    private var listOriginalY = 0f
    private var listOriginalHeight = 0
    private var bottomGap = 0
    private var windowOwned = false
    private var fullscreenBit = 0
    private var statusColor = 0
    private var statusContrast = false
    private data class Clip(val children: Boolean, val padding: Boolean, val outline: Boolean, val bounds: android.graphics.Rect?)
    private val backgrounds = LinkedHashMap<ViewGroup, android.graphics.drawable.Drawable>()
    private val clipping = LinkedHashMap<ViewGroup, Clip>()
    private val position = IntArray(2)
    private val rootPosition = IntArray(2)

    fun bind(feed: WeiboHomeTopBinding.Feed?, header: View) {
        val view = feed?.branch
        if (view === body && feed?.list === list) return
        restoreBody()
        if (view == null || view.height <= 0) return
        val nativeList = feed?.list ?: return
        body = view; originalHeight = view.layoutParams.height; originalY = view.translationY
        bodyPaddingTop = view.paddingTop
        pager = feed?.pager; pagerPaddingTop = feed?.pager?.paddingTop ?: 0
        val relative = view.layoutParams as? android.widget.RelativeLayout.LayoutParams
        originalBelow = relative?.getRule(android.widget.RelativeLayout.BELOW) ?: 0
        ownsBelow = relative != null && WeiboHomeTopPolicy.ownsBelow(originalBelow, header.id)
        if (ownsBelow) {
            relative!!.removeRule(android.widget.RelativeLayout.BELOW)
            view.layoutParams = relative
        }
        listOriginalY = nativeList.translationY; listOriginalHeight = nativeList.layoutParams.height
        list = nativeList; listPaddingTop = nativeList.paddingTop; listClip = nativeList.clipToPadding
        nativeListBackground = nativeList.background
        offsetReader = nativeList.javaClass.getMethod("computeVerticalScrollOffset")
        refresh = WeiboHomeRefresh(view, nativeList)
        view.getLocationInWindow(position); root.getLocationInWindow(rootPosition)
        var diagnostic: View? = nativeList
        repeat(16) {
            val node = diagnostic
            if (node is ViewGroup && node !== root) {
                node.getLocationInWindow(position)
                WeiboHomeTopProbe.record(activity, "viewport ancestor ${WeiboHomeTopBinding.name(node)} type=${node.javaClass.name} top=${position[1]} h=${node.height} clip=${node.clipChildren}/${node.clipToPadding} bounds=${node.clipBounds}")
                diagnostic = node.parent as? View
            }
        }
        view.getLocationInWindow(position)
        bottomGap = (root.height - (position[1] - rootPosition[1] + view.height)).coerceAtLeast(0)
    }
    fun update() {
        refresh?.sync()
        val view = body?.takeIf { it.isAttachedToWindow } ?: return
        if (!windowOwned) {
            fullscreenBit = activity.window.decorView.systemUiVisibility and (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            statusColor = activity.window.statusBarColor
            statusContrast = activity.window.isStatusBarContrastEnforced
            windowOwned = true
        }
        val decor = activity.window.decorView
        val flags = decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (decor.systemUiVisibility != flags) decor.systemUiVisibility = flags
        if (activity.window.statusBarColor != Color.TRANSPARENT) activity.window.statusBarColor = Color.TRANSPARENT
        if (activity.window.isStatusBarContrastEnforced) activity.window.isStatusBarContrastEnforced = false
        if (view.paddingTop != 0) view.setPadding(view.paddingLeft, 0, view.paddingRight, view.paddingBottom)
        pager?.let { if (it.paddingTop != 0) it.setPadding(it.paddingLeft, 0, it.paddingRight, it.paddingBottom) }
        var parent = list
        while (parent != null) {
            val container = parent
            if (container !== root && container !== list) container.background?.let { backgrounds[container] = it; container.background = null }
            clipping.getOrPut(container) { Clip(container.clipChildren, container.clipToPadding, container.clipToOutline, container.clipBounds) }
            if (parent !== list && parent.clipChildren) parent.clipChildren = false
            if (parent.clipToPadding) parent.clipToPadding = false
            if (parent.clipToOutline) parent.clipToOutline = false
            if (parent.clipBounds != null) parent.clipBounds = null
            if (parent === root) break
            parent = parent.parent as? ViewGroup
        }
        view.getLocationInWindow(position); root.getLocationInWindow(rootPosition)
        val nativeTop = position[1] - rootPosition[1] - (view.translationY - originalY)
        val desiredY = originalY - nativeTop
        if (view.translationY != desiredY) view.translationY = desiredY
        val height = (root.height - bottomGap).coerceAtLeast(1)
        if (view.layoutParams.height != height) view.layoutParams = view.layoutParams.apply { this.height = height }
        list?.let { native ->
            val offset = (offsetReader?.invoke(native) as? Int ?: 0).coerceAtLeast(0)
            val start = (topReservation - offset).coerceAtLeast(0)
            val color = backgrounds.values.filterIsInstance<android.graphics.drawable.ColorDrawable>().firstOrNull()?.color
                ?: if (contentDark) 0xFF1B1B1B.toInt() else Color.WHITE
            if (contentStart != start || contentColor != color) {
                contentStart = start; contentColor = color; listSurface.invalidateSelf()
            }
            if (native.background !== listSurface) {
                native.background?.let { nativeListBackground = it }
                native.background = listSurface
            }
            native.getLocationInWindow(position)
            val restingTop = position[1] - rootPosition[1] - (native.translationY - listOriginalY)
            // PullDownView lays out its content at -H and uses offsetTopAndBottom
            // while dragging/rebounding. Its idle content top is zero. Cancel only
            // the static ancestor inset, never this positive native pull distance.
            val nativePull = native.top.coerceAtLeast(0)
            val desiredListY = listOriginalY - (restingTop - nativePull)
            if (native.translationY != desiredListY) native.translationY = desiredListY
            if (native.layoutParams.height != height) native.layoutParams = native.layoutParams.apply { this.height = height }
            if (native.paddingTop != topReservation) native.setPadding(native.paddingLeft, topReservation, native.paddingRight, native.paddingBottom)
            if (!initialPositionApplied && topReservation > 0) {
                initialPositionApplied = true
                refresh?.requestInitialTop()
            }
            if (native.clipToPadding) native.clipToPadding = false
        }
    }
    fun report(): String {
        val b = body ?: return "no body"
        b.getLocationInWindow(position)
        val bodyTop = position[1]
        list?.getLocationInWindow(position)
        return "pageTop=$bodyTop pageHeight=${b.height} listTop=${position[1]} pagerPadding=${pager?.paddingTop} listPadding=${list?.paddingTop}"
    }
    private fun restoreBody() {
        refresh?.close(); refresh = null
        body?.let {
            if (it.paddingTop != bodyPaddingTop) it.setPadding(it.paddingLeft, bodyPaddingTop, it.paddingRight, it.paddingBottom)
            if (it.translationY != originalY) it.translationY = originalY
            val params = it.layoutParams
            params.height = originalHeight
            if (ownsBelow && params is android.widget.RelativeLayout.LayoutParams)
                params.addRule(android.widget.RelativeLayout.BELOW, originalBelow)
            it.layoutParams = params
        }
        body = null
        topReservation = 0
        ownsBelow = false
        list?.let {
            if (it.background === listSurface) it.background = nativeListBackground
            if (it.paddingTop != listPaddingTop) it.setPadding(it.paddingLeft, listPaddingTop, it.paddingRight, it.paddingBottom)
            it.translationY = listOriginalY
            it.layoutParams = it.layoutParams.apply { height = listOriginalHeight }
            it.clipToPadding = listClip
        }
        list = null
        nativeListBackground = null; offsetReader = null
        pager?.let { if (it.paddingTop != pagerPaddingTop) it.setPadding(it.paddingLeft, pagerPaddingTop, it.paddingRight, it.paddingBottom) }
        pager = null
        clipping.forEach { (view, saved) ->
            view.clipChildren = saved.children; view.clipToPadding = saved.padding; view.clipToOutline = saved.outline; view.clipBounds = saved.bounds
        }
        clipping.clear()
        backgrounds.forEach { (view, background) -> view.background = background }
        backgrounds.clear()
    }
    fun suspend() {
        restoreBody()
        if (windowOwned) {
            val decor = activity.window.decorView
            decor.systemUiVisibility = (decor.systemUiVisibility and (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE).inv()) or fullscreenBit
            activity.window.statusBarColor = statusColor
            activity.window.isStatusBarContrastEnforced = statusContrast
            windowOwned = false
        }
    }
}

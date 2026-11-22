package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.RelativeLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.globalGlassBottomGapPx
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Floating chrome for NoteDetailActivity. The note presenter still owns each action. */
internal object XhsNoteTop {
    private val sessions = WeakHashMap<Activity, Session>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "LiquidTab-XhsNoteTop").apply { isDaemon = true }
    }

    fun start(activity: Activity) = sessions.getOrPut(activity) { Session(activity) }.start()
    fun pause(activity: Activity) { sessions[activity]?.pause() }
    fun dispose(activity: Activity) { sessions.remove(activity)?.dispose() }

    private class Session(activity: Activity) {
        private val root = activity.window.decorView as ViewGroup
        private var config: GlassConfig? = null
        private var skin: Skin? = null
        private var observer: ViewTreeObserver? = null
        private var layoutChanged = true
        private var configPending = false
        private var configAt = -1000L
        private var searchAt = -350L
        private var missingLogged = false
        private val onLayout = ViewTreeObserver.OnGlobalLayoutListener { layoutChanged = true }
        private val onDraw = ViewTreeObserver.OnPreDrawListener { update(); true }
        private val retry = Runnable { root.invalidate() }

        fun start() {
            if (observer?.isAlive == true) return
            root.viewTreeObserver.also {
                observer = it
                it.addOnGlobalLayoutListener(onLayout)
                it.addOnPreDrawListener(onDraw)
            }
            layoutChanged = true
            refreshConfig()
        }

        private fun refreshConfig() {
            if (configPending) return
            configPending = true
            configAt = SystemClock.uptimeMillis()
            val context = root.context.applicationContext
            worker.execute {
                val next = runCatching { HookConfigReader.read(context, context.packageName).normalized() }.getOrNull()
                root.post {
                    configPending = false
                    if (observer != null && next != null && next != config) {
                        config = next
                        root.invalidate()
                    }
                }
            }
        }

        private fun update() {
            val now = SystemClock.uptimeMillis()
            if (now - configAt >= 1000L) refreshConfig()
            val currentConfig = config ?: return
            if (!currentConfig.enabled || !currentConfig.xhsTopBar) {
                skin?.restore()
                skin = null
                layoutChanged = true
                return
            }
            val current = skin
            if (current != null && current.binding.isCurrent()) {
                current.update(currentConfig, layoutChanged)
                layoutChanged = false
                return
            }
            current?.suspendActions()
            if (!layoutChanged) return
            val delay = 350L - (now - searchAt)
            if (delay > 0L) {
                root.removeCallbacks(retry)
                root.postDelayed(retry, delay)
                return
            }
            searchAt = now
            layoutChanged = false
            val binding = XhsNoteTopBinding.find(root, current?.binding?.feed)
            if (binding == null) {
                if (!missingLogged) Log.w("LiquidTabXhsNoteTop", "note title/feed contract unavailable")
                missingLogged = true
                return
            }
            missingLogged = false
            current?.restore()
            skin = Skin(binding).also { it.update(currentConfig, true) }
            Log.i("LiquidTabXhsNoteTop", "note title attached to native feed and actions")
        }

        fun pause() {
            root.removeCallbacks(retry)
            skin?.pause()
            observer?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalLayoutListener(onLayout)
                it.removeOnPreDrawListener(onDraw)
            }
            observer = null
        }

        fun dispose() { pause(); skin?.restore(); skin = null }
    }

    private class Layer(context: Context) : FrameLayout(context), AdapterOwnedOverlay {
        var feedback: XhsPressFeedback? = null
        private var observingTouch = false
        init {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                observingTouch = super.dispatchTouchEvent(event)
                if (observingTouch) feedback?.observe(event)
                return observingTouch
            }
            if (observingTouch) {
                feedback?.observe(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    observingTouch = false
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private class Hit(context: Context, val target: () -> View?) : View(context) {
        init {
            isFocusable = true
            setOnClickListener {
                target()?.takeIf { it.isAttachedToWindow && it.isShown && it.isEnabled && it.hasOnClickListeners() }
                    ?.performClick()
            }
        }
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }

    private class Skin(val binding: XhsNoteTopBinding) {
        private val context = binding.title.context
        private val plates = XhsNoteTopPlates(binding)
        private val hits = Layer(context)
        private var followControls = emptyList<XhsNoteTopBinding.Follow>()
        private var follow: XhsNoteTopBinding.Follow? = null
        private val actions = listOf(
            Hit(context) { binding.back },
            Hit(context) { binding.title },
            Hit(context) { follow?.control },
            Hit(context) { binding.share },
        )
        private val press = XhsPressFeedback(hits, actions, groupTrailingActions = false, changed = ::animatePress)
        private val animatedArtwork = LinkedHashMap<View, ArtworkTransform>()
        private var paused = false
        private val backgrounds = LinkedHashMap<View, Drawable?>()
        private val foregrounds = LinkedHashMap<View, Drawable?>()
        private val positions = LinkedHashMap<View, Pair<Float, Float>>()
        private val nameClip = binding.nameGroup.clipBounds
        private val avatarScaleX = binding.avatarFrame.scaleX
        private val avatarScaleY = binding.avatarFrame.scaleY
        private val avatarPivot = binding.avatarFrame.takeIf { it.isPivotSet }?.let { it.pivotX to it.pivotY }
        private var headerPadding = binding.header.paddingTop
        private var appliedHeaderPadding = headerPadding
        private val titleClickable = binding.title.isClickable
        private val clipChildren = binding.container.clipChildren
        private val feedBelow = binding.status.id
        private var feedMargin = (binding.feed.layoutParams as RelativeLayout.LayoutParams).topMargin
        private var followPlate: View? = null
        private val density get() = binding.row.resources.displayMetrics.density
        private val buttonHeight get() = minOf(dp(40), binding.row.height)

        private fun buttonTop(height: Int): Int = ((binding.row.height - height) / 2 - dp(6)).coerceAtLeast(0)

        init {
            hits.feedback = press
            plates.feedback = press
            val full = ViewGroup.LayoutParams.MATCH_PARENT
            binding.container.clipChildren = false
            binding.container.addView(plates, binding.container.indexOfChild(binding.title), FrameLayout.LayoutParams(full, full))
            binding.container.addView(hits, FrameLayout.LayoutParams(full, full))
            actions.forEach { hits.addView(it, FrameLayout.LayoutParams(0, 0)) }
        }

        fun update(config: GlassConfig, layoutChanged: Boolean) {
            val visible = binding.title.isShown
            val visibility = if (visible) View.VISIBLE else View.INVISIBLE
            val visibilityChanged = plates.visibility != visibility || hits.visibility != visibility
            if (plates.visibility != visibility) plates.visibility = visibility
            if (hits.visibility != visibility) hits.visibility = visibility
            if (!visible) { pause(); return }
            paused = false

            if (layoutChanged || visibilityChanged) followControls = binding.followControls()
            val next = followControls.singleOrNull { it.control.parent === binding.row && it.control.isShown }
            val followChanged = next?.control !== follow?.control
            if (followChanged) {
                releaseFollow()
                follow = next
            }
            clear(binding.container, true)
            clear(binding.title, true)
            clear(binding.row, true)
            clear(binding.titleStatus, true)
            clear(binding.status, true)
            if (binding.title.isClickable) binding.title.isClickable = false
            follow?.control?.let { followPlate = it; clear(it, false) }
            extendFeed()
            reserveTop()
            plates.update(config)
            if (layoutChanged || visibilityChanged || followChanged) layout()
        }

        fun pause() {
            if (paused) return
            paused = true
            press.reset()
            plates.pause()
        }

        private fun reserveTop() {
            val height = buttonHeight
            if (height <= 0) return
            val header = binding.header
            if (header.paddingTop != appliedHeaderPadding) headerPadding = header.paddingTop
            // Leave a small gap below the glass edge in the scrolling header.
            val clearance = binding.container.top + binding.title.top + binding.row.top +
                buttonTop(height) + height + dp(4)
            appliedHeaderPadding = headerPadding + clearance
            if (header.paddingTop != appliedHeaderPadding)
                header.setPadding(header.paddingLeft, appliedHeaderPadding, header.paddingRight, header.paddingBottom)
        }

        fun suspendActions() {
            if (hits.visibility == View.VISIBLE) hits.visibility = View.INVISIBLE
            pause()
        }

        private fun extendFeed() {
            val feed = binding.feed
            val params = feed.layoutParams as? RelativeLayout.LayoutParams ?: return
            val below = params.getRule(RelativeLayout.BELOW)
            if (below != 0 && below != feedBelow) return
            if (below == 0 && params.topMargin == 0) return
            if (params.topMargin != 0) feedMargin = params.topMargin
            params.removeRule(RelativeLayout.BELOW)
            params.topMargin = 0
            feed.layoutParams = params
        }

        private fun layout() {
            val row = binding.row
            val width = row.width
            val height = buttonHeight
            if (height <= 0 || width <= 0) return
            val side = globalGlassBottomGapPx(row)
            val gap = dp(6)
            val top = buttonTop(height)
            val y = binding.title.top + row.top + top
            val centerY = top + height / 2f
            val end = width - side
            val shareStart = end - height
            val followView = follow?.control?.takeIf { it.isShown && it.width > 0 }
            val followWidth = followView?.width?.coerceAtLeast(dp(56)) ?: 0
            val followStart = shareStart - gap - followWidth
            val authorStart = side + height + gap
            val authorEnd = if (followView == null) shareStart - gap else followStart - gap
            if (authorEnd - authorStart < height) return
            restoreArtwork()
            val avatarCenter = authorStart + height / 2f
            val avatarScale = 0.88f
            val avatarRadius = binding.avatarDiameter() * avatarScaleX * avatarScale / 2f
            val name = binding.name
            val textLayout = name.layout?.takeIf { it.lineCount > 0 }
            val textLeft = textLayout?.getLineLeft(0) ?: 0f
            val textWidth = textLayout?.getLineWidth(0) ?: name.width.toFloat()
            val nameStart = avatarCenter + avatarRadius + dp(3)
            val authorRight = (nameStart + textWidth + dp(10)).roundToInt()
                .coerceIn(authorStart + height, authorEnd)

            place(0, side, y, height, height)
            place(1, authorStart, y, authorRight - authorStart, height)
            place(2, followStart, y, followWidth, height)
            place(3, shareStart, y, height, height)

            center(binding.back, side + height / 2f, centerY)
            center(binding.share, shareStart + height / 2f, centerY)
            val avatar = binding.avatar
            val frame = binding.avatarFrame
            frame.pivotX = avatar.left + avatar.width / 2f
            frame.pivotY = avatar.top + avatar.height / 2f
            frame.scaleX = avatarScaleX * avatarScale
            frame.scaleY = avatarScaleY * avatarScale
            move(frame, avatarCenter - leftInRow(frame) - frame.pivotX, centerY - topInRow(frame) - frame.pivotY)
            val nameLeft = leftInRow(name) + name.compoundPaddingLeft + textLeft - name.scrollX
            val lineCenter = name.baseline.takeIf { it >= 0 }?.let { baseline ->
                baseline + (name.paint.ascent() + name.paint.descent()) / 2f
            } ?: (name.height / 2f)
            val nameCenter = topInRow(name) + lineCenter - name.scrollY
            val nameShift = nameStart - nameLeft
            move(binding.nameGroup, nameShift, centerY - nameCenter)
            val clipWidth = (authorRight - dp(8) - leftInRow(binding.nameGroup) - nameShift)
                .roundToInt().coerceAtLeast(0)
            val clip = Rect(0, 0, clipWidth, binding.nameGroup.height)
            if (binding.nameGroup.clipBounds != clip) binding.nameGroup.clipBounds = clip
            followView?.let { center(it, followStart + followWidth / 2f, centerY) }
            describe(0, binding.back.contentDescription ?: "返回")
            describe(1, binding.name.text)
            describe(2, follow?.label?.text)
            describe(3, binding.share.contentDescription ?: "转发")
            animatePress()
        }

        private fun animatePress() {
            animateArtwork(binding.back, 0)
            animateArtwork(binding.avatarFrame, 1)
            animateArtwork(binding.nameGroup, 1)
            follow?.control?.let { animateArtwork(it, 2) }
            animateArtwork(binding.share, 3)
            plates.invalidate()
        }

        private fun animateArtwork(view: View, index: Int) {
            val scale = press.scale(index)
            if (scale == 1f) {
                animatedArtwork.remove(view)?.restore(view)
                return
            }
            val params = actions[index].layoutParams as FrameLayout.LayoutParams
            val centerX = params.leftMargin + params.width / 2f
            val centerY = params.topMargin + params.height / 2f - binding.title.top - binding.row.top
            val base = animatedArtwork.getOrPut(view) { ArtworkTransform(view) }
            // Scale the native artwork about the same center as its glass plate.
            val pivotX = leftInRow(view) + view.pivotX + base.x
            val pivotY = topInRow(view) + view.pivotY + base.y
            view.translationX = base.x + (pivotX - centerX) * (scale - 1f)
            view.translationY = base.y + (pivotY - centerY) * (scale - 1f)
            view.scaleX = base.scaleX * scale
            view.scaleY = base.scaleY * scale
        }

        private fun restoreArtwork() {
            animatedArtwork.forEach { (view, base) -> base.restore(view) }
            animatedArtwork.clear()
        }

        private fun place(index: Int, left: Int, top: Int, width: Int, height: Int) {
            plates.place(index, left, top, width, height)
            val view = actions[index]
            val visibility = if (width > 0) View.VISIBLE else View.GONE
            if (view.visibility != visibility) view.visibility = visibility
            val params = view.layoutParams as FrameLayout.LayoutParams
            if (params.leftMargin == left && params.topMargin == top && params.width == width && params.height == height) return
            params.gravity = android.view.Gravity.LEFT or android.view.Gravity.TOP
            params.leftMargin = left
            params.topMargin = top
            params.width = width
            params.height = height
            view.layoutParams = params
        }

        private fun describe(index: Int, label: CharSequence?) {
            if (actions[index].contentDescription != label) actions[index].contentDescription = label
        }

        private fun center(view: View, x: Float, y: Float) {
            move(view, x - leftInRow(view) - view.width / 2f, y - topInRow(view) - view.height / 2f)
        }

        private fun topInRow(view: View): Int {
            var top = view.top
            var parent = view.parent
            while (parent is View && parent !== binding.row) { top += parent.top; parent = parent.parent }
            return top
        }

        private fun leftInRow(view: View): Int {
            var left = view.left
            var parent = view.parent
            while (parent is View && parent !== binding.row) { left += parent.left; parent = parent.parent }
            return left
        }

        private fun move(view: View, x: Float, y: Float) {
            if (!positions.containsKey(view)) positions[view] = view.translationX to view.translationY
            if (view.translationX != x) view.translationX = x
            if (view.translationY != y) view.translationY = y
        }

        private fun clear(view: View, foreground: Boolean) {
            view.background?.let { backgrounds[view] = it; view.background = null }
            if (foreground) view.foreground?.let { foregrounds[view] = it; view.foreground = null }
        }

        private fun releaseFollow() {
            followPlate?.let { view ->
                animatedArtwork.remove(view)?.restore(view)
                backgrounds.remove(view)?.let { if (view.background == null) view.background = it }
                positions.remove(view)?.let { (x, y) -> view.translationX = x; view.translationY = y }
            }
            followPlate = null
        }

        fun restore() {
            pause()
            restoreArtwork()
            plates.release()
            binding.container.removeView(hits)
            binding.container.removeView(plates)
            binding.container.clipChildren = clipChildren
            binding.title.isClickable = titleClickable
            binding.nameGroup.clipBounds = nameClip
            binding.avatarFrame.apply {
                scaleX = avatarScaleX
                scaleY = avatarScaleY
                if (avatarPivot == null) resetPivot() else {
                    pivotX = avatarPivot.first
                    pivotY = avatarPivot.second
                }
            }
            binding.header.let {
                if (it.paddingTop == appliedHeaderPadding && it.paddingTop != headerPadding)
                    it.setPadding(it.paddingLeft, headerPadding, it.paddingRight, it.paddingBottom)
            }
            positions.forEach { (view, position) -> view.translationX = position.first; view.translationY = position.second }
            backgrounds.forEach { (view, drawable) -> if (view.background == null) view.background = drawable }
            foregrounds.forEach { (view, drawable) -> if (view.foreground == null) view.foreground = drawable }
            val params = binding.feed.layoutParams as? RelativeLayout.LayoutParams ?: return
            if (binding.feed.parent === binding.page && params.getRule(RelativeLayout.BELOW) == 0) {
                params.addRule(RelativeLayout.BELOW, feedBelow)
                params.topMargin = feedMargin
                binding.feed.layoutParams = params
            }
        }

        private fun dp(value: Int) = (density * value).roundToInt()
    }

    private class ArtworkTransform(view: View) {
        val x = view.translationX
        val y = view.translationY
        val scaleX = view.scaleX
        val scaleY = view.scaleY

        fun restore(view: View) {
            view.translationX = x
            view.translationY = y
            view.scaleX = scaleX
            view.scaleY = scaleY
        }
    }

}

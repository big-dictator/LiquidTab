package io.github.offlineglass.hook.adapters.xhs


import android.app.Activity
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.TextView
import android.widget.ImageView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.BloomTiltTracker
import io.github.offlineglass.hook.globalGlassBottomGapPx
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Detail-page-only skin. Native hierarchy, listeners and touch bounds never change. */
internal object XhsDetailGlass {
    private val sessions = WeakHashMap<Activity, Session>()
    private val paddingOwners = WeakHashMap<View, WeakReference<Session>>()
    private val artworkOwners = WeakHashMap<View, WeakReference<VerticalButtonSkin>>()
    private val observerOwners = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Session, Boolean>())
    private val observers = ArrayList<XC_MethodHook.Unhook>()
    private val configWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "LiquidTab-XhsConfig").apply { isDaemon = true }
    }
    private fun observe(session: Session) {
        if (!observerOwners.add(session) || observers.isNotEmpty()) return
        observers += XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = sessions[param.thisObject as? Activity] ?: return
                (param.args.firstOrNull() as? android.view.MotionEvent)?.let(owner::observeTouch)
            }
        })
        observers += XposedBridge.hookAllMethods(View::class.java, "invalidateDrawable", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                artworkOwners[param.thisObject as? View]?.get()?.invalidateSelf()
            }
        })
        observers += XposedBridge.hookAllMethods(View::class.java, "setPadding", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = paddingOwners[param.thisObject as? View]?.get() ?: return
                if (owner.extending && param.args.size == 4) param.args[3] = 0
            }
        })
    }
    private fun unobserve(session: Session) {
        observerOwners.remove(session)
        if (observerOwners.isEmpty()) { observers.forEach { it.unhook() }; observers.clear() }
    }
    fun installHooks() {
        // Register before the detail activity's first traversal, not after its
        // initial resume/enter transition has already presented native controls.
        XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name != "com.xingin.matrix.notedetail.NoteDetailActivity") return
                sessions.getOrPut(activity) { Session(activity) }.start()
                XhsNoteTop.start(activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name != "com.xingin.matrix.notedetail.NoteDetailActivity") return
                sessions.getOrPut(activity) { Session(activity) }.start()
                XhsNoteTop.start(activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                XhsNoteTop.dispose(activity)
                sessions.remove(activity)?.dispose()
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onStop", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                XhsNoteTop.pause(activity)
                sessions[activity]?.pause()
            }
        })
    }

    private class Session(activity: Activity) {
        private val root = activity.window.decorView as ViewGroup
        private val original = WeakHashMap<View, Drawable?>()
        private val originalClipping = WeakHashMap<ViewGroup, Triple<Boolean, Boolean, Boolean>>()
        private var row: ViewGroup? = null
        private var comment: TextView? = null
        private var skin: DetailBackground? = null
        private var lastCheck = 0L
        private var listening = false
        private var viewport: ViewGroup? = null
        private var viewportBottom = 0
        private var viewportPadding = 0
        private var viewportClip = true
        private var viewportParent: ViewGroup? = null
        private var parentPadding = 0
        private var parentClip = true
        var extending = false
            private set
        private var previousExtending: Boolean? = null
        @Volatile private var latestConfig = GlassConfig()
        private val configReading = java.util.concurrent.atomic.AtomicBoolean(false)
        private val buttonSkins = ArrayList<VerticalButtonSkin>()
        private var compactActions: List<View> = emptyList()
        private val actionCapsule = RectF()
        private val originalBounds = WeakHashMap<View, Rect>()
        private var originalRowTranslation = 0f
        private var commentColors: android.content.res.ColorStateList? = null
        private var commentAlpha = 1f
        private var commentScaleX = 1f
        private var commentScaleY = 1f
        private var pressFeedback: XhsPressFeedback? = null
        private var observingTouch = false
        private val touchOrigin = IntArray(2)
        private val touchRoot = IntArray(2)
        fun observeTouch(event: android.view.MotionEvent) {
            val target = row?.takeIf { it.isShown } ?: run {
                if (observingTouch) pressFeedback?.reset()
                observingTouch = false
                return
            }
            val feedback = pressFeedback ?: return
            target.getLocationInWindow(touchOrigin); root.getLocationInWindow(touchRoot)
            val x = event.x + touchRoot[0] - touchOrigin[0]
            val y = event.y + touchRoot[1] - touchOrigin[1]
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
                observingTouch = x >= 0 && y >= 0 && x < target.width && y < target.height
            if (!observingTouch) return
            val local = android.view.MotionEvent.obtain(event)
            try {
                local.offsetLocation((touchRoot[0] - touchOrigin[0]).toFloat(), (touchRoot[1] - touchOrigin[1]).toFloat())
                feedback.observe(local)
            } finally { local.recycle() }
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL)
                observingTouch = false
        }
        private val listener = ViewTreeObserver.OnPreDrawListener {
            val previousRow = row
            val now = SystemClock.uptimeMillis()
            // Initial post-resume may run before the toolbar has dimensions.
            // Retry at the first drawable frame instead of waiting 600ms.
            if (row?.isShown != true || buttonSkins.any { !it.isBound } || now - lastCheck >= 600L) {
                lastCheck = now
                update()
            }
            updateViewport()
            row?.let { allowShadowOverflow(it) }
            compactButtons()
            buttonSkins.forEach { it.refresh(now) }
            if (buttonSkins.size == 3 && buttonSkins.all { it.isBound && it.hasGlyph }) {
                buttonSkins.forEach { it.activate() }
            }
            comment?.let { input ->
                val foreground = if (skin?.isDarkPage() == true) Color.WHITE else Color.BLACK
                if (input.currentTextColor != foreground) {
                    input.setTextColor(foreground)
                    input.compoundDrawables.filterNotNull().forEach { it.setTint(foreground) }
                }
                if (input.alpha != 1f) input.alpha = 1f
            }
            // The initial viewport extension requests a measure/layout pass.
            // Do not present its old native geometry in that transitional frame.
            if (row != null && row !== previousRow) {
                root.postInvalidateOnAnimation()
                false
            } else true
        }
        fun start() {
            observe(this)
            if (!listening) {
                root.viewTreeObserver.addOnPreDrawListener(listener)
                listening = true
            }
            root.post { update() }
        }
        private fun clear(view: View) {
            if (!original.containsKey(view)) original[view] = view.background
            if (view.background != null) view.background = null
        }
        private fun update() {
            // Provider IPC can stall; never wait for it inside a drawing traversal.
            if (configReading.compareAndSet(false, true)) {
                val context = root.context.applicationContext
                configWorker.execute {
                    try {
                        latestConfig = HookConfigReader.read(context, "com.xingin.xhs").normalized()
                    } finally { configReading.set(false) }
                }
            }
            val config = latestConfig
            if (!config.enabled) { restore(); return }
            val label = comment?.takeIf { it.isAttachedToWindow && it.isShown }
                ?: findComment(root) ?: run { restore(); return }
            val candidate = label.parent as? ViewGroup ?: return
            // Fail closed if another app version changes the toolbar structure.
            val actions = (0 until candidate.childCount).map { candidate.getChildAt(it) }
                .filter { it !== label && it.isShown && it.isClickable }
            if (actions.size != 3 || candidate.height <= 0 || candidate.width <= 0) return
            if (row !== candidate || comment !== label || compactActions != actions) {
                restore()
                row = candidate
                originalRowTranslation = candidate.translationY
                comment = label
                commentColors = label.textColors
                commentAlpha = label.alpha
                commentScaleX = label.scaleX
                commentScaleY = label.scaleY
                label.setTextColor(Color.WHITE)
                label.alpha = 1f
                label.compoundDrawables.filterNotNull().forEach { it.mutate().setTint(Color.WHITE) }
                compactActions = actions
                (actions + label).forEach { originalBounds[it] = Rect(it.left, it.top, it.right, it.bottom) }
                skin = DetailBackground(candidate, label, actionCapsule)
                original[candidate] = candidate.background
                clear(label)
                // Only the compact native plate, never the page or keyboard.
                (candidate.parent as? ViewGroup)?.takeIf {
                    it.height <= candidate.height * 2
                }?.let { clear(it) }
                candidate.background = skin
                allowShadowOverflow(candidate)
                val feedback = XhsPressFeedback(candidate, listOf(label) + actions) {
                    val scale = pressFeedback?.scale(0) ?: 1f
                    label.scaleX = commentScaleX * scale
                    label.scaleY = commentScaleY * scale
                    skin?.invalidateSelf()
                    buttonSkins.forEach { it.invalidateSelf() }
                }
                pressFeedback = feedback
                skin?.feedback = feedback
                discoverViewport(candidate)
                android.util.Log.i("LiquidTabInject", "XHS detail: two native glass capsules attached")
            }
            if (buttonSkins.any { !it.isBound }) {
                buttonSkins.forEach { it.restore() }
                buttonSkins.clear()
            }
            if (buttonSkins.isEmpty()) {
                val artwork = actions.mapIndexedNotNull { index, action ->
                    (action as? ViewGroup)?.let { VerticalButtonSkin.create(it, actionCapsule, index) }
                }
                if (artwork.size == actions.size) {
                    artwork.forEach { it.feedback = pressFeedback }
                    buttonSkins.addAll(artwork)
                }
            }
            skin?.update(config)
            // Native theme/rebind code can replace these plates after attachment.
            for (view in original.keys.toList()) {
                if (view !== candidate && view.background != null) view.background = null
            }
            if (candidate.background !== skin) candidate.background = skin
            allowShadowOverflow(candidate)
        }
        private fun allowShadowOverflow(toolbar: ViewGroup) {
            // The native 56dp toolbar only leaves 4dp around a 48dp capsule,
            // so its ancestors must not clip the 10dp ambient shadow.
            var container: ViewGroup? = toolbar
            while (container != null) {
                if (!originalClipping.containsKey(container)) {
                    originalClipping[container] = Triple(
                        container.clipChildren, container.clipToPadding, container.clipToOutline)
                }
                if (container.clipChildren) container.clipChildren = false
                if (container.clipToPadding) container.clipToPadding = false
                if (container.clipToOutline) container.clipToOutline = false
                if (container === root) break
                container = container.parent as? ViewGroup
            }
        }
        private fun discoverViewport(toolbar: ViewGroup) {
            val toolbarPosition = IntArray(2)
            val position = IntArray(2)
            toolbar.getLocationInWindow(toolbarPosition)
            fun visit(view: View) {
                if (view === toolbar || !view.isShown) return
                if (view is ViewGroup && view.javaClass.name ==
                    "com.xingin.comment.consumer.list.CommentListView") {
                    view.getLocationInWindow(position)
                    if (view.width >= toolbar.width * 0.9f && view.height > toolbar.height * 2 &&
                        (view.parent as? View)?.javaClass?.name ==
                        "com.xingin.matrix.base.widgets.NestedScrollLayout") {
                        viewport = view
                        viewportBottom = view.bottom
                        viewportPadding = view.paddingBottom
                        viewportClip = view.clipToPadding
                        viewportParent = view.parent as ViewGroup
                        parentPadding = viewportParent!!.paddingBottom
                        parentClip = viewportParent!!.clipToPadding
                        paddingOwners[viewportParent!!] = WeakReference(this@Session)
                    }
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
        }
        private fun updateViewport() {
            val list = viewport ?: return
            val toolbar = row ?: return
            val parent = viewportParent ?: return
            val keyboard = root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            val extend = !keyboard && toolbar.isShown && list.isShown
            extending = extend
            if (previousExtending == extend) return
            previousExtending = extend
            val wantedParentPadding = if (extend) 0 else parentPadding
            if (parent.paddingBottom != wantedParentPadding)
                parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, wantedParentPadding)
            parent.clipToPadding = if (extend) false else parentClip
            val clearance = if (extend) parentPadding else 0
            val wantedPadding = viewportPadding + clearance
            if (list.paddingBottom != wantedPadding)
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, wantedPadding)
            list.clipToPadding = if (extend) false else viewportClip
        }
        private fun findComment(view: View): TextView? {
            if (!view.isShown) return null
            if (view is TextView && view.text.toString() in setOf("说点什么...", "说点什么…")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                findComment(view.getChildAt(i))?.let { return it }
            }
            return null
        }
        private fun compactButtons() {
            val toolbar = row ?: return
            val input = comment ?: return
            if (compactActions.size != 3 || toolbar.width <= 0) return
            val dp = toolbar.resources.displayMetrics.density
            // Keep native 48dp touch targets, with screen-relative outer margins.
            val slot = (48f * dp).toInt()
            val side = globalGlassBottomGapPx(toolbar)
            val end = toolbar.width - side
            val nativeStart = originalBounds[compactActions.first()]?.left ?: return
            val start = maxOf(nativeStart, end - slot * 3)
            compactActions.forEachIndexed { index, action ->
                val left = start + (end - start) * index / 3
                val right = start + (end - start) * (index + 1) / 3
                if (action.left != left || action.right != right) {
                    action.layout(left, action.top, right, action.bottom)
                    action.invalidate()
                    toolbar.invalidate()
                }
            }
            val capsuleLeft = start + 4f * dp
            val capsuleHeight = minOf(48f * dp, toolbar.height.toFloat())
            val capsuleTop = (toolbar.height - capsuleHeight) / 2f
            val capsuleBottom = capsuleTop + capsuleHeight
            if (actionCapsule.left != capsuleLeft || actionCapsule.right != end.toFloat() ||
                actionCapsule.top != capsuleTop || actionCapsule.bottom != capsuleBottom) {
                actionCapsule.set(capsuleLeft, capsuleTop, end.toFloat(), capsuleBottom)
                skin?.invalidateSelf()
                buttonSkins.forEach { it.invalidateSelf() }
            }
            val inputRight = start - (12f * dp).toInt()
            if (inputRight > side && (input.right != inputRight || input.left != side))
                input.layout(side, input.top, inputRight, input.bottom)
            if (!extending) {
                toolbar.translationY = originalRowTranslation
                return
            }
            val position = IntArray(2)
            val rootPosition = IntArray(2)
            toolbar.getLocationOnScreen(position)
            root.getLocationOnScreen(rootPosition)
            val gap = globalGlassBottomGapPx(toolbar) * if (latestConfig.classicNavigation) 2 else 1
            val target = rootPosition[1] + root.height - gap
            val translation = target - (position[1] - toolbar.translationY + capsuleBottom)
            if (kotlin.math.abs(toolbar.translationY - translation) > 0.5f)
                toolbar.translationY = translation
        }
        private fun restore() {
            pressFeedback?.reset()
            pressFeedback = null
            for ((container, clipping) in originalClipping) {
                container.clipChildren = clipping.first
                container.clipToPadding = clipping.second
                container.clipToOutline = clipping.third
            }
            originalClipping.clear()
            row?.translationY = originalRowTranslation
            comment?.let { input ->
                commentColors?.let { input.setTextColor(it) }
                input.alpha = commentAlpha
                input.scaleX = commentScaleX
                input.scaleY = commentScaleY
                input.compoundDrawables.filterNotNull().forEach { it.clearColorFilter(); it.setTintList(null) }
            }
            commentColors = null
            for ((view, rect) in originalBounds) view.layout(rect.left, rect.top, rect.right, rect.bottom)
            originalBounds.clear()
            compactActions = emptyList()
            actionCapsule.setEmpty()
            extending = false
            previousExtending = null
            viewportParent?.let { paddingOwners.remove(it) }
            buttonSkins.forEach { it.restore() }
            buttonSkins.clear()
            viewport?.let { list ->
                list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, viewportPadding)
                list.clipToPadding = viewportClip
            }
            viewportParent?.let { parent ->
                parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, parentPadding)
                parent.clipToPadding = parentClip
            }
            viewportParent = null
            viewport = null
            val active = skin
            for ((view, background) in original) {
                if (view.background == null || view.background === active) view.background = background
            }
            original.clear()
            skin?.release()
            skin = null
            row = null
            comment = null
        }
        fun pause() {
            observingTouch = false
            unobserve(this)
            pressFeedback?.reset()
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)
            listening = false
            skin?.pause()
        }
        fun dispose() {
            observingTouch = false
            unobserve(this)
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(listener)
            listening = false
            restore()
        }
    }

    /** Project each native action into the same centered icon-and-label layout. */
    private class VerticalButtonSkin(
        private val button: ViewGroup, private val icon: ImageView, private val count: TextView,
        private val actionCapsule: RectF, private val index: Int,
    ) : Drawable() {
        var feedback: XhsPressFeedback? = null
        private var active = false
        private var iconAlpha = 1f
        private var countAlpha = 1f
        private var glyph: RectF? = null
        val hasGlyph get() = glyph != null
        val isBound get() = icon.parent === button && count.parent === button &&
            if (active) button.foreground === this else button.foreground == null

        private data class Source(
            val drawable: Drawable, val composition: Any?, val selected: Boolean, val width: Int, val height: Int,
        ) {
            fun matches(drawable: Drawable?, composition: Any?, icon: ImageView): Boolean =
                this.drawable === drawable && this.composition === composition && selected == icon.isSelected &&
                    width == icon.width && height == icon.height
        }

        private var measuredSource: Source? = null
        private var failedSource: Source? = null
        private var lastProbe = 0L
        private var lastDrawable: Drawable? = null
        private var lastProgress: Float? = null
        private var lastSelected = false
        private var lastComposition: Any? = null
        private val progressGetter = runCatching {
            icon.javaClass.getMethod("getProgress").takeIf { it.returnType == Float::class.javaPrimitiveType }
        }.getOrNull()
        private val compositionGetter = progressGetter?.let {
            runCatching { icon.javaClass.getMethod("getComposition") }.getOrNull()
        }
        private val animatingGetter = progressGetter?.let {
            runCatching {
                icon.javaClass.getMethod("isAnimating").takeIf { it.returnType == Boolean::class.javaPrimitiveType }
            }.getOrNull()
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        private val fontMetrics = Paint.FontMetrics()
        private var lastText = ""
        private var lastColor = 0
        private var lastButtonWidth = 0
        private var lastButtonHeight = 0

        fun activate() {
            if (!isBound || active) return
            iconAlpha = icon.alpha
            countAlpha = count.alpha
            active = true
            artworkOwners[icon] = WeakReference(this)
            icon.alpha = 0f
            count.alpha = 0f
            button.foreground = this
        }

        fun refresh(now: Long) {
            if (!isBound) return
            val drawable = icon.drawable
            val progress = runCatching { progressGetter?.invoke(icon) as? Float }.getOrNull()
            val composition = runCatching { compositionGetter?.invoke(icon) }.getOrNull()
            val animating = (drawable as? android.graphics.drawable.Animatable)?.isRunning == true ||
                runCatching { animatingGetter?.invoke(icon) as? Boolean }.getOrNull() == true
            if (drawable != null && icon.width > 0 && icon.height > 0 && !animating &&
                measuredSource?.matches(drawable, composition, icon) != true &&
                (failedSource?.matches(drawable, composition, icon) != true || now - lastProbe >= 250L)) {
                val source = Source(drawable, composition, icon.isSelected, icon.width, icon.height)
                val bounds = measureVisibleGlyph()
                lastProbe = now
                if (bounds != null) {
                    glyph = bounds
                    measuredSource = source
                    failedSource = null
                    invalidateSelf()
                } else {
                    failedSource = source
                }
            }
            val text = count.text.toString()
            if (lastText != text || lastColor != count.currentTextColor ||
                lastButtonWidth != button.width || lastButtonHeight != button.height ||
                lastDrawable !== drawable || lastSelected != icon.isSelected ||
                lastComposition !== composition || lastProgress != progress) {
                lastText = text
                lastColor = count.currentTextColor
                lastButtonWidth = button.width
                lastButtonHeight = button.height
                lastDrawable = drawable
                lastSelected = icon.isSelected
                lastComposition = composition
                lastProgress = progress
                invalidateSelf()
            }
            if (active) {
                if (icon.alpha != 0f) icon.alpha = 0f
                if (count.alpha != 0f) count.alpha = 0f
            }
        }

        private fun measureVisibleGlyph(): RectF? {
            // Only the unanimated icon is sampled. The displayed frames are
            // still drawn live with the cached anchor, including during bursts.
            val scale = minOf(1f, 192f / maxOf(icon.width, icon.height))
            val width = maxOf(1, kotlin.math.ceil(icon.width * scale).toInt())
            val height = maxOf(1, kotlin.math.ceil(icon.height * scale).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                val probe = Canvas(bitmap)
                probe.scale(scale, scale)
                icon.draw(probe)
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                var left = width
                var top = height
                var right = -1
                var bottom = -1
                for (y in 0 until height) for (x in 0 until width) {
                    if (Color.alpha(pixels[y * width + x]) >= 64) {
                        left = minOf(left, x)
                        top = minOf(top, y)
                        right = maxOf(right, x)
                        bottom = maxOf(bottom, y)
                    }
                }
                if (right <= left || bottom <= top) null
                else RectF(left / scale, top / scale, (right + 1) / scale, (bottom + 1) / scale)
            } catch (_: Exception) {
                null
            } finally {
                bitmap.recycle()
            }
        }

        override fun draw(canvas: Canvas) {
            val source = glyph ?: return
            if (button.width <= 0 || button.height <= 0) return
            val display = button.resources.displayMetrics
            val dp = display.density
            val availableHeight = minOf(48f * dp, button.height.toFloat())
            paint.color = count.currentTextColor
            paint.textSize = minOf(10f * display.scaledDensity, availableHeight / 2f)
            paint.getFontMetrics(fontMetrics)
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val gap = minOf(3f * dp, (availableHeight - lineHeight).coerceAtLeast(0f))
            val iconHeight = minOf(18f * dp, availableHeight - lineHeight - gap).coerceAtLeast(0f)
            val centerX = if (actionCapsule.isEmpty) button.width / 2f else
                actionCapsule.left + actionCapsule.width() * (index + 0.5f) / 3f - button.left
            val centerY = button.height / 2f
            val stackTop = centerY - (iconHeight + gap + lineHeight) / 2f
            val baseline = stackTop + iconHeight + gap - fontMetrics.ascent
            val feedbackSave = canvas.save()
            val pressScale = feedback?.actionScale() ?: 1f
            val pivotX = if (actionCapsule.isEmpty) centerX else actionCapsule.centerX() - button.left
            val pivotY = if (actionCapsule.isEmpty) centerY else actionCapsule.centerY() - button.top
            canvas.scale(pressScale, pressScale, pivotX, pivotY)
            if (iconHeight > 0f) {
                val iconSave = canvas.save()
                val scale = iconHeight / source.height()
                canvas.translate(centerX, stackTop + iconHeight / 2f)
                canvas.scale(scale, scale)
                canvas.translate(-source.centerX(), -source.centerY())
                icon.draw(canvas)
                canvas.restoreToCount(iconSave)
            }
            canvas.drawText(count.text.toString(), centerX, baseline, paint)
            canvas.restoreToCount(feedbackSave)
        }

        fun restore() {
            if (!active) return
            active = false
            artworkOwners.remove(icon)
            if (button.foreground === this) button.foreground = null
            if (icon.alpha == 0f) icon.alpha = iconAlpha
            if (count.alpha == 0f) count.alpha = countAlpha
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

        companion object {
            fun create(button: ViewGroup, actionCapsule: RectF, index: Int): VerticalButtonSkin? {
                if (button.foreground != null) return null
                val children = (0 until button.childCount).map(button::getChildAt)
                val icon = children.filterIsInstance<ImageView>().singleOrNull() ?: return null
                val count = children.filterIsInstance<TextView>().singleOrNull() ?: return null
                return VerticalButtonSkin(button, icon, count, actionCapsule, index)
            }
        }
    }

    private class DetailBackground(
        private val row: ViewGroup, private val label: View, private val actionCapsule: RectF,
    ) : Drawable() {
        var feedback: XhsPressFeedback? = null
        private val capsules = List(2) { XhsNoteCapsule(row, "detail-$it") }
        private val location = IntArray(2)
        private val rowLocation = IntArray(2)
        private var config = GlassConfig()
        private var trackingTilt = false
        fun update(value: GlassConfig) {
            if (config != value) { config = value; invalidateSelf() }
            val track = value.enabled && value.liquidGlassEnabled && !value.solidBarEnabled &&
                value.postureEffectsEnabled && row.isShown && row.windowVisibility == View.VISIBLE
            if (track != trackingTilt) {
                trackingTilt = track
                if (track) BloomTiltTracker.register(row) else BloomTiltTracker.unregister(row)
            }
        }
        override fun draw(canvas: Canvas) {
            if (!canvas.isHardwareAccelerated || !row.isShown) return
            val density = row.resources.displayMetrics.density
            // Kyant0 catalog LiquidButton: height(48.dp), capsule shape.
            val height = minOf(48f * density, row.height.toFloat())
            if (height <= 0) return
            val y = (row.height - height) / 2f
            val left = RectF(label.left.toFloat(), y, label.right.toFloat(), y + height)
            val dark = isDarkPage()
            for ((index, rect) in listOf(left, actionCapsule).withIndex()) {
                if (rect.isEmpty) continue
                val feedbackSave = canvas.save()
                val scale = if (index == 0) feedback?.scale(0) ?: 1f
                    else feedback?.actionScale() ?: 1f
                canvas.scale(scale, scale, rect.centerX(), rect.centerY())
                val page = pageTint(dark)
                val alpha = if (config.solidBarEnabled) 255 else (config.lightAlpha * 255).toInt()
                val tint = (page and 0x00ffffff) or (alpha shl 24)
                capsules[index].draw(canvas, rect, config, dark, tint, page,
                    recordBackdrop = ::recordBackdrop,
                    drawPress = { feedback?.drawLight(it, rect, index) })
                canvas.restoreToCount(feedbackSave)
            }
        }
        private fun recordBackdrop(recording: Canvas) {
            row.getLocationInWindow(rowLocation)
            recording.translate(-rowLocation[0].toFloat(), -rowLocation[1].toFloat())
            // Replay disjoint sibling display lists only. Never capture an ancestor
            // containing this drawable: that would create recursive feedback.
            val layers = ArrayList<Pair<View, ViewGroup>>()
            var branch: View = row
            var parent = branch.parent as? ViewGroup
            while (parent != null) {
                layers.add(branch to parent)
                branch = parent
                parent = branch.parent as? ViewGroup
            }
            // Composite far ancestors first. Only siblings behind the branch
            // belong to the backdrop; later siblings are foreground overlays.
            for ((currentBranch, currentParent) in layers.asReversed()) {
                // A parent's background is not part of any sibling display
                // list. Composite it first so uncovered areas retain the
                // actual page color rather than a different fallback grey.
                currentParent.background?.let { background ->
                    currentParent.getLocationInWindow(location)
                    val save = recording.save()
                    recording.translate(location[0].toFloat(), location[1].toFloat())
                    background.draw(recording)
                    recording.restoreToCount(save)
                }
                for (i in 0 until currentParent.childCount) {
                    val source = currentParent.getChildAt(i)
                    if (source === currentBranch) break
                    if (!source.isShown || source.alpha <= 0f) continue
                    val sourceNode = XhsNoteCapsule.renderNode(source) ?: continue
                    if (!sourceNode.hasDisplayList()) continue
                    source.getLocationInWindow(location)
                    val save = recording.save()
                    recording.translate(location[0] - source.left - source.translationX,
                        location[1] - source.top - source.translationY)
                    recording.drawRenderNode(sourceNode)
                    recording.restoreToCount(save)
                }
            }
        }
        fun isDarkPage(): Boolean = when (config.themeMode) {
            GlassConfig.THEME_LIGHT -> false
            GlassConfig.THEME_DARK -> true
            else -> {
                val nativeNight = row.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val color = pageTint(nativeNight)
                (Color.red(color) * 0.2126f + Color.green(color) * 0.7152f + Color.blue(color) * 0.0722f) < 128f
            }
        }
        private fun pageTint(dark: Boolean): Int {
            var ancestor = row.parent as? View
            repeat(8) {
                val view = ancestor ?: return if (dark) Color.rgb(25, 25, 31) else Color.WHITE
                val color = (view.background as? ColorDrawable)?.color
                if (color != null && Color.alpha(color) == 255) return color
                ancestor = view.parent as? View
            }
            return if (dark) Color.rgb(25, 25, 31) else Color.WHITE
        }
        fun pause() {
            if (trackingTilt) BloomTiltTracker.unregister(row)
            trackingTilt = false
        }
        fun release() { pause(); capsules.forEach { it.release() } }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

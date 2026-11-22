package io.github.offlineglass.hook.adapters.apple_music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.SpringFloat
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import kotlin.math.abs
import kotlin.math.roundToInt

/** Library sections share a native toolbar and a separate content scene. */
internal class AppleMusicLibraryDetailsToolbar(
    private val context: Context,
    private val native: AppleMusicOverflowGlass,
) {
    internal data class MenuFrame(val bounds: RectF, val glyph: RectF)

    private enum class Action(val label: String) {
        BACK("navigate_up"), NEW_PLAYLIST("new_playlist_button"),
        FILTER("library_filter_actionsheet_title"), MORE("more_options"),
    }

    private enum class Section(val actions: List<Action>) {
        PLAYLISTS(listOf(Action.BACK, Action.NEW_PLAYLIST, Action.FILTER, Action.MORE)),
        ARTISTS(listOf(Action.BACK, Action.FILTER)),
        ALBUMS(listOf(Action.BACK, Action.FILTER, Action.MORE)),
        SONGS(ALBUMS.actions),
    }

    private enum class Part(val actions: List<Action>) {
        BACK(listOf(Action.BACK)), CREATE(listOf(Action.NEW_PLAYLIST)),
        MENU(listOf(Action.FILTER, Action.MORE)),
    }

    private data class Binding(
        val page: ViewGroup,
        val toolbar: View,
        val scene: ViewGroup,
        val buttons: Map<Action, AppleMusicOverflowGlass.Button>,
        val model: LibraryModel,
    ) {
        val reference get() = buttons.getValue(Action.BACK)
        val moreGlyph get() = buttons[Action.MORE]?.geometry?.glyph
    }

    private data class LibraryModel(val section: Section, val filterResource: () -> Int)

    private val pageId = resource("details_page_container", "id")
    private val toolbarId = resource("floating_app_bar_container", "id")
    private val sceneId = resource("blur_target", "id")
    private val fragmentTag = resource("fragment_container_view_tag", "id")
    private val labels = Action.entries.associateWith { resource(it.label, "string") }
    private val back = drawable("ic_arrow_back_black_24")
    private val create = drawable("toolbar_new_playlist")
    private val more = drawable("ic_navbar_platter_more")
    private val filterOffId = resource("library_details_favorite_filter_off", "drawable")
    private val filterOnId = resource("library_details_favorite_filter_on", "drawable")
    private val filterOff = drawable("library_details_favorite_filter_off")
    private val filterOn = drawable("library_details_favorite_filter_on")
    private val filterColor = resource("system_blue", "color").takeIf { it != 0 }?.let(context::getColor)
    private val location = IntArray(2)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var pressed: Panel? = null
    private var binding: Binding? = null
    private var nextProbeAt = 0L
    private var probePage: ViewGroup? = null
    private var firstProbes = 0
    private var present = false
    private var progress = 0f
    private var filterMix = 0f
    private var filterSelected = false
    private var menuEntry: MenuFrame? = null
    private var menuHandedOff = false
    private var lastVisibleAlpha = 1f
    private var hasDrawnFrame = false
    val acceptedIncomingMenu get() = present && menuEntry != null && binding?.moreGlyph != null
    private val fadeStart = java.util.EnumMap<Part, Float>(Part::class.java)
    private val entryOpacity = java.util.EnumMap<Part, Float>(Part::class.java)
    private val pendingDrawRoots = ArrayList<Any>(2)
    private val panels = LinkedHashMap<Part, Panel>(Part.entries.size)
    private val travel = lazy {
        SpringFloat(0f, 285f, .68f, .001f) { value, _ ->
            progress = value
            panels.values.firstOrNull()?.glass?.postInvalidateOnAnimation()
        }
    }
    private val filterFade = lazy {
        SpringFloat(0f, 300f, 1f, .001f) { value, _ ->
            filterMix = value.coerceIn(0f, 1f)
            panels[Part.MENU]?.glass?.invalidate()
        }
    }

    private fun resource(name: String, type: String) =
        context.resources.getIdentifier(name, type, targetSpec.packageName)

    private fun drawable(name: String) = resource(name, "drawable").takeIf { it != 0 }
        ?.let(context::getDrawable)?.mutate()

    fun update(scope: ViewGroup, bottom: GlassHostLayout?, refresh: Boolean, incomingMenu: (() -> MenuFrame?)? = null): Boolean {
        var current = binding
        if (refresh || current?.page?.isShown != true) {
            val now = SystemClock.uptimeMillis()
            if (refresh || probePage != null && nextProbeAt <= now) {
                nextProbeAt = now + 240L
                val page = current?.page?.takeIf { it.isShown && it.isAttachedToWindow }
                    ?: scope.findViewById<ViewGroup>(pageId)?.takeIf { it.isShown && it.isAttachedToWindow }
                if (page != null) {
                    if (probePage !== page) {
                        probePage = page
                        firstProbes = 0
                    }
                    val discovered = discover(page) { owner -> watchToolbarDraw(page, owner) }
                    if (discovered != null) {
                        val same = current?.page === page && current.model.section == discovered.model.section &&
                            current.buttons.all { (role, button) ->
                            val next = discovered.buttons[role]
                            next?.node === button.node && next.nativeBlur === button.nativeBlur
                        }
                        if (!same && replaceBinding(discovered)) {
                            current = discovered
                        }
                    } else if (current == null && firstProbes++ < 4) {
                        nextProbeAt = now + 16L
                        page.postInvalidateDelayed(16L)
                    }
                } else {
                    probePage = null
                }
            }
        }
        if (current == null) return false
        val nativeAlpha = (current.page.alpha * current.toolbar.alpha).coerceIn(0f, 1f)
        val visible = current.page.isShown && current.toolbar.isShown && current.page.isAttachedToWindow &&
            nativeAlpha > .001f
        if (visible) {
            current.buttons.values.forEach { it.hideNativeBlur() }
            if (bottom != null) {
                val parent = bottom.rootView.findViewById<FrameLayout>(android.R.id.content)
                if (parent != null) {
                    if (panels.isEmpty()) {
                        menuEntry = current.moreGlyph?.let { incomingMenu?.invoke() }
                        if (menuEntry != null) entryOpacity[Part.MENU] = 1f
                        for (part in Part.entries) {
                            val actions = part.actions.filter(current.buttons::containsKey)
                            if (actions.isNotEmpty()) panels[part] = Panel(part, actions, parent, bottom)
                        }
                    }
                    val filter = current.model.filterResource() == filterOnId
                    if (filter != filterSelected) {
                        filterSelected = filter
                        filterFade.value.animateTo(if (filter) 1f else 0f)
                    }
                    current.buttons.forEach { (role, button) ->
                        native.measureDetailsButton(button, labels.getValue(role))?.let { button.geometry = it }
                    }
                }
            }
        }
        if (panels.isNotEmpty() && present != visible) {
            if (!visible) {
                panels.forEach { (part, panel) -> fadeStart[part] = panel.glass.alpha }
                travel.value.snapTo(1f)
            }
            present = visible
            if (visible) {
                menuHandedOff = false
                if (hasDrawnFrame) {
                    menuEntry = current.moreGlyph?.let { panels[Part.MENU]?.menuFrame(it) }
                    // Resume from the live opacity when the user immediately goes back.
                    panels.forEach { (part, panel) -> entryOpacity[part] = panel.glass.alpha }
                    travel.value.snapTo(0f)
                }
                travel.value.resumeOscillation()
                travel.value.animateTo(1f)
            } else {
                travel.value.returnTo(0f)
            }
        }
        if (!visible && (!travel.isInitialized() || !travel.value.isRunning)) {
            clear()
            return false
        }
        if (visible) {
            lastVisibleAlpha = nativeAlpha
            panels.values.forEach { it.place(current, lastVisibleAlpha) }
            hasDrawnFrame = panels.isNotEmpty()
        } else {
            panels.forEach { (part, panel) -> panel.fade((fadeStart[part] ?: lastVisibleAlpha) * progress.coerceIn(0f, 1f)) }
        }
        return visible
    }

    fun takeOutgoingMenu(): MenuFrame? {
        if (present || menuHandedOff) return null
        val panel = panels[Part.MENU]?.takeIf { it.glass.isShown } ?: return null
        val glyph = binding?.moreGlyph ?: return null
        val frame = panel.menuFrame(glyph)
        menuHandedOff = true
        panel.glass.visibility = View.INVISIBLE
        return frame
    }

    private fun discover(page: ViewGroup, onOwner: ((View) -> Unit)? = null): Binding? {
        if (back == null || filterOff == null || filterOn == null) return null
        val toolbar = page.uniqueChild(toolbarId) ?: return null
        val scene = page.uniqueChild(sceneId) as? ViewGroup ?: return null
        val fragment = generateSequence(page as View) { it.parent as? View }.take(4)
            .mapNotNull { it.getTag(fragmentTag) }
            .firstOrNull { it.javaClass.name == "com.apple.android.music.library.fragments.LibraryDetailsPageFragment" }
            ?: return null
        val model = readModel(fragment) ?: return null
        val actions = model.section.actions
        if (Action.NEW_PLAYLIST in actions && create == null || Action.MORE in actions && more == null) return null
        val buttons = actions.associateWith { role ->
            val label = labels.getValue(role).takeIf { it != 0 } ?: return null
            native.findInToolbar(toolbar, context.getString(label), onOwner,
                atStart = role == Action.BACK) ?: return null
        }
        val reference = buttons.getValue(Action.BACK)
        if (buttons.values.any { it.owner !== reference.owner ||
                abs(it.bounds.centerY() - reference.bounds.centerY()) > 3 } ||
            buttons.values.map { it.node }.toSet().size != buttons.size) return null
        val direction = if (toolbar.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        if (!actions.zipWithNext().all { (left, right) ->
                (buttons.getValue(right).bounds.centerX() - buttons.getValue(left).bounds.centerX()) * direction >=
                    reference.bounds.width()
            }) return null
        return Binding(page, toolbar, scene, buttons, model)
    }

    private fun watchToolbarDraw(page: ViewGroup, owner: View) {
        if (pendingDrawRoots.size >= 2) return
        runCatching {
            val abi = OverflowDraw.abi(owner)
            val root = abi.root.invoke(owner) ?: return
            if (pendingDrawRoots.any { it === root }) return
            pendingDrawRoots.add(root)
            var attempts = 0
            OverflowDraw.observeRoot(abi.draw, root) {
                if (++attempts >= 4) {
                    OverflowDraw.stopObservingRoot(root)
                    pendingDrawRoots.remove(root)
                }
                if (page.isShown && binding?.page !== page) {
                    runCatching {
                        discover(page)?.let { if (replaceBinding(it)) owner.postInvalidateOnAnimation() }
                    }
                }
            }
        }
    }

    private fun readModel(fragment: Any): LibraryModel? = runCatching {
        val field = fragment.javaClass.declaredFields.single {
            it.type.name == "com.apple.android.music.library.LibraryDetailsViewModel"
        }.apply { isAccessible = true }
        val model = field.get(fragment) ?: return null
        if (field.type.getMethod("isTopLibrarySection").invoke(model) != true) return null
        val nativeSection = field.type.getMethod("getDetailTypeSection").invoke(model) as? Enum<*> ?: return null
        val section = Section.entries.singleOrNull { it.name == nativeSection.name } ?: return null
        val read = field.type.getMethod("resourceForFavoriteFilterStatus")
        if (read.returnType != Int::class.javaPrimitiveType) return null
        val initial = read.invoke(model) as Int
        if (initial != filterOffId && initial != filterOnId) return null
        LibraryModel(section) { runCatching { read.invoke(model) as Int }.getOrDefault(initial) }
    }.getOrNull()

    private fun replaceBinding(next: Binding): Boolean {
        if (!native.observeNativeTouches(next.reference.owner)) return false
        if (binding?.buttons?.keys?.let { it != next.buttons.keys } == true) {
            clearPanels()
        }
        releaseNative(except = next)
        binding = next
        stopWatchingToolbarDraw()
        next.buttons.values.forEach { button ->
            OverflowDraw.hidden[button.node] = Unit
            button.hideNativeBlur()
        }
        next.reference.invalidateNative()
        val filter = next.model.filterResource() == filterOnId
        if (panels.isEmpty()) {
            filterSelected = filter
            if (filterFade.isInitialized()) filterFade.value.cancel()
            filterMix = if (filter) 1f else 0f
        }
        panels.values.forEach { it.glass.invalidateOpticalBackdropForAdapter() }
        ArtistDiagnostics.log("library ${next.model.section} toolbar bound: ${next.buttons.keys}")
        return true
    }

    private fun releaseNative(except: Binding? = null) {
        val previous = binding ?: return
        val owner = previous.reference.owner
        if (except?.reference?.owner !== owner) native.stopObservingNativeTouches(owner)
        for ((role, button) in previous.buttons) {
            val replacement = except?.buttons?.get(role)
            if (replacement?.node !== button.node) OverflowDraw.hidden.remove(button.node)
            button.restoreNativeBlur()
        }
        if (except?.reference?.owner !== owner) {
            previous.reference.invalidateNative()
        }
    }

    fun onTouch(owner: View, event: MotionEvent): Boolean {
        val current = binding?.takeIf { it.reference.owner === owner } ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                val role = current.buttons.entries.firstOrNull {
                    it.value.geometry.touch.contains(downX.toInt(), downY.toInt())
                }?.key
                pressed?.releasePress(0L)
                pressed = panels.values.firstOrNull { role in it.actions }
                pressed?.press(downX, downY)
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                pressed?.releasePress(0L)
                pressed = null
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                val delay = if (event.actionMasked == MotionEvent.ACTION_UP)
                    (event.downTime + 60L - SystemClock.uptimeMillis()).coerceAtLeast(0L) else 0L
                pressed?.releasePress(delay)
                pressed = null
            }
        }
        return true
    }

    fun restoreOptics() = panels.values.forEach { it.glass.restoreOpticalSceneForAdapter() }

    fun clear() {
        stopWatchingToolbarDraw()
        if (binding == null && panels.isEmpty()) {
            probePage = null
            return
        }
        releaseNative()
        binding = null
        clearPanels()
        nextProbeAt = 0L
        probePage = null
    }

    private fun clearPanels() {
        pressed = null
        panels.values.forEach(Panel::close)
        panels.clear()
        if (travel.isInitialized()) travel.value.snapTo(0f)
        if (filterFade.isInitialized()) filterFade.value.cancel()
        present = false
        menuEntry = null
        menuHandedOff = false
        lastVisibleAlpha = 1f
        hasDrawnFrame = false
        fadeStart.clear()
        entryOpacity.clear()
    }

    private fun stopWatchingToolbarDraw() {
        pendingDrawRoots.forEach(OverflowDraw::stopObservingRoot)
        pendingDrawRoots.clear()
    }

    private inner class Panel(val part: Part, val actions: List<Action>,
        parent: FrameLayout, private val bottom: GlassHostLayout) {
        private val paired = Action.MORE in actions
        private val ambient = AppleMusicPanelAmbient(parent)
        private var material = TopBarMaterialPolicy(TopBarRole.ACTION, iconOnly = true, selectedAccent = false)
        private val face = RectF()
        private var pressure = 0f
        private var glyphProgress = 0f
        private val drawnMore = RectF()
        private val glyphPosition = RectF()
        private var entryScale = 1f
        private var previousConfig: GlassConfig? = null
        private val light = Paint(Paint.ANTI_ALIAS_FLAG)
        private val press = lazy {
            SpringFloat(0f, 300f, .55f, .001f) { value, _ ->
                pressure = value
                applyScale()
                glass.invalidate()
            }
        }
        private val endPress = Runnable { if (press.isInitialized()) press.value.animateTo(0f) }
        val glass: GlassHostLayout

        init {
            val state = object : AppNavigationState {
                override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = binding != null
                override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = binding != null
                override fun requiresSampledBackdrop() = present
                override fun resolveBackdropScene(host: View): View? = binding?.scene
                override fun pauseBackdropWork(host: View) = !present
                override fun resolveAutoDarkMode(host: View) = bottom.isDarkGlass()
                override fun allowsHostTouch() = false
                override val allowsSoftwareBackdropCapture = false
                override fun drawsPanelAmbientShadow() = false
                override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = drawContent(canvas, frame)
            }
            val adapter = object : TargetAdapter {
                override val key = "apple_music_library_details_toolbar"
                override val ownsHostLayout = true
                override val ownsNavigationDrawing = true
                override val skipNavigationSnapshot = true
                override val skipBackdropStableDelay = true
                override val allowsSystemBackgroundBlur = false
                override val actionOnlyIndices = setOf(0)
                override fun normalizeConfig(config: GlassConfig) = material.apply(bottom.config)
                override fun pushedConfig() = material.apply(bottom.config)
                override fun createNavigationState(context: Context) = state
            }
            glass = GlassHostLayout(parent.context, material.apply(bottom.config), 1,
                navigationSource = null, sliderEnabled = false, adapterOverride = adapter).apply {
                elevation = 0f
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            ambient.bind(glass)
            parent.addView(glass, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        }

        fun place(current: Binding, alpha: Float) {
            val end = current.buttons.getValue(actions.last()).bounds
            val first = current.buttons.getValue(actions.first()).bounds
            val extent = if (paired) progress.coerceIn(0f, 1.08f) else 1f
            val origin = menuEntry?.bounds?.takeIf { paired }
            face.set(
                lerp(origin?.left ?: end.left.toFloat(), minOf(first.left, end.left).toFloat(), extent),
                lerp(origin?.top ?: end.top.toFloat(), end.top.toFloat(), extent),
                lerp(origin?.right ?: end.right.toFloat(), maxOf(first.right, end.right).toFloat(), extent),
                lerp(origin?.bottom ?: end.bottom.toFloat(), end.bottom.toFloat(), extent),
            )
            val width = face.width().roundToInt().coerceAtLeast(1)
            val height = face.height().roundToInt().coerceAtLeast(1)
            val animating = travel.isInitialized() && travel.value.isRunning
            if (!animating) {
                val params = glass.layoutParams
                if (params.width != width || params.height != height) {
                    params.width = width
                    params.height = height
                    glass.layoutParams = params
                }
            }
            if (glass.width != width || glass.height != height) {
                glass.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                glass.layout(0, 0, width, height)
            }
            (glass.parent as View).getLocationOnScreen(location)
            glass.translationX = face.left - location[0] - glass.left
            glass.translationY = face.top - location[1] - glass.top
            glyphProgress = progress.coerceIn(0f, 1f)
            glass.alpha = alpha * lerp(entryOpacity[part] ?: 0f, 1f, glyphProgress)
            glass.visibility = if (glass.alpha > 0f) View.VISIBLE else View.INVISIBLE
            if (paired) positionMoreGlyph(current.buttons.getValue(Action.MORE).geometry.glyph, drawnMore)
            entryScale = if (paired) 1f else .84f + .16f * progress.coerceIn(0f, 1.08f)
            applyScale()
            val heightDp = height / glass.resources.displayMetrics.density
            if (previousConfig != bottom.config || material.heightDp != heightDp) {
                previousConfig = bottom.config
                material = material.copy(heightDp = heightDp)
                glass.refreshAdapterDerivedConfig()
            }
            if (animating) glass.invalidate()
        }

        fun fade(alpha: Float) {
            glass.alpha = if (part == Part.MENU && menuHandedOff) 0f else alpha
            glass.visibility = if (glass.alpha > 0f) View.VISIBLE else View.INVISIBLE
            ambient.refresh(glass.isDarkGlass(), 1f)
        }

        private fun applyScale() {
            val scale = entryScale * (1f + .06f * pressure.coerceIn(-.2f, 1.2f))
            glass.scaleX = scale
            glass.scaleY = scale
            ambient.refresh(glass.isDarkGlass(), 1f)
        }

        fun press(x: Float, y: Float) {
            light.shader = RadialGradient(x - face.left, y - face.top,
                glass.height.coerceAtLeast(1).toFloat(), 0x65ffffff, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            glass.removeCallbacks(endPress)
            press.value.animateTo(1f)
        }

        fun releasePress(delay: Long) {
            glass.removeCallbacks(endPress)
            if (delay > 0) glass.postDelayed(endPress, delay) else endPress.run()
        }

        private fun drawContent(canvas: Canvas, frame: AdapterNavigationFrame) {
            val controls = binding?.buttons ?: return
            val intensity = (pressure * bottom.config.darkBarHighlightStrength).coerceIn(0f, 1f)
            if (intensity > 0f) {
                val save = canvas.save()
                canvas.clipPath(glass.clipPath)
                light.alpha = (intensity * 255f).roundToInt()
                canvas.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), light)
                canvas.restoreToCount(save)
            }
            val color = if (frame.darkGlass) Color.WHITE else Color.BLACK
            for (action in actions) {
                val glyph = controls.getValue(action).geometry.glyph
                when (action) {
                    Action.BACK -> {
                        glyphPosition.set(glyph)
                        drawGlyph(canvas, back, glyphPosition, color)
                    }
                    Action.NEW_PLAYLIST -> {
                        glyphPosition.set(glyph)
                        drawGlyph(canvas, create, glyphPosition, color)
                    }
                    Action.MORE -> drawGlyph(canvas, more, drawnMore, color)
                    Action.FILTER -> {
                        var opacity = 1f
                        if (paired) {
                            val moreGlyph = controls.getValue(Action.MORE).geometry.glyph
                            val x = lerp((moreGlyph.centerX() - glyph.width() / 2).toFloat(),
                                glyph.left.toFloat(), glyphProgress)
                            val y = drawnMore.centerY() - glyph.height() / 2f
                            glyphPosition.set(x, y, x + glyph.width(), y + glyph.height())
                            opacity = ((glyphProgress - .30f) / .70f).coerceIn(0f, 1f)
                        } else glyphPosition.set(glyph)
                        drawGlyph(canvas, filterOff, glyphPosition, color, opacity * (1f - filterMix))
                        drawGlyph(canvas, filterOn, glyphPosition, filterColor ?: color, opacity * filterMix)
                    }
                }
            }
        }

        fun menuFrame(glyph: Rect): MenuFrame =
            MenuFrame(RectF(face), RectF(drawnMore.takeUnless { it.isEmpty } ?: RectF(glyph)))

        private fun positionMoreGlyph(glyph: Rect, position: RectF) {
            val origin = menuEntry?.glyph
            position.set(glyph)
            if (origin != null) {
                val extent = progress.coerceIn(0f, 1.08f)
                position.set(lerp(origin.left, position.left, extent), lerp(origin.top, position.top, extent),
                    lerp(origin.right, position.right, extent), lerp(origin.bottom, position.bottom, extent))
            }
        }

        private fun drawGlyph(canvas: Canvas, icon: Drawable?, glyph: RectF, tint: Int, alpha: Float = 1f) {
            if (icon == null || alpha <= 0f) return
            val left = (glyph.left - face.left).roundToInt()
            val top = (glyph.top - face.top).roundToInt()
            icon.setLayoutDirection(binding?.toolbar?.layoutDirection ?: View.LAYOUT_DIRECTION_LTR)
            icon.setBounds(left, top, left + glyph.width().roundToInt(), top + glyph.height().roundToInt())
            icon.setTint(tint)
            icon.alpha = (255f * alpha).roundToInt()
            icon.draw(canvas)
        }

        fun close() {
            glass.removeCallbacks(endPress)
            if (press.isInitialized()) press.value.cancel()
            (glass.parent as? ViewGroup)?.removeView(glass)
        }
    }

    private fun lerp(from: Float, to: Float, progress: Float) = from + (to - from) * progress
}

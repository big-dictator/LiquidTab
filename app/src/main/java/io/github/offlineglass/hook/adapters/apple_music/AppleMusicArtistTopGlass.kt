package io.github.offlineglass.hook.adapters.apple_music

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.SpringFloat
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.apple_music.AppleMusicArtistGlass.Role
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs

/** The artist toolbar hosts a native BlurView below each Compose icon. */
internal class AppleMusicArtistTopGlass(
    private val bottom: GlassHostLayout, private val tintFraction: () -> Float,
    private val albumDetail: () -> Boolean,
    private val curator: () -> Boolean,
) {
    private val blurId = id("option_button_blur")
    private var items = emptyMap<Role, Button>()
    val size get() = items.size
    private val point = IntArray(2)
    private val bar = IntArray(2)
    private val suppressed = mutableSetOf<View>()

    fun suppressGroupedNativeBackdrop(owner: View, anchor: Anchor?) {
        if (anchor == null) return
        val native = nativeViews(owner).singleOrNull { view ->
            view.getLocationOnScreen(point)
            abs(point[0] + view.width / 2 - anchor.bounds.centerX()) <= 3 &&
                abs(point[1] + view.height / 2 - anchor.bounds.centerY()) <= 3
        } ?: return
        val draw = native.javaClass.getDeclaredMethod("draw", Canvas::class.java)
        if (suppressionHooks.add(draw)) XposedBridge.hookMethod(draw, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val toolbar = suppressedDraws[param.thisObject] ?: return
                if (toolbar.bottom.config.enabled && toolbar.bottom.config.liquidGlassEnabled) {
                    param.result = null
                }
            }
        })
        if (suppressed.add(native)) {
            suppressedDraws[native] = this
            native.invalidate()
        }
    }
    data class Anchor(val bounds: Rect, val placement: AppleMusicArtistCompose.Placement?,
        val exterior: AppleMusicArtistCompose.PressLayer? = null,
        val faceDraws: List<Pair<Any, AppleMusicArtistCompose.Draw>> = emptyList())

    fun pressBack(x: Float, y: Float) = items[Role.BACK]?.press(x, y)
    fun releasePress(delay: Long) { items[Role.BACK]?.releasePress(delay) }

    fun bind(owner: View, content: ViewGroup, anchors: Map<Role, Anchor>): Set<Role> {
        val views = nativeViews(owner)
        val next = LinkedHashMap<Role, Button>()
        for ((role, anchor) in anchors) {
            val native = views.singleOrNull { view ->
                view.getLocationOnScreen(point)
                abs(point[0] + view.width / 2 - anchor.bounds.centerX()) <= 3 &&
                    abs(point[1] + view.height / 2 - anchor.bounds.centerY()) <= 3
            } ?: continue
            // The artist is a Compose destination above the tab fragments. A
            // global blur_target lookup can return the Search page underneath it.
            // Follow this BlurView's own controller to its actual native source.
            val blur = nativeBlur(native) ?: continue
            val scene = blur.scene
            val previous = items[role]
            val same = previous?.view === native && previous.scene === scene &&
                previous.placement?.target === anchor.placement?.target && previous.exterior?.layout === anchor.exterior?.layout
            if (same) {
                val retained = requireNotNull(previous)
                retained.updateFaceDraws(anchor.faceDraws)
                next[role] = retained
            }
            else {
                previous?.close()
                val button = Button(native, blur, anchor.placement, anchor.exterior, owner)
                if (button.attach()) {
                    button.updateFaceDraws(anchor.faceDraws)
                    next[role] = button
                } else button.close()
            }
        }
        for ((role, previous) in items) {
            if (role in next) continue
            if (previous.view.isAttachedToWindow && previous.scene.isAttachedToWindow &&
                generateSequence(previous.view as View) { it.parent as? View }.any { it === owner }) {
                next[role] = previous
            } else previous.close()
        }
        items = next
        refresh()
        return next.keys
    }

    fun refresh() {
        if (items.isEmpty()) return
        bottom.getLocationOnScreen(bar)
        val more = items[Role.MORE]
        val left = items[Role.BACK]
        if (more != null && left != null && bottom.width > 0 && bottom.isShown) {
            // Both right-side buttons use the More platter as their shared edge.
            more.view.getLocationOnScreen(point)
            val moreCenter = point[0] + more.view.width / 2
            val rightOfScreen = moreCenter > bar[0] + bottom.width / 2
            val rightDelta = if (rightOfScreen) bar[0] + bottom.width - (point[0] + more.view.width)
                else bar[0] - point[0]
            val rightOffset = rightDelta + more.appliedOffset
            more.move(rightOffset)
            items[Role.SHARE]?.move(rightOffset)
            left.view.getLocationOnScreen(point)
            val leftDelta = if (rightOfScreen) bar[0] - point[0]
                else bar[0] + bottom.width - (point[0] + left.view.width)
            left.move(leftDelta + left.appliedOffset)
        } else if (curator() && left != null && bottom.width > 0 && bottom.isShown) {
            left.view.getLocationOnScreen(point)
            val start = if (left.view.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                bar[0] + bottom.width - point[0] - left.view.width else bar[0] - point[0]
            left.move(start + left.appliedOffset)
        }
        items.values.forEach(Button::refresh)
    }

    fun invalidateTint() = items.values.forEach { it.panel?.invalidate() }

    fun restoreOptics() = items.values.forEach { it.panel?.restoreOpticalSceneForAdapter() }

    fun clear() {
        for (view in suppressed) {
            if (suppressedDraws[view] === this) suppressedDraws.remove(view)
            view.invalidate()
        }
        suppressed.clear()
        items.values.forEach(Button::close)
        items = emptyMap()
    }

    private fun nativeViews(owner: View): List<FrameLayout> {
        if (blurId == 0) return emptyList()
        val pending = ArrayDeque<View>()
        val found = ArrayList<FrameLayout>(3)
        pending.add(owner)
        var budget = 72
        while (pending.isNotEmpty() && budget-- > 0) {
            val view = pending.removeFirst()
            if (!view.isShown) continue
            if (view is FrameLayout && view.id == blurId && view.width > 0 && view.height > 0 &&
                view.javaClass.name == "eightbitlab.com.blurview.BlurView" && view.childCount <= 1
            ) {
                found.add(view)
            } else if (view is ViewGroup) {
                for (i in 0 until minOf(view.childCount, 20)) pending.add(view.getChildAt(i))
            }
        }
        return if (pending.isEmpty()) found else emptyList()
    }

    private fun id(name: String) = bottom.resources.getIdentifier(name, "id", targetSpec.packageName)

    private data class NativeBlur(val scene: View, val controller: Any, val autoUpdate: Method)

    private fun nativeBlur(view: FrameLayout): NativeBlur? = runCatching {
        view.javaClass.declaredFields.mapNotNull { field ->
            val contract = field.type
            if (!contract.isInterface || contract.methods.none {
                it.name == "destroy" && it.parameterCount == 0
            }) return@mapNotNull null
            val autoUpdate = contract.methods.singleOrNull {
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                    it.returnType == contract
            } ?: return@mapNotNull null
            field.isAccessible = true
            val controller = field.get(view) ?: return@mapNotNull null
            val members = controller.javaClass.declaredFields.onEach { it.isAccessible = true }
            if (members.count { it.type.isInstance(view) && it.get(controller) === view } != 1)
                return@mapNotNull null
            if (members.count { android.view.ViewTreeObserver.OnPreDrawListener::class.java
                    .isAssignableFrom(it.type) } != 1) return@mapNotNull null
            val scene = members.singleOrNull { it.type.name == "eightbitlab.com.blurview.BlurTarget" }
                ?.get(controller) as? View ?: return@mapNotNull null
            if (!scene.isAttachedToWindow || scene.rootView !== view.rootView) return@mapNotNull null
            NativeBlur(scene, controller, autoUpdate.apply { isAccessible = true })
        }.singleOrNull()
    }.getOrNull()
    private inner class Button(
        val view: FrameLayout, private val blur: NativeBlur, val placement: AppleMusicArtistCompose.Placement?,
        val exterior: AppleMusicArtistCompose.PressLayer?,
        owner: View,
    ) {
        private val face = RectF()
        private val ambient = AppleMusicControlAmbient(owner, view)
        val scene get() = blur.scene
        private var lastTint = Float.NaN
        var panel: GlassHostLayout? = null
            private set
        var offset = 0
            private set
        var appliedOffset = 0
            private set
        private var lastConfig: GlassConfig? = null
        private var lastDark: Boolean? = null
        private var material = TopBarMaterialPolicy(TopBarRole.ACTION, iconOnly = true, selectedAccent = false)
        private val enabled get() = panel?.isAttachedToWindow == true && view.isShown &&
            bottom.config.enabled && bottom.config.liquidGlassEnabled
        private val ownsFace = {
            enabled && panel?.let { it.width == view.width && it.height == view.height && it.width > 0 } == true
        }
        private var faceDraws = emptyList<Pair<Any, AppleMusicArtistCompose.Draw>>()
        private var pressure = 0f
        private val pressScale get() = 1f + .08f * pressure.coerceIn(-.2f, 1.2f)
        private val pressLight = Paint(Paint.ANTI_ALIAS_FLAG)
        private val spring = lazy {
            SpringFloat(0f, 300f, .5f, .001f) { value, _ ->
                pressure = value
                exterior?.invalidate?.invoke()
                view.invalidate()
                refreshAmbient()
            }
        }
        private val endPress = Runnable { if (spring.isInitialized()) spring.value.animateTo(0f) }
        private val scalePlatter: (Canvas) -> Int = scale@{ canvas ->
            val parent = exterior?.parentBounds?.invoke() ?: return@scale -1
            if (!enabled || pressure == 0f) return@scale -1
            panelBounds()
            val save = canvas.save()
            canvas.scale(pressScale, pressScale, face.centerX() - parent.left, face.centerY() - parent.top)
            save
        }

        fun updateFaceDraws(draws: List<Pair<Any, AppleMusicArtistCompose.Draw>>) {
            if (faceDraws == draws) return
            AppleMusicArtistOptics.releaseNativeFaces(faceDraws, ownsFace)
            AppleMusicArtistOptics.bindNativeFaces(draws, ownsFace)
            faceDraws = draws
            exterior?.invalidate?.invoke()
        }

        private fun panelBounds() {
            view.getLocationOnScreen(point)
            face.set(point[0].toFloat(), point[1].toFloat(),
                (point[0] + view.width).toFloat(), (point[1] + view.height).toFloat())
        }

        fun press(x: Float, y: Float) {
            if (!enabled) return
            panelBounds()
            if (!face.contains(x, y)) return
            pressLight.shader = RadialGradient(x - face.left, y - face.top, view.height.toFloat(),
                0x50ffffff, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            view.removeCallbacks(endPress)
            exterior?.let { AppleMusicArtistOptics.bindNativePress(it, scalePlatter) }
            spring.value.animateTo(1f)
        }

        fun releasePress(delay: Long) {
            if (!spring.isInitialized()) return
            view.removeCallbacks(endPress)
            if (delay > 0) view.postDelayed(endPress, delay) else endPress.run()
        }

        private fun drawPressLight(canvas: Canvas) {
            val amount = pressure * curatorHighlightStrength(bottom.config.darkBarHighlightStrength)
            if (amount <= .001f) return
            pressLight.alpha = (255 * amount.coerceAtMost(1f)).toInt()
            canvas.drawOval(0f, 0f, view.width.toFloat(), view.height.toFloat(), pressLight)
        }

        fun attach(): Boolean = runCatching {
            val draw = view.javaClass.getDeclaredMethod("draw", Canvas::class.java)
            if (drawHooks.add(draw)) XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val target = bound[param.thisObject] ?: return
                    if (!target.enabled) return
                    val canvas = param.args[0] as Canvas
                    if (!canvas.isHardwareAccelerated) return
                    if (capture.get() == true) {
                        param.result = null
                        return
                    }
                    val glass = target.panel ?: return
                    if (glass.width != target.view.width || glass.height != target.view.height) return
                    capture.set(true)
                    try {
                        glass.draw(canvas)
                        target.drawPressLight(canvas)
                        param.result = null
                    } finally { capture.remove() }
                }
            })
            placement?.let { move ->
                AppleMusicArtistOptics.installPlacement(move) {
                    (offset.takeIf { enabled } ?: 0).also { appliedOffset = it }
                }
            }
            panel = createPanel()
            bound[view] = this
            val update = blur.controller.javaClass.getMethod(blur.autoUpdate.name, Boolean::class.javaPrimitiveType)
            if (autoUpdateHooks.add(update)) XposedBridge.hookMethod(update, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (controllers[param.thisObject]?.enabled == true) param.args[0] = false
                }
            })
            controllers[blur.controller] = this
            blur.autoUpdate.invoke(blur.controller, false)
            ambient.attach()
            view.invalidate()
            true
        }.getOrElse {
            ArtistDiagnostics.log("top platter binding rejected ${it.message}")
            false
        }

        fun move(wanted: Int) {
            val nativePlacement = placement ?: return
            if (abs(wanted) > 32f * view.resources.displayMetrics.density || offset == wanted) return
            offset = wanted
            runCatching { nativePlacement.request() }
        }

        fun refresh() {
            val tint = tintFraction()
            val dark = bottom.isDarkGlass()
            refreshAmbient()
            if (lastTint != tint || dark != lastDark) {
                lastTint = tint
                lastDark = dark
                panel?.invalidate()
                view.invalidate()
            }
            if (lastConfig != bottom.config) {
                lastConfig = bottom.config
                material = material.copy(heightDp = view.height / view.resources.displayMetrics.density)
                panel?.refreshAdapterDerivedConfig()
                view.invalidate()
            }
        }

        private fun refreshAmbient() {
            panelBounds()
            ambient.refresh(face.takeIf { enabled }, bottom.isDarkGlass(),
                if (exterior != null) pressScale else 1f, panel?.clipPath?.takeUnless { it.isEmpty })
        }

        private fun panelConfig(): GlassConfig {
            val config = material.apply(bottom.config)
            return if (curator()) config.copy(
                darkBarHighlightStrength = curatorHighlightStrength(config.darkBarHighlightStrength)
            ) else if (albumDetail()) config.copy(
                darkBarHighlightStrength = albumHighlightStrength(config.darkBarHighlightStrength)
            ) else config
        }

        private fun createPanel(): GlassHostLayout {
            val state = object : AppNavigationState {
                override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = view.isShown
                override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = view.isShown
                override fun allowsHostTouch() = false
                override fun drawsNavigationContent() = false
                override fun drawsPanelAmbientShadow() = false
                override fun surfaceTintFraction() = tintFraction()
                override val allowsSoftwareBackdropCapture = false
                override fun resolveAutoDarkMode(host: View) = bottom.isDarkGlass()
                override fun resolveBackdropScene(host: View): View = scene
            }
            val adapter = object : TargetAdapter {
                override val key = "apple_music_artist_platter"
                override val ownsHostLayout = true
                override val skipNavigationSnapshot = true
                override val skipBackdropStableDelay = true
                override val allowsSystemBackgroundBlur = false
                override val allowRenderNodeRefresh = true
                override fun normalizeConfig(config: GlassConfig) = panelConfig()
                override fun pushedConfig() = panelConfig()
                override fun createNavigationState(context: Context) = state
            }
            material = material.copy(heightDp = view.height / view.resources.displayMetrics.density)
            return GlassHostLayout(view.context, panelConfig(), 1,
                navigationSource = null, sliderEnabled = false, adapterOverride = adapter).apply {
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                view.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT))
            }
        }

        fun close() {
            view.removeCallbacks(endPress)
            if (spring.isInitialized()) spring.value.cancel()
            exterior?.let { AppleMusicArtistOptics.releaseNativePress(it, scalePlatter) }
            updateFaceDraws(emptyList())
            ambient.close()
            if (bound[view] === this) bound.remove(view)
            if (controllers[blur.controller] === this) {
                controllers.remove(blur.controller)
                runCatching { blur.autoUpdate.invoke(blur.controller, true) }
            }
            placement?.let(AppleMusicArtistOptics::releasePlacement)
            panel?.let(view::removeView)
            panel = null
            view.invalidate()
        }
    }

    companion object {
        private val autoUpdateHooks = HashSet<Method>()
        private val controllers = WeakHashMap<Any, AppleMusicArtistTopGlass.Button>()
        private val suppressionHooks = HashSet<Method>()
        private val suppressedDraws = WeakHashMap<Any, AppleMusicArtistTopGlass>()
        private val drawHooks = HashSet<Method>()
        private val bound = WeakHashMap<Any, AppleMusicArtistTopGlass.Button>()
        private val capture = ThreadLocal<Boolean>()
    }
}

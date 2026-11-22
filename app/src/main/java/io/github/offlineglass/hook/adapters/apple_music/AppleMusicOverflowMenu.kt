package io.github.offlineglass.hook.adapters.apple_music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import io.github.offlineglass.BuildConfig
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.math.abs
import kotlin.math.roundToInt

/** The popup window keeps native input and dismissal. Its pixels are drawn over the activity backdrop. */
internal class AppleMusicOverflowMenu(private val bottom: GlassHostLayout) {
    private data class Row(val label: String, val top: Int)
    private class Menu(val root: ViewGroup, val rows: List<Row>) {
        var recordedOptics = false
    }
    private data class PopupFields(val owner: Field, val params: Field)

    private fun label(name: String): String? {
        val res = bottom.resources
        val id = res.getIdentifier(name, "string", targetSpec.packageName)
        return id.takeIf { it != 0 }?.let(res::getString)
    }

    private val settings = label("settings")
    private val systemLabels = setOfNotNull(settings, label("account_settings"), label("sign_in"),
        label("settings_user_debug_settings"))
    private val libraryIcons: Map<String, Drawable> = listOf(
        "new_playlist_button" to "toolbar_new_playlist",
        "library_edit_bottom_sheet_edit_pins_item" to "actionsheet_pin",
        "library_edit_bottom_sheet_edit_categories" to "actionsheet_line4horizontal",
    ).mapNotNull { (name, icon) ->
        val text = label(name) ?: return@mapNotNull null
        val drawable = bottom.resources.getIdentifier(icon, "drawable", targetSpec.packageName)
        if (drawable == 0) null else bottom.context.getDrawable(drawable)?.mutate()?.let { text to it }
    }.toMap()
    private val rowLabels = systemLabels + libraryIcons.keys
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val pos = IntArray(2)
    private val parentPos = IntArray(2)
    private var menu: Menu? = null
    private var rows: List<Row> = emptyList()
    private var panel: GlassHostLayout? = null
    private var panelAmbient: AppleMusicPanelAmbient? = null
    private var showing = false
    private var scope: ViewGroup? = null
    private var anchorOwner: View? = null
    private var anchor: Rect? = null
    private var windowToken: IBinder? = null
    private var material = TopBarMaterialPolicy(TopBarRole.MENU, selectedAccent = false)
    private var appliedConfig: GlassConfig? = null
    private val popupFocus = ViewTreeObserver.OnWindowFocusChangeListener { panel?.refreshOpticalWindowFocus() }
    private val probeFile = if (BuildConfig.DEBUG) {
        bottom.context.getExternalFilesDir(null)?.let { File(it, "liquidtab-menu-probe.txt") }
            ?.takeIf(File::isFile)
    } else null

    init { trace("process=${android.os.Process.myPid()} menu renderer initialized") }

    private fun trace(message: String) {
        val file = probeFile ?: return
        runCatching {
            if (file.length() < 8192L) file.appendText("${SystemClock.uptimeMillis()} $message\n")
        }
    }
    private val popupListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) {
            if (menu?.root === view) close(animate = true)
        }
    }

    fun update(scope: ViewGroup?, anchorOwner: View?, anchor: Rect?) {
        this.scope = scope
        this.anchorOwner = anchorOwner
        this.anchor = anchor
        val token = scope?.applicationWindowToken
        if (windowToken != token) {
            windowToken?.let(sessions::remove)
            windowToken = token
            if (token != null) {
                sessions[token] = WeakReference(this)
                installWindowHook().onFailure {
                    trace("window hook error=${it.message}")
                    XposedBridge.log("[OfflineGlass][AppleMusic] native menu window hook: $it")
                }
            }
        }
        if (scope == null) {
            close(animate = false)
            return
        }
        menu?.let { current ->
            if (current.root.isAttachedToWindow && current.root.isShown) place(current) else close(animate = true)
        }
    }

    private fun watchPopup(root: View, params: WindowManager.LayoutParams) {
        val openingScope = scope ?: return
        trace("subpanel added")
        if (root.id != android.R.id.content || root !is ViewGroup) return
        val fields = popupFields(root.javaClass) ?: return
        val source = runCatching {
            (fields.owner.get(root) as? View)?.takeIf {
                fields.params.get(root) === params && it.applicationWindowToken == windowToken
            }
        }.getOrNull() ?: return
        if (!generateSequence(source) { it.parent as? View }.any { it === openingScope }) return
        trace("subpanel owner matched")
        // The native popup keeps the originating Compose owner. Follow the
        // current anchor if that owner finishes rebinding after insertion.
        val openingAnchor = anchor?.takeIf { source === anchorOwner }?.let(::Rect)

        var deadline = 0L
        var masked = false
        var bound = false
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val current = menu
                if (bound && current?.root === root) {
                    place(current)
                    return true
                }
                if (bound || scope !== openingScope || current != null) {
                    stop()
                    return true
                }
                val now = SystemClock.uptimeMillis()
                if (deadline == 0L) deadline = now + 300L
                val density = root.resources.displayMetrics.density
                val sized = root.width >= 104f * density && root.height >= 48f * density
                if (sized && root.childCount == 1 && !installPopupDrawHook(root)) {
                    stop()
                    return true
                }
                val rows = if (sized) resolveRows(root) else null
                if (rows != null) {
                    bound = open(Menu(root, rows))
                    if (bound) ArtistDiagnostics.log("overflow menu bound rows=${rows.size} owner=${System.identityHashCode(source)}")
                    if (!bound) stop()
                } else if (now >= deadline) {
                    stop()
                    trace("menu semantics timed out")
                    if (masked) XposedBridge.log("[OfflineGlass][AppleMusic] popup menu rows unavailable before first draw")
                } else {
                    // When the parent is the bound platter's Compose owner,
                    // also cover the window while its semantics are being laid out.
                    val currentAnchor = openingAnchor ?: anchor?.takeIf { source === anchorOwner }
                    if (sized && currentAnchor != null) {
                        root.getLocationOnScreen(pos)
                        val aligned = abs(pos[0] + root.width - currentAnchor.right) <= 24f * density &&
                            abs(pos[1] - currentAnchor.bottom) <= 24f * density
                        if (aligned) {
                            setDrawMasked(root, true)
                            masked = true
                        }
                    }
                    if (currentAnchor != null || source === anchorOwner) root.postInvalidateOnAnimation() else stop()
                }
                return true
            }

            private fun stop() {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                if (!bound && masked) setDrawMasked(root, false)
            }
        }
        root.viewTreeObserver.addOnPreDrawListener(listener)
    }

    private fun open(candidate: Menu): Boolean {
        if (!place(candidate)) return false
        menu = candidate
        candidate.root.addOnAttachStateChangeListener(popupListener)
        candidate.root.viewTreeObserver.addOnWindowFocusChangeListener(popupFocus)
        setDrawMasked(candidate.root, true)
        trace("menu bound rows=${candidate.rows.size}; native window input retained")
        val glass = checkNotNull(panel)
        glass.animate().cancel()
        glass.pivotX = glass.width.toFloat()
        glass.pivotY = 0f
        glass.alpha = 0f
        glass.scaleX = 0.88f
        glass.scaleY = 0.82f
        glass.restoreOpticalSceneForAdapter()
        glass.refreshOpticalWindowFocus()
        glass.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220L).setInterpolator(easing).setUpdateListener { refreshPanelFrame(glass) }
            .withEndAction { glass.animate().setUpdateListener(null) }.start()
        return true
    }

    private fun resolveRows(root: View): List<Row>? {
        val childMethod = childId ?: return null
        val group = root as? ViewGroup ?: return null
        val owner = group.getChildAt(0) ?: return null
        val provider = owner.accessibilityNodeProvider ?: return null
        val pending = ArrayDeque<Pair<Int, Int?>>().apply { add(View.NO_ID to null) }
        val visited = HashSet<Int>()
        val actions = HashSet<Int>()
        val labeledActions = HashSet<Int>()
        val items = ArrayList<Row>()
        while (pending.isNotEmpty() && visited.size < 64) {
            val (id, parentAction) = pending.removeFirst()
            if (!visited.add(id)) continue
            val node = provider.createAccessibilityNodeInfo(id) ?: continue
            val action = if (node.isVisibleToUser && node.isEnabled && node.isClickable &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            ) {
                actions.add(id)
                id
            } else parentAction
            val text = node.text?.toString()
            if (node.isVisibleToUser && text != null && text in rowLabels && action != null) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (!bounds.isEmpty) {
                    if (!labeledActions.add(action)) return null
                    items.add(Row(text, bounds.top))
                }
            }
            if (node.childCount > 12) return null
            for (i in 0 until node.childCount) {
                val packed = runCatching { childMethod.invoke(node, i) as Long }.getOrNull() ?: return null
                pending.add((packed shr 32).toInt() to action)
            }
        }
        return items.sortedBy(Row::top).takeIf {
            val labels = it.map(Row::label)
            val settingsIndex = labels.indexOf(settings)
            pending.isEmpty() && actions == labeledActions && labels.distinct().size == labels.size &&
                settingsIndex >= 0 && labels.take(settingsIndex).all(libraryIcons::containsKey) &&
                labels.drop(settingsIndex + 1).all(systemLabels::contains)
        }
    }

    private fun place(current: Menu): Boolean {
        val parent = bottom.rootView.findViewById<FrameLayout>(android.R.id.content) ?: return false
        val glass = panel ?: createPanel(parent).also { panel = it }
        val root = current.root
        root.getLocationOnScreen(pos)
        parent.getLocationOnScreen(parentPos)
        showing = true
        rows = current.rows
        if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE
        val inset = (8f * root.resources.displayMetrics.density).roundToInt()
        val height = (root.height - 2 * inset).coerceAtLeast(1)
        val params = glass.layoutParams as FrameLayout.LayoutParams
        if (params.width != root.width || params.height != height) {
            params.width = root.width
            params.height = height
            glass.layoutParams = params
            glass.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            glass.layout(0, 0, root.width, height)
        }
        glass.translationX = (pos[0] - parentPos[0] - glass.left).toFloat()
        glass.translationY = (pos[1] + inset - parentPos[1] - glass.top).toFloat()
        val heightDp = height / root.resources.displayMetrics.density
        if (appliedConfig != bottom.config || material.heightDp != heightDp) {
            appliedConfig = bottom.config
            material = material.copy(heightDp = heightDp)
            glass.refreshAdapterDerivedConfig()
        }
        panelAmbient?.refresh(glass.isDarkGlass(), 1f)

        return true
    }

    private fun refreshPanelFrame(glass: GlassHostLayout) {
        panelAmbient?.refresh(glass.isDarkGlass(), 1f)
        glass.invalidate()
    }

    private fun createPanel(parent: FrameLayout): GlassHostLayout {
        val ambient = AppleMusicPanelAmbient(parent)
        panelAmbient = ambient
        val state = object : AppNavigationState {
            override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = showing
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = showing
            override fun allowsHostTouch() = false
            override fun resolveAutoDarkMode(host: View) = bottom.isDarkGlass()
            override fun drawsPanelAmbientShadow() = false
            override fun drawAdditionalAmbientShadow(canvas: android.graphics.Canvas, dark: Boolean, alphaScale: Float) {
                ambient.refresh(dark, alphaScale)
            }
            override fun focusedOpticalWindow(host: View) = menu?.root
            override fun pauseBackdropWork(host: View) = !showing
            override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> =
                (0 until hostIndex).map(content::getChildAt)
                    .filterNot { it is GlassHostLayout || it is AdapterOwnedOverlay }

            override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
                val current = menu ?: return
                val glass = panel ?: return
                if (current.recordedOptics || !glass.liveBackdropActiveForAdapter || !current.root.hasWindowFocus()) return
                current.recordedOptics = true
                trace("hardware scene live=true " +
                    "refraction=${glass.outerRefractionActiveForAdapter} blur=${glass.config.blurRadius}dp")
            }

            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
                val foreground = if (frame.darkGlass) Color.WHITE else Color.BLACK
                val rtl = bottom.layoutDirection == View.LAYOUT_DIRECTION_RTL
                paint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
                paint.textSize = 18f * frame.scaledDensity
                val baselineOffset = -(paint.ascent() + paint.descent()) / 2f
                val rowHeight = 48f * frame.density
                val settingsIndex = rows.indexOfFirst { it.label == settings }
                val divider = if (settingsIndex > 0) 0.5f * frame.density else 0f
                val top = (frame.height - rows.size * rowHeight - divider) / 2f
                if (divider > 0f) {
                    paint.color = if (frame.darkGlass) 0x33ffffff else 0x33000000
                    val y = top + settingsIndex * rowHeight
                    canvas.drawRect(0f, y, frame.width.toFloat(), y + divider, paint)
                }
                paint.color = foreground
                rows.forEachIndexed { index, row ->
                    val center = top + (index + 0.5f) * rowHeight + if (index >= settingsIndex) divider else 0f
                    val inset = 12f * frame.density
                    val icon = libraryIcons[row.label]
                    val leading = inset + if (icon != null) 36f * frame.density else 0f
                    icon?.let {
                        val size = (24f * frame.density).roundToInt()
                        val x = (if (rtl) frame.width - inset - size else inset).roundToInt()
                        val y = (center - size / 2f).roundToInt()
                        it.setTint(foreground)
                        it.setBounds(x, y, x + size, y + size)
                        it.draw(canvas)
                    }
                    canvas.drawText(row.label, if (rtl) frame.width - leading else leading, center + baselineOffset, paint)
                }
            }
        }
        val adapter = object : TargetAdapter {
            override val key = "apple_music_overflow_menu"
            override val ownsHostLayout = true
            override val ownsNavigationDrawing = true
            override val skipNavigationSnapshot = true
            override val skipBackdropStableDelay = true
            override val allowsSystemBackgroundBlur = false
            override val outerRefractionScale = 1.6f
            override val managesHostSiblingOrder = true
            override fun normalizeConfig(config: GlassConfig) = material.apply(bottom.config)
            override fun pushedConfig() = material.apply(bottom.config)
            override fun createNavigationState(context: Context) = state
        }
        return GlassHostLayout(parent.context, material.apply(bottom.config), 1,
            navigationSource = null, sliderEnabled = false, adapterOverride = adapter).apply {
            visibility = View.GONE
            elevation = 18f * resources.displayMetrics.density
            // Elevation is for stacking. The tracked overlay draws the shadow.
            outlineProvider = null
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            ambient.bind(this)
            parent.addView(this, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        }
    }

    private fun close(animate: Boolean) {
        val current = menu
        menu = null
        if (current != null) {
            current.root.removeOnAttachStateChangeListener(popupListener)
            current.root.viewTreeObserver.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(popupFocus)
            setDrawMasked(current.root, false)
        }
        val glass = panel ?: return
        glass.refreshOpticalWindowFocus()
        if (!animate) {
            glass.animate().cancel()
            glass.animate().setUpdateListener(null)
            showing = false
            glass.visibility = View.GONE
        } else {
            glass.animate().cancel()
            glass.animate().alpha(0f).scaleX(0.88f).scaleY(0.82f)
                .setDuration(150L).setInterpolator(easing).setUpdateListener { refreshPanelFrame(glass) }.withEndAction {
                    if (menu == null) {
                        glass.animate().setUpdateListener(null)
                        showing = false
                        glass.visibility = View.GONE
                    }
                }.start()
        }
    }

    fun restoreOptics() = panel?.restoreOpticalSceneForAdapter()

    fun dispose() {
        close(animate = false)
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panel = null
        panelAmbient = null
        appliedConfig = null
        windowToken?.let(sessions::remove)
        windowToken = null
        scope = null
        anchorOwner = null
        anchor = null
    }

    private companion object {
        private val sessions = HashMap<IBinder, WeakReference<AppleMusicOverflowMenu>>()
        private val popupContracts = HashMap<Class<*>, PopupFields?>()
        private val maskedPopups = java.util.WeakHashMap<View, Unit>()
        private val drawHooks = HashSet<java.lang.reflect.Method>()

        private fun setDrawMasked(root: View, masked: Boolean) {
            val changed = if (masked) maskedPopups.put(root, Unit) == null
                else maskedPopups.remove(root) != null
            if (changed) (root as? ViewGroup)?.getChildAt(0)?.invalidate()
        }

        private fun installPopupDrawHook(root: ViewGroup): Boolean = runCatching {
            if (root.childCount != 1) return false
            val owner = root.getChildAt(0)
            OverflowDraw.abi(owner)
            val draw = owner.javaClass.getDeclaredMethod("dispatchDraw", Canvas::class.java)
            if (draw !in drawHooks) {
                XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                    private val saves = java.util.IdentityHashMap<MethodHookParam, Int>()
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val owner = param.thisObject as? View ?: return
                        val popup = owner.parent as? View ?: return
                        if (!maskedPopups.containsKey(popup)) return
                        // Keep Compose's draw-time layout work and native input active.
                        val canvas = param.args[0] as Canvas
                        saves[param] = canvas.save()
                        canvas.clipRect(0, 0, 0, 0)
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val count = saves.remove(param) ?: return
                        (param.args[0] as Canvas).restoreToCount(count)
                    }
                })
                drawHooks.add(draw)
            }
            true
        }.getOrDefault(false)

        private var hooked = false

        private fun popupFields(type: Class<*>): PopupFields? = popupContracts.getOrPut(type) {
            val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            val owner = fields.singleOrNull { it.type == View::class.java } ?: return@getOrPut null
            val params = fields.singleOrNull { it.type == WindowManager.LayoutParams::class.java }
                ?: return@getOrPut null
            PopupFields(owner.apply { isAccessible = true }, params.apply { isAccessible = true })
        }

        fun installWindowHook(): Result<Unit> {
            if (hooked) return Result.success(Unit)
            return runCatching {
                val manager = Class.forName("android.view.WindowManagerGlobal")
                val signature = arrayOf(View::class.java, ViewGroup.LayoutParams::class.java,
                    Display::class.java, Window::class.java, Int::class.javaPrimitiveType)
                // Android's five-argument overload can forward to the one that
                // carries window options. Hook the actual insertion once.
                val add = try {
                    manager.getDeclaredMethod("addView", *signature, Bundle::class.java)
                } catch (_: NoSuchMethodException) {
                    manager.getDeclaredMethod("addView", *signature)
                }
                XposedBridge.hookMethod(add, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val root = param.args[0] as? View ?: return
                        val params = param.args[1] as? WindowManager.LayoutParams ?: return
                        if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL) return
                        sessions[params.token]?.get()?.watchPopup(root, params)
                    }
                })
                hooked = true
            }
        }

        val childId = runCatching {
            AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }
}

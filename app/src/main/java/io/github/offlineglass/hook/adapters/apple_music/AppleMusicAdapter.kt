package io.github.offlineglass.hook.adapters.apple_music

import android.app.Activity
import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.localizeNavigationLabel
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.targets.TargetSpec
import java.util.WeakHashMap

internal object AppleMusicAdapter : TargetAdapter {
    private val toolbars = WeakHashMap<Activity, AppleMusicOverflowGlass>()
    override val allowRenderNodeRefresh = true
    override val key = "apple_music"
    override val ownsNavigationFinding = true
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val managesNavigationSourceAppearance = true
    override val recheckNavigationBindingOnLayout = true
    override val refreshHostGeometryOnRebind = true
    override val hasPerFrameScene = true
    override val updatesSceneDuringTapTransition = true
    override val skipNavigationSnapshot = true
    override val nativeSelectionReliable = true
    override val suppressPlatformElevation = true
    override val usesContentDarkMode = true
    override val usesNavigationOpticalMix = true
    override val foregroundNavigationOverSelection = true
    override val foregroundMaskInsetDp = 0f

    override fun createNavigationState(context: Context, host: GlassHostLayout): AppNavigationState {
        val toolbar = (context as? Activity)?.let { toolbars.getOrPut(it) { AppleMusicOverflowGlass(it) } }
            ?: AppleMusicOverflowGlass(context)
        return AppleMusicNavigationState(host, toolbar)
    }

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        XposedBridge.hookAllMethods(Activity::class.java, "onPostResume", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.javaClass.name !in spec.activityHints || !HookConfigReader.read(activity, spec.packageName).enabled) return
                toolbars.getOrPut(activity) { AppleMusicOverflowGlass(activity) }
                    .watchFirstDraw(activity.window.decorView)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onDestroy", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                toolbars.remove(param.thisObject as? Activity)?.dispose()
            }
        })
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val ids = NavigationIds(root)
        if (ids.navigation == 0 || ids.content == 0) return null
        val pending = ArrayDeque<Pair<View, Int>>()
        pending.add(root to 0)
        var found: NavigationBinding? = null
        var budget = 320
        while (pending.isNotEmpty() && budget-- > 0) {
            val (view, depth) = pending.removeFirst()
            if (view.id == ids.content && view.isShown) {
                val source = (view as? ViewGroup)?.uniqueChild(ids.navigation) as? ViewGroup ?: continue
                if (!source.isShown || source.width <= 0 || source.height <= 0) continue
                val binding = NavigationBinding.resolve(source, ids) ?: continue
                if (found != null) {
                    XposedBridge.log("[OfflineGlass][AppleMusic] multiple matching bottom navigation rows")
                    return null
                }
                found = binding
                continue
            }
            // The main content and player can contain arbitrary feed/UI subtrees.
            if (view is ViewGroup && depth < 8 &&
                view.id != ids.backdrop && view.id != ids.player
            ) {
                for (index in 0 until minOf(view.childCount, 24)) {
                    pending.add(view.getChildAt(index) to depth + 1)
                }
            }
        }
        if (pending.isNotEmpty()) return null
        return found?.let { NavigationCandidate(it.source, it.tabs.size, 1_000) }
    }

    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        NavigationBinding.resolve(navigation) != null
}

private class NavigationIds(view: View) {
    private fun id(view: View, name: String) =
        view.resources.getIdentifier(name, "id", targetSpec.packageName)

    val navigation = id(view, "bottom_nav_view")
    val blur = id(view, "bottom_nav_blur")
    val player = id(view, "player_coordinator")
    val backdrop = id(view, "blur_target")
    val content = id(view, "content_container")
    val icon = id(view, "navigation_bar_item_icon_view")
    val smallLabel = id(view, "navigation_bar_item_small_label_view")
}

internal fun ViewGroup.uniqueChild(id: Int): View? {
    if (id == 0) return null
    var match: View? = null
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        if (child.id != id) continue
        if (match != null) return null
        match = child
    }
    return match
}

private class NavigationBinding(
    val source: ViewGroup,
    val blur: View,
    val content: ViewGroup,
    val scene: ViewGroup,
    private val row: ViewGroup,
    private val menu: Menu,
    val tabs: List<Tab>,
) {
    class Tab(
        val cell: ViewGroup,
        val item: MenuItem,
        val icon: ImageView,
        val smallLabel: TextView,
    ) {
        private var artworkSource: Drawable? = null
        private var artwork: Drawable? = null
        private var artworkTint: Int? = null

        fun drawIcon(canvas: Canvas, color: Int, accented: Boolean, fallbackPaint: Paint) {
            val native = icon.drawable
            if (artworkSource !== native) {
                artworkSource = native
                artwork = native?.constantState?.newDrawable(icon.resources, icon.context.theme)?.mutate()
                artworkTint = null
            }
            val drawable = artwork
            if (drawable == null) {
                fallbackPaint.colorFilter = BlendModeColorFilter(color, BlendMode.SRC_IN)
                val layer = canvas.saveLayer(0f, 0f, icon.width.toFloat(), icon.height.toFloat(), fallbackPaint)
                icon.draw(canvas)
                canvas.restoreToCount(layer)
                return
            }
            drawable.state = if (accented) checkedState else uncheckedState
            if (artworkTint != color) {
                drawable.setTint(color)
                artworkTint = color
            }
            drawable.setBounds(0, 0, icon.width, icon.height)
            drawable.draw(canvas)
        }
    }

    fun isCurrent(): Boolean {
        if (source.parent !== content || blur.parent !== content || scene.parent !== content || row.parent !== source ||
            row.childCount != tabs.size || menu.size() != tabs.size) return false
        return tabs.indices.all { index ->
            val tab = tabs[index]
            row.getChildAt(index) === tab.cell && menu.findItem(tab.cell.id) === tab.item && tab.item.isVisible
        }
    }

    fun selectedIndex(): Int = tabs.indexOfFirst { it.item.isChecked }

    fun isPresented(): Boolean = source.isShown &&
        source.height > 0 && source.translationY < source.height / 2f

    companion object {
        fun resolve(source: ViewGroup, ids: NavigationIds = NavigationIds(source)): NavigationBinding? {
            val content = source.parent as? ViewGroup ?: return null
            if (ids.navigation == 0 || source.id != ids.navigation || content.id != ids.content ||
                content.uniqueChild(ids.player) !is ViewGroup || content.uniqueChild(ids.backdrop) !is ViewGroup ||
                content.uniqueChild(ids.navigation) !== source
            ) return null
            val blur = content.uniqueChild(ids.blur) ?: return null

            // The Material widget's public Menu contract survives private class renames.
            val menu = runCatching {
                val getter = source.javaClass.getMethod("getMenu")
                if (!Menu::class.java.isAssignableFrom(getter.returnType)) return null
                getter.invoke(source) as? Menu
            }.getOrNull() ?: return null
            if (menu.size() !in targetSpec.preferredSlots || source.childCount != 1) return null
            val row = source.getChildAt(0) as? ViewGroup ?: return null
            if (row.childCount != menu.size()) return null
            val itemIds = HashSet<Int>()
            val tabs = List(row.childCount) { index ->
                val cell = row.getChildAt(index) as? ViewGroup ?: return null
                if (!itemIds.add(cell.id) || !cell.hasOnClickListeners()) return null
                val item = menu.findItem(cell.id)?.takeIf { it.isVisible && it.isCheckable } ?: return null
                val icon = cell.findViewById<View>(ids.icon) as? ImageView ?: return null
                val smallLabel = cell.findViewById<View>(ids.smallLabel) as? TextView ?: return null
                Tab(cell, item, icon, smallLabel)
            }
            if (tabs.count { it.item.isChecked } != 1) return null
            val scene = content.uniqueChild(ids.backdrop) as? ViewGroup ?: return null
            return NavigationBinding(source, blur, content, scene, row, menu, tabs)
        }
    }
}

private val checkedState = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked,
    android.R.attr.state_selected)
private val uncheckedState = intArrayOf(android.R.attr.state_enabled)

private class AppleMusicNavigationState(
    private val host: GlassHostLayout,
    private val overflow: AppleMusicOverflowGlass,
) : AppNavigationState {
    private val miniPlayer = AppleMusicMiniPlayerGlass(host) { artist.pageTintFraction }
    private val searchTabId = host.resources.getIdentifier("top_level_search_page", "id", targetSpec.packageName)
    private val compactBar = AppleMusicCompactBar(host, ::drawCompactIcon, ::clickCompact) { artist.pageTintFraction }
    private class IconOrigin(var x: Float, var y: Float, var size: Float, var color: Int)
    private val compactOrigins = HashMap<Int, IconOrigin>(5)
    private val artist = AppleMusicArtistGlass(host)
    private val settings = AppleMusicSettingsGlass(host)
    private val labelPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val iconFallbackPaint = Paint()
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.TRANSPARENT }
    private var edgeGlowDensity = 0f
    private var binding: NavigationBinding? = null
    private var failedSource: ViewGroup? = null
    private var failedAt = 0L
    private var chrome: HiddenChrome? = null
    private var restoreScenePending = false

    private class HiddenChrome(val binding: NavigationBinding) {
        private var sourceAlpha = binding.source.alpha
        private var blurAlpha = binding.blur.alpha
        private val clickable = binding.tabs.map { it.cell.isClickable }

        fun hide() {
            if (binding.source.alpha != 0f) {
                sourceAlpha = binding.source.alpha
                binding.source.alpha = 0f
            }
            if (binding.blur.alpha != 0f) {
                blurAlpha = binding.blur.alpha
                binding.blur.alpha = 0f
            }
            binding.tabs.forEach { if (it.cell.isClickable) it.cell.isClickable = false }
        }

        fun restore() {
            if (binding.source.alpha == 0f) binding.source.alpha = sourceAlpha
            if (binding.blur.alpha == 0f) binding.blur.alpha = blurAlpha
            binding.tabs.forEachIndexed { index, tab ->
                if (!tab.cell.isClickable && tab.item.isEnabled && clickable[index]) {
                    tab.cell.isClickable = true
                }
            }
        }
    }

    private fun restoreChrome() {
        chrome?.restore()
        chrome = null
    }

    override fun suppressRedrawnSource() = false

    override fun onHostPreDraw(
        host: View, source: ViewGroup?, selected: () -> Int, enabled: Boolean, density: Float,
        barHeightPx: Int, surfaceColor: () -> Int,
    ) {
        overflow.attachHost(this.host)
        if (binding?.let { it.source === source && it.isCurrent() } != true) {
            restoreChrome()
            binding = null
            if (source != null && source.isAttachedToWindow && enabled) {
                val now = android.os.SystemClock.uptimeMillis()
                if (source !== failedSource || now - failedAt >= 300L) {
                    binding = NavigationBinding.resolve(source)
                    if (binding == null && source !== failedSource) {
                        XposedBridge.log("[OfflineGlass][AppleMusic] bottom navigation no longer matches its native menu")
                    }
                    failedSource = if (binding == null) source else null
                    failedAt = now
                }
            }
        }
        val current = binding
        if (restoreScenePending && host.hasWindowFocus() && host.windowVisibility == View.VISIBLE && enabled) {
            restoreScenePending = false
            this.host.restoreOpticalSceneForAdapter()
            overflow.restoreOptics()
            miniPlayer.restoreOptics()
            artist.restoreOptics()
        }
        val selectedId = current?.let { it.tabs.getOrNull(it.selectedIndex())?.item?.itemId } ?: View.NO_ID
        val navigationPresented = enabled && current?.isPresented() == true
        overflow.update(current?.content, selectedId, navigationPresented, enabled)
        miniPlayer.update(current?.content?.takeIf { enabled }, navigationPresented,
            if (navigationPresented) compactBar.progress else 0f, compactBar.gap)
        val compactHeight = miniPlayer.compactHeight
        val currentTab = compactTab(false)
        val searchTab = compactTab(true)
        compactBar.scene = resolveBackdropScene(this.host)
        compactBar.prepare(navigationPresented && current.tabs.size == 5 &&
            currentTab != null && searchTab != null &&
            compactHeight > 0 && this.host.width > compactHeight * 4 + compactBar.gap * 2)
        compactBar.layout(compactHeight, currentTab?.item?.title, searchTab?.item?.title)
        artist.update(current?.content, enabled)
        settings.update(host.rootView.takeIf { enabled })
        if (current == null || !enabled || !current.isPresented()) {
            restoreChrome()
            return
        }
        if (current.tabs.size != this.host.adapterSlotCount) {
            this.host.updateAdapterSlotCount(current.tabs.size, current.selectedIndex().coerceAtLeast(0))
        }
        val hidden = chrome ?: HiddenChrome(current).also { chrome = it }
        hidden.hide()
    }

    override fun onHostPreDrawDuringTapTransition(host: View) {
        overflow.attachHost(this.host)
        val current = binding
        val selectedId = current?.let { it.tabs.getOrNull(it.selectedIndex())?.item?.itemId } ?: View.NO_ID
        val enabled = this.host.config.enabled
        overflow.update(current?.content, selectedId, enabled && current?.isPresented() == true, enabled)
    }

    override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?): Boolean =
        binding?.let { it.source === host.adapterNavigationSource && it.isPresented() } == true && chrome != null

    override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int): Int? =
        binding?.takeIf { it.source === source }?.selectedIndex()?.takeIf { it in 0 until slotCount }

    override fun allowsHostTouch(): Boolean = chrome != null && compactBar.progress < .65f

    override fun pauseBackdropWork(host: View) = compactBar.progress >= 1f && host.alpha == 0f

    private fun compactTab(search: Boolean): NavigationBinding.Tab? {
        val current = binding ?: return null
        if (search) return current.tabs.singleOrNull { it.item.itemId == searchTabId }
        return current.tabs.getOrNull(current.selectedIndex())
            ?.takeUnless { it.item.itemId == searchTabId }
            ?: current.tabs.firstOrNull { it.item.itemId != searchTabId }
    }

    private fun clickCompact(search: Boolean) {
        val current = binding?.takeIf { it.isPresented() && it.isCurrent() } ?: return
        val tab = compactTab(search)?.takeIf { it in current.tabs && it.item.isEnabled && it.cell.isEnabled } ?: return
        tab.cell.performClick()
    }

    private fun drawCompactIcon(canvas: Canvas, search: Boolean, x: Float, y: Float,
        sideHeight: Float, fraction: Float) {
        val tab = compactTab(search) ?: return
        val icon = tab.icon
        if (icon.width <= 0 || icon.height <= 0) return
        val origin = compactOrigins[tab.item.itemId]
        val targetSize = minOf(origin?.size ?: (28.8f * host.resources.displayMetrics.density), sideHeight * .65f)
        val size = origin?.let { it.size + (targetSize - it.size) * fraction } ?: targetSize
        val centerX = origin?.let { it.x + (x - it.x) * fraction } ?: x
        val centerY = origin?.let { it.y + (y - it.y) * fraction } ?: y
        val colors = tab.smallLabel.textColors
        val targetColor = colors.getColorForState(if (search) uncheckedState else checkedState, colors.defaultColor)
        val nativeColors = artist.pageTintFraction < 1f
        val checked = if (nativeColors) tab.item.isChecked else !search
        val state = if (checked) checkedState else uncheckedState
        val color = if (nativeColors) (icon.imageTintList ?: colors).getColorForState(state, colors.defaultColor)
            else ColorUtils.setAlphaComponent(origin?.color ?: targetColor, 255)
        val save = canvas.save()
        canvas.translate(centerX - size / 2f, centerY - size / 2f)
        canvas.scale(size / icon.width, size / icon.height)
        tab.drawIcon(canvas, color, checked, iconFallbackPaint)
        canvas.restoreToCount(save)
    }

    override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        val current = binding?.takeIf { it.source === source && it.isCurrent() && it.isPresented() } ?: return false
        val tab = current.tabs.getOrNull(index)?.takeIf { it.item.isEnabled && it.cell.isEnabled } ?: return false
        return tab.cell.performClick()
    }

    // During the handoff the overlay owns the moving glyphs. Disable both the
    // sampled navigation row and foreground cutout until that handoff ends.
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) =
        !compactBar.shapeTransitionActive && artist.pageTintFraction == 1f

    override fun navigationOpticalMixEnabled(): Boolean =
        !compactBar.shapeTransitionActive && artist.pageTintFraction == 1f

    override fun surfaceTintFraction(): Float = artist.pageTintFraction

    override fun selectionOpticalFraction(): Float = compactBar.selectionOpticalFraction

    override fun resolveBackdropScene(host: View): View? = binding?.scene

    override fun backdropSceneBaseColor(scene: View, fallback: Int): Int =
        appleMusicBackdropBase(scene, fallback)

    override fun drawForeground(
        canvas: Canvas, frame: AdapterNavigationFrame, selectionPath: Path, drawSource: () -> Unit,
    ): Boolean {
        val mix = frame.navigationOpticalMix
        if (mix <= 0f || selectionPath.isEmpty) {
            drawSource()
            return true
        }
        val outside = canvas.save()
        canvas.clipOutPath(selectionPath)
        drawSource()
        canvas.restoreToCount(outside)
        if (mix < 1f) {
            val inside = canvas.save()
            canvas.clipPath(selectionPath)
            canvas.saveLayerAlpha(0f, 0f, frame.width.toFloat(), frame.height.toFloat(),
                ((1f - mix) * 255f).toInt())
            drawSource()
            canvas.restoreToCount(inside)
        }
        return true
    }

    override fun drawPanelEdgeGlow(canvas: Canvas, path: Path, density: Float) {
        if (!host.isDarkGlass()) return
        if (edgeGlowDensity != density) {
            edgeGlowDensity = density
            edgeGlowPaint.setShadowLayer(18f * density, 0f, 0f, Color.argb(14, 220, 235, 255))
        }
        val saved = canvas.save()
        canvas.clipOutPath(path)
        canvas.drawPath(path, edgeGlowPaint)
        canvas.restoreToCount(saved)
    }

    override fun drawsPanelAmbientShadow() = !compactBar.ownsMovingIcons

    override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
        val tabs = binding?.tabs ?: return
        val left = compactTab(false)
        val right = compactTab(true)
        frame.enabledIndices.forEachIndexed { visualIndex, index ->
            val tab = tabs.getOrNull(index) ?: return@forEachIndexed
            val center = frame.density * 4f + (visualIndex + 0.5f) * frame.slotWidth
            val icon = tab.icon
            if (icon.width <= 0 || icon.height <= 0) return@forEachIndexed

            val activeWeight = when {
                artist.pageTintFraction < 1f -> if (tab.item.isChecked) 1f else 0f
                frame.hideSelectedForOptics -> 1f
                index == frame.selectedIndex -> 1f - frame.navigationOpticalMix
                else -> 0f
            }
            val colors = tab.smallLabel.textColors
            val normalColor = colors.getColorForState(uncheckedState, colors.defaultColor)
            val activeColor = colors.getColorForState(checkedState, colors.defaultColor)
            val color = ColorUtils.blendARGB(normalColor, activeColor, activeWeight)
            val iconColor = if (artist.pageTintFraction < 1f) {
                icon.imageTintList?.getColorForState(
                    if (tab.item.isChecked) checkedState else uncheckedState, color,
                ) ?: color
            } else color
            labelPaint.typeface = tab.smallLabel.typeface
            labelPaint.color = color
            labelPaint.textSize = frame.textSize * frame.scaledDensity * frame.extraScale
            val gap = if (frame.iconOnly) 0f else 2f * frame.density
            val textHeight = if (frame.iconOnly) 0f else labelPaint.descent() - labelPaint.ascent()
            val iconSize = minOf(
                28.8f * frame.density * frame.iconScale * frame.extraScale,
                frame.slotWidth * 0.8f,
                frame.height - textHeight - gap - 4f * frame.density,
            ).coerceAtLeast(0f)
            val contentHeight = iconSize + gap + textHeight
            val top = (frame.height - contentHeight) / 2f
            if (!frame.hideSelectedForOptics) {
                val origin = compactOrigins.getOrPut(tab.item.itemId) { IconOrigin(0f, 0f, 0f, 0) }
                origin.x = center
                origin.y = top + iconSize / 2f
                origin.size = iconSize
                origin.color = iconColor
            }
            if (!compactBar.ownsMovingIcons || (tab !== left && tab !== right)) {
                val saved = canvas.save()
                canvas.translate(center - iconSize / 2f, top)
                canvas.scale(iconSize / icon.width, iconSize / icon.height)
                tab.drawIcon(canvas, iconColor, activeWeight >= 0.5f, iconFallbackPaint)
                canvas.restoreToCount(saved)
            }

            if (!frame.iconOnly) {
                val title = frame.localizeNavigationLabel(tab.item.title?.toString().orEmpty())
                val available = (frame.slotWidth - 4f * frame.density).coerceAtLeast(1f)
                val text = TextUtils.ellipsize(title, labelPaint, available, TextUtils.TruncateAt.END)
                canvas.drawText(text, 0, text.length, center,
                    top + iconSize + gap - labelPaint.ascent(), labelPaint)
            }
        }
    }

    override fun onHostDetached() {
        compactBar.clear()
        compactOrigins.clear()
        restoreChrome()
        overflow.detachHost(host)
        miniPlayer.clear()
        artist.clear()
        settings.clear()
        binding = null
        failedSource = null
        restoreScenePending = true
    }

    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) restoreScenePending = true
        else if (restoreScenePending) host.postInvalidateOnAnimation()
    }

    override fun dispose() = onHostDetached()
}

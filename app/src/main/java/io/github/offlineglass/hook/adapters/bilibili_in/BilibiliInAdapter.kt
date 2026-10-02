package io.github.offlineglass.hook.adapters.bilibili_in

import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.WindowInsets
import android.graphics.Insets
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.targets.TargetSpec
import io.github.offlineglass.config.GlassConfig

/** International Bilibili's tab_host is Compose-backed, not a row of child Views. */
internal object BilibiliInAdapter : TargetAdapter {
    override val key = "bilibili_in"
    override val ownsNavigationFinding = true
    override val nativeSelectionReliable: Boolean = false
    const val PACKAGE_NAME = "com.bilibili.app.in"
    const val ACCENT_COLOR = 0xFFFB7299.toInt()
    const val SOURCE_ICON_SCALE = 1.15f
    const val SOURCE_SPACING_DP = 2f
    const val SOURCE_GROUP_OFFSET_DP = 2f
    override val sourceIconScale: Float = SOURCE_ICON_SCALE
    override val sourceTextTranslationDp: Float = SOURCE_SPACING_DP + SOURCE_GROUP_OFFSET_DP
    override val sourceIconTranslationDp: Float = -SOURCE_SPACING_DP + SOURCE_GROUP_OFFSET_DP
    override fun hookSignals() = io.github.offlineglass.hook.adapters.bilibili_in.hookSignals()
    @Volatile private var latestPushedConfig: GlassConfig? = null
    override fun pushedConfig(): GlassConfig? = latestPushedConfig
    override fun acceptPushedConfig(config: GlassConfig): Boolean {
        latestPushedConfig = config.normalized()
        return true
    }
    override val ownsNavigationDrawing = true
    override val usesCommittedSelection = true
    override val usesContentDarkMode = true
    override val sceneVisibilityAnimation = true
    override val drawsNavigationInOptics = true
    override val suppressSourceOnHostRebind = true
    override val accentColor: Int = ACCENT_COLOR
    override fun configureNavigationInsets(decor: View, enabled: Boolean) = configureInsets(decor, enabled)
    override fun drawNavigation(canvas: Canvas, frame: AdapterNavigationFrame) = BilibiliInRenderer.draw(
        canvas, frame.extraScale, frame.slotCount, frame.width, frame.height, frame.slotWidth,
        frame.density, frame.iconScale, frame.textSize, frame.scaledDensity,
        frame.selectedIndex, frame.enabledIndices, frame.iconOnly, frame.darkGlass,
    )
    override fun dispatchNavigationTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean =
        dispatchTabTap(source, index, slotCount)
    override fun suppressNativeBottomChrome(navigation: ViewGroup) = suppressNativeBottomBar(navigation)
    override fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) = suppressNativeBottomBar(navigation)

    /** Compose's hidden row has no reliable View-level selected state. */
    fun resolvedSelectedIndex(committedIndex: Int, slotCount: Int): Int =
        committedIndex.coerceIn(0, (slotCount - 1).coerceAtLeast(0))

    /** No publish/shop tab: the universal veil is eligible on all four pages. */
    fun allowsUniversalVeil(): Boolean = true

    override fun immersionWasReset(immersiveStub: Any?): Boolean = immersiveStub != null &&
        runCatching { XposedHelpers.callMethod(immersiveStub, "isForceImmersive") as? Boolean }
            .getOrNull() == false

    private val edgeToEdgeWindows = WeakHashMap<View, Boolean>()
    private var insetsHookInstalled = false

    /** Only the international Compose tab host needs its navigation inset removed. */
    fun configureInsets(decor: View, enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (enabled) edgeToEdgeWindows[decor] = true else edgeToEdgeWindows.remove(decor)
        if (insetsHookInstalled || !enabled) return
        XposedBridge.hookAllMethods(ViewGroup::class.java, "dispatchApplyWindowInsets",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (edgeToEdgeWindows[param.thisObject] != true) return
                    val original = param.args.firstOrNull() as? WindowInsets ?: return
                    val type = WindowInsets.Type.navigationBars()
                    val visible = original.getInsets(type)
                    val stable = original.getInsetsIgnoringVisibility(type)
                    if (visible.bottom == 0 && stable.bottom == 0) return
                    param.args[0] = WindowInsets.Builder(original)
                        .setInsets(type, Insets.of(visible.left, visible.top, visible.right, 0))
                        .setInsetsIgnoringVisibility(type,
                            Insets.of(stable.left, stable.top, stable.right, 0))
                        .build()
                }
            })
        insetsHookInstalled = true
    }

    fun suppressNativeBottomBar(source: ViewGroup?) {
        if (source == null) return
        if (source.alpha != 0f) source.alpha = 0f
        if (source.background != null) source.background = null
    }

    /** Compose's pointer input consumes the synthesized native tab tap. */
    fun dispatchTabTap(source: ViewGroup?, index: Int, slotCount: Int): Boolean {
        if (source == null || !source.isAttachedToWindow || source.width <= 0 || slotCount <= 0) return false
        val x = source.width * (index + 0.5f) / slotCount
        val y = source.height / 2f
        val now = SystemClock.uptimeMillis()
        listOf(
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0),
            MotionEvent.obtain(now, now + 48L, MotionEvent.ACTION_UP, x, y, 0),
        ).forEach { event ->
            runCatching { source.dispatchTouchEvent(event) }
            event.recycle()
        }
        return true
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) { view ->
            (view.javaClass.name == WINDOW_AWARE_TAB_HOST ||
                classHierarchyContains(view.javaClass, COMPOSE_VIEW)) &&
                AdapterNavigationSearch.resourceEntryName(view) == "tab_host" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
        } ?: return null
        return NavigationCandidate(navigation, spec.preferredSlots.first.coerceIn(2, 7), 1_000)
    }

    private fun classHierarchyContains(initial: Class<*>, name: String): Boolean {
        var type: Class<*>? = initial
        while (type != null) {
            if (type.name == name) return true
            type = type.superclass
        }
        return false
    }

    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView"
    private const val WINDOW_AWARE_TAB_HOST =
        "tv.danmaku.bili.home.components.bottomtab.WindowAwareComposeView"
}

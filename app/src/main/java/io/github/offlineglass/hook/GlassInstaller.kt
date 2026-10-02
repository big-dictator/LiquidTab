package io.github.offlineglass.hook

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.adapters.TargetAdapterRegistry
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.wechat.WeChatChatTransitionState
import io.github.offlineglass.targets.TargetSpec
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

private const val COOLAPK_REFERENCE_SCREEN_HEIGHT_PX = 2656f
private const val COOLAPK_REFERENCE_BOTTOM_GAP_PX = 68f
private const val FILE_MANAGER_ACTION_BAR_ID = "split_action_bar"
private const val FILE_MANAGER_BOTTOM_NAV_ID = "bottom_navigation_container"

/**
 * WeChat's floating-bar lift above the shared screen-bottom gap (dp). The side
 * insets mirror this exact value so the bar's left/right margins stay equal to
 * its bottom margin.
 */

/** Keeps the glass edge on the same screen-relative line as Coolapk's native bar. */
internal fun globalGlassBottomGapPx(view: View): Int {
    val rootHeight = view.rootView.height.takeIf { it > 0 }
    val displayHeight = view.resources.displayMetrics.heightPixels
    // Bilibili swaps its root between inset and edge-to-edge page containers at
    // runtime.  The shorter transient root used to shrink the floating gap until
    // the process was restarted.  Keep this app on the stable physical-window
    // reference; the other adapters retain their established parent behaviour.
    val screenHeight = TargetAdapterRegistry.forPackage(view.context.packageName)
        ?.screenReferenceHeight(rootHeight, displayHeight) ?: (rootHeight ?: displayHeight)
    return (screenHeight * COOLAPK_REFERENCE_BOTTOM_GAP_PX /
        COOLAPK_REFERENCE_SCREEN_HEIGHT_PX).roundToInt().coerceAtLeast(1)
}

internal fun glassHostWidthPx(view: View, slots: Int, config: GlassConfig, density: Float): Int {
    val legacyAvailableWidth = (view.width - 24f * density).toInt()
        .coerceAtLeast((128f * density).toInt())
    if (slots < 5) {
        return min(legacyAvailableWidth, (slots * config.tabWidth * density).toInt())
    }

    // Five-item bars need the same breathing room on the left and right as the
    // approved bar has below it. Besides looking symmetric, this keeps the
    // damped, enlarged indicator away from the physical screen clip.
    val screenWidth = view.rootView.width.takeIf { it > 0 }
        ?: view.resources.displayMetrics.widthPixels
    val sideGap = globalGlassBottomGapPx(view)
    val symmetricAvailableWidth = (screenWidth - sideGap * 2)
        .coerceAtLeast((128f * density).toInt())
    val availableWidth = min(legacyAvailableWidth, symmetricAvailableWidth)
    return min(availableWidth, (slots * config.tabWidth * density).toInt())
}

internal fun glassHostHeightPx(density: Float, config: GlassConfig, packageName: String = ""): Int {
    return (config.barHeight * density).roundToInt().coerceAtLeast(1)
}

    /**
     * Keep the screen-fixed gap stable while a host parent's bottom still differs
     * from the window during the one-frame transition into immersive navigation.
     * Once immersion settles this naturally reduces to the ordinary global gap.
     */
internal fun glassHostBottomMarginPx(view: View, config: GlassConfig? = null): Int {
    val gap = globalGlassBottomGapPx(view)
    val adapter = TargetAdapterRegistry.forPackage(view.context.packageName)
    val base = adapter?.hostBottomMarginPx(view, gap) ?: gap
    val classic = (config ?: HookConfigReader.read(view.context, view.context.packageName)).classicNavigation
    // Add another screen-relative gap, never double inset compensation inside
    // the adapter margin (WeChat may use a shorter content parent).
    val lift = ((adapter?.hostLiftDp ?: 0f) * view.resources.displayMetrics.density).roundToInt()
    return base + if (classic) gap + lift else 0
}

/**
 * Horizontal geometry must stay screen-relative even when an app keeps a
 * raised navigation-bar content inset. In particular, WeChat's compensated
 * bottom margin is expressed in its shorter content parent's coordinates and
 * must never be reused as a side margin; doing so makes its bar wider than the
 * approved Mi Health layout whenever Xiaomi's gesture area is raised.
 */
internal fun symmetricGlassSideMarginPx(view: View, density: Float): Int =
    globalGlassBottomGapPx(view) + if (TargetAdapterRegistry.forPackage(view.context.packageName)?.hostLiftDp != null) {
        // WeChat's side insets match its bottom margin (gap + lift) so the
        // bar reads with equal gaps on all three sides.
        ((TargetAdapterRegistry.forPackage(view.context.packageName)?.hostLiftDp ?: 0f) * density).toInt()
    } else {
        0
    }

/**
 * Width of the symmetric Mi Health / WeChat floating bar for [slots] visible
 * tabs. Four tabs fill the screen minus the symmetric side insets. Fewer tabs
 * keep the exact per-slot width of that 4-slot design — the slider and every
 * slot keep their 4-tab length, the bar simply shortens and stays centred, and
 * the bottom margin is untouched (side insets are no longer forced equal to
 * the bottom margin).
 */
internal fun symmetricGlassHostWidthPx(
    view: View,
    density: Float,
    config: GlassConfig,
    slots: Int,
): Int {
    val screenWidth = view.width.coerceAtLeast(
        view.resources.displayMetrics.widthPixels,
    )
    val fullWidth = (screenWidth - 2 * symmetricGlassSideMarginPx(view, density))
        .coerceAtLeast((4 * config.tabWidth * density).toInt())
    if (slots >= 4) return fullWidth
    val slotWidth = (fullWidth - 8f * density) / 4f
    return (8f * density + slotWidth * slots)
        .toInt()
        .coerceAtLeast((slots * config.tabWidth * density).toInt())
}

object GlassInstaller {
    private val installed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    // Apps whose bottom bar is a natively floating pill inside a full-width
    // container: the host must follow the pill's real geometry instead of the
    // module's symmetric floating layout.
    private val floatingPillKeys = setOf("mi_gallery", "mi_theme")
    // Theme Store's pill expands from the center right after attach, so the
    // first scans report a fraction of the final width. Track the last measured
    // rect per navigation and only trust geometry that was stable across two
    // scans at least 150 ms apart.
    @Volatile
    private var lastPillOwner: WeakReference<ViewGroup>? = null
    @Volatile
    private var lastPillRect: Rect? = null
    @Volatile
    private var lastPillRectTime = 0L
    private val activeHosts = WeakHashMap<Activity, WeakReference<GlassHostLayout>>()
    private val navigationImmersiveApplied = WeakHashMap<Activity, Boolean>()
    private val originalNavigationBarState = WeakHashMap<Activity, NavigationBarState>()
    private val qqReadiness = WeakHashMap<ViewGroup, QqReadiness>()
    private data class QqReadiness(var signature: Long, var stablePasses: Int)

    private data class NavigationBarState(
        val systemUiVisibility: Int,
        val navigationBarColor: Int,
        val navigationBarDividerColor: Int?,
        val navigationBarContrastEnforced: Boolean?,
        val miuiForceImmersive: Boolean?,
    )

    /**
     * Lets only an Activity that actually owns a LiquidTab host draw into the
     * gesture-navigation area. The system gesture handle remains controlled by
     * SystemUI; this removes the app-content lift/reserved navigation strip.
     *
     * WeChat now uses the same immersive navigation fitting in both its chat
     * list and embedded chat screen. ChatFooter supplies its own fixed internal
     * spacing, so switching pages must never change the window inset.
     */
    @Synchronized
    internal fun ensureNavigationBarImmersion(activity: Activity) {
        setNavigationBarImmersion(activity, true)
    }

    /**
     * Re-seats the system chooser card's bottom gesture bar. Removes any
     * immersive / layout-into-navigation flags and opts the window back into
     * system window fitting, so the card's bottom scrive hugs the screen edge
     * instead of floating or climbing up from it.
     */
    internal fun applySystemPickerBottomBar(activity: Activity) {
        runCatching {
            val window = activity.window
            val decor = window.decorView
            val immersive = (View.SYSTEM_UI_FLAG_IMMERSIVE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            decor.systemUiVisibility = decor.systemUiVisibility and immersive.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                XposedHelpers.callMethod(window, "setDecorFitsSystemWindows", true)
            } else {
                decor.fitsSystemWindows = true
            }
            window.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            )
        }
    }

    internal fun updateAdapterNavigationBarForPage(activity: Activity) {
        val immersion = TargetAdapterRegistry.forPackage(activity.packageName)?.navigationImmersionOverride ?: return
        // Raised navigation is now the invariant for every adapted WeChat
        // surface. Do not derive it from transient host/fragment visibility:
        // both are briefly false while LauncherUI resumes, which previously
        // produced one immersive frame followed by an inset-restoring frame.
        setNavigationBarImmersion(activity, enabled = immersion)
    }

    /**
     * Reverses only the window changes made by LiquidTab. WeChat embeds its
     * chat screen inside LauncherUI, so that screen must be allowed to resume
     * the app's original navigation-bar fitting while the four main tabs stay
     * full-bleed behind their liquid bar.
     */
    @Synchronized
    internal fun setNavigationBarImmersion(activity: Activity, enabled: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        val window = activity.window ?: return
        val decor = window.decorView ?: return
        TargetAdapterRegistry.forPackage(activity.packageName)?.configureNavigationInsets(decor, enabled)
        // Never let a stale chat container or a late WeChat callback lift the
        // already-visible conversation list after the exit animation.
        if (!enabled && WeChatChatTransitionState.isExiting(activity)) return
        val requiredFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val immersiveStub = runCatching {
            XposedHelpers.callMethod(decor, "getDecorViewImmersiveStub")
        }.getOrNull()
        val original = originalNavigationBarState.getOrPut(activity) {
            NavigationBarState(
                systemUiVisibility = decor.systemUiVisibility,
                navigationBarColor = window.navigationBarColor,
                navigationBarDividerColor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.navigationBarDividerColor
                } else {
                    null
                },
                navigationBarContrastEnforced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced
                } else {
                    null
                },
                miuiForceImmersive = immersiveStub?.let { stub ->
                    runCatching {
                        XposedHelpers.callMethod(stub, "isForceImmersive") as? Boolean
                    }.getOrNull()
                },
            )
        }

        if (!enabled) {
            // Adapters whose embedded pages rewrite window flags may request
            // repeated restoration; the default remains a one-shot restore.
            if (navigationImmersiveApplied[activity] != true &&
                TargetAdapterRegistry.forPackage(activity.packageName)?.repeatImmersionRestoreWhileInactive != true &&
                TargetAdapterRegistry.forPackage(activity.packageName)?.navigationImmersionOverride != false
            ) return
            runCatching {
                immersiveStub?.let { stub ->
                    XposedHelpers.callMethod(
                        stub,
                        "setForceImmersiveNavBar",
                        original.miuiForceImmersive ?: false,
                    )
                    XposedHelpers.callMethod(decor, "updateColorViews", null, false)
                }
            }
            // Preserve unrelated flags that WeChat may have changed while
            // restoring only the two navigation-layout bits LiquidTab owns.
            decor.systemUiVisibility =
                (decor.systemUiVisibility and requiredFlags.inv()) or
                    (original.systemUiVisibility and requiredFlags)
            window.navigationBarColor = original.navigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                original.navigationBarDividerColor?.let { window.navigationBarDividerColor = it }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                original.navigationBarContrastEnforced?.let {
                    window.isNavigationBarContrastEnforced = it
                }
            }
            navigationImmersiveApplied[activity] = false
            decor.requestApplyInsets()
            return
        }

        var changed = false

        // On HyperOS, the same force-immersive state used by the framework's
        // navigation policy removes the DecorView's navigation-bar fit without
        // changing status-bar fitting. Keep this best-effort so AOSP/older ROMs
        // continue through the standard flag path below.
        val appImmersionWasReset = TargetAdapterRegistry.forPackage(activity.packageName)
            ?.immersionWasReset(immersiveStub) == true
        if (navigationImmersiveApplied[activity] != true || appImmersionWasReset
        ) {
            runCatching {
                if (immersiveStub != null) {
                    XposedHelpers.callMethod(immersiveStub, "setForceImmersiveNavBar", true)
                    XposedHelpers.callMethod(decor, "updateColorViews", null, false)
                }
            }
            navigationImmersiveApplied[activity] = true
            changed = true
        }

        // Legacy navigation-layout flags affect only the navigation edge. Do
        // not use setDecorFitsSystemWindows(false): it would also opt the status
        // bar out of framework fitting and can pull toolbars under the cutout.
        val currentFlags = decor.systemUiVisibility
        if (currentFlags and requiredFlags != requiredFlags) {
            decor.systemUiVisibility = currentFlags or requiredFlags
            changed = true
        }
        if (window.navigationBarColor != Color.TRANSPARENT) {
            window.navigationBarColor = Color.TRANSPARENT
            changed = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            window.navigationBarDividerColor != Color.TRANSPARENT
        ) {
            window.navigationBarDividerColor = Color.TRANSPARENT
            changed = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window.isNavigationBarContrastEnforced) {
            window.isNavigationBarContrastEnforced = false
            changed = true
        }
        if (changed) decor.requestApplyInsets()
    }

    @Synchronized
    fun setAdapterBlockingOverlayVisible(packageName: String, visible: Boolean) {
        val adapter = TargetAdapterRegistry.forPackage(packageName) ?: return
        if (adapter.isBlockingOverlayVisible() == visible) return
        adapter.setBlockingOverlayVisible(visible)
        val hosts = activeHosts.entries
            .mapNotNull { (activity, reference) ->
                reference.get()?.takeIf { activity.packageName == packageName }
            }
        hosts.forEach { host -> host.onAdapterBlockingOverlayVisibilityChanged(visible) }
    }

    @Synchronized
    fun install(
        activity: Activity,
        navigation: ViewGroup,
        slots: Int,
        rawConfig: GlassConfig,
        spec: TargetSpec,
    ): Boolean {
        TargetAdapterRegistry.forSpec(spec)?.resolveNestedNavigation(navigation)?.let { (row, visibleSlots) ->
            if (row !== navigation) return install(activity, row, visibleSlots, rawConfig, spec)
        }
        if (TargetAdapterRegistry.forSpec(spec)?.acceptsNavigationSource(navigation) == false) return false
        if (navigation in installed || navigation.parent is GlassHostLayout) {
            TargetAdapterRegistry.forSpec(spec)?.suppressNativeBottomChromeOnRebind(navigation)
            return true
        }
        val parent = navigation.parent as? ViewGroup ?: return false
        TargetAdapterRegistry.forSpec(spec)?.suppressNativeBottomChrome(navigation)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        if (navigation === content || navigation === activity.window.decorView) return false
        if (spec.key == "qq" && !isActiveQqNavigation(activity, navigation)) return false

        val config = TargetAdapterRegistry.forSpec(spec)?.normalizeConfig(rawConfig.normalized())
            ?: rawConfig.normalized()
        val qqBottomChrome = if (spec.key == "qq") findQqBottomChrome(navigation) else emptyList()
        val originalParentBackground = parent.background
        val originalNavigationAlpha = navigation.alpha
        val originalNavigationBackground = navigation.background
        val originalPadding = intArrayOf(
            navigation.paddingLeft,
            navigation.paddingTop,
            navigation.paddingRight,
            navigation.paddingBottom,
        )
            var host: GlassHostLayout? = null

        return try {
            val density = activity.resources.displayMetrics.density
            val visibleSlots = (0 until slots)
                .count { index -> config.hiddenMask and (1 shl index) == 0 }
                .coerceAtLeast(1)
            // Mi Gallery and Theme Store render natively floating pills: the
            // navigation container is full-width while only the pill is drawn.
            // Derive the host geometry from the pill's laid-out children so
            // the liquid glass keeps the app's original floating layout
            // instead of the module's symmetric floating bar. The pill animates
            // in right after attach (Theme Store expands from the center), so
            // defer the install until the geometry is stable across two scans.
            val isFileManagerActionBar = spec.key == "mi_file_manager" &&
                resourceEntryName(navigation) == FILE_MANAGER_ACTION_BAR_ID
            val isFileManagerMainBar = spec.key == "mi_file_manager" &&
                resourceEntryName(navigation) == FILE_MANAGER_BOTTOM_NAV_ID
            val pillRect = when {
                spec.key in floatingPillKeys ||
                    TargetAdapterRegistry.forSpec(spec)?.floatingPillUsesSharedInsets == true ||
                    isFileManagerActionBar -> {
                    val rect = floatingPillRect(navigation, content)
                    if (rect != null && isPillSettled(navigation, rect, slots)) {
                        rect
                    } else {
                        XposedBridge.log(
                            "[OfflineGlass][Pill] deferred install: rect=$rect " +
                                "container=${navigation.width}x${navigation.height} slots=$slots",
                        )
                        return false
                    }
                }
                isFileManagerMainBar -> {
                    val rect = fileManagerMainPillRect(navigation, content)
                    if (rect != null && isPillSettled(navigation, rect, slots)) {
                        rect
                    } else {
                        XposedBridge.log(
                            "[OfflineGlass][FileManager] deferred main install: rect=$rect " +
                                "container=${navigation.width}x${navigation.height}",
                        )
                        return false
                    }
                }
                else -> null
            }
            // Projection source for floating pills: use the pill itself, not the
            // full-width container. The container is ~308 px tall while the host
            // is ~171 px, so a container source would force a 0.55x scale and
            // shrink the icons/labels (the squeezed look). The pill source keeps
            // the artwork at ~native size inside the host.
            val pillSource = if (pillRect != null &&
                (spec.key in floatingPillKeys ||
                    TargetAdapterRegistry.forSpec(spec)?.floatingPillUsesSharedInsets == true ||
                    isFileManagerActionBar)) {
                floatingPillSource(navigation, content, pillRect)
            } else null
            val hostWidth = pillRect?.width()
                ?: glassHostWidthPx(content, visibleSlots, config, density)
            val hostHeight = if (TargetAdapterRegistry.forSpec(spec)?.floatingPillUsesConfiguredHeight == true) {
                // Amap renders its own centered icons/labels, so it follows the
                // module's configured bar height instead of the short native
                // pill; the host is bottom-anchored, so it grows upward safely.
                glassHostHeightPx(density, config, activity.packageName)
            } else {
                pillRect?.height()
                    ?: glassHostHeightPx(density, config, activity.packageName)
            }
            if (spec.key == "qq" || TargetAdapterRegistry.forSpec(spec)?.logNavigationHierarchy == true) {
                logBottomHierarchy(navigation, content, spec.key.uppercase())
            }

            // Keep the app's navigation in its original hierarchy. Its own
            // controller can then continue hiding it on chat/detail pages and
            // updating selection state. Only its pixels are suppressed; the
            // glass proxy below renders and forwards clicks to it.
            val looksLikeBottomShell = parent !== content &&
                parent.height in 1..(196f * density).toInt() &&
                parent.width >= content.width * 0.5f
            if (looksLikeBottomShell || parent.height <= (196f * density).toInt()) {
                parent.background = ColorDrawable(Color.TRANSPARENT)
            }
            // WeChat: preserve the original parent background so Xiaomi's
            // navigation indicator ("小白条") can read the correct backdrop
            // color and avoid visible tinting artifacts.
            if (TargetAdapterRegistry.forSpec(spec)?.preserveNativeParentBackground == true) {
                parent.background = originalParentBackground
            }
            val nonSelectable = when {
                spec.supportsPostButton -> setOf(slots / 2)
                // The rightmost gallery entry is the standalone search button:
                // it stays a plain tap target and never receives the slider.
                spec.key == "mi_gallery" -> setOf((slots - 1).coerceAtLeast(0))
                // The file manager's main tab bar mirrors the gallery: 最近/浏览
                // tabs plus a standalone circular search key on the right.
                // The multi-select action bar (split_action_bar) is all plain
                // action buttons, so it keeps every slot selectable.
                spec.key == "mi_file_manager" &&
                    resourceEntryName(navigation) != FILE_MANAGER_ACTION_BAR_ID ->
                    setOf((slots - 1).coerceAtLeast(0))
                else -> emptySet()
            }
            // Bilibili International's ComposeView bar exposes no View-level
            // selection state either, so its selection is self-driven.
            val nativeSelectionReliable = TargetAdapterRegistry.forSpec(spec)?.nativeSelectionReliable ?:
                (spec.key != "qq" && spec.key != "jd" && spec.key != "meituan")
            // The pressed indicator grows beyond the panel, and the liquid
            // lighting / refraction edges overflow the bar bounds on all sides.
            // Disable clipping on the overlay content by default so every
            // adapter gets uncropped composition out of the box.
            content.clipChildren = false
            content.clipToPadding = false
            TargetAdapterRegistry.forSpec(spec)?.prepareContentForInstall(content)
            navigation.background = ColorDrawable(Color.TRANSPARENT)
            clearNavigationSurfaces(navigation)
            TargetAdapterRegistry.forSpec(spec)?.suppressNativeBottomChrome(navigation)
            if (spec.key == "qq" || TargetAdapterRegistry.forSpec(spec)?.clearSourceSelectionSurfaces == true) {
                clearTabSelectionSurfaces(navigation, slots)
            }
            navigation.setPadding(originalPadding[0], originalPadding[1], originalPadding[2], 0)
            applyGlassOptions(navigation, slots, config, spec, density, hostWidth, hostHeight)
            if (spec.key == "mi_theme") {
                // The theme store pill paints its moving selection indicator on
                // a dedicated OverlayView that sits above the four tab rows.
                // The glass host redraws the tab icons itself and owns the
                // slider, so the native overlay must not bleed into the liquid
                // glass projection. Hide it at install time and again after a
                // layout pass in case the pill rebuilds its chrome.
                pillSource?.let { suppressThemeStorePillChrome(it) }
            }
            TargetAdapterRegistry.forSpec(spec)?.prepareNavigationSource(navigation, density)
            navigation.post { TargetAdapterRegistry.forSpec(spec)?.prepareNavigationSource(navigation, density) }
            navigation.post { TargetAdapterRegistry.forSpec(spec)?.suppressNativeBottomChrome(navigation) }
            if (spec.key == "qq") {
                // Hide the replacement native chrome while its custom icon/text
                // sizing performs the final QQ layout pass. The retained glass
                // host remains visible and interactive during this short wait.
                qqBottomChrome.forEach { it.visibility = View.INVISIBLE }
                navigation.alpha = 0f
                if (!isQqNavigationReady(navigation, slots)) return false
            }

            // A QQ colour-mode switch constructs a replacement QQTabLayout.
            // Retain the renderer and rebind its source only after the new row
            // is stable, preserving optical and gesture state across the swap.
            val existingHost = activeHosts[activity]?.get()
                ?.takeIf {
                    it.isAttachedToWindow && it.parent === content
                }
            if (existingHost != null) {
                existingHost.rebindNavigationSource(navigation, qqBottomChrome, config)
                qqBottomChrome.forEach { it.visibility = View.INVISIBLE }
                navigation.alpha = 0f
                if (TargetAdapterRegistry.forSpec(spec)?.navigationImmersionOverride != null) {
                    updateAdapterNavigationBarForPage(activity)
                } else {
                    ensureNavigationBarImmersion(activity)
                }
                installed += navigation
                XposedBridge.log("[OfflineGlass] rebound ${spec.packageName} slots=$slots source=${spec.source}")
                return true
            }

            val createdHost = GlassHostLayout(
                activity,
                config,
                slots,
                nonSelectable,
                nativeSelectionReliable,
                pillSource ?: navigation,
                qqBottomChrome,
                // The file manager's multi-select action bar is a plain row of
                // action buttons (发送/移动/删除/更多): liquid glass panel only,
                // no slider between slots. Its main tab bar (最近/浏览/搜索)
                // keeps the slider between 最近 and 浏览, with the standalone
                // search key as a plain tap target.
                sliderEnabled = spec.key != "mi_file_manager" ||
                    resourceEntryName(navigation) != FILE_MANAGER_ACTION_BAR_ID,
            ).apply {
                // WeChat uses the same explicit tight shadow drawn by the
                // manager bar. Do not stack Android's strongly downward-biased
                // elevation shadow underneath it.
                if (TargetAdapterRegistry.forSpec(spec)?.suppressPlatformElevation == true) {
                    elevation = 0f
                    translationZ = 0f
                    stateListAnimator = null
                } else {
                    elevation = 14f * density
                    translationZ = 2f * density
                }
                clipChildren = false
                clipToPadding = false
            }
            host = createdHost
            // Four-or-fewer bars share the five-tab height and screen-bottom
            // gap. A four-tab bar also uses the exact same screen-relative side
            // gap as five tabs; below four, its slots retain that four-tab width.
            // App-specific lift is supplied by its adapter.
            val symmetricFloatingLayout = visibleSlots <= 4
            val desiredBottomMargin = glassHostBottomMarginPx(content, config) +
                if (symmetricFloatingLayout && TargetAdapterRegistry.forSpec(spec)?.hostLiftDp != null) {
                    ((TargetAdapterRegistry.forSpec(spec)?.hostLiftDp ?: 0f) * density).toInt()
                } else {
                    0
                }
            val finalWidth = if (symmetricFloatingLayout) {
                symmetricGlassHostWidthPx(content, density, config, visibleSlots)
            } else {
                hostWidth
            }
            val hostParams = if (pillRect != null) {
                // Pin the glass host exactly over the native floating pill:
                // same width, same horizontal position, same bottom gap. The
                // rect is content-local already; margins are relative to the
                // content parent the host is attached to.
                if (TargetAdapterRegistry.forSpec(spec)?.floatingPillUsesSharedInsets == true) {
                    // Amap stays on the proven pill-pinning path, but its native
                    // insets (14dp sides / 16dp bottom) do not match the design
                    // spec, whose three shared edges all equal the global gap.
                    // Pin the same pill but reposition it to spec: width, centred
                    // slot distribution and equal three-way margins.
                    FrameLayout.LayoutParams(
                        symmetricGlassHostWidthPx(content, density, config, visibleSlots),
                        hostHeight,
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        bottomMargin = glassHostBottomMarginPx(content, config)
                    }
                } else {
                    FrameLayout.LayoutParams(hostWidth, hostHeight).apply {
                        gravity = Gravity.BOTTOM or Gravity.LEFT
                        leftMargin = pillRect.left
                        rightMargin = (content.width - pillRect.right).coerceAtLeast(0)
                        val nativeBottom = (content.height - pillRect.bottom).coerceAtLeast(0)
                        bottomMargin = nativeBottom * if (config.classicNavigation) 2 else 1
                    }
                }
            } else {
                FrameLayout.LayoutParams(finalWidth, hostHeight).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = desiredBottomMargin
                }
            }
            content.addView(createdHost, hostParams)
            activeHosts[activity] = WeakReference(createdHost)
            if (spec.key == "mi_theme") {
                // The install-time hide already ran; force the projection to
                // re-capture so the stale snapshot (still holding the native
                // slider overlay) is replaced by one without it.
                createdHost.post { createdHost.invalidateProjectionCache() }
            }
            if (TargetAdapterRegistry.forSpec(spec)?.navigationImmersionOverride != null) {
                updateAdapterNavigationBarForPage(activity)
            } else {
                ensureNavigationBarImmersion(activity)
            }
            qqBottomChrome.forEach { it.visibility = View.INVISIBLE }
            if (TargetAdapterRegistry.forSpec(spec)?.keepNavigationSourceVisible != true) {
                navigation.alpha = 0f
            }
            installed += navigation
            XposedBridge.log(
                "[OfflineGlass] installed ${spec.packageName} slots=$slots " +
                    "source=${spec.source} geometry=${hostWidth}x${hostHeight}",
            )
            true
        } catch (error: Throwable) {
            runCatching {
                host?.let { h -> (h.parent as? ViewGroup)?.removeView(h) }
                parent.background = originalParentBackground
                navigation.alpha = originalNavigationAlpha
                navigation.background = originalNavigationBackground
                navigation.setPadding(originalPadding[0], originalPadding[1], originalPadding[2], originalPadding[3])
            }
            XposedBridge.log("[OfflineGlass] install failed ${spec.packageName}: $error")
            false
        }
    }

    /**
     * Whether the main-tab activity's live host is currently sitting on XHS's
     * message tab. Used by the finish/moveTaskToBack interception in HookEntry
     * to decide whether system back should be consumed for the bar peek.
     */
    fun isAdapterBackPeekTabActive(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        return activeHosts[activity]?.get()?.isAdapterBackPeekTab() == true
    }

    fun notifyWindowTouch(activity: Activity, event: MotionEvent) {
        if (activity.isFinishing || activity.isDestroyed) return
        activeHosts[activity]?.get()?.let { host ->
            TargetAdapterRegistry.forPackage(activity.packageName)?.onWindowTouch(host, event)
        }
    }

    @Synchronized
    fun notifyBackPressed(activity: Activity): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        activeHosts[activity]?.get()?.let { host ->
            if (host.appNavigationState?.handleSystemBack(host) == true) return true
        }
        val backPeekAdapter = io.github.offlineglass.hook.adapters.TargetAdapterRegistry.forPackage(activity.packageName)
        if (backPeekAdapter?.isBackPeekActivity(activity) == true) {
            val host = activeHosts[activity]?.get()
            if (host != null && host.isAdapterBackPeekTab()) {
                // Same contract as Weibo's message tab: consume back, stay on
                // the page, peek the bar for 1.5s.
                host.adapterMsgHideTime = 0L
                host.adapterMsgPeekUntil = android.os.SystemClock.uptimeMillis() +
                    backPeekAdapter.backPeekDurationMs
                host.postInvalidateOnAnimation()
                return true
            }
        }
        activeHosts[activity]?.get()?.onSystemBackPressed()
        return false
    }

    internal fun findViewByClass(root: View, className: String): View? {
        if (root.javaClass.simpleName == className) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val found = findViewByClass(root.getChildAt(i), className)
                if (found != null) return found
            }
        }
        return null
    }

    private fun resourceEntryName(view: View): String? = runCatching {
        if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
    }.getOrNull()

    /**
     * The app's page-content branch under android.R.id.content: the direct
     * child hosting the whole page tree, identified by walking up from the
     * navigation row (every adapted app keeps it inside its main branch) and
     * falling back to the first full-window child. Our veil and host are
     * never part of the branch, so their output cannot feed back into the
     * blur, and overlays added later (drawers, full-screen players) never
     * displace the base page layout.
     */
    internal fun findFullWindowContentBranch(content: ViewGroup, navigation: View): View? {
        var ancestor = navigation.parent
        while (ancestor is View && ancestor !== content) {
            val parent = ancestor.parent ?: break
            if (parent === content && ancestor.width >= content.width * 0.80f &&
                ancestor.height >= content.height * 0.70f
            ) {
                XposedBridge.log(
                    "[OfflineGlass][ContentBranch] branch(nav)=${ancestor.javaClass.name} " +
                        "size=${ancestor.width}x${ancestor.height}",
                )
                return ancestor
            }
            ancestor = parent
        }
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child === navigation || child is GlassHostLayout ||
                child is AdapterOwnedOverlay
            ) continue
            if (child.visibility != View.VISIBLE || child.alpha <= 0.5f) continue
            if (child.width >= content.width * 0.80f && child.height >= content.height * 0.70f) {
                XposedBridge.log(
                    "[OfflineGlass][ContentBranch] branch(first)=${child.javaClass.name} " +
                        "size=${child.width}x${child.height}",
                )
                return child
            }
        }
        return null
    }

    /** Require two identical, fully laid-out QQ rows before taking ownership. */
    private fun isQqNavigationReady(navigation: ViewGroup, slots: Int): Boolean {
        if (!navigation.isAttachedToWindow || navigation.width <= 0 || navigation.height <= 0) return false
        val container = findSlotContainer(navigation, slots) ?: return false
        if (container.childCount < slots) return false
        var signature = 17L
        signature = signature * 31L + navigation.width
        signature = signature * 31L + navigation.height
        signature = signature * 31L + container.width
        signature = signature * 31L + container.height
        signature = signature * 31L + container.childCount
        for (index in 0 until slots) {
            val child = container.getChildAt(index)
            if (child.width <= 0 || child.height <= 0) return false
            signature = signature * 31L + System.identityHashCode(child)
            signature = signature * 31L + child.left
            signature = signature * 31L + child.top
            signature = signature * 31L + child.width
            signature = signature * 31L + child.height
        }
        val state = qqReadiness[navigation]
        if (state == null || state.signature != signature) {
            qqReadiness[navigation] = QqReadiness(signature, 1)
            return false
        }
        state.stablePasses++
        return state.stablePasses >= 2
    }

    private fun isActiveQqNavigation(activity: Activity, navigation: ViewGroup): Boolean {
        val root = activity.window?.decorView ?: return false
        val rootRect = android.graphics.Rect().also(root::getGlobalVisibleRect)
        val candidates = ArrayList<Pair<ViewGroup, android.graphics.Rect>>()
        val stack = ArrayDeque<View>()
        stack += root
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view.javaClass.name == QQ_TAB_LAYOUT_CLASS && view is ViewGroup &&
                view.visibility == View.VISIBLE && view.width > 0 && view.height > 0
            ) {
                val rect = android.graphics.Rect()
                if (view.getGlobalVisibleRect(rect) && rect.bottom >= rootRect.top + root.height * 0.72f) {
                    candidates += view to rect
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index)
            }
        }
        val best = candidates.minWithOrNull(
            compareBy<Pair<ViewGroup, android.graphics.Rect>> {
                kotlin.math.abs(rootRect.bottom - it.second.bottom)
            }.thenBy { it.first.height },
        )?.first
        return best == null || best === navigation
    }

    private fun findQqBottomChrome(navigation: ViewGroup): List<View> {
        val parent = navigation.parent as? ViewGroup ?: return emptyList()
        val navLocation = IntArray(2).also(navigation::getLocationInWindow)
        val matches = ArrayList<View>(2)
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val location = IntArray(2).also(child::getLocationInWindow)
            val sameBand = kotlin.math.abs(location[1] - navLocation[1]) <= 2 &&
                kotlin.math.abs(child.height - navigation.height) <= 2 &&
                child.width >= navigation.width * 0.9f
            val topDivider = child.javaClass == View::class.java && child.height in 1..2 &&
                child.width >= navigation.width * 0.9f &&
                kotlin.math.abs(location[1] + child.height - navLocation[1]) <= 2
            if ((child.javaClass.name == QQ_BOTTOM_BLUR_CLASS && sameBand) || topDivider) {
                matches += child
            }
        }
        return matches
    }

    private fun clearTabSelectionSurfaces(root: ViewGroup, slots: Int) {
        runCatching {
            XposedHelpers.callMethod(root, "setSelectedTabIndicator", ColorDrawable(Color.TRANSPARENT))
        }
        val container = findSlotContainer(root, slots) ?: return
        for (index in 0 until min(container.childCount, slots)) {
            val item = container.getChildAt(index)
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += item to 0
            while (stack.isNotEmpty()) {
                val (view, depth) = stack.removeLast()
                val isContainerSurface = view is ViewGroup ||
                    (view !is ImageView && view !is TextView &&
                        view.width >= item.width * 0.55f && view.height >= item.height * 0.45f)
                if (isContainerSurface) {
                    view.background = ColorDrawable(Color.TRANSPARENT)
                    view.backgroundTintList = null
                    view.foreground = null
                    view.stateListAnimator = null
                }
                if (view is ViewGroup && depth < 5) {
                    for (childIndex in 0 until view.childCount) {
                        stack += view.getChildAt(childIndex) to (depth + 1)
                    }
                }
            }
        }
    }

    /**
     * Weibo wraps the five tab items in one or more page-specific bottom
     * shells.  Those shells provide the opaque white/black bar and the native
     * selected background.  Keep the actual icon, label, badge and click
     * target views intact while removing only the decorative surfaces.
     */
    private fun logBottomHierarchy(navigation: ViewGroup, content: ViewGroup, tag: String) {
        fun describe(view: View): String {
            val location = IntArray(2)
            runCatching { view.getLocationInWindow(location) }
            val background = view.background?.javaClass?.name ?: "null"
            val children = (view as? ViewGroup)?.childCount ?: -1
            return "${view.javaClass.name} id=${view.id} xy=${location[0]},${location[1]} " +
                "size=${view.width}x${view.height} alpha=${view.alpha} vis=${view.visibility} " +
                "shown=${view.isShown} clickable=${view.isClickable} selected=${view.isSelected} " +
                "activated=${view.isActivated} bg=$background children=$children"
        }

        XposedBridge.log("[OfflineGlass][${tag}Tree] navigation ${describe(navigation)}")
        val descendants = ArrayDeque<Pair<View, Int>>()
        descendants += navigation to 0
        var logged = 0
        while (descendants.isNotEmpty() && logged++ < 96) {
            val (view, depth) = descendants.removeFirst()
            XposedBridge.log("[OfflineGlass][${tag}Tree] nav[$depth] ${describe(view)}")
            if (view is ViewGroup && depth < 6) {
                for (index in 0 until view.childCount) descendants += view.getChildAt(index) to (depth + 1)
            }
        }
        var current: View? = navigation
        repeat(10) { depth ->
            val view = current ?: return
            XposedBridge.log("[OfflineGlass][${tag}Tree] ancestor[$depth] ${describe(view)}")
            val group = view as? ViewGroup
            if (group != null) {
                for (index in 0 until group.childCount.coerceAtMost(12)) {
                    XposedBridge.log("[OfflineGlass][${tag}Tree] ancestor[$depth].child[$index] ${describe(group.getChildAt(index))}")
                }
            }
            if (view === content) return
            current = view.parent as? View
        }
    }

    /**
     * Floating-pill apps (Mi Gallery, Theme Store) render their bottom bar as
     * a natively floating capsule: a full-width navigation container with only
     * the pill drawn. The container's own bounds are wider than the pill, so
     * derive the pill from the union of its visible children's global bounds
     * and convert the result into content-local coordinates for the host
     * LayoutParams.
     */
    /** Locates the pill child whose laid-out bounds equal the measured rect. */
    internal fun floatingPillSource(
        navigation: ViewGroup,
        content: ViewGroup,
        rect: Rect,
    ): ViewGroup? {
        val contentLocation = IntArray(2).also(content::getLocationInWindow)
        val global = Rect()
        val local = Rect()
        for (index in 0 until navigation.childCount) {
            val child = navigation.getChildAt(index) ?: continue
            if (child.visibility != View.VISIBLE) continue
            if (!child.getGlobalVisibleRect(global)) continue
            local.set(global)
            local.offset(-contentLocation[0], -contentLocation[1])
            if (local == rect) return child as? ViewGroup
        }
        return null
    }

    internal fun floatingPillRect(navigation: ViewGroup, content: ViewGroup): Rect? {
        val rect = Rect()
        val tmp = Rect()
        var found = false
        for (index in 0 until navigation.childCount) {
            val child = navigation.getChildAt(index) ?: continue
            if (child.visibility != View.VISIBLE) continue
            if (!child.getGlobalVisibleRect(tmp)) continue
            if (!found) {
                rect.set(tmp)
                found = true
            } else {
                rect.union(tmp)
            }
        }
        if (!found && !navigation.getGlobalVisibleRect(rect)) return null
        val contentLocation = IntArray(2).also(content::getLocationInWindow)
        rect.offset(-contentLocation[0], -contentLocation[1])
        return rect
    }

    internal fun fileManagerMainPillRect(navigation: ViewGroup, content: ViewGroup): Rect? {
        if (resourceEntryName(navigation) != FILE_MANAGER_BOTTOM_NAV_ID) return null
        val globalUnion = Rect()
        val childRect = Rect()
        var found = 0
        val navWidth = navigation.width.coerceAtLeast(1)
        val navHeight = navigation.height.coerceAtLeast(1)
        for (index in 0 until navigation.childCount) {
            val child = navigation.getChildAt(index) ?: continue
            if (child.visibility != View.VISIBLE || child.width <= 0 || child.height <= 0) continue
            if (!child.getGlobalVisibleRect(childRect)) continue
            val looksLikeControl =
                child.width <= (navWidth * 0.72f).roundToInt() &&
                    child.height <= (navHeight * 0.72f).roundToInt() &&
                    childRect.height() >= (48f * navigation.resources.displayMetrics.density).roundToInt()
            if (!looksLikeControl) continue
            if (found == 0) globalUnion.set(childRect) else globalUnion.union(childRect)
            found++
        }
        // 最近/浏览 tab + 移动指示器 + 搜索圆形按钮，至少应有 3 个可见控制。
        // 如果没有稳定露出这些子控件，不要退回全宽容器，否则会锁定成巨大错位底栏。
        if (found < 3) return null
        val contentLocation = IntArray(2).also(content::getLocationInWindow)
        globalUnion.offset(-contentLocation[0], -contentLocation[1])
        return globalUnion
    }

    private fun isPillSettled(navigation: ViewGroup, rect: Rect, slots: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val owner = lastPillOwner?.get()
        if (owner !== navigation) {
            lastPillOwner = WeakReference(navigation)
            lastPillRect = null
            lastPillRectTime = 0L
        }
        val prev = lastPillRect
        lastPillRect = Rect(rect)
        if (prev == null || prev != rect || now - lastPillRectTime < 150L) {
            lastPillRectTime = now
            return false
        }
        // Belt-and-suspenders: the pill must already expose its tab cells; a
        // mid-inflate pill can briefly report a stable frame. Accept leniently
        // (slots - 1) so a bar whose selected tab drops its clickable flag
        // never stalls the install.
        var cells = 0
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += navigation to 0
        while (stack.isNotEmpty() && cells < slots - 1) {
            val (view, depth) = stack.removeLast()
            if (view !== navigation && (view.isClickable || view.isSelected || view.isActivated)) {
                cells++
            }
            if (view is ViewGroup && depth < 6) {
                for (index in 0 until view.childCount) {
                    stack += view.getChildAt(index) to depth + 1
                }
            }
        }
        return cells >= slots - 1
    }

    private fun applyGlassOptions(
        root: ViewGroup,
        slots: Int,
        config: GlassConfig,
        spec: TargetSpec,
        density: Float,
        hostWidth: Int,
        hostHeight: Int,
    ) {
        // Market owns its configurable tab list. Preserve its native visibility,
        // typography and controller state; the glass renderer projects the current
        // TabView artwork into independent slots.
        if (TargetAdapterRegistry.forSpec(spec)?.managesNativeTabVisibility == true) return
        findSlotContainer(root, slots)?.let { container ->
            for (index in 0 until min(container.childCount, slots)) {
                val hiddenByMask = config.hiddenMask and (1 shl index) != 0
                val hiddenPost = spec.supportsPostButton && index == slots / 2 && !config.showPostButton
                // Preserve every native controller index. The glass row owns
                // compaction; removing native children would remap later clicks
                // to the wrong app page.
                container.getChildAt(index).visibility = if (hiddenByMask || hiddenPost) {
                    View.INVISIBLE
                } else {
                    View.VISIBLE
                }
            }
        }

        val sourceAdapter = TargetAdapterRegistry.forSpec(spec)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            when (view) {
                is TextView -> {
                    // QQ and WeChat own and re-theme their source rows. Never
                    // change their native text metrics: the independent glass
                    // renderer applies the user's scale after projection.
                    if (sourceAdapter?.sourceTextSizeSp != null) {
                        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sourceAdapter.sourceTextSizeSp!!)
                    } else if (spec.key != "qq" && sourceAdapter?.preserveNativeSourceMetrics != true) {
                        if (sourceAdapter?.configureSourceText(view, config, density) != true) {
                            view.textSize = config.textSize
                        }
                        if (sourceAdapter?.sourceTextTranslationDp != null) {
                            view.translationY = sourceAdapter.sourceTextTranslationDp!! * density
                        }
                    }
                    view.alpha = if (config.iconOnly && view.compoundDrawables.all { it == null }) 0f else 0.96f
                    if (!config.showChannel && view.text?.toString()?.contains("频道") == true) {
                        view.visibility = View.GONE
                    }
                }

                is ImageView -> if (
                    spec.key != "qq" && sourceAdapter?.preserveNativeSourceMetrics != true &&
                    view.width <= 48f * density && view.height <= 48f * density
                ) {
                    if (sourceAdapter?.configureSourceIcon(view, config, density) == true) continue
                    val appIconScale = sourceAdapter?.sourceIconScale ?: 1f
                    view.scaleX = config.iconScale * appIconScale
                    view.scaleY = config.iconScale * appIconScale
                    if (sourceAdapter?.sourceIconTranslationDp != null) {
                        view.translationY = sourceAdapter.sourceIconTranslationDp!! * density
                    }
                    val isPost = spec.supportsPostButton && isNearHorizontalCenter(view, root)
                    if (isPost && config.customAccentEnabled) view.setColorFilter(config.customAccentColor)
                    view.alpha = 0.96f
                }
            }
            if (view is ViewGroup && depth < 6) {
                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)
            }
        }
    }

    /**
     * QQ renders each main tab icon inside a full-slot custom View rather than.
     * an ImageView. Use QQ's public icon-size API so only the drawable changes;
     * scaling the full custom View would also displace its animation pivot.
     */

    private fun resizeQqTabIcons(
        root: ViewGroup,
        slots: Int,
        density: Float,
        projectedSourceScale: Float,
        userScale: Float,
    ) {
        val container = findSlotContainer(root, slots) ?: return
        val sourceIconSize = (
            QQ_TARGET_ICON_DP * density * userScale / projectedSourceScale.coerceAtLeast(0.0001f)
            ).toInt().coerceAtLeast(1)
        for (index in 0 until min(container.childCount, slots)) {
            val item = container.getChildAt(index)
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += item to 0
            while (stack.isNotEmpty()) {
                val (view, depth) = stack.removeLast()
                if (view.javaClass.name == QQ_TAB_DRAG_ANIMATION_VIEW_CLASS) {
                    runCatching {
                        XposedHelpers.callMethod(view, "setIconSize", sourceIconSize, sourceIconSize)
                        view.invalidate()
                    }
                    break
                }
                if (view is ViewGroup && depth < 5) {
                    for (childIndex in 0 until view.childCount) {
                        stack += view.getChildAt(childIndex) to (depth + 1)
                    }
                }
            }
        }
    }

    private fun clearNavigationSurfaces(root: ViewGroup) {
        val rootWidth = root.width.coerceAtLeast(root.measuredWidth).coerceAtLeast(1)
        val rootHeight = root.height.coerceAtLeast(root.measuredHeight).coerceAtLeast(1)
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            val largeSurface = view === root ||
                (view.width >= rootWidth * 0.72f && view.height >= rootHeight * 0.48f)
            if (largeSurface) {
                view.background = ColorDrawable(Color.TRANSPARENT)
                view.backgroundTintList = null
            }
            if (view is ViewGroup && depth < 5) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to (depth + 1)
            }
        }
    }

    private fun suppressThemeStorePillChrome(pill: ViewGroup) {
        if (pill.width <= 0) return
        for (i in 0 until pill.childCount) {
            val child = pill.getChildAt(i)
            if (child.javaClass.simpleName == "OverlayView" &&
                child.width >= pill.width / 5f &&
                child.visibility != View.INVISIBLE
            ) {
                child.visibility = View.INVISIBLE
            }
        }
    }

    private fun findSlotContainer(root: ViewGroup, slots: Int): ViewGroup? {
        var best: ViewGroup? = null
        var bestDepth = Int.MAX_VALUE
        val stack = ArrayDeque<Pair<ViewGroup, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (group, depth) = stack.removeLast()
            if (group.childCount in (slots - 1).coerceAtLeast(2)..(slots + 1) && depth < bestDepth) {
                val horizontal = (0 until group.childCount).map { group.getChildAt(it) }
                    .count { it.width > 0 && it.height > 0 } >= 2
                if (horizontal) {
                    best = group
                    bestDepth = depth
                }
            }
            if (depth < 5) {
                for (i in 0 until group.childCount) {
                    (group.getChildAt(i) as? ViewGroup)?.let { stack += it to (depth + 1) }
                }
            }
        }
        return best
    }

    private fun isNearHorizontalCenter(view: View, root: View): Boolean {
        if (root.width <= 0) return false
        val child = IntArray(2)
        val parent = IntArray(2)
        view.getLocationInWindow(child)
        root.getLocationInWindow(parent)
        val center = child[0] - parent[0] + view.width / 2f
        return kotlin.math.abs(center - root.width / 2f) <= root.width * 0.16f
    }

    private const val QQ_BOTTOM_BLUR_CLASS = "com.tencent.qui.quiblurview.QQBlurViewWrapper"
    private const val QQ_TAB_LAYOUT_CLASS = "com.tencent.mobileqq.widget.QQTabLayout"
    private const val QQ_TAB_DRAG_ANIMATION_VIEW_CLASS = "com.tencent.mobileqq.widget.TabDragAnimationView"
    private const val QQ_TARGET_ICON_DP = 28f
}

internal object GlassInsets {
    fun stableNavigationBottom(context: Context, view: View): Int {
        val interactionMode = runCatching {
            val id = context.resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
            if (id == 0) -1 else context.resources.getInteger(id)
        }.getOrDefault(-1)
        // Gesture navigation already reserves its handle inside the system
        // window. Its reported visibility changes during night-mode Activity
        // recreation, so adding that transient inset moves the glass by 52 px.
        if (interactionMode == 2) return 0
        return runCatching {
            view.rootWindowInsets
                ?.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
                ?.bottom ?: 0
        }.getOrDefault(0)
    }
}

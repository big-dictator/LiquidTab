package io.github.offlineglass.hook.adapters.mi_health

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNavigationState
import android.content.Context
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.targets.TargetSpec
import kotlin.math.abs

/** Fitness must wait for the real main_tl_bottom rather than a device-list row. */
internal object MiHealthAdapter : TargetAdapter {
    override val key = "mi_health"
    override val ownsNavigationFinding = true
    override val ownsNavigationTap = true
    // The baseline commits selectionInitialized after Tab.select(). Without it,
    // the first dispatchDraw snaps the spring to the target and skips the tap animation.
    override val commitSelectionAfterTap = true
    // The glass host is the sole tab hit target, so its committed selection is
    // authoritative while TabLayout updates native selected flags.
    override val usesCommittedSelection = true
    // The app briefly detaches/recreates main_tl_bottom during tab fragment
    // transitions. Keep the existing glass bar visible through that handoff
    // instead of playing a one-frame hide/show position animation.
    override val sourceSwapGraceMs = 1_200L
    override val trackSourceDrawableIdentity = true
    override val usesContentDarkMode = true
    override fun createNavigationState(context: Context): AppNavigationState = MiHealthNavigationState()
    private var rnInsetsFloatFields: Pair<Class<*>, Array<java.lang.reflect.Field>>? = null

    override fun configureSourceText(view: TextView, config: GlassConfig, density: Float): Boolean {
        view.textSize = config.textSize * CONTENT_SCALE
        view.translationY = (SPACING_COMPENSATION_DP + GROUP_OFFSET_DP) * density
        return true
    }

    override fun configureSourceIcon(view: ImageView, config: GlassConfig, density: Float): Boolean {
        view.scaleX = ICON_SCALE * config.iconScale
        view.scaleY = ICON_SCALE * config.iconScale
        view.translationY = (-SPACING_COMPENSATION_DP + GROUP_OFFSET_DP) * density
        return true
    }

    override fun prepareNavigationSource(navigation: ViewGroup, density: Float) {
        val navigationName = runCatching { navigation.resources.getResourceEntryName(navigation.id) }.getOrNull()
        if (navigationName != "main_tl_bottom") return
        val parent = navigation.parent as? ViewGroup ?: return
        parent.clipChildren = false; parent.clipToPadding = false
        val stack = ArrayDeque<View>(); stack += navigation
        while (stack.isNotEmpty()) {
            val view = stack.removeLast(); view.clipToOutline = false
            if (view is ViewGroup) {
                view.clipChildren = false; view.clipToPadding = false
                for (index in 0 until view.childCount) stack += view.getChildAt(index)
            }
        }
        val navigationIndex = parent.indexOfChild(navigation)
        if (navigationIndex > 0) {
            val separator = parent.getChildAt(navigationIndex - 1)
            val separatorHeight = separator.height.coerceAtLeast(separator.measuredHeight)
            if (separatorHeight in 0..(2f * density).toInt().coerceAtLeast(1)) separator.visibility = View.GONE
        }
        val navigationHeight = navigation.height.coerceAtLeast(navigation.measuredHeight)
        if (navigationHeight <= 0) return
        (navigation.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            if (params.topMargin != -navigationHeight) {
                params.topMargin = -navigationHeight; navigation.layoutParams = params
            }
        }
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val name = runCatching { child.resources.getResourceEntryName(child.id) }.getOrNull()
            if (name != "main_fl_content") continue
            if (child.paddingBottom != 0) child.setPadding(child.paddingLeft, child.paddingTop, child.paddingRight, 0)
            if (child is ViewGroup) for (item in 0 until child.childCount) {
                child.getChildAt(item).let { nested ->
                    if (nested.paddingBottom != 0) nested.setPadding(nested.paddingLeft, nested.paddingTop, nested.paddingRight, 0)
                }
            }
            child.requestLayout(); child.invalidate(); break
        }
        parent.requestLayout(); parent.invalidate()
    }

    @Suppress("DEPRECATION")
    override fun prepareContentForInstall(content: ViewGroup) {
        if (android.os.Build.VERSION.SDK_INT < 21) return
        content.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.systemWindowInsetBottom
            if (bottom in 1..200) {
                insets.replaceSystemWindowInsets(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    0,
                )
            } else {
                insets
            }
        }
        content.requestApplyInsets()
    }

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        installReactSafeAreaHook(lpparam, spec.packageName)
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val navigation = AdapterNavigationSearch.findFirst(root) { view ->
            view.javaClass.name == "com.google.android.material.tabs.TabLayout" &&
                AdapterNavigationSearch.resourceEntryName(view) == "main_tl_bottom" &&
                view.isAttachedToWindow && view.visibility == View.VISIBLE &&
                view.width > 0 && view.height > 0
        }
        if (navigation == null) {
            Log.i("MhGlassDiag", "find: main_tl_bottom not ready; retrying")
            return null
        }
        return NavigationCandidate(navigation, spec.preferredSlots.first.coerceIn(2, 7), 1_000)
    }

    /** Keep this build's React Native safe-area correction scoped to Mi Health. */
    private fun installReactSafeAreaHook(
        lpparam: XC_LoadPackage.LoadPackageParam,
        packageName: String,
    ) {
        val helperClass = runCatching {
            XposedHelpers.findClass("x7n", lpparam.classLoader)
        }.getOrNull() ?: run {
            XposedBridge.log("[OfflineGlass][MiHealth] RN safe-area helper not found; skipping")
            return
        }
        runCatching {
            XposedBridge.hookAllMethods(helperClass, "c", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val insets = param.result ?: return
                    val view = param.args.firstOrNull() as? View ?: return
                    val context = view.context ?: return
                    if (!HookConfigReader.read(context, packageName).enabled) return
                    zeroReactSafeAreaBottom(view, insets)
                }
            })
            XposedBridge.log("[OfflineGlass][MiHealth] RN safe-area bottom-zero hook installed")
        }.onFailure {
            XposedBridge.log("[OfflineGlass][MiHealth] RN safe-area hook failed: $it")
        }
    }

    private fun zeroReactSafeAreaBottom(view: View, insets: Any) {
        runCatching {
            val root = view.rootView ?: return
            val rootInsets = root.rootWindowInsets ?: return
            val sysBars = WindowInsets.Type.systemBars()
            val expectedBottom = minOf(
                rootInsets.getInsets(sysBars).bottom,
                runCatching { rootInsets.getInsetsIgnoringVisibility(sysBars).bottom }
                    .getOrDefault(Int.MAX_VALUE),
            )
            if (expectedBottom < 8) return
            val cls = insets.javaClass
            val fields = rnInsetsFloatFields?.takeIf { it.first == cls }?.second
                ?: cls.declaredFields
                    .filter { it.type == java.lang.Float.TYPE }
                    .onEach { it.isAccessible = true }
                    .toTypedArray()
                    .also { rnInsetsFloatFields = cls to it }
            var bottomField: java.lang.reflect.Field? = null
            for (field in fields) {
                val value = field.getFloat(insets)
                if (value > 0f && abs(value - expectedBottom) <= 6f) {
                    if (bottomField != null) return
                    bottomField = field
                }
            }
            bottomField?.setFloat(insets, 0f)
        }
    }

    private const val CONTENT_SCALE = 1.10f
    private const val ICON_SCALE = 1.416f
    private const val SPACING_COMPENSATION_DP = 1.25f
    private const val GROUP_OFFSET_DP = 2f
}

package io.github.offlineglass.hook.adapters.douyin

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Native compound comment layout; no resource IDs or numeric version bindings. */
internal object DouyinCommentLayoutHooks {
    private const val WIDGETS = "com.ss.android.ugc.aweme.comment.widget."
    private var installed = false
    private data class HeightOwnership(val original: Int, var applied: Int)
    private val imposedHeights = WeakHashMap<View, HeightOwnership>()
    private val sheets = WeakHashMap<View, WeakReference<ViewGroup>>()
    private val reported = WeakHashMap<View, Boolean>()
    private var reports = 0

    fun requiresNativeNavigationFit(root: View): Boolean {
        val sheet = sheets[root]?.get() ?: return false
        return sheet.isAttachedToWindow && sheet.isShown && sheet.alpha > 0f &&
            sheet.height > 0 && sheet.rootView === root
    }

    private data class NavigationAppearance(val color: Int, val darkIcons: Boolean)
    private data class NavigationOwnership(val original: NavigationAppearance, val applied: NavigationAppearance)
    private val navigationAppearance = WeakHashMap<Activity, NavigationOwnership>()

    fun updateNavigationAppearance(activity: Activity, root: View) {
        val window = activity.window
        val controller = window.insetsController ?: return
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val current = NavigationAppearance(window.navigationBarColor, controller.systemBarsAppearance and light != 0)
        val owned = navigationAppearance[activity]
        val sheet = sheets[root]?.get()?.takeIf { requiresNativeNavigationFit(root) }
        val color = sheet?.let(::panelColor)
        if (color == null) {
            if (owned != null) {
                navigationAppearance.remove(activity)
                if (current.color == owned.applied.color) window.navigationBarColor = owned.original.color
                if (current.darkIcons == owned.applied.darkIcons) {
                    controller.setSystemBarsAppearance(if (owned.original.darkIcons) light else 0, light)
                }
            }
            return
        }

        val desired = NavigationAppearance(color, Color.luminance(color) > 0.179f)
        if (current == desired) return
        navigationAppearance[activity] = NavigationOwnership(owned?.original ?: current, desired)
        if (current.color != desired.color) window.navigationBarColor = desired.color
        if (current.darkIcons != desired.darkIcons) {
            controller.setSystemBarsAppearance(if (desired.darkIcons) light else 0, light)
        }
    }

    private fun panelColor(sheet: ViewGroup): Int? {
        // The native sheet has a separate full-width FrameLayout below its scrim.
        var panel: FrameLayout? = null
        for (index in 0 until sheet.childCount) {
            val child = sheet.getChildAt(index) as? FrameLayout ?: continue
            if (!child.isShown || child.width != sheet.width || child.bottom != sheet.height || child.height <= 0) continue
            if (panel != null) return null
            panel = child
        }
        val surface = panel ?: return null
        return opaqueBackground(surface) ?: (surface.getChildAt(0) as? LinearLayout)?.let(::opaqueBackground)
    }

    private fun opaqueBackground(view: View): Int? {
        val color = view.backgroundTintList?.getColorForState(view.drawableState, Color.TRANSPARENT)
            ?: when (val background = view.background) {
                is ColorDrawable -> background.color
                is GradientDrawable -> background.color?.getColorForState(view.drawableState, Color.TRANSPARENT)
                else -> null
            }
        return color?.takeIf { Color.alpha(it) == 255 }
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        runCatching {
            val rootType = Class.forName(WIDGETS + "CommentNestedLayout", false, loader)
            require(ViewGroup::class.java.isAssignableFrom(rootType))
            val signature = arrayOf(Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            var type: Class<*>? = rootType
            var entry: java.lang.reflect.Method? = null
            while (type != null && entry == null) {
                entry = runCatching { type!!.getDeclaredMethod("onLayout", *signature) }
                    .getOrNull()?.takeUnless { Modifier.isAbstract(it.modifiers) }
                type = type.superclass
            }
            val callback = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val root = p.thisObject as? ViewGroup ?: return
                    if (rootType.isInstance(root) && root.isAttachedToWindow && root.isShown) repairRoot(root)
                }
            }
            XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java, XC_MethodHook::class.java)
                .invoke(null, requireNotNull(entry), callback)
            // CommentListPageFragment owns this native sheet. Its keyboard is
            // a separate fragment that reads the window's navigation/IME insets.
            val measure = rootType.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            XposedBridge.hookMethod(measure, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val sheet = p.thisObject as? ViewGroup ?: return
                    if (!sheet.isAttachedToWindow || !sheet.isShown) return
                    val root = sheet.rootView
                    if (sheets[root]?.get() !== sheet) sheets[root] = WeakReference(sheet)
                }
            })
            installed = true
        }.onFailure { XposedBridge.log("[LiquidTab] comment layout binding unavailable: $it") }
    }

    private fun isWidget(view: View, simpleName: String): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == WIDGETS + simpleName) return true
            type = type.superclass
        }
        return false
    }

    private fun tabBranch(view: View, depth: Int = 0): Boolean {
        if (isWidget(view, "CommentDmtTabLayout")) return true
        if (depth >= 3 || view !is ViewGroup || view.childCount > 24) return false
        for (i in 0 until view.childCount) if (tabBranch(view.getChildAt(i), depth + 1)) return true
        return false
    }

    private fun repairRoot(root: ViewGroup) {
        // Breadth first, bounded to the sheet shell. Never enter comment lists.
        val queue = ArrayDeque<ViewGroup>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 64) {
            val group = queue.removeFirst()
            if (!group.isShown) continue
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (isWidget(child, "FixedCommentViewPager")) {
                    if (group is LinearLayout && group.orientation == LinearLayout.VERTICAL) repair(group, child)
                    return
                }
            }
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is ViewGroup && child.isShown) queue.add(child)
            }
        }
    }

    private fun repair(content: LinearLayout, pager: View) {
        val pagerIndex = content.indexOfChild(pager)
        val tabsIndex = (0 until pagerIndex).singleOrNull { tabBranch(content.getChildAt(it)) } ?: return
        val tabs = content.getChildAt(tabsIndex)
        if (!tabs.isShown || tabs.height <= 0) return
        // The native vertical shell places the optional card frame directly
        // before the tab branch. Its XML declares no vertical spacing here.
        if (tabsIndex == 0) return
        val header = content.getChildAt(tabsIndex - 1) as? FrameLayout ?: return
        if (!header.isShown) return
        val params = header.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val tabsParams = tabs.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = content.resources.displayMetrics.density
        val normalGap = (24f * density).roundToInt()
        val excessive = (64f * density).roundToInt()
        val gap = tabs.top - header.bottom
        val oldMargin = params.bottomMargin
        val oldTabsMargin = tabsParams.topMargin
        val oldHeight = header.height
        var headerChanged = false
        var tabsChanged = false
        if (gap > (8f * density).roundToInt() && oldMargin >= 0 && oldTabsMargin >= 0 &&
            kotlin.math.abs(gap - oldMargin - oldTabsMargin) <= 1) {
            // Keep spacing inside the card. Reclaim only the measured external
            // margins, including the 24dp left by the previous repair.
            if (oldMargin > 0) { params.bottomMargin = 0; headerChanged = true }
            if (oldTabsMargin > 0) { tabsParams.topMargin = 0; tabsChanged = true }
        }

        // Count visible content conservatively; never crop media/unknown depth.
        var occupiedBottom = 0
        var media = false
        var complete = true
        val stack = ArrayDeque<Pair<View, Int>>()
        stack.add(header to 0)
        val origin = IntArray(2).also(header::getLocationOnScreen)
        val location = IntArray(2)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 160) {
            val (view, depth) = stack.removeLast()
            if (!view.isShown) continue
            if (view is android.view.SurfaceView || view is android.view.TextureView || view is android.webkit.WebView) media = true
            val occupied = when (view) {
                is TextView -> !view.text.isNullOrBlank()
                is ImageView -> view.drawable != null
                is ViewGroup -> view !== header && view.background != null
                else -> view.javaClass != View::class.java || view.isClickable
            }
            if (view !== header && occupied && view.width > 0 && view.height > 0) {
                view.getLocationOnScreen(location)
                occupiedBottom = maxOf(occupiedBottom, location[1] - origin[1] + view.height)
            }
            if (view is ViewGroup) {
                if (depth >= 8 && view.childCount > 0) { complete = false; continue }
                for (i in 0 until view.childCount) stack.add(view.getChildAt(i) to depth + 1)
            }
        }
        if (stack.isNotEmpty()) complete = false
        val owned = imposedHeights[header]
        val stillOwned = owned != null && params.height == owned.applied
        if (owned != null && !stillOwned) imposedHeights.remove(header)
        if (stillOwned && (media || !complete || occupiedBottom == 0)) {
            // Recycled/async cards must regain their native measurement.
            params.height = owned!!.original
            imposedHeights.remove(header); headerChanged = true
        } else if (!media && complete && occupiedBottom > 0) {
            val wanted = occupiedBottom + normalGap
            if ((stillOwned || header.height - occupiedBottom > excessive) && params.height != wanted) {
                val original = if (stillOwned) owned!!.original else params.height
                params.height = wanted
                imposedHeights[header] = HeightOwnership(original, wanted)
                headerChanged = true
            }
        }
        if (headerChanged) header.layoutParams = params
        if (tabsChanged) tabs.layoutParams = tabsParams
        if ((headerChanged || tabsChanged) && reported.put(content, true) == null && reports++ < 6) {
            val message = "time=${System.currentTimeMillis()} structural=true gap=$gap header=$oldHeight " +
                "bottomMargin=$oldMargin->${params.bottomMargin} tabMargin=$oldTabsMargin->${tabsParams.topMargin} " +
                "height=${params.height} occupied=$occupiedBottom\n"
            android.util.Log.i("OfflineGlassDouyin", "comment repair $message")
            val destination = java.io.File(content.context.getExternalFilesDir(null), "dy_comment_layout_status.txt")
            Thread { runCatching { destination.writeText(message) } }.start()
        }
    }
}

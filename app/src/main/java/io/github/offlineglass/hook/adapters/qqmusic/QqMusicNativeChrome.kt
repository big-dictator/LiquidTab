package io.github.offlineglass.hook.adapters.qqmusic

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.glassHostHeightPx
import io.github.offlineglass.hook.globalGlassBottomGapPx
import kotlin.math.roundToInt
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap
import android.content.res.Resources

/** Resource/class pairs are grounded in QQMusic 20.9.0.8 layout b0, d7 and c9. */
internal object QqMusicNativeChrome {
    const val SHELL_CLASS = "com.tencent.qqmusic.ui.minibar.ClipBottomConstraintLayout"
    private const val NAV_CLASS = "com.tencent.qqmusic.ui.minibar.navigation.MainDeskNavigateContainer"
    private var guidelineFailureLogged = false
    private val scenes = WeakHashMap<View, WeakReference<View>>()
    private val names = WeakHashMap<View, Pair<Int, String?>>()
    private val topFields = HashMap<Class<*>, Field>()
    private val guidelineFields = HashMap<Class<*>, List<Field>>()
    private val resourceIds = WeakHashMap<Resources, MutableMap<String, Int>>()
    fun isNavigation(view: View) = view.javaClass.name == NAV_CLASS && name(view) == "g5b"

    fun navigation(shell: ViewGroup): ViewGroup? = (0 until shell.childCount)
        .map { shell.getChildAt(it) }.firstOrNull(::isNavigation) as? ViewGroup

    fun player(shell: ViewGroup): ViewGroup? = (0 until shell.childCount)
        .map { shell.getChildAt(it) }.firstOrNull { name(it) == "g5x" } as? ViewGroup

    /** The home row remains VISIBLE behind pushed pages; StackLayout owns the foreground. */
    fun navigationPageVisible(source: View): Boolean {
        val stack = scene(source) ?: return false
        if (name(stack) != "g65") return false
        val top = runCatching {
            topFields.getOrPut(stack.javaClass) {
                stack.javaClass.getDeclaredField("mTop").apply { isAccessible = true }
            }.get(stack) as? View
        }.getOrNull()
            ?: return false
        return name(top) == "g61"
    }

    fun isMainShell(view: ViewGroup): Boolean =
        view.javaClass.name == SHELL_CLASS && name(view) in setOf("g5a", "g6h") &&
            (0 until view.childCount).any {
                val child = view.getChildAt(it)
                name(child) == "g5b" && child.javaClass.name == NAV_CLASS
            }

    fun clear(shell: ViewGroup, config: GlassConfig) {
        if (!isMainShell(shell)) return
        if (!config.enabled) return
        val gap = globalGlassBottomGapPx(shell)
        val density = shell.resources.displayMetrics.density
        val navVisible = navigation(shell)?.let { it.isShown && navigationPageVisible(it) } == true
        // The native shell crops the retained row on secondary pages. Its row is
        // already transparent here; release that crop before relocating the player.
        if (!navVisible) runCatching { XposedHelpers.callMethod(shell, "setClipBottom", 0f) }
        // Keep a small but tighter separation between the nav capsule and player.
        // k0.I0 hides the native row by translating the entire shell down by w0().
        // The guideline is relative to that translated shell, not the window bottom.
        val playerBottom = if (navVisible) glassHostHeightPx(density, config) + gap + gap / 2
            else gap + shell.translationY.roundToInt()
        // b0: player.bottom = guideline + ktp(7.5dp); d7: plate.bottom is
        // player.bottom - aim(14dp). Compensate both native offsets exactly.
        val anchorOffset = dimension(shell, "aiq", 7.5f) - dimension(shell, "aim", 14f)
        clearPlate(shell)
        for (index in 0 until shell.childCount) {
            val view = shell.getChildAt(index)
            if (QqMusicPromoHooks.suppress(view)) continue
            when (name(view)) {
                "g5b" -> if (view.javaClass.name == NAV_CLASS) {
                    // Keep native lifecycle/selection updates; only its pixels are hidden.
                    if (view.alpha != 0f) view.alpha = 0f
                    clearPlate(view)
                }
                "g5_" -> positionPlayerGuideline(view, playerBottom + anchorOffset)
                "hi7", "g5v" -> view.visibility = View.GONE
                "g5x" -> {
                    clearPlayerBackground(view)
                    // Constrain the original controls together with their glass backdrop.
                    val params = view.layoutParams as? ViewGroup.MarginLayoutParams
                    if (params != null && (params.leftMargin != gap || params.rightMargin != gap)) {
                        params.leftMargin = gap
                        params.rightMargin = gap
                        view.layoutParams = params
                    }
                }
            }
        }
    }

    private fun positionPlayerGuideline(view: View, bottom: Int) {
        val params = view.layoutParams ?: return
        runCatching {
            val fields = guidelineFields.getOrPut(params.javaClass) {
                listOf("guideEnd", "guideBegin", "guidePercent").map(params.javaClass::getField)
            }
            val end = fields[0]
            if (end.getInt(params) == bottom.coerceAtLeast(0)) return
            end.setInt(params, bottom.coerceAtLeast(0))
            fields[1].setInt(params, -1)
            fields[2].setFloat(params, -1f)
            view.layoutParams = params
        }.onFailure {
            if (!guidelineFailureLogged) {
                guidelineFailureLogged = true
                XposedBridge.log("[OfflineGlass][QQMusic] player guideline failed: $it")
            }
        }
    }

    private fun clearPlayerBackground(player: View) {
        // g5s is the wide skin plate. Album art, title, progress and playback controls
        // are separate siblings under g64 and must keep their native listeners.
        val background = player.findViewById<View>(id(player, "g5s"))
        background?.visibility = View.INVISIBLE
        clearPlate(player)
    }

    fun scene(source: View): View? {
        // c9/fu: StackLayout is a full-window sibling of the minibar shell.
        var shell: View? = source
        while (shell != null && shell.javaClass.name != SHELL_CLASS) shell = shell.parent as? View
        val shellRoot = shell ?: return null
        scenes[shellRoot]?.get()?.takeIf {
            it.isAttachedToWindow && it.rootView === shellRoot.rootView && it.isShown && it.width > 0 && it.height > 0
        }?.let { return it }
        var branch: View = shellRoot
        repeat(5) {
            val currentBranch = branch
            val parent = currentBranch.parent as? ViewGroup ?: return null
            val candidate = (0 until parent.childCount).map { parent.getChildAt(it) }
                .filter { view ->
                    view !== currentBranch && view.javaClass.name == "com.tencent.qqmusic.activity.base.StackLayout" &&
                        view.isShown && view.width > 0 && view.height > 0 &&
                        (name(view) in setOf("cvd", "o45") || view.width >= shellRoot.width * 0.75f)
                }
                .maxByOrNull { it.width.toLong() * it.height }
            if (candidate != null) {
                scenes[shellRoot] = WeakReference(candidate)
                return candidate
            }
            branch = parent
        }
        return null
    }

    /** StackLayout paints a separate transition rectangle after its page content. */
    fun ownsTransitionLayer(stack: ViewGroup): Boolean {
        val root = stack.rootView
        val shell = listOf("g5a", "g6h").firstNotNullOfOrNull { entry ->
            root.findViewById<ViewGroup>(id(stack, entry))?.takeIf(::isMainShell)
        } ?: return false
        return scene(shell) === stack &&
            (navigation(shell)?.isShown == true || player(shell)?.isShown == true)
    }

    fun id(view: View, entry: String): Int = resourceId(view, entry, "id")

    private fun resourceId(view: View, entry: String, type: String): Int {
        val cache = resourceIds.getOrPut(view.resources) { HashMap() }
        return cache.getOrPut("$type/$entry") {
            view.resources.getIdentifier(entry, type, targetSpec.packageName)
        }
    }

    fun dimension(view: View, entry: String, fallbackDp: Float): Int {
        val resource = resourceId(view, entry, "dimen")
        return if (resource != 0) view.resources.getDimensionPixelSize(resource)
        else (fallbackDp * view.resources.displayMetrics.density).toInt()
    }

    private fun name(view: View): String? {
        names[view]?.takeIf { it.first == view.id }?.let { return it.second }
        return AdapterNavigationSearch.resourceEntryName(view).also { names[view] = view.id to it }
    }

    private fun clearPlate(view: View) {
        if (view.background != null) view.background = null
        if (view.foreground != null) view.foreground = null
        if (view.backgroundTintList != null) view.backgroundTintList = null
        if (view.elevation != 0f) view.elevation = 0f
        if (view.translationZ != 0f) view.translationZ = 0f
    }
}

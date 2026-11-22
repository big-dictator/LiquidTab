package io.github.offlineglass.hook.adapters.qqmusic

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.glassHostHeightPx
import io.github.offlineglass.hook.globalGlassBottomGapPx
import kotlin.math.roundToInt
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap

/**
 * Owns QQMusic's native chrome cleanup. Every structural decision is delegated to
 * [QqMusicBinding]; this class holds only the relocation / suppression behaviour,
 * so no obfuscated resource or member name is used to gate it.
 */
internal object QqMusicNativeChrome {
    // Retained for callers that locate the shell class by its semantic name.
    const val SHELL_CLASS = QqMusicBinding.SHELL_CLASS

    private var guidelineFailureLogged = false
    private val scenes = WeakHashMap<View, WeakReference<View>>()
    private val topFields = HashMap<Class<*>, Field>()
    private val guidelineFields = HashMap<Class<*>, List<Field>>()

    fun isNavigation(view: View) = QqMusicBinding.isNavigation(view)

    fun navigation(shell: ViewGroup): ViewGroup? = QqMusicBinding.navigationOf(shell)

    fun player(shell: ViewGroup): ViewGroup? = QqMusicBinding.playerOf(shell)

    /** The home row remains VISIBLE behind pushed pages; StackLayout owns the foreground. */
    fun navigationPageVisible(source: View): Boolean {
        val stack = scene(source) ?: return false
        val top = runCatching {
            topFields.getOrPut(stack.javaClass) {
                stack.javaClass.getDeclaredField("mTop").apply { isAccessible = true }
            }.get(stack) as? View
        }.getOrNull() ?: return false
        return QqMusicBinding.isMainPage(top)
    }

    fun isMainShell(view: ViewGroup): Boolean = QqMusicBinding.isMainShell(view)

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
        // The shell hides the native row by translating itself down; the guideline
        // is relative to that translated shell, not the window bottom.
        val playerBottom = if (navVisible) glassHostHeightPx(density, config) + gap + gap / 2
            else gap + shell.translationY.roundToInt()
        // b0: player.bottom = guideline + guide-space; the plate sits at
        // player.bottom - plate-bottom. Compensate both native offsets exactly.
        val player = player(shell)
        val plateBottom = player?.let { QqMusicBinding.plateMetrics(it).bottomMarginPx }
            ?: ((14f * density).toInt())
        var guideline: View? = null
        for (index in 0 until shell.childCount) {
            val view = shell.getChildAt(index)
            if (QqMusicPromoHooks.suppress(view)) continue
            when {
                isNavigation(view) -> {
                    // Keep native lifecycle/selection updates; only its pixels are hidden.
                    if (view.alpha != 0f) view.alpha = 0f
                    clearPlate(view)
                }
                QqMusicBinding.isGuideline(view) -> guideline = view
                QqMusicBinding.isSkinPlate(view) -> view.visibility = View.GONE
                QqMusicBinding.isPlayerRoot(view) -> {
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
        guideline?.let {
            val guideSpace = QqMusicBinding.guidelineSpaceHeight(shell, it)
            positionPlayerGuideline(it, playerBottom + guideSpace - plateBottom)
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
        // The wide skin plate is a MiniBarContentChangeImageView. Album art, title,
        // progress and playback controls are separate siblings and keep their listeners.
        QqMusicBinding.findSkinPlate(player)?.visibility = View.INVISIBLE
        clearPlate(player)
    }

    fun scene(source: View): View? {
        // StackLayout is a full-window sibling of the minibar shell.
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
                    view !== currentBranch && view.javaClass.name == QqMusicBinding.STACK_CLASS &&
                        view.isShown && view.width > 0 && view.height > 0
                }
                .singleOrNull()
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
        val root = stack.rootView as? ViewGroup ?: return false
        val shell = findMainShell(root) ?: return false
        return scene(shell) === stack &&
            (navigation(shell)?.isShown == true || player(shell)?.isShown == true)
    }

    private fun findMainShell(root: ViewGroup): ViewGroup? {
        val stack = ArrayDeque<View>()
        stack.add(root)
        var seen = 0
        while (stack.isNotEmpty() && seen < 4000) {
            val view = stack.removeFirst()
            seen++
            if (view is ViewGroup) {
                if (isMainShell(view)) return view
                for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
            }
        }
        return null
    }

    private fun clearPlate(view: View) {
        if (view.background != null) view.background = null
        if (view.foreground != null) view.foreground = null
        if (view.backgroundTintList != null) view.backgroundTintList = null
        if (view.elevation != 0f) view.elevation = 0f
        if (view.translationZ != 0f) view.translationZ = 0f
    }
}

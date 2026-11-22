package io.github.offlineglass.hook.adapters.qqmusic

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Space
import android.widget.TextView
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * Contract-driven binding layer for the QQMusic native shell and navigation rows.
 *
 * QQMusic ships heavily obfuscated resource and member names that drift between
 * builds. Per the workspace robustness spec we never gate behaviour on those
 * symbols: no hardcoded short names ("g7d", "h", "m"), no candidate-name tables,
 * no version whitelists. Every element is located through stable semantic
 * anchors (unobfuscated class / entry names, standard Android interface
 * signatures) combined with structural contracts (parent/child shape, LayoutParams
 * roles, value anchors). A missing contract simply disables the dependent
 * capability instead of guessing.
 *
 * Results that need per-instance caching are keyed by the live [Class] or [View]
 * identity. An APK update produces fresh class / view objects, so the caches
 * self-invalidate and each handle is re-derived from the new APK's real structure.
 */
internal object QqMusicBinding {
    private val themedLoaders = WeakHashMap<ClassLoader, java.lang.reflect.Method>()
    private fun themedDrawable(source: View, id: Int): android.graphics.drawable.Drawable? {
        // APK Resource wrappers both delegate here; two equivalent wrappers made
        // singleOrNull reject every icon pair. Use their actual platform source.
        return runCatching { source.context.applicationContext.resources.getDrawable(id) }.getOrNull()
    }
    data class NativeIconPair(val normal: android.graphics.drawable.Drawable,
        val selected: android.graphics.drawable.Drawable)

    fun nativeIconPair(source: ViewGroup, cell: View): NativeIconPair? = runCatching {
        val item = io.github.offlineglass.hook.compat.XposedHelpers.callMethod(source,
            "getItem", cell.tag as Int) ?: return@runCatching null
        val resources = source.resources
        val ids = item.javaClass.declaredFields.filter { it.type == Int::class.javaPrimitiveType &&
            !java.lang.reflect.Modifier.isStatic(it.modifiers) }.mapNotNull { field ->
            val id = field.apply { isAccessible = true }.getInt(item)
            runCatching {
                if (resources.getResourceTypeName(id) != "drawable") return@runCatching null
                resources.getResourceEntryName(id) to id
            }.getOrNull()
        }.toMap()
        val pairs = ids.filterKeys { it.endsWith("_selected") }.mapNotNull { (name, selected) ->
            val normal = ids[name.removeSuffix("_selected") + "_normal"]
                ?: ids.filterKeys { it != name }.values.singleOrNull()
                ?: return@mapNotNull null
            val normalIcon = themedDrawable(source, normal) ?: return@mapNotNull null
            val selectedIcon = themedDrawable(source, selected) ?: return@mapNotNull null
            NativeIconPair(normalIcon, selectedIcon)
        }
        pairs.singleOrNull()
    }.getOrNull()
    /** Only a unique bottom-aligned decorative sibling of the video pager is a footer. */
    fun isVideoFooter(view: View, page: View): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        if (view.javaClass != View::class.java || view.isClickable || view.hasOnClickListeners()) return false
        if (parent !== page || !anyChild(parent) { it.javaClass.name == MAIN_CHILD_PAGER_CLASS }) return false
        fun footer(candidate: View): Boolean {
            val params = candidate.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return false
            return candidate.javaClass == View::class.java && !candidate.isClickable &&
                params.width == ViewGroup.LayoutParams.MATCH_PARENT && params.height > 0 &&
                params.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK == android.view.Gravity.BOTTOM
        }
        return footer(view) && (0 until parent.childCount).count { footer(parent.getChildAt(it)) } == 1
    }
    // ---- Stable semantic class anchors (unobfuscated in the APK) ----
    const val SHELL_CLASS = "com.tencent.qqmusic.ui.minibar.ClipBottomConstraintLayout"
    const val NAV_CLASS = "com.tencent.qqmusic.ui.minibar.navigation.MainDeskNavigateContainer"
    const val STACK_CLASS = "com.tencent.qqmusic.activity.base.StackLayout"
    const val MINIBAR_ROOT_CLASS = "com.tencent.qqmusic.ui.minibar.music.view.MinibarRootLayout"
    const val SKIN_PLATE_CLASS = "com.tencent.qqmusic.ui.MiniBarContentChangeImageView"
    const val MAIN_CHILD_PAGER_CLASS = "com.tencent.qqmusic.ui.customview.viewpager.MainDesckChildViewPager"
    const val DISCOVERY_TAB_CLASS = "com.tencent.qqmusic.ui.DiscoveryDeskTitleTabViewNew"
    const val MUSIC_HALL_RECYCLER_CLASS =
        "com.tencent.qqmusic.modular.module.musichall.views.recycler.MusicHallCellRecyclerView"

    // Semantic resource entry name marking the main desktop page subtree.
    private const val MAIN_PAGER_ENTRY = "main_desk_fragment_pager"

    // Standard ConstraintLayout.LayoutParams anchor fields (androidx, stable).
    private const val GUIDE_END = "guideEnd"
    private const val GUIDE_BEGIN = "guideBegin"
    private const val GUIDE_PERCENT = "guidePercent"
    private const val TOP_TO_TOP = "topToTop"

    // Value anchors (dp) used only when a structural measurement is unavailable.
    private const val GUIDE_SPACE_DP = 7.5f
    private const val PLATE_BOTTOM_DP = 14f
    private const val PLATE_HEIGHT_DP = 46f

    // ---- Class-identity caches: invalid naturally when an APK update replaces a class. ----
    private val mainPageCache = WeakHashMap<View, Boolean>()
    private val paramsFieldCache = WeakHashMap<Class<*>, Map<String, java.lang.reflect.Field?>>()

    /** Structural metrics read from the real skin plate, with dp fallbacks. */
    data class PlateMetrics(val heightPx: Int, val bottomMarginPx: Int)

    /** Native artwork discovered inside a navigation cell by view structure. */
    data class Artwork(
        val icons: List<ImageView>,
        val label: TextView?,
        val badges: List<View>,
    )

    // ------------------------------------------------------------------
    // Bounded structural probes
    // ------------------------------------------------------------------

    private fun forEachDescendant(root: View, budget: Int = 4000, visit: (View) -> Boolean) {
        val queue = ArrayDeque<View>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < budget) {
            val view = queue.removeFirst()
            seen++
            if (visit(view)) return
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
    }

    private inline fun anyChild(group: ViewGroup, predicate: (View) -> Boolean): Boolean {
        for (i in 0 until group.childCount) if (predicate(group.getChildAt(i))) return true
        return false
    }

    fun findEntry(root: View, entry: String): View? {
        var found: View? = null
        forEachDescendant(root) {
            if (AdapterNavigationSearch.resourceEntryName(it) == entry) {
                found = it
                true
            } else false
        }
        return found
    }

    private fun findChildByClass(group: ViewGroup, className: String): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.javaClass.name == className) return child
        }
        return null
    }

    // ------------------------------------------------------------------
    // Semantic / structural recognition
    // ------------------------------------------------------------------

    fun isNavigation(view: View): Boolean = view.javaClass.name == NAV_CLASS

    /** The player root is the frame that carries the minibar controls holder. */
    fun isPlayerRoot(view: View): Boolean =
        view is ViewGroup && findChildByClass(view, MINIBAR_ROOT_CLASS) != null

    /** A ConstraintLayout guideline is the only child configured with a guide value. */
    fun isGuideline(view: View): Boolean {
        val params = view.layoutParams ?: return false
        val fields = guideFields(params.javaClass)
        return guideActive(params, fields)
    }

    /** The wide decorative image plate that backs the native row / player skin. */
    fun isSkinPlate(view: View): Boolean {
        if (view.javaClass.name == SKIN_PLATE_CLASS) return true
        val shell = view.parent as? ViewGroup ?: return false
        if (!isMainShell(shell)) return false
        fun anchoredPlate(candidate: View): Boolean {
            if (candidate.javaClass.name != "com.tencent.qqmusic.ui.RoundedConstraintLayout") return false
            val group = candidate as? ViewGroup ?: return false
            if (group.childCount != 1 || group.isClickable || group.hasOnClickListeners()) return false
            val clip = group.getChildAt(0) as? ViewGroup ?: return false
            if (clip.javaClass.name != "com.tencent.qqmusic.ClipTopFrameLayout" || clip.childCount != 1) return false
            val image = clip.getChildAt(0) as? ImageView ?: return false
            if (image.isClickable || image.hasOnClickListeners()) return false
            val params = group.layoutParams ?: return false
            fun intField(owner: Any, name: String): Int? = runCatching {
                owner.javaClass.getField(name).getInt(owner)
            }.getOrNull()
            if (params.width != 0 || params.height != 0 || intField(params, "bottomToBottom") != 0) return false
            val anchorId = intField(params, "topToTop") ?: return false
            val anchor = (0 until shell.childCount).map(shell::getChildAt)
                .singleOrNull { it.id == anchorId && it is Space } ?: return false
            val player = playerOf(shell) ?: return false
            return player.id != View.NO_ID &&
                intField(anchor.layoutParams ?: return false, "bottomToBottom") == player.id
        }
        return anchoredPlate(view) &&
            (0 until shell.childCount).count { anchoredPlate(shell.getChildAt(it)) } == 1
    }

    /**
     * The real main shell carries BOTH the navigation row and the player frame.
     * The dual requirement rejects theme/settings preview instances that reuse the
     * shell class but lack one of the two, so cleanup never touches them.
     */
    fun isMainShell(view: ViewGroup): Boolean =
        view.javaClass.name == SHELL_CLASS &&
            (0 until view.childCount).count { isNavigation(view.getChildAt(it)) } == 1 &&
            (0 until view.childCount).count { isPlayerRoot(view.getChildAt(it)) } == 1

    /** The home page is the subtree that owns the main desktop pager. */
    fun isMainPage(view: View): Boolean = mainPageCache.getOrPut(view) {
        if (!mainPageObservers.containsKey(view)) {
            val listener = View.OnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
                mainPageCache.remove(changed)
            }
            mainPageObservers[view] = listener
            view.addOnLayoutChangeListener(listener)
        }
        findEntry(view, MAIN_PAGER_ENTRY) != null
    }

    private val mainPageObservers = WeakHashMap<View, View.OnLayoutChangeListener>()

    // ------------------------------------------------------------------
    // Family finders
    // ------------------------------------------------------------------

    fun navigationOf(shell: ViewGroup): ViewGroup? {
        for (i in 0 until shell.childCount) {
            val child = shell.getChildAt(i)
            if (isNavigation(child)) return child as? ViewGroup
        }
        return null
    }

    fun playerOf(shell: ViewGroup): ViewGroup? {
        for (i in 0 until shell.childCount) {
            val child = shell.getChildAt(i)
            if (isPlayerRoot(child)) return child as? ViewGroup
        }
        return null
    }

    /** The player's wide skin plate (a MiniBarContentChangeImageView). */
    fun findSkinPlate(player: View): View? {
        var plate: View? = null
        forEachDescendant(player, 256) {
            if (it.javaClass.name == SKIN_PLATE_CLASS) {
                plate = it
                true
            } else false
        }
        return plate
    }

    /** The Space paired with the player guideline; its height anchors the gap. */
    private fun guidelineSpace(shell: ViewGroup, guideline: View): Space? {
        for (i in 0 until shell.childCount) {
            val child = shell.getChildAt(i)
            if (child is Space && anchorTopToTop(child) == guideline.id && guideline.id != View.NO_ID) {
                return child
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Structural metrics
    // ------------------------------------------------------------------

    /** Skin plate geometry measured from the live view, with dp fallbacks. */
    fun plateMetrics(view: View): PlateMetrics {
        val plate = findSkinPlate(view)
            ?: (if (view.javaClass.name == SKIN_PLATE_CLASS) view else null)
        val density = view.resources.displayMetrics.density
        val params = plate?.layoutParams as? ViewGroup.MarginLayoutParams
        val height = params?.height?.takeIf { it > 0 } ?: dp(view, PLATE_HEIGHT_DP)
        val bottom = params?.bottomMargin?.takeIf { it > 0 } ?: dp(view, PLATE_BOTTOM_DP)
        return PlateMetrics(height, bottom)
    }

    /** Gap between the guideline and the player = paired Space height (aiq). */
    fun guidelineSpaceHeight(shell: ViewGroup, guideline: View, fallbackDp: Float = GUIDE_SPACE_DP): Int {
        val space = guidelineSpace(shell, guideline)
        val measured = space?.layoutParams?.height?.takeIf { it > 0 }
        return measured ?: dp(shell, fallbackDp)
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // ConstraintLayout.LayoutParams reflection (stable androidx field names)
    // ------------------------------------------------------------------

    private fun guideFields(paramsClass: Class<*>): Map<String, java.lang.reflect.Field?> =
        paramsFieldCache.getOrPut(paramsClass) {
            listOf(GUIDE_END, GUIDE_BEGIN, GUIDE_PERCENT, TOP_TO_TOP).associateWith { name ->
                runCatching { paramsClass.getField(name) }.getOrNull()
            }
        }

    private fun guideActive(params: Any, fields: Map<String, java.lang.reflect.Field?>): Boolean {
        fun int(name: String) = fields[name]?.let { runCatching { it.getInt(params) }.getOrDefault(-1) } ?: -1
        fun float(name: String) = fields[name]?.let { runCatching { it.getFloat(params) }.getOrDefault(-1f) } ?: -1f
        return int(GUIDE_END) >= 0 || int(GUIDE_BEGIN) >= 0 || float(GUIDE_PERCENT) >= 0f
    }

    private fun anchorTopToTop(view: View): Int {
        val params = view.layoutParams ?: return View.NO_ID
        val field = guideFields(params.javaClass)[TOP_TO_TOP] ?: return View.NO_ID
        return runCatching { field.getInt(params) }.getOrDefault(View.NO_ID)
    }

    // ------------------------------------------------------------------
    // Native artwork extraction from a navigation cell
    // ------------------------------------------------------------------

    /**
     * A cell owns one icon, one label and optional badge overlays. They are found
     * by view structure (icon = dominant image, label = longest text, badge =
     * BadgeView class or a small overlay image) rather than obfuscated accessors.
     */
    fun extractArtwork(cell: View): Artwork {
        val images = ArrayList<ImageView>()
        val texts = ArrayList<TextView>()
        val badgeViews = ArrayList<View>()
        forEachDescendant(cell, 1200) { view ->
            when {
                view is ImageView -> images.add(view)
                view is TextView -> texts.add(view)
                view.javaClass.simpleName.contains("Badge") && view !== cell -> badgeViews.add(view)
            }
            false
        }
        val sized = images.sortedByDescending { it.width.toLong() * it.height }
        val iconArea = sized.firstOrNull()?.let { it.width.toLong() * it.height } ?: 0L
        // The dominant image is the icon; anything a fraction of its size is a badge dot.
        val icons = sized.filter { it.drawable != null && it.width.toLong() * it.height >= iconArea / 3 }
            .ifEmpty { sized.filter { it.drawable != null } }
        val iconSet = icons.toSet()
        badgeViews.addAll(
            images.filter {
                it !in iconSet && it.drawable != null && iconArea > 0 &&
                    it.width.toLong() * it.height < iconArea / 3
            }
        )
        val label = texts.filter { it.text?.isNotBlank() == true }.maxByOrNull { it.text.length }
        return Artwork(icons, label, badgeViews)
    }
}

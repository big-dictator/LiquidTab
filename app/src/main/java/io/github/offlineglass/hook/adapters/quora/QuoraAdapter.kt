package io.github.offlineglass.hook.adapters.quora

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Native TabLayout owns routing, selection, themed artwork and unread badges. */
internal object QuoraAdapter : TargetAdapter {
    override val key = "quora"
    override val ownsNavigationFinding = true
    override val nativeSelectionReliable = true
    override val ownsNavigationTap = true
    override val commitSelectionAfterTap = true
    override val simpleSceneVisibilityAnimation = true
    override val recheckNavigationBindingOnLayout = true
    override val usesContentDarkMode = true
    override val sourceIconScale = 1.32f
    override fun onConfigurationReady(context: Context,
        param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        QuoraSystemTheme.install(context,param.classLoader)
        QuoraSidebarInsets.install(context,param.classLoader)
    }
    override val ownsNavigationDrawing = true
    override val drawsNavigationInOptics = true
    override val foregroundNavigationOverSelection = true
    override val usesNavigationOpticalMix = true
    override val persistentNavigationOpticalMix = false
    override val foregroundMaskInsetDp = 0f
    override val retainsExistingBackdropRenderNode = true
    private val nativeBackgrounds = java.util.WeakHashMap<ViewGroup, Drawable?>()

    // Installer calls this before clearing parent chrome. Retain its native
    // theme surface for our explicit shutdown restoration.
    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        val bar = navigation.parent as? ViewGroup ?: return
        if (!nativeBackgrounds.containsKey(bar) && row(bar) === navigation) {
            nativeBackgrounds[bar] = bar.background
        }
    }

    internal fun originalBackground(bar: ViewGroup): Drawable? = nativeBackgrounds[bar]
    internal fun forgetBackground(bar: ViewGroup) { nativeBackgrounds.remove(bar) }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val matches = ArrayList<ViewGroup>()
        val queue = java.util.ArrayDeque<Pair<View, Int>>()
        queue.add(root to 0)
        var budget = 4096
        while (queue.isNotEmpty() && budget-- > 0) {
            val (view, depth) = queue.removeFirst()
            if (view !is ViewGroup) continue
            if (name(view) == "tab_bar_tabs" && view.isShown) {
                row(view)?.let(matches::add)
            }
            if (depth < 16) for (i in 0 until view.childCount) queue.add(view.getChildAt(i) to depth + 1)
        }
        // An incomplete traversal cannot establish uniqueness.
        val source = QuoraNavigationContract.only(matches, queue.isEmpty()) ?: return null
        return NavigationCandidate(source, source.childCount, 1000)
    }

    override fun acceptsNavigationSource(navigation: ViewGroup): Boolean =
        (navigation.parent as? ViewGroup)?.let { row(it) === navigation } == true

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? =
        group.childCount.takeIf { acceptsNavigationSource(group) }

    override fun createNavigationState(context: Context): AppNavigationState = QuoraNavigationState()

    internal fun name(view: View): String? {
        val id=view.id
        // NO_ID and generated IDs have no resource entry. Avoid exceptions in traversals.
        if(id==View.NO_ID || id ushr 24==0)return null
        return runCatching { view.resources.getResourceEntryName(id) }.getOrNull()
    }

    /** No class/member names: resource ownership + Material's actual tab/custom-view hierarchy. */
    internal fun row(bar: ViewGroup): ViewGroup? {
        return (QuoraNavigationContract.row(ViewNode(bar)) as? ViewNode)?.view as? ViewGroup
    }

    internal class ViewNode(val view: View) : QuoraNavigationContract.Node {
        override fun resource() = name(view)
        override fun parent() = (view.parent as? View)?.let(::ViewNode)
        override fun children(): List<QuoraNavigationContract.Node> = (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).map { ViewNode(group.getChildAt(it)) }
        } ?: emptyList()
        override fun vertical() = view is LinearLayout && view.orientation == LinearLayout.VERTICAL
        override fun clickable() = view.isClickable
        override fun selected() = view.isSelected
        override fun measured() = view.width > 0 && view.height > 0
        override fun image() = view is ImageView
    }
}

private class QuoraNavigationState : QuoraChromeState {
    override val pageBackdrop = QuoraPageBackdrop()
    override val usesArtworkOnlyIndicatorLens = true
    private var bar: ViewGroup? = null
    private var originalTopMargin = 0
    private var appliedTopMargin: Int? = null
    private var originalBackground: Drawable? = null
    private var insetOwner: ViewGroup? = null
    private var originalBottomPadding: Int? = null
    private val webSafety = QuoraWebSafety()
    private val artwork = QuoraNavigationArtwork()
    private val topBar = QuoraTopBar(pageBackdrop)
    private val sidebar = QuoraSidebar()
    private val gradients = QuoraEdgeGradients()
    override fun onHostFrame(host: View) {
        sidebar.update(insetOwner)
        (host as? io.github.offlineglass.hook.GlassHostLayout)?.let {
            val sidebarOpen = sidebar.open()
            topBar.update(it, insetOwner, sidebarOpen)
            gradients.update(it, topBar.visibilityHost())
            pageBackdrop.update(webSafety.visibleBoundWeb(), it, topBar.glassHosts())
        }
    }
    override fun pageAllowsNavigation(root: View?): Boolean? = if(sidebar.open()) false else null
    override fun interceptNavigationTouch(host: View,event: android.view.MotionEvent): Boolean? =
        if(sidebar.open()) true else null

    override fun prepare(source: ViewGroup?, schedule: (Long, () -> Unit) -> Unit) = artwork.prepare(source)
    override val readyForDrawing: Boolean get() = artwork.ready
    override fun onHostTouchDown(host: View,selectedIndex: Int) { artwork.gestureActive=true }
    override fun onBarGesture(phase: Int,host: View) { artwork.gestureActive=phase!=2 }
    override fun performTap(host: View,source: ViewGroup?,index: Int,slotCount: Int): Boolean {
        val row=source ?: return false
        if(sidebar.open()||!QuoraAdapter.acceptsNavigationSource(row)||index !in 0 until row.childCount)return false
        val cell=row.getChildAt(index)
        return cell.isAttachedToWindow&&cell.isClickable&&cell.performClick()
    }
    override fun resolveSelectedIndex(source: ViewGroup?,slotCount: Int): Int? =
        QuoraViews.selected(source).takeIf { it>=0 }
    override fun includeNavigationInIndicatorScene(surfaceActive: Boolean) = true
    override fun draw(canvas: android.graphics.Canvas, frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame) =
        artwork.draw(canvas, frame)
    override fun drawForeground(canvas: android.graphics.Canvas,
        frame: io.github.offlineglass.hook.adapters.AdapterNavigationFrame,
        selectionPath: android.graphics.Path, drawSource: () -> Unit): Boolean =
        artwork.drawForeground(canvas, frame, selectionPath, drawSource)

    override fun adjustFloatingActions(host: View, source: ViewGroup?) {
        // Chrome animation is not page navigation. Retain DOM head/tail space
        // for the whole sidebar transaction, including the first re-entry frame.
        if(sidebar.open()||!host.isShown||
            (host as? io.github.offlineglass.hook.GlassHostLayout)?.barVisibilityAnimating==true)return
        webSafety.update(host, source, insetOwner, topBar.contentSafetyBottom())
    }

    override fun suppressNativeChrome(source: ViewGroup?) {
        val current = source?.parent as? ViewGroup ?: return
        if (bar !== current) {
            restore()
            if (QuoraAdapter.row(current) !== source) return
            bar = current
            originalBackground = QuoraAdapter.originalBackground(current)
            originalTopMargin = (current.layoutParams as? LinearLayout.LayoutParams)?.topMargin ?: return
            insetOwner = current.parent as? ViewGroup
        }
        extendThroughNavigationInset()
        // QTabBar's native theme callback may replace the parent surface while
        // the source row stays bound. Keep the new drawable for disable, then
        // remove its opaque pixels without touching the native icon drawables.
        current.background?.let { background ->
            if (background !is android.graphics.drawable.ColorDrawable ||
                background.color != android.graphics.Color.TRANSPARENT) originalBackground = background
            current.background = null
        }
        val params = current.layoutParams as? LinearLayout.LayoutParams ?: return
        // activity_quora.xml: weighted content_wrapper followed by the tab bar.
        // Cancel only the bar's measured layout contribution; keep it measured,
        // within the window, and alive for snapshots, selection and native clicks.
        val height = current.height.takeIf { it > 0 } ?: return
        val desired = originalTopMargin - height
        if (params.topMargin != desired) {
            params.topMargin = desired
            appliedTopMargin = desired
            current.layoutParams = params
        }
    }

    private fun extendThroughNavigationInset() {
        // Verified EdgeToEdgeUtil.applyNonTopWindowInsetsPadding writes only
        // systemBars/displayCutout insets to the shared tab_container. Do not
        // replace its listener, touch web CSS, or consume keyboard insets.
        val owner = insetOwner ?: return
        val insets = owner.rootWindowInsets ?: return
        val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        if (!QuoraNavigationContract.isNavigationPadding(owner.paddingBottom,
                insets.getInsets(types).bottom,
                insets.getInsetsIgnoringVisibility(types).bottom)) return
        originalBottomPadding = owner.paddingBottom
        owner.setPadding(owner.paddingLeft, owner.paddingTop, owner.paddingRight, 0)
    }

    private fun restore() {
        webSafety.restore()
        insetOwner?.let { owner ->
            originalBottomPadding?.let { padding ->
                if (owner.paddingBottom == 0) {
                    owner.setPadding(owner.paddingLeft, owner.paddingTop, owner.paddingRight, padding)
                    owner.requestApplyInsets()
                }
            }
        }
        insetOwner = null
        originalBottomPadding = null
        bar?.let { view ->
            val params = view.layoutParams as? LinearLayout.LayoutParams ?: return@let
            if (params.topMargin == appliedTopMargin) {
                params.topMargin = originalTopMargin
                view.layoutParams = params
            }
            if (view.background == null) view.background = originalBackground
            QuoraAdapter.forgetBackground(view)
        }
        bar = null
        appliedTopMargin = null
        originalBackground = null
    }

    override fun dispose() {
        pageBackdrop.dispose()
        gradients.clear()
        sidebar.restore();topBar.dispose(); restore(); artwork.prepare(null)
    }

    override fun onHostDetached() = pageBackdrop.dispose()

    override fun onHostWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) pageBackdrop.dispose()
    }
}

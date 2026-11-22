package io.github.offlineglass.hook.adapters.apple_music

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import org.xmlpull.v1.XmlPullParser

/** Replaces only the material behind the native search input. */
internal class AppleMusicSearchGlass(private val context: Context) {
    private enum class Layout { LANDING, EDITOR, RESULTS }

    private val resources = context.resources
    private fun id(name: String) = resources.getIdentifier(name, "id", targetSpec.packageName)
    private val fragmentTag = id("fragment_container_view_tag")
    private val landingId = id("floating_search_bar")
    private val offlineId = id("search_container")
    private val headerId = id("header_title")
    private val queryId = id("search_bar")
    private val editorContainerId = id("fragment_container")
    private val editorPagerId = id("root_pager_layout")
    private val queryContextId = id("query_context_container")
    private val resultsId = id("results_container")
    private val strokeId = id("search_view_background_stroke")
    private val backgroundId = id("search_view_background")
    private val fieldId = id("searchview")
    private val dummyId = id("dummyView")
    private val blurId = id("floating_search_bar_blur")
    private val targetId = id("blur_target")
    private val navHostId = id("nav_host_fragment_activity_main")
    private val skipBranches = setOf(id("landing_search_recycler"), id("floating_app_bar_container"),
        id("player_coordinator"), id("top_and_bottom_gradients"), id("bottom_nav_view"))
        .filter { it != 0 }.toSet()

    private val routes by lazy(LazyThreadSafetyMode.NONE) {
        readRoutes("search_navigation", mapOf(id("top_level_search_page") to Layout.LANDING)) +
            readRoutes("nav_graph", mapOf(id("recently_search_fragment") to Layout.EDITOR,
                id("results_search_fragment") to Layout.RESULTS))
    }
    private var root: View? = null
    private var navigationScope: View? = null
    private var observer: ViewTreeObserver? = null
    private var needsDiscovery = true
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener { needsDiscovery = true }
    private val bindings = LinkedHashMap<View, Binding>(4)
    private val location = IntArray(2)

    fun update(root: View, bottom: GlassHostLayout?) {
        if (this.root !== root) {
            clear()
            this.root = root
            observer = root.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutChanged) }
        }
        val iterator = bindings.values.iterator()
        while (iterator.hasNext()) {
            val current = iterator.next()
            if (!current.isCurrent(needsDiscovery)) {
                current.release()
                iterator.remove()
                needsDiscovery = true
            }
        }
        if (needsDiscovery) {
            needsDiscovery = false
            val scope = navigationScope?.takeIf { it.isAttachedToWindow && it.isShown }
                ?: root.findViewById<View>(navHostId)?.also { navigationScope = it }
            discover(scope ?: root)
        }
        bindings.values.forEach { it.update(bottom) }
    }

    fun overlaps(other: Rect): Boolean = bindings.values.any { it.visible && Rect.intersects(it.bounds, other) }

    fun restoreOptics() {
        needsDiscovery = true
        bindings.values.forEach { if (it.visible) it.glass?.restoreOpticalSceneForAdapter() }
    }

    fun clear() {
        val tree = observer?.takeIf { it.isAlive } ?: root?.viewTreeObserver?.takeIf { it.isAlive }
        tree?.removeOnGlobalLayoutListener(layoutChanged)
        observer = null
        root = null
        navigationScope = null
        bindings.values.forEach(Binding::release)
        bindings.clear()
        needsDiscovery = true
    }

    private fun readRoutes(graph: String, destinations: Map<Int, Layout>): Map<String, Layout> {
        val resource = resources.getIdentifier(graph, "navigation", targetSpec.packageName)
        if (resource == 0) return emptyMap()
        return runCatching {
            resources.getXml(resource).use { xml ->
                buildMap {
                    while (xml.next() != XmlPullParser.END_DOCUMENT) {
                        if (xml.eventType != XmlPullParser.START_TAG || xml.name != "fragment") continue
                        val destination = xml.getAttributeResourceValue(ANDROID_NS, "id", View.NO_ID)
                        val layout = destinations[destination] ?: continue
                        val name = xml.getAttributeValue(ANDROID_NS, "name") ?: continue
                        put(if (name.startsWith(".")) targetSpec.packageName + name else name, layout)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun discover(root: View) {
        if (fragmentTag == 0 || routes.isEmpty()) return
        val pending = ArrayDeque<View>()
        pending.add(root)
        var budget = 180
        while (pending.isNotEmpty() && budget-- > 0) {
            val view = pending.removeFirst() as? ViewGroup ?: continue
            if (!view.isShown || view.id in skipBranches || view is GlassHostLayout) continue
            val fragment = view.getTag(fragmentTag)
            when (routes[fragment?.javaClass?.name]) {
                Layout.LANDING -> discoverLanding(view)
                Layout.EDITOR -> if (allowsSearchInput(fragment)) {
                    if (view.uniqueChild(editorContainerId) is ViewGroup && view.uniqueChild(editorPagerId) is ViewGroup) {
                        bind(view, queryId)
                    }
                }
                Layout.RESULTS -> if (allowsSearchInput(fragment)) {
                    val coordinator = (0 until view.childCount).mapNotNull { view.getChildAt(it) as? ViewGroup }
                        .singleOrNull { it.uniqueChild(queryContextId) != null && it.uniqueChild(resultsId) != null }
                    if (coordinator != null) bind(view, queryId)
                }
                null -> for (index in 0 until minOf(view.childCount, 30)) pending.add(view.getChildAt(index))
            }
        }
    }

    private fun allowsSearchInput(fragment: Any?): Boolean = runCatching {
        // Playlist pickers reuse the editor/results fragments with a different mode.
        fragment ?: return false
        val method = fragment.javaClass.getMethod("isAddMusicMode")
        method.returnType == Boolean::class.javaPrimitiveType && method.invoke(fragment) == false
    }.getOrDefault(false)

    private fun discoverLanding(page: ViewGroup) {
        val target = page.uniqueChild(targetId) as? ViewGroup
        val blur = page.uniqueChild(blurId)
        if (target != null && blur != null) bind(page, landingId, blur, target)
        // The error/loader binding can have its own include and input hierarchy.
        for (index in 0 until page.childCount) {
            val layer = page.getChildAt(index) as? ViewGroup ?: continue
            val candidates = sequenceOf(layer) + sequence {
                for (child in 0 until minOf(layer.childCount, 12)) {
                    (layer.getChildAt(child) as? ViewGroup)?.let { yield(it) }
                }
            }
            candidates.filter { it.isShown && it.uniqueChild(headerId) != null }.forEach { bind(it, offlineId) }
        }
    }

    private fun bind(container: ViewGroup, searchId: Int, blur: View? = null, target: ViewGroup? = null) {
        val search = container.uniqueChild(searchId) as? ViewGroup ?: return
        if (bindings.containsKey(search)) return
        val stroke = search.uniqueChild(strokeId) as? FrameLayout ?: return
        val background = stroke.uniqueChild(backgroundId) as? ViewGroup ?: return
        val field = background.uniqueChild(fieldId) ?: return
        bindings[search] = Binding(container, search, stroke, background, field, background.uniqueChild(dummyId), blur, target)
        ArtistDiagnostics.log("search glass bound ${resources.getResourceEntryName(searchId)} feed=${target != null}")
    }

    private inner class Binding(
        private val container: ViewGroup,
        private val search: ViewGroup,
        private val stroke: FrameLayout,
        private val background: ViewGroup,
        private val field: View,
        private val dummy: View?,
        private val blur: View?,
        private val target: ViewGroup?,
    ) {
        private val ambient = AppleMusicPanelAmbient(search)
        private var strokeBackground: Drawable? = null
        private var fieldBackground: Drawable? = null
        private var blurAlpha: Float? = null
        private var owner: GlassHostLayout? = null
        private var lastConfig: GlassConfig? = null
        private var material = TopBarMaterialPolicy(TopBarRole.SEARCH, selectedAccent = false)
        var glass: GlassHostLayout? = null
            private set
        val bounds = Rect()
        var visible = false
            private set

        fun isCurrent(checkStructure: Boolean): Boolean {
            if (!stroke.isAttachedToWindow || search.parent !== container || stroke.parent !== search ||
                background.parent !== stroke || field.parent !== background ||
                (dummy != null && dummy.parent !== background) ||
                (blur != null && blur.parent !== container) || (target != null && target.parent !== container)) return false
            return !checkStructure || (container.uniqueChild(search.id) === search &&
                search.uniqueChild(strokeId) === stroke && stroke.uniqueChild(backgroundId) === background &&
                background.uniqueChild(fieldId) === field && background.uniqueChild(dummyId) === dummy &&
                (blur == null || container.uniqueChild(blurId) === blur) &&
                (target == null || container.uniqueChild(targetId) === target))
        }

        fun update(bottom: GlassHostLayout?) {
            suppressNativeMaterial()
            val wasVisible = visible
            visible = (field.isShown || dummy?.isShown == true) && stroke.getLocalVisibleRect(bounds) &&
                generateSequence(stroke as View) { it.parent as? View }.all { it.alpha > .001f }
            if (!visible) {
                bounds.setEmpty()
                glass?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
                return
            }
            stroke.getLocationOnScreen(location)
            bounds.offset(location[0], location[1])
            if (bottom == null) return
            if (owner !== bottom) {
                detachGlass()
                owner = bottom
            }
            val heightDp = stroke.height / stroke.resources.displayMetrics.density
            val configChanged = lastConfig != bottom.config || material.heightDp != heightDp
            if (configChanged) {
                lastConfig = bottom.config
                material = material.copy(heightDp = heightDp)
            }
            val panel = glass ?: createPanel(bottom).also { glass = it }
            if (panel.width != stroke.width || panel.height != stroke.height) {
                panel.measure(View.MeasureSpec.makeMeasureSpec(stroke.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(stroke.height, View.MeasureSpec.EXACTLY))
                panel.layout(0, 0, stroke.width, stroke.height)
            }
            if (configChanged) panel.refreshAdapterDerivedConfig()
            if (panel.visibility != View.VISIBLE) panel.visibility = View.VISIBLE
            if (!wasVisible) panel.restoreOpticalSceneForAdapter()
            // The field and its ambient overlay share the moving native search container.
            ambient.refresh(panel.isDarkGlass(), 1f)
        }

        private fun createPanel(bottom: GlassHostLayout): GlassHostLayout {
            val state = object : AppNavigationState {
                override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = visible
                override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = visible
                override fun pauseBackdropWork(host: View) = !visible
                override fun allowsHostTouch() = false
                override fun drawsNavigationContent() = false
                override fun resolveAutoDarkMode(host: View) = bottom.isDarkGlass()
                override fun drawsPanelAmbientShadow() = false
                override fun drawAdditionalAmbientShadow(canvas: android.graphics.Canvas, dark: Boolean, alphaScale: Float) {
                    ambient.refresh(dark, alphaScale)
                }
                override val allowsSoftwareBackdropCapture = false
                override fun requiresSampledBackdrop() = target != null
                override fun resolveBackdropScene(host: View): View? = target
            }
            val adapter = object : TargetAdapter {
                override val key = "apple_music_search"
                override val ownsHostLayout = true
                override val skipNavigationSnapshot = true
                override val skipBackdropStableDelay = true
                override val allowsSystemBackgroundBlur = false
                override fun normalizeConfig(config: GlassConfig) = material.apply(bottom.config)
                override fun pushedConfig() = material.apply(bottom.config)
                override fun createNavigationState(context: Context) = state
            }
            return GlassHostLayout(stroke.context, material.apply(bottom.config), 1,
                navigationSource = null, sliderEnabled = false, adapterOverride = adapter).apply {
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                ambient.bind(this)
                stroke.addView(this, 0, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                ))
            }
        }

        private fun suppressNativeMaterial() {
            stroke.background?.let { strokeBackground = it; stroke.background = null }
            background.background?.let { fieldBackground = it; background.background = null }
            blur?.let { layer ->
                if (layer.alpha != 0f) {
                    blurAlpha = layer.alpha
                    layer.alpha = 0f
                }
            }
        }

        private fun detachGlass() {
            glass?.let { (it.parent as? ViewGroup)?.removeView(it) }
            glass = null
        }

        fun release() {
            visible = false
            detachGlass()
            if (stroke.background == null) strokeBackground?.let { stroke.background = it }
            if (background.background == null) fieldBackground?.let { background.background = it }
            blurAlpha?.let { if (blur?.alpha == 0f) blur.alpha = it }
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}

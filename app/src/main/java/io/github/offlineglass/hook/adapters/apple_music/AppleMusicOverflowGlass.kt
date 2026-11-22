package io.github.offlineglass.hook.adapters.apple_music

import android.content.Context
import android.content.res.Configuration
import android.view.Menu
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.SpringFloat
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.spotify.SpotifyComposeIndex
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser

/** Replaces the floating overflow platter, preserving the Compose click and menu anchor. */
internal class AppleMusicOverflowGlass(private val context: Context) {
    private var host: GlassHostLayout? = null
    private val bottom get() = checkNotNull(host)
    private var menuRenderer: AppleMusicOverflowMenu? = null
    private val searchGlass = AppleMusicSearchGlass(context)
    private val detailsToolbar = AppleMusicLibraryDetailsToolbar(context, this)
    private val overflowMenu get() = checkNotNull(menuRenderer)
    private val resources = context.resources
    private val packageName = targetSpec.packageName
    private val tabsId = id("tab_navigation_host")
    private val contentId = id("content_container")
    private val backdropId = id("blur_target")
    private val navId = id("bottom_nav_view")
    private val fragmentTag = id("fragment_container_view_tag")
    private val nativeBlurId = id("option_button_blur")
    private val appBarId = id("floating_app_bar_container")
    private val searchDestination = id("top_level_search_page")
    private val overflowLabel = resources.getIdentifier("more_options", "string", packageName)
    private val icon = resources.getIdentifier("ic_navbar_platter_more", "drawable", packageName)
        .takeIf { it != 0 }?.let(context::getDrawable)?.mutate()
    private val destinationClasses = HashMap<Int, String?>()
    private val libraryId = id("top_level_library_page")
    private val playlistLabel = resources.getIdentifier("new_playlist_button", "string", packageName)
    private val playlistIcon = resources.getIdentifier("toolbar_new_playlist", "drawable", packageName)
        .takeIf { it != 0 }?.let { resources.getDrawable(it, context.theme).mutate() }
    private val startupDestinations by lazy {
        listOf("home", "new", "radio", "library", "search").map { id("top_level_${it}_page") }.filter { it != 0 }
    }
    private var startupObserver: ViewTreeObserver? = null
    private var startupListener: ViewTreeObserver.OnPreDrawListener? = null
    private val startupRoots = ArrayList<Any>()
    private var startupAttempts = 0
    private var startupView: View? = null
    private var startupSelection: (() -> Int)? = null
    private val screenPosition = IntArray(2)
    private var libraryPlaylist: Button? = null
    private var libraryMore: Button? = null
    private var playlistMask: Button? = null
    private var libraryLayoutChanged = true
    private var detailsLayoutChanged = true
    private var libraryPairSeenAt = 0L
    private var libraryFirstFrames = 0
    private var touchOwner: View? = null
    private var libraryProbeAt = 0L
    private val libraryFrame = Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val touchLight = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressHeld = false
    private var downX = 0f
    private var downY = 0f
    private var pressProgress = 0f
    private val pressSpring = lazy {
        SpringFloat(0f, 300f, .55f, .001f) { value, _ ->
            pressProgress = value
            panel?.let { glass ->
                val scale = 1f + .06f * value.coerceIn(-.2f, 1.2f)
                glass.scaleX = scale
                glass.scaleY = scale
                glass.invalidate()
                panelAmbient?.refresh(glass.isDarkGlass(), 1f)
            }
        }
    }
    private val endPress = Runnable { if (pressSpring.isInitialized()) pressSpring.value.animateTo(0f) }
    private val visibleBounds = Rect()
    private var native: Button? = null
    private val maskedButtons = HashMap<Int, Button>()
    private val knownButtons = HashMap<Int, Button>()
    private var holdVisualUntil = 0L
    private var panelVisible = false
    private var panelOccluded = false
    private var panelRetiring = false
    private val displayedBounds = Rect()
    private var pendingPageMotion = false
    private val motionStart = RectF()
    private val displayedPanel = RectF()
    private val motionGlyphStart = RectF()
    private val displayedMoreGlyph = RectF()
    private var motionProgress = 1f
    private var playlistFadeProgress = 1f
    private var playlistStartAlpha = 0f
    private var playlistAlpha = 0f
    private var playlistAtRight = false
    private var lastPlaylistGeometry: ActionGeometry? = null
    private var toolbarMore: ActionGeometry? = null
    private val toolbarTarget = Rect()
    private val pageMotion = lazy {
        SpringFloat(1f, 290f, .70f, .001f) { progress, _ ->
            motionProgress = progress
            panel?.postInvalidateOnAnimation()
        }
    }
    private var sourceContent: ViewGroup? = null
    private var scope: ViewGroup? = null
    private var opticalScene: ViewGroup? = null
    private var opticalSceneReady = false
    private var refreshNativeSceneUntil = 0L
    private var ownershipGapUntil = 0L
    private var destination = View.NO_ID
    private var nextProbe = 0L
    private var misses = 0
    private var presented = false
    private var verifyBinding = true
    private var restorePanelScene = false
    private var lastTrace = ""
    private var tracedOptics: Pair<Int, Boolean>? = null
    private var panel: GlassHostLayout? = null
    private var panelAmbient: AppleMusicPanelAmbient? = null
    private var previousConfig: GlassConfig? = null
    private var panelDark: Boolean? = null
    private var material = TopBarMaterialPolicy(TopBarRole.ACTION, iconOnly = true, selectedAccent = false)
    private val layoutChanged = ViewTreeObserver.OnGlobalLayoutListener {
        verifyBinding = true
        libraryLayoutChanged = true
        detailsLayoutChanged = true
        libraryProbeAt = 0L
        retry()
    }
    private val scrolled = ViewTreeObserver.OnScrollChangedListener {
        if (native == null && misses >= 4) retry()
    }

    private fun id(name: String) = resources.getIdentifier(name, "id", packageName)

    private fun trace(line: String) {
        if (line == lastTrace) return
        lastTrace = line
        ArtistDiagnostics.attach(context)
        ArtistDiagnostics.log("overflow $line")
    }

    fun watchFirstDraw(decor: View) {
        if (host != null || startupListener != null || overflowLabel == 0 || icon == null) return
        startupAttempts = 0
        val observer = decor.viewTreeObserver
        startupObserver = observer
        startupView = decor
        startupListener = ViewTreeObserver.OnPreDrawListener {
            if (++startupAttempts > 12) stopWatchingFirstDraw()
            else tryPrimeToolbar(decor)
            true
        }.also(observer::addOnPreDrawListener)
    }

    private fun tryPrimeToolbar(decor: View) {
        runCatching { primeToolbar(decor) }.onFailure {
            trace("startup toolbar probe rejected: ${it.cause ?: it}")
        }
    }

    private fun primeToolbar(decor: View) {
        val content = decor.findViewById<ViewGroup>(contentId) ?: return
        val navigation = content.uniqueChild(navId) as? ViewGroup ?: return
        if (sourceContent !== content) startupSelection = null
        setScope(content)
        searchGlass.update(decor, host)
        val tabHost = scope ?: return
        detailsToolbar.update(tabHost, null, refresh = true)
        val selected = (startupSelection ?: createStartupSelection(navigation)?.also { startupSelection = it })
            ?.invoke()?.takeIf { it in startupDestinations }
        val candidates = if (selected != null && findPage(tabHost, selected) != null) listOf(selected) else startupDestinations
        for (id in candidates) {
            val page = findPage(tabHost, id) ?: continue
            val cached = maskedButtons[id]?.takeIf { it.owner.isAttachedToWindow && it.owner.isShown }
            if (cached != null && (id != libraryId || playlistMask?.owner === cached.owner)) continue
            val more = findInPage(page, resources.getString(overflowLabel), onOwner = { owner ->
                observeFirstToolbarDraw(owner, decor)
            }) ?: continue
            knownButtons[id] = more
            maskOverflow(id, more)
            if (id == libraryId) warmLibraryPlaylist(more)
        }
    }

    private fun createStartupSelection(navigation: View): (() -> Int)? {
        val menu = runCatching { navigation.javaClass.getMethod("getMenu").invoke(navigation) as? Menu }.getOrNull()
            ?: return null
        return {
            var selected = View.NO_ID
            for (index in 0 until menu.size()) {
                val item = menu.getItem(index)
                if (item.isChecked && item.isVisible) {
                    if (selected != View.NO_ID) {
                        selected = View.NO_ID
                        break
                    }
                    selected = item.itemId
                }
            }
            selected
        }
    }

    private fun observeFirstToolbarDraw(owner: View, decor: View) {
        if (startupRoots.size >= 6) return
        runCatching {
            val abi = OverflowDraw.abi(owner)
            val root = abi.root.invoke(owner) ?: return
            if (startupRoots.any { it === root }) return
            startupRoots.add(root)
            OverflowDraw.observeRoot(abi.draw, root) {
                // Compose performs its final measure before calling its root draw.
                if (startupListener != null) tryPrimeToolbar(decor)
            }
        }
    }

    private fun stopWatchingFirstDraw() {
        startupListener?.let { listener ->
            startupObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                ?: startupView?.viewTreeObserver
                    ?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        startupListener = null
        startupObserver = null
        startupView = null
        startupSelection = null
        startupRoots.forEach(OverflowDraw::stopObservingRoot)
        startupRoots.clear()
    }

    fun attachHost(value: GlassHostLayout) {
        if (host === value) return
        host?.let(::detachHost)
        host = value
        menuRenderer = AppleMusicOverflowMenu(value)
    }

    fun detachHost(value: GlassHostLayout) {
        if (host === value) dispose()
    }

    private fun setScope(content: ViewGroup?) {
        if (sourceContent === content && scope?.isAttachedToWindow == true) return
        detailsToolbar.clear()
        releaseMasks()
        knownButtons.clear()
        native = null
        sourceContent = content
        removeScopeListeners()
        opticalScene = content?.uniqueChild(backdropId) as? ViewGroup
        scope = opticalScene?.uniqueChild(tabsId) as? ViewGroup
        opticalSceneReady = false
        libraryLayoutChanged = true
        detailsLayoutChanged = true
        scope?.viewTreeObserver?.addOnGlobalLayoutListener(layoutChanged)
        scope?.viewTreeObserver?.addOnScrollChangedListener(scrolled)
        retry()
    }

    fun retry() {
        nextProbe = 0L
        misses = 0
    }

    fun restoreOptics() {
        retry()
        panel?.restoreOpticalSceneForAdapter()
        searchGlass.restoreOptics()
        overflowMenu.restoreOptics()
        detailsToolbar.restoreOptics()
    }

    fun update(content: ViewGroup?, selectedId: Int, navigationPresented: Boolean, glassEnabled: Boolean) {
        val now = SystemClock.uptimeMillis()
        val liveContent = content ?: sourceContent?.takeIf { glassEnabled && it.isAttachedToWindow }
        if (sourceContent !== liveContent || scope?.isAttachedToWindow != true && liveContent != null) setScope(liveContent)
        if (glassEnabled) searchGlass.update(bottom.rootView, bottom) else searchGlass.clear()
        val tabHost = scope
        val detailsActive = if (glassEnabled && tabHost != null) {
            val refresh = detailsLayoutChanged
            detailsLayoutChanged = false
            detailsToolbar.update(tabHost, bottom, refresh, ::displayedMenuFrame)
        } else {
            detailsToolbar.clear()
            false
        }
        val active = glassEnabled && navigationPresented && tabHost != null && overflowLabel != 0 && icon != null &&
            !detailsActive
        if (presented != active) {
            presented = active
            native = null
            verifyBinding = true
            retry()
            if (active) restorePanelScene = true
        }
        if (destination != selectedId && (navigationPresented || !glassEnabled)) {
            opticalSceneReady = false
            panel?.invalidateOpticalBackdropForAdapter()
            clearLibraryPair()
            libraryFirstFrames = 0
            holdVisualUntil = if (panelVisible && isMainDestination(selectedId)) now + 320L else 0L
            pendingPageMotion = holdVisualUntil > now
            if (holdVisualUntil > now) bottom.rootView.postInvalidateDelayed(holdVisualUntil - now)
            destination = selectedId
            native = null
            retry()
        }
        if (!detailsActive && glassEnabled && tabHost != null && overflowLabel != 0 && libraryLayoutChanged) {
            libraryLayoutChanged = false
            // The selected Library is discovered below. Prewarm only when
            // Compose has attached its toolbar ahead of selection.
            if (selectedId != libraryId || !active) warmLibraryToolbar(tabHost)
        }
        if (!active) {
            if (glassEnabled && tabHost != null && selectedId == View.NO_ID && panelVisible) {
                if (ownershipGapUntil == 0L) {
                    ownershipGapUntil = now + 96L
                    bottom.postInvalidateDelayed(96L)
                }
                if (now < ownershipGapUntil) {
                    pendingPageMotion = true
                    clearOpticalScene()
                    return
                }
            }
            trace("suspended selected=$selectedId visible=$navigationPresented scope=${tabHost != null}")
            overflowMenu.update(null, null, null)
            if (!glassEnabled || tabHost == null) {
                releaseMasks()
            }
            if (!glassEnabled) stopWatchingFirstDraw()
            native = null
            holdVisualUntil = 0L
            show(null, now, handedOff = detailsToolbar.acceptedIncomingMenu || !glassEnabled)
            return
        }
        ownershipGapUntil = 0L
        playlistMask?.takeUnless { it.owner.isAttachedToWindow }?.let { stale ->
            release(stale)
            playlistMask = null
        }
        val masks = maskedButtons.iterator()
        while (masks.hasNext()) {
            val entry = masks.next()
            if (!entry.value.owner.isAttachedToWindow) {
                release(entry.value)
                masks.remove()
            }
        }
        if (native?.owner?.isShown == false) {
            native = null
            retry()
        }
        val current = native
        if (current != null) {
            // Follow the native semantics every traversal, including placement
            // changes during a swipe and the first frame of a hide.
            val measured = measureButton(current, overflowLabel)
            native = measured?.let {
                if (verifyBinding) {
                    bind(current.owner, current.virtualId, it)
                } else current.also { button -> button.geometry = it }
            }
            if (native == null) retry()
        }
        verifyBinding = false
        val probeLimit = if (selectedId == libraryId) 8 else 4
        if (native == null && now >= nextProbe && misses < probeLimit) {
            val cached = knownButtons[selectedId]?.takeIf {
                it.owner.isAttachedToWindow && it.owner.isShown &&
                    generateSequence(it.owner) { view -> view.parent as? View }.any { view -> view === tabHost }
            }
            native = cached?.let {
                readButton(it.owner, it.virtualId, resources.getString(overflowLabel))?.let { bounds ->
                    bind(it.owner, it.virtualId, bounds)
                }
            } ?: discover(tabHost, selectedId)
            misses++
            val delay = if (misses <= 3 || selectedId == libraryId && misses <= 5) 16L else 100L * misses
            nextProbe = now + delay
            if (native == null && misses < probeLimit) bottom.rootView.postInvalidateDelayed(delay)
        }
        val anchor = native ?: maskedButtons[destination]
        val currentButton = native
        trace("destination=$destination selected=$selectedId button=${currentButton != null} " +
            "owner=${currentButton?.owner?.let(System::identityHashCode)} " +
            "node=${currentButton?.node?.let(System::identityHashCode)} " +
            "blur=${currentButton?.nativeBlur != null}")
        overflowMenu.update(tabHost.takeIf { isMainDestination(selectedId) },
            anchor?.owner, anchor?.bounds?.takeUnless { selectedId == searchDestination && searchGlass.overlaps(it) })
        if (native == null && now < holdVisualUntil) {
            clearOpticalScene()
            panel?.let(::syncOverflowVisibility)
            return
        }
        holdVisualUntil = 0L
        show(native, now)
    }

    private fun measureButton(button: Button, label: Int, force: Boolean = verifyBinding): ActionGeometry? {
        button.owner.getLocationOnScreen(screenPosition)
        val moved = screenPosition[0] != button.ownerX || screenPosition[1] != button.ownerY
        if (!force && !moved && !button.owner.isDirty &&
            button.nativeBlur?.isDirty != true) return button.geometry
        button.ownerX = screenPosition[0]
        button.ownerY = screenPosition[1]
        return readButton(button.owner, button.virtualId, resources.getString(label))?.let { geometry ->
            button.nativeBlur?.let(::viewBounds)?.let { geometry.copy(bounds = it) } ?: geometry
        }
    }

    fun measureDetailsButton(button: Button, label: Int): ActionGeometry? = measureButton(button, label, force = false)

    private fun isMainDestination(id: Int): Boolean =
        destinationClasses.getOrPut(id) { destinationClass(id) } != null

    private fun viewBounds(view: View): Rect? {
        if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) return null
        if (!view.getLocalVisibleRect(visibleBounds)) return null
        view.getLocationOnScreen(screenPosition)
        val left = screenPosition[0]
        val top = screenPosition[1]
        return Rect(left, top, left + view.width, top + view.height)
    }

    private fun discover(tabHost: ViewGroup, selectedId: Int): Button? {
        val page = findPage(tabHost, selectedId)
        if (page == null) {
            if (misses == 3) ArtistDiagnostics.log("overflow discovery destination=$selectedId no unique page")
            return null
        }
        val button = findInPage(page, resources.getString(overflowLabel))
        if (button == null && misses == 3) {
            ArtistDiagnostics.log("overflow discovery destination=$selectedId no unique header action")
        }
        return button
    }

    private fun findPage(tabHost: ViewGroup, selectedId: Int): View? {
        if (fragmentTag == 0) return null
        val className = destinationClasses.getOrPut(selectedId) { destinationClass(selectedId) } ?: return null
        val pending = ArrayDeque<View>()
        pending.add(tabHost)
        var foundPage: View? = null
        var budget = 96
        while (pending.isNotEmpty() && budget-- > 0) {
            val view = pending.removeFirst()
            if (!view.isShown || view.width <= 0 || view.height <= 0) continue
            if (view.getTag(fragmentTag)?.javaClass?.name == className) {
                if (foundPage != null) return null
                foundPage = view
            } else if (view is ViewGroup) {
                for (index in 0 until minOf(view.childCount, 12)) pending.add(view.getChildAt(index))
            }
        }
        return foundPage?.takeIf { pending.isEmpty() }
    }

    private fun destinationClass(destination: Int): String? = runCatching {
        val entry = resources.getResourceEntryName(destination)
        if (!entry.startsWith("top_level_") || !entry.endsWith("_page")) return null
        val key = entry.removePrefix("top_level_").removeSuffix("_page")
        if (key !in setOf("home", "new", "radio", "library", "search")) return null
        val graph = resources.getIdentifier("${key}_navigation", "navigation", packageName)
        if (graph == 0) return null
        resources.getXml(graph).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "fragment" &&
                    xml.getAttributeResourceValue(ANDROID_NS, "id", View.NO_ID) == destination
                ) return xml.getAttributeValue(ANDROID_NS, "name")?.let {
                    if (it.startsWith(".")) packageName + it else it
                }
            }
        }
        null
    }.getOrNull()

    private fun findInPage(page: View, label: String, onOwner: ((View) -> Unit)? = null): Button? {
        // The native Home/Search layouts declare a separate Compose toolbar.
        // Its action owner is independent of the recycled feed's Compose owners.
        val appBar = if (appBarId != 0) page.findViewById<View>(appBarId) else null
        return findInToolbar(appBar ?: page, label, onOwner)
    }

    internal fun findInToolbar(toolbar: View, label: String, onOwner: ((View) -> Unit)? = null,
        atStart: Boolean = false): Button? {
        val pending = ArrayDeque<View>()
        pending.add(toolbar)
        var found: Triple<View, Int, ActionGeometry>? = null
        var budget = 72
        while (pending.isNotEmpty() && budget-- > 0) {
            val view = pending.removeFirst()
            if (!view.isShown || view.width <= 0 || view.height <= 0) continue
            if (view.accessibilityNodeProvider != null && view.javaClass.methods.any { it.name == "getLayoutNodes" }) {
                onOwner?.invoke(view)
                val ids = findButtons(view, label, atStart)
                if (ids.size > 1 || (ids.isNotEmpty() && found != null)) return null
                val match = ids.singleOrNull()
                if (match != null) found = Triple(view, match.first, match.second)
            } else if (view is ViewGroup) {
                // Below-header feed content cannot own the floating platter.
                for (index in 0 until minOf(view.childCount, 14)) pending.add(view.getChildAt(index))
            }
        }
        return found?.takeIf { pending.isEmpty() }?.let { (owner, id, bounds) -> bind(owner, id, bounds) }
    }

    private fun findButtons(owner: View, label: String, atStart: Boolean = false): List<Pair<Int, ActionGeometry>> {
        val provider = owner.accessibilityNodeProvider ?: return emptyList()
        val childId = accessibilityChildId ?: return emptyList()
        val pending = ArrayDeque<Int>()
        val visited = HashSet<Int>()
        val matches = ArrayList<Pair<Int, ActionGeometry>>(1)
        pending.add(View.NO_ID)
        var budget = 110
        while (pending.isNotEmpty() && budget-- > 0) {
            val id = pending.removeFirst()
            if (!visited.add(id)) continue
            val node = provider.createAccessibilityNodeInfo(id) ?: continue
            val bounds = checkedBounds(owner, node, label, provider, discovering = true, atStart = atStart)
            if (bounds != null) matches.add(id to bounds)
            for (index in 0 until minOf(node.childCount, 28)) {
                val packed = runCatching { childId.invoke(node, index) as Long }
                    .getOrNull() ?: continue
                pending.add((packed shr 32).toInt())
            }
        }
        return if (pending.isEmpty()) matches else emptyList()
    }

    private fun readButton(owner: View, id: Int, label: String): ActionGeometry? {
        val provider = owner.accessibilityNodeProvider ?: return null
        val node = provider.createAccessibilityNodeInfo(id) ?: return null
        return checkedBounds(owner, node, label, provider, discovering = false)
    }

    private fun checkedBounds(
        owner: View, node: AccessibilityNodeInfo, label: String,
        provider: android.view.accessibility.AccessibilityNodeProvider,
        discovering: Boolean,
        atStart: Boolean = false,
    ): ActionGeometry? {
        if (!node.isVisibleToUser || (discovering && (!node.isClickable || !node.isEnabled ||
                node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }))
        ) return null
        val bounds = Rect().also(node::getBoundsInScreen)
        val density = owner.resources.displayMetrics.density
        owner.getLocationOnScreen(screenPosition)
        val side = bounds.height() / density
        val top = (bounds.top - screenPosition[1]) / density
        val rightHalf = bounds.centerX() > screenPosition[0] + owner.width / 2
        val expectsRight = atStart == (owner.layoutDirection == View.LAYOUT_DIRECTION_RTL)
        if (bounds.isEmpty || (discovering && (side !in 38f..52f ||
                kotlin.math.abs(bounds.width() - bounds.height()) > 2 || top !in -1f..200f || rightHalf != expectsRight))
        ) return null
        // Apple puts the localized description on the icon child and the
        // click on its parent. The 48dp accessibility rect includes the
        // minimum touch padding around the centered 40dp native platter.
        val childId = accessibilityChildId ?: return null
        if (node.childCount != 1) return null
        val packed = runCatching { childId.invoke(node, 0) as Long }.getOrNull() ?: return null
        val iconNode = provider.createAccessibilityNodeInfo((packed shr 32).toInt()) ?: return null
        if (iconNode.contentDescription?.toString() != label || !iconNode.isVisibleToUser) return null
        val iconBounds = Rect().also(iconNode::getBoundsInScreen)
        if (iconBounds.isEmpty || (discovering && (!bounds.contains(iconBounds) ||
                kotlin.math.abs(iconBounds.centerX() - bounds.centerX()) > 2 ||
                kotlin.math.abs(iconBounds.centerY() - bounds.centerY()) > 2))) return null
        val platterSize = (40f * density).roundToInt()
        val left = iconBounds.centerX() - platterSize / 2
        val upper = iconBounds.centerY() - platterSize / 2
        return ActionGeometry(Rect(left, upper, left + platterSize, upper + platterSize), iconBounds, bounds)
    }

    private fun bind(owner: View, id: Int, geometry: ActionGeometry): Button? = runCatching {
        val abi = OverflowDraw.abi(owner)
        val root = abi.root.invoke(owner) ?: return null
        val map = abi.layoutNodes.invoke(owner) ?: return null
        val node = SpotifyComposeIndex.read(root, map)?.node(id) ?: error("layout node missing for native menu")
        val nativeBlur = findNativeBlur(owner, geometry.bounds)
        val platter = abi.platterBranch(node, root)
        val mask = platter ?: node.takeIf { nativeBlur != null }
            ?: error("native platter blur branch did not match")
        OverflowDraw.install(abi.draw)
        owner.getLocationOnScreen(screenPosition)
        Button(owner, id, geometry, mask, root, abi.invalidateLayers, nativeBlur, screenPosition[0], screenPosition[1])
    }.onFailure {
        if (misses == 3) ArtistDiagnostics.log("overflow bind destination=$destination rejected: ${it.message}")
        XposedBridge.log("[OfflineGlass][AppleMusic] overflow draw binding rejected: ${it.message}")
    }.getOrNull()

    private fun findNativeBlur(owner: View, bounds: Rect): View? {
        if (nativeBlurId == 0) return null
        val pending = ArrayDeque<View>()
        pending.add(owner)
        var found: View? = null
        var budget = 96
        while (pending.isNotEmpty() && budget-- > 0) {
            val view = pending.removeFirst()
            if (view.id == nativeBlurId && view.javaClass.name == "eightbitlab.com.blurview.BlurView") {
                view.getLocationOnScreen(screenPosition)
                if (kotlin.math.abs(screenPosition[0] - bounds.left) <= 2 &&
                    kotlin.math.abs(screenPosition[1] - bounds.top) <= 2 &&
                    kotlin.math.abs(view.width - bounds.width()) <= 2 &&
                    kotlin.math.abs(view.height - bounds.height()) <= 2
                ) {
                    if (found != null) return null
                    found = view
                }
            } else if (view is ViewGroup) {
                for (index in 0 until minOf(view.childCount, 24)) pending.add(view.getChildAt(index))
            }
        }
        return found.takeIf { pending.isEmpty() }
    }

    private fun show(button: Button?, now: Long, handedOff: Boolean = false) {
        if (button == null) {
            clearOpticalScene()
            val glass = panel
            if (glass == null || handedOff || glass.visibility != View.VISIBLE) {
                glass?.animate()?.cancel()
                finishPanelExit()
            } else if (!panelRetiring) {
                panelRetiring = true
                pendingPageMotion = false
                if (pageMotion.isInitialized()) pageMotion.value.cancel()
                glass.animate().alpha(0f).setDuration(170L)
                    .setUpdateListener { panelAmbient?.refresh(glass.isDarkGlass(), 1f) }
                    .withEndAction { if (panelRetiring) finishPanelExit() }.start()
            }
            return
        }
        maskOverflow(destination, button)
        knownButtons[destination] = button
        val parent = bottom.rootView.findViewById<FrameLayout>(android.R.id.content) ?: return
        val glass = panel ?: createPanel(parent).also { panel = it }
        if (panelRetiring) {
            panelRetiring = false
            glass.animate().cancel()
            pendingPageMotion = true
            glass.animate().alpha(1f).setDuration(150L)
                .setUpdateListener { panelAmbient?.refresh(glass.isDarkGlass(), 1f) }
                .withEndAction { glass.animate().setUpdateListener(null) }.start()
        }
        if (destination == libraryId && !panelVisible) {
            detailsToolbar.takeOutgoingMenu()?.let { outgoing ->
                parent.getLocationOnScreen(screenPosition)
                displayedPanel.set(outgoing.bounds)
                displayedMoreGlyph.set(outgoing.glyph)
                displayedPanel.offset(-screenPosition[0].toFloat(), -screenPosition[1].toFloat())
                displayedMoreGlyph.offset(-screenPosition[0].toFloat(), -screenPosition[1].toFloat())
                pendingPageMotion = true
            }
        }
        panelVisible = true
        val config = bottom.config
        val dark = when (config.themeMode) {
            GlassConfig.THEME_LIGHT -> false
            GlassConfig.THEME_DARK -> true
            // The tab bar can infer darkness from artwork. For the overflow
            // platter use the native page's configuration, even over artwork.
            else -> when (button.owner.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> true
                Configuration.UI_MODE_NIGHT_NO -> false
                else -> config.systemDark
            }
        }
        val themeChanged = panelDark != dark
        panelDark = dark
        val wasPaired = libraryMore != null
        val paired = updateLibraryPair(button, now)
        if (destination == libraryId && !paired && libraryFirstFrames in 1..3) {
            clearOpticalScene()
            panelVisible = hasDisplayedPanel
            if (panelVisible) syncOverflowVisibility(glass) else glass.visibility = View.INVISIBLE
            return
        }
        opticalSceneReady = opticalScene != null
        if (startupListener != null && (destination != libraryId || paired)) stopWatchingFirstDraw()
        if (wasPaired != paired && glass.visibility == View.VISIBLE && toolbarMore != null) {
            pendingPageMotion = true
        }
        val bounds = if (paired) libraryFrame else button.bounds
        if (toolbarTarget != bounds || toolbarMore?.glyph != button.geometry.glyph) glass.invalidate()
        toolbarTarget.set(bounds)
        toolbarMore = button.geometry
        libraryPlaylist?.geometry?.takeIf { paired }?.let {
            lastPlaylistGeometry = it
            playlistAtRight = it.bounds.centerX() > button.bounds.centerX()
        }
        parent.getLocationOnScreen(screenPosition)
        val left = bounds.left - screenPosition[0]
        val top = bounds.top - screenPosition[1]
        movePanel(glass, left, top, bounds.width(), bounds.height(), button.geometry.glyph, paired)
        syncOverflowVisibility(glass)
        if (!panelOccluded) panelAmbient?.refresh(dark, 1f)
        if (restorePanelScene) {
            restorePanelScene = false
            glass.restoreOpticalSceneForAdapter()
        }
        if (previousConfig != config || themeChanged) {
            previousConfig = config
            material = material.copy(heightDp = bounds.height() / resources.displayMetrics.density)
            glass.refreshAdapterDerivedConfig()
            glass.invalidate()
        }
    }

    private fun finishPanelExit() {
        clearLibraryPair()
        panelVisible = false
        panelOccluded = false
        pendingPageMotion = false
        panelRetiring = false
        stopPageMotion()
        panel?.let { glass ->
            glass.animate().setUpdateListener(null)
            glass.visibility = View.GONE
            glass.alpha = 1f
        }
    }

    private fun clearOpticalScene() {
        if (!opticalSceneReady) return
        opticalSceneReady = false
        panel?.invalidateOpticalBackdropForAdapter()
    }

    private fun onNativeMaskChanged() {
        // The existing HWUI scene may have been recorded before ownership
        // changed. Mark the feed scene dirty and discard the derived frame.
        refreshNativeSceneUntil = SystemClock.uptimeMillis() + 48L
        opticalScene?.invalidate()
        panel?.invalidateOpticalBackdropForAdapter()
        host?.invalidateOpticalBackdropForAdapter()
    }

    private fun maskOverflow(destination: Int, button: Button) {
        val previous = maskedButtons[destination]
        if (previous?.node !== button.node || previous.nativeBlur !== button.nativeBlur ||
            !OverflowDraw.hidden.containsKey(button.node)) {
            previous?.let(::release)
            OverflowDraw.hidden[button.node] = Unit
            maskedButtons[destination] = button
            button.hideNativeBlur()
            button.invalidateNative()
            onNativeMaskChanged()
        }
        maskedButtons[destination]?.hideNativeBlur()
    }

    private fun updateLibraryPair(more: Button, now: Long): Boolean {
        if (destination != libraryId || playlistLabel == 0 || playlistIcon == null) {
            clearLibraryPair()
            return false
        }
        val cached = (libraryPlaylist ?: playlistMask)?.takeIf { it.owner === more.owner }
        var playlist = cached?.let { old -> measureButton(old, playlistLabel)?.let { geometry ->
            old.also { it.geometry = geometry }
        } }
        if ((playlist == null || libraryProbeAt == 0L) && now >= libraryProbeAt) {
            libraryProbeAt = now + if (playlistMask == null) 16L else 100L
            playlist = findButtons(more.owner, resources.getString(playlistLabel)).singleOrNull()?.let { (id, geometry) ->
                bind(more.owner, id, geometry)
            }
        }
        if (playlist == null || !isLibraryPair(playlist, more)) {
            if (libraryMore?.owner === more.owner && now - libraryPairSeenAt < 120L) {
                bottom.rootView.postInvalidateOnAnimation()
                return true
            }
            if (libraryFirstFrames < 4) {
                libraryFirstFrames++
                bottom.rootView.postInvalidateOnAnimation()
            }
            clearLibraryPair()
            return false
        }
        if (!observeLibraryTouch(more.owner)) {
            clearLibraryPair()
            return false
        }
        maskLibraryPlaylist(playlist)
        if (libraryMore?.geometry != more.geometry || libraryPlaylist?.geometry != playlist.geometry) {
            panel?.invalidate()
        }
        libraryPlaylist = playlist
        libraryMore = more
        libraryPairSeenAt = now
        libraryFrame.set(more.bounds)
        libraryFrame.union(playlist.bounds)
        return true
    }

    private fun isLibraryPair(playlist: Button, more: Button): Boolean {
        val gap = maxOf(playlist.bounds.left, more.bounds.left) - minOf(playlist.bounds.right, more.bounds.right)
        val density = resources.displayMetrics.density
        return playlist.owner === more.owner && playlist.node !== more.node &&
            gap in 0..(24 * density).roundToInt() &&
            kotlin.math.abs(playlist.bounds.centerY() - more.bounds.centerY()) <= 3 * density &&
            kotlin.math.abs(playlist.bounds.height() - more.bounds.height()) <= 3 * density
    }

    private fun warmLibraryToolbar(tabHost: ViewGroup) {
        if (libraryId == 0 || playlistLabel == 0 || playlistIcon == null) return
        val page = findPage(tabHost, libraryId) ?: return
        val more = findInPage(page, resources.getString(overflowLabel)) ?: return
        if (!warmLibraryPlaylist(more)) return
        knownButtons[libraryId] = more
        maskOverflow(libraryId, more)
    }

    private fun warmLibraryPlaylist(more: Button): Boolean {
        if (playlistLabel == 0 || playlistIcon == null) return false
        val (id, geometry) = findButtons(more.owner, resources.getString(playlistLabel)).singleOrNull() ?: return false
        val playlist = bind(more.owner, id, geometry) ?: return false
        if (!isLibraryPair(playlist, more)) return false
        maskLibraryPlaylist(playlist)
        return true
    }

    private fun maskLibraryPlaylist(playlist: Button) {
        if (playlistMask?.node !== playlist.node || playlistMask?.nativeBlur !== playlist.nativeBlur) {
            playlistMask?.let(::release)
            OverflowDraw.hidden[playlist.node] = Unit
            playlistMask = playlist
            playlist.hideNativeBlur()
            playlist.invalidateNative()
            onNativeMaskChanged()
        }
        playlistMask?.hideNativeBlur()
    }

    private fun observeLibraryTouch(owner: View): Boolean {
        if (touchOwner === owner) return true
        if (!observeNativeTouches(owner)) return false
        touchOwner?.let(touchObservers::remove)
        touchOwner = owner
        return true
    }

    internal fun observeNativeTouches(owner: View): Boolean {
        val dispatch = runCatching {
            owner.javaClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        }.getOrNull() ?: return false
        if (dispatch !in touchHooks) {
            XposedBridge.hookMethod(dispatch, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    touchObservers[view]?.get()?.let { session ->
                        val event = param.args[0] as MotionEvent
                        if (!session.detailsToolbar.onTouch(view, event)) session.onLibraryTouch(event)
                    }
                }
            })
            touchHooks += dispatch
        }
        touchObservers[owner] = WeakReference(this)
        return true
    }

    internal fun stopObservingNativeTouches(owner: View) { touchObservers.remove(owner) }

    private fun onLibraryTouch(event: MotionEvent) {
        val more = libraryMore ?: return
        val playlist = libraryPlaylist ?: return
        val glass = panel ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                val x = downX.toInt()
                val y = downY.toInt()
                if (!more.geometry.touch.contains(x, y) && !playlist.geometry.touch.contains(x, y)) return
                glass.removeCallbacks(endPress)
                pressHeld = true
                touchLight.shader = RadialGradient(downX - libraryFrame.left, downY - libraryFrame.top,
                    glass.height.coerceAtLeast(1).toFloat(), 0x65ffffff, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                pressSpring.value.animateTo(1f)
            }
            MotionEvent.ACTION_MOVE -> if (pressHeld &&
                (kotlin.math.abs(event.rawX - downX) > touchSlop || kotlin.math.abs(event.rawY - downY) > touchSlop)) {
                pressHeld = false
                endPress.run()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> if (pressHeld) {
                pressHeld = false
                if (event.actionMasked == MotionEvent.ACTION_UP) glass.postDelayed(endPress,
                    (event.downTime + 60L - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                else endPress.run()
            }
        }
    }

    private fun clearLibraryPair() {
        if (libraryMore == null && touchOwner == null) return
        touchOwner?.let(touchObservers::remove)
        touchOwner = null
        libraryPlaylist = null
        libraryMore = null
        libraryPairSeenAt = 0L
        libraryProbeAt = 0L
        pressHeld = false
        panel?.removeCallbacks(endPress)
        if (pressSpring.isInitialized()) pressSpring.value.snapTo(0f)
        panel?.invalidate()
    }

    private fun syncOverflowVisibility(glass: GlassHostLayout) {
        glass.getLocationOnScreen(screenPosition)
        displayedBounds.set(screenPosition[0], screenPosition[1],
            screenPosition[0] + (glass.width * glass.scaleX).roundToInt(),
            screenPosition[1] + (glass.height * glass.scaleY).roundToInt())
        panelOccluded = destination == searchDestination && searchGlass.overlaps(displayedBounds)
        val wanted = if (panelOccluded) View.INVISIBLE else View.VISIBLE
        if (glass.visibility != wanted) glass.visibility = wanted
    }

    private fun movePanel(glass: GlassHostLayout, targetX: Int, targetY: Int, targetWidth: Int,
        targetHeight: Int, targetGlyph: Rect, paired: Boolean) {
        val targetAlpha = if (paired) 1f else 0f
        val glyphX = targetX + targetGlyph.left - toolbarTarget.left
        val glyphY = targetY + targetGlyph.top - toolbarTarget.top
        if (pendingPageMotion) {
            pendingPageMotion = false
            val changed = displayedPanel.left != targetX.toFloat() || displayedPanel.top != targetY.toFloat() ||
                displayedPanel.width() != targetWidth.toFloat() || displayedPanel.height() != targetHeight.toFloat() ||
                displayedMoreGlyph.left != glyphX.toFloat() || displayedMoreGlyph.top != glyphY.toFloat() ||
                displayedMoreGlyph.width() != targetGlyph.width().toFloat() ||
                displayedMoreGlyph.height() != targetGlyph.height().toFloat() || playlistAlpha != targetAlpha
            if (hasDisplayedPanel && changed) {
                motionStart.set(displayedPanel)
                motionGlyphStart.set(displayedMoreGlyph)
                playlistStartAlpha = playlistAlpha
                playlistFadeProgress = 0f
                pageMotion.value.snapTo(0f)
                pageMotion.value.animateTo(1f)
            } else if (pageMotion.isInitialized()) {
                pageMotion.value.cancel()
            }
        }
        val running = pageMotion.isInitialized() && pageMotion.value.isRunning
        val progress = if (running) motionProgress else 1f
        playlistFadeProgress = maxOf(playlistFadeProgress, progress.coerceIn(0f, 1f))
        val alpha = interpolate(playlistStartAlpha, targetAlpha, playlistFadeProgress)
        if (alpha != playlistAlpha || running) glass.invalidate()
        playlistAlpha = alpha
        val x = interpolate(motionStart.left, targetX.toFloat(), progress)
        val y = interpolate(motionStart.top, targetY.toFloat(), progress)
        val width = interpolate(motionStart.width(), targetWidth.toFloat(), progress).roundToInt().coerceAtLeast(1)
        val height = interpolate(motionStart.height(), targetHeight.toFloat(), progress).roundToInt().coerceAtLeast(1)
        displayedPanel.set(x, y, x + width, y + height)
        displayedMoreGlyph.set(
            interpolate(motionGlyphStart.left, glyphX.toFloat(), progress),
            interpolate(motionGlyphStart.top, glyphY.toFloat(), progress),
            interpolate(motionGlyphStart.right, (glyphX + targetGlyph.width()).toFloat(), progress),
            interpolate(motionGlyphStart.bottom, (glyphY + targetGlyph.height()).toFloat(), progress),
        )
        if (!running) {
            val params = glass.layoutParams as FrameLayout.LayoutParams
            if (params.width != width || params.height != height) {
                params.width = width
                params.height = height
                glass.layoutParams = params
            }
        }
        if (glass.width != width || glass.height != height) {
            glass.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            glass.layout(0, 0, width, height)
        }
        glass.translationX = x - glass.left
        glass.translationY = y - glass.top
    }

    private fun interpolate(start: Float, end: Float, progress: Float) = start + (end - start) * progress

    private val hasDisplayedPanel get() = displayedPanel.width() > 1f

    private fun displayedMenuFrame(): AppleMusicLibraryDetailsToolbar.MenuFrame? {
        val glass = panel?.takeIf { destination == libraryId && hasDisplayedPanel && it.isShown } ?: return null
        val parent = glass.parent as? View ?: return null
        parent.getLocationOnScreen(screenPosition)
        val frame = RectF(displayedPanel).apply { offset(screenPosition[0].toFloat(), screenPosition[1].toFloat()) }
        val glyph = RectF(displayedMoreGlyph).apply { offset(screenPosition[0].toFloat(), screenPosition[1].toFloat()) }
        return AppleMusicLibraryDetailsToolbar.MenuFrame(frame, glyph)
    }

    private fun stopPageMotion() {
        if (pageMotion.isInitialized()) pageMotion.value.cancel()
        displayedPanel.setEmpty()
        displayedMoreGlyph.setEmpty()
        toolbarMore = null
        lastPlaylistGeometry = null
        playlistAlpha = 0f
    }

    private fun createPanel(parent: FrameLayout): GlassHostLayout {
        val ambient = AppleMusicPanelAmbient(parent)
        panelAmbient = ambient
        val state = object : AppNavigationState {
            override fun retainNavigationWithoutNativeSource(selectedIndex: Int) = panelVisible && !panelOccluded
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = panelVisible && !panelOccluded
            override fun pauseBackdropWork(host: View) = !panelVisible || panelOccluded
            override fun requiresSampledBackdrop() = opticalSceneReady && !panelOccluded
            override val allowsSoftwareBackdropCapture = false
            override fun resolveBackdropScene(host: View): View? = opticalScene
            override fun forceDarkMode(selectedIndex: Int): Boolean? = panelDark
            override fun backdropSceneBaseColor(scene: View, fallback: Int): Int =
                appleMusicBackdropBase(scene, fallback)
            override fun drawsPanelAmbientShadow() = false
            override fun drawAdditionalAmbientShadow(canvas: android.graphics.Canvas, dark: Boolean, alphaScale: Float) {
                ambient.refresh(dark, alphaScale)
            }
            override fun allowsHostTouch() = false

            override fun onOuterBackdropRecorded(backdrop: android.graphics.Bitmap?, width: Int, height: Int) {
                val glass = panel ?: return
                if (glass.liveBackdropActiveForAdapter) refreshNativeSceneUntil = 0L
                val key = destination to glass.liveBackdropActiveForAdapter
                if (key == tracedOptics) return
                tracedOptics = key
                ArtistDiagnostics.log("overflow optics destination=$destination live=${key.second} ${width}x$height")
            }

            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) {
                val dots = icon ?: return
                val more = toolbarMore
                if (more != null) {
                    drawToolbarGlyphs(canvas, frame, more, dots)
                    return
                }
                val left = (frame.width - dots.intrinsicWidth) / 2
                val top = (frame.height - dots.intrinsicHeight) / 2
                dots.setTint(if (frame.darkGlass) Color.WHITE else Color.BLACK)
                dots.setBounds(left, top, left + dots.intrinsicWidth, top + dots.intrinsicHeight)
                dots.draw(canvas)
            }
        }
        val adapter = object : TargetAdapter {
            override val key = "apple_music_overflow"
            override val ownsHostLayout = true
            override val ownsNavigationDrawing = true
            override val skipNavigationSnapshot = true
            override val allowsSystemBackgroundBlur = false
            override val allowRenderNodeRefresh get() = SystemClock.uptimeMillis() < refreshNativeSceneUntil
            override val actionOnlyIndices = setOf(0)
            override val indicatorInteractionPolicy get() = material.indicatorInteractionPolicy
            override val skipBackdropStableDelay = true
            override fun normalizeConfig(config: GlassConfig) = material.apply(bottom.config)
            override fun pushedConfig() = material.apply(bottom.config)
            override fun createNavigationState(context: Context) = state
        }
        return GlassHostLayout(parent.context, material.apply(bottom.config), 1,
            navigationSource = null, sliderEnabled = false, adapterOverride = adapter).apply {
            elevation = 0f
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            ambient.bind(this)
            parent.addView(this, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        }
    }

    private fun drawToolbarGlyphs(canvas: Canvas, frame: AdapterNavigationFrame, more: ActionGeometry,
        dots: android.graphics.drawable.Drawable) {
        val glass = panel ?: return
        val light = (pressProgress * curatorHighlightStrength(bottom.config.darkBarHighlightStrength)).coerceIn(0f, 1f)
        if (light > 0f) {
            val save = canvas.save()
            canvas.clipPath(glass.clipPath)
            touchLight.alpha = (255 * light).roundToInt()
            canvas.drawRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat(), touchLight)
            canvas.restoreToCount(save)
        }
        val save = canvas.save()
        // Only the capsule grows. Restore the native screen scale for its glyphs.
        canvas.scale(1f / glass.scaleX, 1f / glass.scaleY, glass.pivotX, glass.pivotY)
        val color = if (frame.darkGlass) Color.WHITE else Color.BLACK
        val moreX = (displayedMoreGlyph.left - displayedPanel.left).roundToInt()
        val moreY = (displayedMoreGlyph.top - displayedPanel.top).roundToInt()
        drawToolbarGlyph(canvas, dots, moreX, moreY, displayedMoreGlyph.width().roundToInt(),
            displayedMoreGlyph.height().roundToInt(), color, 255)
        val playlist = lastPlaylistGeometry
        if (playlist != null && playlistIcon != null && playlistAlpha > 0f) {
            val left = if (playlistAtRight) frame.width - (playlist.bounds.right - playlist.glyph.left)
                else playlist.glyph.left - playlist.bounds.left
            val room = ((frame.width - more.bounds.width()).toFloat() /
                playlist.bounds.width().coerceAtLeast(1)).coerceIn(0f, 1f)
            drawToolbarGlyph(canvas, playlistIcon, left,
                frame.height / 2 + playlist.glyph.top - playlist.bounds.centerY(),
                playlist.glyph.width(), playlist.glyph.height(), color,
                (255 * minOf(playlistAlpha, room)).roundToInt())
        }
        canvas.restoreToCount(save)
    }

    private fun drawToolbarGlyph(canvas: Canvas, icon: android.graphics.drawable.Drawable,
        left: Int, top: Int, width: Int, height: Int, color: Int, alpha: Int) {
        icon.setTint(color)
        icon.alpha = alpha
        icon.setBounds(left, top, left + width, top + height)
        icon.draw(canvas)
    }

    private fun release(button: Button) {
        OverflowDraw.hidden.remove(button.node)
        button.restoreNativeBlur()
        button.invalidateNative()
    }

    private fun releaseMasks() {
        clearLibraryPair()
        playlistMask?.let(::release)
        playlistMask = null
        maskedButtons.values.forEach(::release)
        maskedButtons.clear()
    }

    fun dispose() {
        stopWatchingFirstDraw()
        detailsToolbar.clear()
        menuRenderer?.dispose()
        searchGlass.clear()
        menuRenderer = null
        releaseMasks()
        knownButtons.clear()
        native = null
        panelVisible = false
        holdVisualUntil = 0L
        pendingPageMotion = false
        stopPageMotion()
        removeScopeListeners()
        scope = null
        sourceContent = null
        opticalScene = null
        opticalSceneReady = false
        refreshNativeSceneUntil = 0L
        ownershipGapUntil = 0L
        presented = false
        verifyBinding = true
        restorePanelScene = false
        panelRetiring = false
        panel?.let {
            it.animate().cancel()
            (it.parent as? ViewGroup)?.removeView(it)
        }
        panel = null
        panelAmbient = null
        previousConfig = null
        panelDark = null
        host = null
    }

    private fun removeScopeListeners() {
        val observer = scope?.viewTreeObserver?.takeIf { it.isAlive } ?: return
        observer.removeOnGlobalLayoutListener(layoutChanged)
        observer.removeOnScrollChangedListener(scrolled)
    }

    internal data class ActionGeometry(val bounds: Rect, val glyph: Rect, val touch: Rect)

    internal class Button(
        val owner: View,
        val virtualId: Int,
        var geometry: ActionGeometry,
        val node: Any,
        val root: Any,
        val invalidateLayers: Method,
        val nativeBlur: View?,
        var ownerX: Int,
        var ownerY: Int,
    ) {
        val bounds get() = geometry.bounds
        private var originalBlurAlpha: Float? = null

        fun hideNativeBlur() {
            val blur = nativeBlur ?: return
            if (blur.alpha != 0f) {
                originalBlurAlpha = blur.alpha
                blur.alpha = 0f
            }
        }

        fun restoreNativeBlur() {
            originalBlurAlpha?.let { if (nativeBlur?.alpha == 0f) nativeBlur.alpha = it }
        }
        fun invalidateNative() {
            if (!owner.isAttachedToWindow) return
            runCatching { invalidateLayers.invoke(null, root) }
            owner.invalidate()
        }
    }

    private companion object {
        val touchHooks = HashSet<Method>()
        val touchObservers = WeakHashMap<View, WeakReference<AppleMusicOverflowGlass>>()
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val accessibilityChildId = runCatching {
            AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()
    }
}

internal object OverflowDraw {
    data class Abi(
        val root: Method, val layoutNodes: Method, val draw: Method, val invalidateLayers: Method,
        val parentGetters: List<Method>, val childVectors: List<Method>,
    ) {
        fun parentOf(child: Any): Any? {
            val parent = parentGetters.firstOrNull()?.invoke(child) ?: return null
            return parent.takeIf { parentGetters.all { getter -> getter.invoke(child) === parent } }
        }

        fun children(node: Any, limit: Int = 8, matchOrdering: Boolean = true): List<Any>? {
            if (childVectors.isEmpty()) return null
            val copies = childVectors.map { getter ->
                val vector = getter.invoke(node) ?: return null
                val fields = vector.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) }
                val values = fields.singleOrNull { it.type == Array<Any>::class.java }
                    ?.get(vector) as? Array<*> ?: return null
                val size = fields.singleOrNull { it.type == Int::class.javaPrimitiveType }
                    ?.getInt(vector) ?: return null
                if (size !in 0..limit || size > values.size) return null
                List(size) { values[it] ?: return null }
            }
            val first = copies.first()
            return first.takeIf { copies.all { copy ->
                copy.size == first.size && if (matchOrdering) {
                    copy.indices.all { copy[it] === first[it] }
                } else {
                    copy.all { child -> first.count { it === child } == 1 }
                }
            } }
        }

        fun platterBranch(click: Any, root: Any): Any? {
            var child = click
            repeat(4) {
                val box = parentOf(child) ?: return null
                if (box === root) return null
                val children = children(box) ?: return null
                if (children.size == 1 && children[0] === child) {
                    child = box
                } else {
                    // Apple builds the platter as [non-semantic blur leaf,
                    // clickable material/icon]. Never suppress a larger branch.
                    return box.takeIf { children.size == 2 && children[1] === child &&
                        this.children(children[0])?.isEmpty() == true }
                }
            }
            return null
        }
    }

    private val contracts = HashMap<Class<*>, Abi>()
    private val hooks = HashSet<Method>()
    val hidden = WeakHashMap<Any, Unit>()
    private val rootObservers = java.util.IdentityHashMap<Any, () -> Unit>()

    fun observeRoot(draw: Method, root: Any, callback: () -> Unit) {
        install(draw)
        rootObservers[root] = callback
    }

    fun stopObservingRoot(root: Any) { rootObservers.remove(root) }

    fun abi(owner: View): Abi = contracts.getOrPut(owner.javaClass) {
        val type = owner.javaClass
        val root = type.getMethod("getRoot")
        val layoutNodes = type.methods.single {
            it.name == "getLayoutNodes" && it.parameterCount == 0 && !it.isBridge
        }
        val node = root.returnType
        val draw = node.methods.single { method ->
            method.returnType == Void.TYPE && method.parameterCount == 2 &&
                method.parameterTypes[0].isInterface && method.parameterTypes[0].methods.any {
                    it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java))
                } && method.parameterTypes[0].methods.any {
                    it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                        Float::class.javaPrimitiveType, Int::class.javaPrimitiveType))
                }
        }
        val invalidate = type.declaredMethods.single {
            Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(node))
        }.apply { isAccessible = true }
        val parents = node.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == node
        }
        val children = node.methods.filter {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                java.util.RandomAccess::class.java.isAssignableFrom(it.returnType) &&
                it.returnType.fields.count { field -> field.type == Array<Any>::class.java } == 1 &&
                it.returnType.fields.count { field -> field.type == Int::class.javaPrimitiveType } == 1
        }
        Abi(root, layoutNodes, draw, invalidate, parents, children)
    }

    fun install(draw: Method) {
        if (draw in hooks) return
        XposedBridge.hookMethod(draw, object : XC_MethodHook() {
            private val saved = java.util.IdentityHashMap<MethodHookParam, Pair<Canvas, Int>>()
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (rootObservers.isNotEmpty()) rootObservers[param.thisObject]?.invoke()
                if (hidden.containsKey(param.thisObject) || AppleMusicArtistOptics.hidesNativeBackdrop(param.thisObject)) {
                    param.result = null
                } else {
                    AppleMusicArtistOptics.scaleMovingLayout(param.thisObject, param.args[0])?.let { saved[param] = it }
                }
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (saved.isEmpty()) return
                val (canvas, count) = saved.remove(param) ?: return
                canvas.restoreToCount(count)
            }
        })
        hooks.add(draw)
    }
}

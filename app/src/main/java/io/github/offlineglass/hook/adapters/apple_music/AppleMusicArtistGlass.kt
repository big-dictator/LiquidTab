package io.github.offlineglass.hook.adapters.apple_music

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.spotify.SpotifyComposeIndex
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XC_MethodHook
import java.util.WeakHashMap
import org.xmlpull.v1.XmlPullParser
import kotlin.math.abs
import kotlin.math.roundToInt

/** Artist header and spotlight card. Clicks, scrolling and composition stay with Apple Music. */
internal class AppleMusicArtistGlass(private val bottom: GlassHostLayout) {
    private val resources = bottom.resources
    private var tintFraction = 1f
    private var tintAnimator: android.animation.ValueAnimator? = null
    private var tintTarget = 1f
    val pageTintFraction get() = tintFraction
    private val topGlass = AppleMusicArtistTopGlass(bottom, { toolbarTint }, { album }, { standaloneToolbar })
    private val packageName = targetSpec.packageName
    private val fragmentTag = resource("fragment_container_view_tag", "id")
    private val artistClass = destinationClass("artist_fragment")
    private val albumClasses = setOfNotNull(destinationClass("action_album_compose"), destinationClass("action_song_compose"))
    private val curatorClass = destinationClass("curator_label_fragment")
    private val pathRoomClass = destinationClass("action_compose_path_room")
    private val destinations = albumClasses + listOfNotNull(artistClass, curatorClass, pathRoomClass)
    private var album = false
    private var curator = false
    private var pathRoom = false
    private val standaloneToolbar get() = curator || pathRoom
    private val ownerLocation = IntArray(2)
    private val toolbarTint get() = if (standaloneToolbar) 1f else tintFraction
    init {
        destinations.forEach { install(bottom.context.classLoader, it) }
    }
    private val roles by lazy {
        mapOf(
            Role.BACK to labels("navigate_up", "nav_app_bar_navigate_up_description"),
            Role.SHARE to labels("share"),
            Role.MORE to labels("more_options"),
            Role.INFO to labels("ax_artist_information_button"),
            Role.PLAY to labels("play_button"),
            Role.FAVORITE to labels("favorite_button", "undo_favorite_button"),
        )
    }
    private val childId = runCatching {
        AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }.getOrNull()
    private val albumRoles by lazy {
        roles.filterKeys { it.top } + mapOf(
            Role.SHUFFLE to labels("shuffle", "play_shuffled", "shuffle_all"),
            Role.ALBUM_PLAY to labels("play_button", "play_all"),
            Role.ALBUM_LIBRARY to labels("ax_album_add_to_library_content_description", "add_to_library",
                "ax_album_download_album_content_description", "ax_album_downloaded_album_content_description"))
    }
    private val libraryLabels by lazy { labels("add_to_library", "ax_album_add_to_library_content_description") }
    private val curatorRoles by lazy { roles.filterKeys { it == Role.BACK || it == Role.MORE } }
    private val pathRoomRoles by lazy { mapOf(Role.BACK to roles.getValue(Role.BACK)) }
    private val activeRoles get() = when {
        curator -> curatorRoles
        pathRoom -> pathRoomRoles
        album -> albumRoles
        else -> roles
    }
    private var scope: ViewGroup? = null
    private var page: View? = null
    private var owner: View? = null
    private var nextPageProbe = 0L
    private var nextBind = 0L
    private var lastBind = 0L
    private var controls = emptyList<Control>()
    // Only geometry masks and shader programs survive recycling. Never retain
    // the artist's artwork, native drawing node or click handler here.
    private val edgeShaders = java.util.EnumMap<Role, android.graphics.RuntimeShader>(Role::class.java)
    private var previousIssue = ""
    private var initialBindDeadline = 0L
    private var initialBindAttempts = 0
    private var population: AppleMusicArtistCompose.LayoutPopulation? = null
    private var artistSemantics: AppleMusicArtistSemantics? = null
    private var contentChanged = false
    private var pressed: AppleMusicArtistOptics.Surface? = null
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(bottom.context).scaledTouchSlop
    private var awaitingToolbar = true
    private val layoutChanged = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        nextPageProbe = 0
        if (owner != null && (awaitingToolbar || (!standaloneToolbar && artistSemantics == null))) nextBind = 0
    }

    internal enum class Role(val top: Boolean = false, val fixed: Boolean = false) {
        BACK(top = true), SHARE(top = true), MORE(top = true),
        INFO(fixed = true), PLAY(fixed = true), FAVORITE(fixed = true), CARD, CARD_ACTION,
        SHUFFLE(fixed = true), ALBUM_PLAY(fixed = true), ALBUM_LIBRARY(fixed = true);

        val albumAction get() = this == SHUFFLE || this == ALBUM_PLAY || this == ALBUM_LIBRARY
    }

    private data class Semantic(
        val id: Int, val parent: Int, val bounds: Rect, val label: String?,
        val clickable: Boolean, val longClickable: Boolean,
    )
    private data class Control(
        val role: Role, val anchor: Any, val modifier: Any, val surface: AppleMusicArtistOptics.Surface,
        val backdrop: AppleMusicArtistCompose.Material?,
    )

    fun update(candidate: ViewGroup?, enabled: Boolean) {
        if (!enabled) {
            if (scope != null) clear()
            return
        }
        val content = candidate ?: scope?.takeIf { it.isAttachedToWindow }
        if (scope !== content) {
            clear()
            scope = content
            content?.viewTreeObserver?.addOnGlobalLayoutListener(layoutChanged)
        }
        if (content == null) return
        val now = SystemClock.uptimeMillis()
        val registeredPage = if (page?.isShown != true) artistViews.keys.singleOrNull {
            it.isAttachedToWindow && it.isShown && it.rootView === content.rootView
        } else null
        if (page?.isShown != true && (registeredPage != null || now >= nextPageProbe)) {
            nextPageProbe = now + 350L
            val artist = registeredPage ?: findArtist(content)
            if (page !== artist) {
                release()
                page = artist
                val destination = artist?.let { artistViews[it] ?: it.getTag(fragmentTag)?.javaClass?.name }
                album = destination in albumClasses
                curator = destination != null && destination == curatorClass
                pathRoom = destination != null && destination == pathRoomClass
                owner = null
                nextBind = 0
                initialBindDeadline = 0L
                initialBindAttempts = 0
            }
            if (artist == null) {
                ArtistDiagnostics.log("no visible artist fragment, registered=${artistViews.size}")
            }
        }
        if (page != null && owner?.isAttachedToWindow != true) {
            val replacement = page?.let(::findOwner)
            if (replacement !== owner) {
                release()
                owner = replacement
                nextBind = 0
                lastBind = 0
                initialBindDeadline = if (replacement != null) now + 6_000L else 0L
                initialBindAttempts = 0
                population = replacement?.let { compose ->
                    runCatching {
                        observeTouches(compose, this)
                        val abi = OverflowDraw.abi(compose)
                        artistSemantics = if (standaloneToolbar) null else AppleMusicArtistSemantics.observe(compose, abi,
                            if (album) albumRoles.filterKeys { !it.top } else roles.filterKeys { !it.top } + (Role.CARD_ACTION to libraryLabels)) {
                            if (!contentChanged) {
                                contentChanged = true
                                compose.postInvalidateOnAnimation()
                            }
                        }
                        ArtistDiagnostics.log("artist semantics subscriptions=${artistSemantics != null}")
                        val table = abi.layoutNodes.invoke(compose) ?: return@runCatching null
                        val root = abi.root.invoke(compose) ?: return@runCatching null
                        AppleMusicArtistCompose.LayoutPopulation.resolve(table, root)
                    }.getOrNull()
                }
            }
        }
        val compose = owner?.takeIf { it.isShown && it.isAttachedToWindow }
        animateTint(if (compose == null || standaloneToolbar) 1f else 0f)
        if (compose == null) {
            if (owner?.isAttachedToWindow != true) release()
            if (page != null) report("artist fragment present, Compose owner unavailable")
            return
        }
        val expected = activeRoles.size + if (album || standaloneToolbar) 0 else 2
        val bindingPending = controls.size + topGlass.size < expected
        val initialBinding = bindingPending && now < initialBindDeadline
        val interval = when {
            !initialBinding -> 250L
            initialBindAttempts < 4 -> 16L
            initialBindAttempts < 10 -> 100L
            else -> 250L
        }
        // Recomposition can install the complete toolbar between two timed probes.
        // Spotlight actions may arrive after the toolbar in a separate composition.
        val layoutChangedBeforeDraw = (awaitingToolbar || initialBinding) && population?.changed() == true
        if (contentChanged || layoutChangedBeforeDraw || (now >= nextBind && now - lastBind >= interval)) {
            val contentOnly = contentChanged && !awaitingToolbar && artistSemantics != null
            contentChanged = false
            lastBind = now
            bind(compose, contentOnly)
            if (layoutChangedBeforeDraw && !awaitingToolbar) {
                ArtistDiagnostics.log("toolbar bound before draw on live layout population change")
            }
            val needsRetry = initialBinding && controls.size + topGlass.size < expected
            if (needsRetry) {
                initialBindAttempts++
                val delay = when {
                    initialBindAttempts < 4 -> 16L
                    initialBindAttempts < 10 -> 100L
                    else -> 250L
                }
                nextBind = now + delay
                bottom.postInvalidateDelayed(delay)
            } else {
                nextBind = now + 4000L
            }
        }
        if (controls.isNotEmpty()) {
            val pose = io.github.offlineglass.rendering.PostureGlass.revision(compose, bottom.config.postureEffectsEnabled)
            val dark = bottom.isDarkGlass()
            val light = AppleMusicArtistOptics.lightDirection(compose, bottom.config.postureEffectsEnabled)
            controls.forEach { it.surface.refresh(pose, dark, light) }
        }
        topGlass.refresh()
    }

    private fun animateTint(target: Float) {
        if (tintTarget == target) return
        tintTarget = target
        tintAnimator?.cancel()
        tintAnimator = android.animation.ValueAnimator.ofFloat(tintFraction, target).apply {
            duration = 420L
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener {
                tintFraction = it.animatedValue as Float
                controls.forEach { control -> if (!control.surface.highlightOnly) control.surface.invalidate() }
                topGlass.invalidateTint()
                bottom.postInvalidateOnAnimation()
            }
            start()
        }
    }

    fun clear() {
        tintAnimator?.cancel()
        tintAnimator = null
        tintTarget = 1f
        tintFraction = 1f
        bottom.invalidate()
        scope?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutChanged)
        scope = null
        page = null
        owner = null
        nextPageProbe = 0
        nextBind = 0
        initialBindDeadline = 0L
        initialBindAttempts = 0
        release()
    }

    fun restoreOptics() {
        controls.forEach { it.surface.reset() }
        topGlass.restoreOptics()
        owner?.invalidate()
    }

    private fun release() {
        pressed?.releasePress(0)
        pressed = null
        topGlass.releasePress(0)
        owner?.let { touchOwners.remove(it) }
        artistSemantics?.close()
        artistSemantics = null
        contentChanged = false
        population = null
        awaitingToolbar = true
        controls.forEach { AppleMusicArtistOptics.unbind(it.modifier, it.surface) }
        topGlass.clear()
        edgeShaders.clear()
        controls = emptyList()
        previousIssue = ""
    }

    private fun touch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed?.releasePress(0)
                downX = event.rawX
                downY = event.rawY
                val target = controls.asSequence().map { it.surface }.filter { it.hit(downX, downY) }
                    .minByOrNull { it.originalBounds.width().toLong() * it.originalBounds.height() }
                pressed = target?.pressOwner
                pressed?.press(downX, downY)
                if (pathRoom && pressed == null) topGlass.pressBack(downX, downY)
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop) {
                pressed?.releasePress(0)
                pressed = null
                topGlass.releasePress(0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                val delay = if (event.actionMasked == MotionEvent.ACTION_UP)
                    (event.downTime + 60L - SystemClock.uptimeMillis()).coerceAtLeast(0L) else 0L
                pressed?.releasePress(delay)
                topGlass.releasePress(delay)
                pressed = null
            }
        }
    }

    private fun bind(compose: View, contentOnly: Boolean) {
        runCatching { bindVisibleControls(compose, contentOnly) }
            .onFailure { report(it.message ?: it.javaClass.simpleName) }
    }

    private fun bindVisibleControls(compose: View, contentOnly: Boolean) {
        ArtistDiagnostics.attach(compose.context)
        val abi = OverflowDraw.abi(compose)
        val root = abi.root.invoke(compose) ?: return
        val semantics = if (contentOnly) emptyList() else semantics(compose).orEmpty()
        val albumPlayOwners = if (album && semantics.isNotEmpty()) {
            val byId = semantics.associateBy { it.id }
            semantics.asSequence().filter { it.label in albumRoles.getValue(Role.ALBUM_PLAY) }
                .mapNotNull { label ->
                    generateSequence(label) { byId[it.parent] }.take(6).firstOrNull { it.clickable }?.id
                }.toSet()
        } else emptySet()
        val index by lazy(LazyThreadSafetyMode.NONE) { SpotifyComposeIndex.read(root, abi.layoutNodes.invoke(compose)) }
        val geometry = AppleMusicArtistCompose(abi)
        val density = compose.resources.displayMetrics.density
        val wanted = LinkedHashMap<Role, Pair<Rect, AppleMusicArtistCompose.Material>>()
        val anchors = mutableMapOf<Role, Any>()
        val topAnchors = LinkedHashMap<Role, AppleMusicArtistTopGlass.Anchor>()
        val middleAnchors = LinkedHashMap<Role, Rect>()
        val hits = ArrayList<String>()
        val middleBackdrops = ArrayList<AppleMusicArtistCompose.Material>()
        val ownBackdrops = mutableMapOf<Role, AppleMusicArtistCompose.Material>()
        for ((role, accepted) in activeRoles) {
            if (contentOnly && role.top) continue
            fun fits(box: Rect): Boolean = if (role == Role.ALBUM_PLAY) {
                box.height() in (30 * density).roundToInt()..(72 * density).roundToInt() &&
                    box.width() in box.height() * 2..box.height() * 6
            } else box.width().toFloat() in (30 * density)..(82 * density) &&
                abs(box.width() - box.height()) <= 4 * density
            val matches = semantics.filter { item ->
                item.clickable && ((role == Role.ALBUM_PLAY && item.id in albumPlayOwners) ||
                    item.label in accepted || semantics.any { child ->
                    child.parent == item.id && child.label in accepted &&
                        (role == Role.ALBUM_PLAY || abs(child.bounds.centerX() - item.bounds.centerX()) <= 2) &&
                        abs(child.bounds.centerY() - item.bounds.centerY()) <= 2
                }) && fits(item.bounds)
            }
            val back = topAnchors[Role.BACK]?.bounds
            val item = if ((album || curator) && role.top && back != null) matches.singleOrNull {
                abs(it.bounds.centerY() - back.centerY()) <= 4 * density
            } else matches.singleOrNull()
            val live = if (role.top) null else artistSemantics?.button(role)?.takeIf {
                fits(it.bounds)
            }
            val bounds = live?.bounds ?: item?.bounds
            if (bounds == null) {
                hits += "${role.name}=${matches.size}"
                continue
            }
            val node = live?.node ?: item?.let { index?.node(it.id) }
            if (node == null) {
                hits += "${role.name}=layout"
                continue
            }
            if (role.top) {
                val branch = geometry.platter(node, root) ?: node
                val exterior = if (standaloneToolbar) geometry.exteriorLayer(branch) else null
                topAnchors[role] = AppleMusicArtistTopGlass.Anchor(bounds, geometry.placement(branch), exterior)
            } else {
                middleAnchors[role] = bounds
            }
            anchors[role] = node
            val retained = if (contentOnly) controls.singleOrNull { it.role == role && it.anchor === node } else null
            if (retained != null) {
                retained.backdrop?.let(middleBackdrops::add)
                continue
            }
            val haze = if (role == Role.ALBUM_PLAY) null else geometry.material(node, root, bounds, role, density,
                roomBack = pathRoom)
            if (haze != null && role.fixed) {
                ownBackdrops[role] = haze
                middleBackdrops += haze
            }
            val solid = geometry.solid(node, root, bounds, density, role, toolbarFace = standaloneToolbar)
            val inspectFace = standaloneToolbar || role == Role.SHARE || role == Role.MORE
            val toolbarItem = if (inspectFace) geometry.toolbarItem(node, root) else null
            val nativeBackground = if (inspectFace) {
                geometry.toolbarBackground(node, root, bounds, density).also {
                    ArtistDiagnostics.log("$role native tint located=" + (it != null) +
                        " primary=" + (it?.node === solid?.haze && it != null))
                }
            } else null
            if (pathRoom) {
                val anchor = topAnchors.getValue(role)
                val faceDraws = solid?.covered.orEmpty() + toolbarItem?.decorations.orEmpty() +
                    listOfNotNull(solid?.let { it.haze to it.draw }, haze?.let { it.haze to it.draw },
                        nativeBackground?.let { it.node to it.draw })
                topAnchors[role] = anchor.copy(faceDraws = faceDraws.distinctBy { it.first })
            }
            val material = if (standaloneToolbar && haze != null) haze.copy(
                // The native backdrop is a sibling of the clickable icon face.
                // Keep the glass behind the icon and retire the face's material.
                solid = true,
                covered = (haze.covered + solid?.covered.orEmpty() + toolbarItem?.decorations.orEmpty() +
                    listOfNotNull(solid?.let { it.haze to it.draw }, nativeBackground?.let { it.node to it.draw }))
                    .filter { it.first !== haze.haze }.distinctBy { it.first },
                invalidate = {
                    haze.invalidate()
                    solid?.invalidate?.invoke()
                    nativeBackground?.invalidate?.invoke()
                },
            ) else solid?.copy(
                covered = (solid.covered + haze?.covered.orEmpty() + haze?.let { listOf(it.haze to it.draw) }.orEmpty() +
                    toolbarItem?.decorations.orEmpty() +
                    listOfNotNull(nativeBackground?.let { it.node to it.draw }))
                    .filter { it.first !== solid.haze }.distinctBy { it.first },
                invalidate = {
                    solid.invalidate()
                    haze?.invalidate?.invoke()
                    if (nativeBackground?.node !== solid.haze) nativeBackground?.invalidate?.invoke()
                },
                placement = haze?.placement ?: topAnchors[role]?.placement,
                toolbarItem = toolbarItem,
                sources = haze?.sources ?: { middleBackdrops.flatMap { it.sources() }.distinctBy { it.node } },
            ) ?: haze?.takeUnless { role == Role.PLAY }
            if (material == null) {
                hits += "${role.name}=material"
                continue
            }
            if (pathRoom) ArtistDiagnostics.log("room back compose background=${haze != null} face=${solid != null}")
            wanted[role] = bounds to material
        }
        val curatorPair = if (!curator) true else {
            val back = topAnchors[Role.BACK]?.bounds
            val more = topAnchors[Role.MORE]?.bounds
            compose.getLocationOnScreen(ownerLocation)
            back != null && more != null && AppleMusicArtistOptics.ToolbarGeometry.oppositeSides(
                back.centerX(), more.centerX(), ownerLocation[0] + compose.width / 2)
        }
        val expectedToolbarSize = activeRoles.keys.count { it.top }
        if (topAnchors.size == expectedToolbarSize && (!curatorPair || topAnchors.values.maxOf { it.bounds.centerY() } -
            topAnchors.values.minOf { it.bounds.centerY() } > 4 * density)) {
            topAnchors.clear()
            wanted.keys.removeAll { it.top }
        }
        val boundTop = if (contentOnly) emptySet() else scope?.let { topGlass.bind(compose, it, topAnchors) }.orEmpty()
        wanted.keys.removeAll(boundTop)
        val rowPresent = middleAnchors.size >= 2 && middleAnchors.values.maxOf { it.centerY() } -
            middleAnchors.values.minOf { it.centerY() } <= 4 * density
        val liveAction = artistSemantics?.button(Role.CARD_ACTION)
        val liveCard = liveAction?.let { artistSemantics?.enclosingCard(it, compose.width, density) }
        if (!rowPresent) wanted.keys.removeAll { it.fixed }
        if (!album && !standaloneToolbar && (rowPresent || liveCard != null)) {
            val bottomOfButtons = middleAnchors.values.maxOfOrNull { it.bottom } ?: 0
            val card = if (!rowPresent) null else semantics.filter { item ->
                item.clickable && (item.longClickable || semantics.count { child ->
                    child.parent == item.id && !child.clickable && !child.label.isNullOrBlank()
                } >= 2) &&
                    item.bounds.top in bottomOfButtons..(bottomOfButtons + 44 * density).roundToInt() &&
                    item.bounds.width() > compose.width * 0.65f &&
                    item.bounds.height() in (86 * density).roundToInt()..(160 * density).roundToInt()
            }.singleOrNull()
            val cardBounds = liveCard?.bounds ?: card?.bounds
            if (cardBounds != null) {
                (liveCard?.node ?: card?.let { index?.node(it.id) })?.let { node ->
                    anchors[Role.CARD] = node
                    val cachedCard = controls.singleOrNull { contentOnly && it.role == Role.CARD && it.anchor === node }
                    val sources = if (cachedCard != null) cachedCard.surface.native.sources else {
                        val solid = geometry.solid(node, root, cardBounds, density, Role.CARD)
                        val backdrop = geometry.material(node, root, cardBounds, Role.CARD, density)
                        val cardSources = backdrop?.sources ?: { middleBackdrops.flatMap { it.sources() }.distinctBy { it.node } }
                        solid?.copy(sources = cardSources,
                            covered = (solid.covered + backdrop?.covered.orEmpty() +
                                listOfNotNull(backdrop?.let { it.haze to it.draw }))
                                .filter { it.first !== solid.haze }.distinctBy { it.first },
                            invalidate = { solid.invalidate(); backdrop?.invalidate?.invoke() },
                        )?.let { wanted[Role.CARD] = cardBounds to it }
                        cardSources
                    }
                    val action = semantics.singleOrNull { item ->
                        item.clickable && item.id != card?.id && cardBounds.contains(item.bounds) &&
                            (item.label in libraryLabels || semantics.any { child ->
                                child.parent == item.id && child.label in libraryLabels
                            })
                    }
                    val actionBounds = liveAction?.bounds ?: action?.bounds
                    if (actionBounds != null) {
                        (liveAction?.node ?: action?.let { index?.node(it.id) })?.let actionButton@{ button ->
                            anchors[Role.CARD_ACTION] = button
                            if (contentOnly && controls.any { it.role == Role.CARD_ACTION && it.anchor === button }) return@actionButton
                            if (generateSequence(button, abi::parentOf).take(16).any { it === node }) {
                                geometry.solid(button, node, actionBounds, density, Role.CARD_ACTION)
                                    ?.let { wanted[Role.CARD_ACTION] = actionBounds to it.copy(sources = sources) }
                            }
                        }
                    }
                }
            }
        }
        val next = wanted.mapNotNull { (role, target) ->
            val (bounds, native) = target
            val reused = controls.singleOrNull { it.role == role && it.modifier === native.haze &&
                (topAnchors[role]?.exterior == null || it.surface.pressLayer?.layout === topAnchors[role]?.exterior?.layout) &&
                it.surface.native.placement?.target === native.placement?.target &&
                it.surface.native.toolbarItem?.backdrop === native.toolbarItem?.backdrop &&
                it.surface.native.toolbarItem?.icon === native.toolbarItem?.icon &&
                it.surface.native.covered.map { pair -> pair.first } == native.covered.map { pair -> pair.first } }
            if (reused != null) {
                reused.surface.updateSources(native.sources)
                return@mapNotNull reused
            }
            val surface = AppleMusicArtistOptics.Surface(bottom, compose, role, native, bounds,
                albumDetail = album || standaloneToolbar,
                standaloneToolbar = standaloneToolbar,
                edgeShader = { edgeShaders.getOrPut(role) { io.github.offlineglass.rendering.GlassEdgeHighlight.shader() } },
                tintFraction = { toolbarTint })
            surface.pressLayer = topAnchors[role]?.exterior ?: geometry.pressLayer(anchors.getValue(role), root, bounds)
            if (!role.top) ArtistDiagnostics.log("binding $role node=${System.identityHashCode(native.haze)}")
            ArtistDiagnostics.log("bind $role solid=${native.solid} bounds=${native.bounds()} " +
                "cover=${native.covered.size} highlightOnly=${surface.highlightOnly} frame=${native.frameClip != null} " +
                "art=${native.artwork != null} artClip=${native.artwork?.clip != null}")
            if (!AppleMusicArtistOptics.bind(native.haze, surface)) return@mapNotNull null
            Control(role, anchors.getValue(role), native.haze, surface, ownBackdrops[role])
        }.toMutableList()
        // Accessibility snapshots can omit clipped or recomposing controls.
        // Keep one binding per role until its replacement is verified.
        for (previous in controls) {
            if (!previous.role.top && artistSemantics?.isDetached(previous.anchor) == true) continue
            if (previous.role !in boundTop && next.none { it.role == previous.role }) next += previous
        }
        val share = next.singleOrNull { it.role == Role.SHARE }?.surface
        val more = next.singleOrNull { it.role == Role.MORE }?.surface
        share?.groupWith(more)
        more?.groupUnder(share)
        if (share != null && more != null) AppleMusicArtistOptics.bindToolbarPair(share, more)
        if (share != null && more != null) topGlass.suppressGroupedNativeBackdrop(compose, topAnchors[Role.MORE])
        val removed = controls.filter { it !in next }
        removed.forEach { AppleMusicArtistOptics.unbind(it.modifier, it.surface) }
        if (controls.toSet() != next.toSet()) {
            // Wake the root display list after invalidating the native layers.
            // A clean Compose owner can otherwise retain its pre-bind frame.
            compose.postInvalidateOnAnimation()
            bottom.postInvalidateOnAnimation()
        }
        controls = next
        if (!contentOnly) awaitingToolbar = activeRoles.keys.any { role ->
            role.top && role !in boundTop && next.none { it.role == role }
        }
        report("visible materials ${controls.joinToString { it.role.name }}; native top=$boundTop; " +
            "candidates ${wanted.keys}; semantics=${semantics.size}; rejected ${hits.joinToString()}")
    }

    private fun report(issue: String) {
        if (issue == previousIssue) return
        previousIssue = issue
        ArtistDiagnostics.log(issue)
    }

    private fun semantics(compose: View): List<Semantic>? {
        val provider = compose.accessibilityNodeProvider ?: return null
        val readChild = childId ?: return null
        val pending = ArrayDeque<Pair<Int, Int>>()
        val visited = HashSet<Int>()
        val result = ArrayList<Semantic>()
        pending.add(View.NO_ID to View.NO_ID)
        while (pending.isNotEmpty() && visited.size < 240) {
            val (id, parent) = pending.removeFirst()
            if (!visited.add(id)) continue
            val node = provider.createAccessibilityNodeInfo(id) ?: continue
            try {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (node.isVisibleToUser && !bounds.isEmpty) {
                    result += Semantic(id, parent, bounds, node.contentDescription?.toString()
                        ?: node.text?.toString(), node.isClickable, node.isLongClickable)
                }
                for (i in 0 until minOf(node.childCount, 40)) {
                    val packed = readChild.invoke(node, i) as Long
                    val child = (packed ushr 32).toInt()
                    if (child != Int.MAX_VALUE) pending.add(child to id)
                }
            } finally {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        return result.takeIf { pending.isEmpty() }
    }

    private fun findArtist(content: View): View? {
        if (fragmentTag == 0) return null
        return uniqueView(content, 100) { it.getTag(fragmentTag)?.javaClass?.name in destinations }
    }

    private fun findOwner(artist: View): View? = uniqueView(artist, 18) { candidate ->
        candidate.accessibilityNodeProvider != null &&
            candidate.javaClass.methods.any { it.name == "getLayoutNodes" && it.parameterCount == 0 }
    }

    private fun uniqueView(root: View, limit: Int, matches: (View) -> Boolean): View? {
        val pending = ArrayDeque<View>()
        pending.add(root)
        var found: View? = null
        var budget = limit
        while (pending.isNotEmpty() && budget-- > 0) {
            val next = pending.removeFirst()
            if (!next.isShown) continue
            if (matches(next)) {
                if (found != null) return null
                found = next
            } else if (next is ViewGroup) {
                for (index in 0 until minOf(next.childCount, 18)) pending.add(next.getChildAt(index))
            }
        }
        return found?.takeIf { pending.isEmpty() }
    }

    private fun destinationClass(name: String): String? {
        val artistDestination = resource(name, "id")
        val id = resource("nav_graph", "navigation")
        if (id == 0 || artistDestination == 0) return null
        val android = "http://schemas.android.com/apk/res/android"
        return resources.getXml(id).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "fragment" &&
                    xml.getAttributeResourceValue(android, "id", 0) == artistDestination
                ) return@use xml.getAttributeValue(android, "name")
            }
            null
        }
    }

    private fun resource(name: String, type: String) = resources.getIdentifier(name, type, packageName)

    private fun labels(vararg names: String) = names.mapNotNull { name ->
        resource(name, "string").takeIf { it != 0 }?.let(resources::getString)
    }.toSet()

    companion object {
        private val artistViews = WeakHashMap<View, String>()
        private val detailClasses = HashSet<Class<*>>()
        private val creationHooks = HashSet<java.lang.reflect.Method>()
        private val touchHooks = HashSet<java.lang.reflect.Method>()
        private val touchOwners = WeakHashMap<View, java.lang.ref.WeakReference<AppleMusicArtistGlass>>()

        private fun observeTouches(owner: View, artist: AppleMusicArtistGlass) {
            val dispatch = owner.javaClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            if (dispatch !in touchHooks) {
                XposedBridge.hookMethod(dispatch, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        touchOwners[param.thisObject]?.get()?.touch(param.args[0] as MotionEvent)
                    }
                })
                touchHooks += dispatch
            }
            touchOwners[owner] = java.lang.ref.WeakReference(artist)
        }

        private fun install(loader: ClassLoader, destination: String) {
            runCatching {
                val type = Class.forName(destination, false, loader)
                detailClasses += type
                val create = type.getMethod("onCreateView", LayoutInflater::class.java,
                    ViewGroup::class.java, Bundle::class.java)
                check(View::class.java.isAssignableFrom(create.returnType))
                if (create in creationHooks) return
                XposedBridge.hookMethod(create, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.result as? View ?: return
                        if (param.thisObject.javaClass !in detailClasses) return
                        artistViews[view] = param.thisObject.javaClass.name
                        ArtistDiagnostics.log("artist fragment created ${view.javaClass.name}")
                    }
                })
                creationHooks.add(create)
            }.onFailure { XposedBridge.log("[LiquidTab][AppleMusic][artist] fragment hook rejected: $it") }
        }
    }
}

internal object ArtistDiagnostics {
    private var output: java.io.File? = null
    private val lines = LinkedHashSet<String>()
    fun attach(context: android.content.Context) {
        if (!io.github.offlineglass.BuildConfig.DEBUG || output != null) return
        output = context.getExternalFilesDir(null)?.let { java.io.File(it, "liquidtab-artist-contracts.txt") }
        runCatching { output?.writeText("Artist render contracts, layout population binding, process=${android.os.Process.myPid()}\n") }
    }
    fun log(line: String) {
        if (lines.size >= 100 || !lines.add(line)) return
        XposedBridge.log("[LiquidTab][AppleMusic][artist] $line")
        runCatching { output?.appendText(line + "\n") }
    }
}

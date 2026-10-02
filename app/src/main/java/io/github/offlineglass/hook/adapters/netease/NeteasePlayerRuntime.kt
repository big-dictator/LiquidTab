package io.github.offlineglass.hook.adapters.netease

import android.animation.*
import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.graphics.drawable.toBitmap
import de.robv.android.xposed.*
import io.github.offlineglass.hook.*
import io.github.offlineglass.hook.GlassHostLayout.Companion.ROUNDED_RECT_REFRACTION_SHADER
import io.github.offlineglass.hook.GlassHostLayout.Companion.BLOOM_STROKE_DUAL_SHADER
import io.github.offlineglass.hook.GlassHostLayout.Companion.PANEL_SHADOW_BLUR_DP
import io.github.offlineglass.hook.GlassHostLayout.Companion.PANEL_SHADOW_ALPHA
import io.github.offlineglass.hook.GlassHostLayout.Companion.PANEL_LIGHT_SHADOW_ALPHA
import kotlin.math.*

internal val GlassHostLayout.neteaseRuntime: NeteasePlayerState
    get() = (appNavigationState as NeteaseNavigationState).playerState

internal fun GlassHostLayout.updateNeteaseHeartbeatState() {

        val now = SystemClock.uptimeMillis()

        if (now - neteaseRuntime.lastHeartbeatProbeMs < NETEASE_HEARTBEAT_PROBE_INTERVAL_MS) return

        neteaseRuntime.lastHeartbeatProbeMs = now

        val active = selectedIndex == 0 && neteaseRuntime.hasNavigationBar &&

            heartbeatTabTextSelected()

        if (active != neteaseRuntime.heartbeatActive || now - neteaseRuntime.lastHeartbeatLogMs > 2500L) {

            neteaseRuntime.lastHeartbeatLogMs = now

            android.util.Log.i(

                "GlassPerfNetEasePlayer",

                "heartbeat=$active sel=$selectedIndex bar=$neteaseRuntime.hasNavigationBar",

            )

        }
        if (neteaseRuntime.heartbeatActive != active) postInvalidateOnAnimation()
        neteaseRuntime.heartbeatActive = active

    }



    /** The 心动 top sub-tab is selected if a TextView with that label sits under a

     *  selected container (text itself selected, or its tab root selected). This

     *  is throttled to NETEASE_HEARTBEAT_PROBE_INTERVAL_MS and never per-frame. */

internal fun GlassHostLayout.heartbeatTabTextSelected(): Boolean {

        val root = rootView ?: return false

        val stack = ArrayDeque<Pair<View, Int>>()

        stack += root to 0

        while (stack.isNotEmpty()) {

            val (view, depth) = stack.removeLast()

            if (view is TextView && view.isShown &&
                view.text?.toString()?.trim() == NETEASE_HOME_HEARTBEAT_TAB_TEXT &&

                ancestorSelected(view)

            ) return true

            if (view is ViewGroup && depth < 24) {

                for (i in 0 until view.childCount) stack += view.getChildAt(i) to (depth + 1)

            }

        }

        return false

    }



internal fun GlassHostLayout.ancestorSelected(view: View): Boolean {

        var current: View? = view

        var hops = 0

        while (current != null && hops++ < 12) {

            if (current.isSelected || current.isActivated) return true

            current = current.parent as? View

        }

        return false

    }



internal fun GlassHostLayout.adjustNeteaseMiniPlayer() {

        if (context.packageName != NETEASE_PACKAGE || width <= 0 || height <= 0) return

        val adjStart = System.nanoTime()

        neteaseRuntime.adjustCount++

        val now = SystemClock.uptimeMillis()

        val maintenanceDue = neteaseRuntime.lastContentExtractMs == 0L ||

            now - neteaseRuntime.lastContentExtractMs >= NETEASE_MINI_PLAYER_MAINTENANCE_INTERVAL_MS



        // Pages like MyRecentPlayActivity have no navigation tab bar; the

        // glass surface then hosts only the mini-player capsule. The class scan

        // is a UI-thread tree walk; refresh only on maintenance cadence, never

        // on every PreDraw frame under a scrolling feed.

        if (maintenanceDue) {

            neteaseRuntime.hasNavigationBar = findViewByClassName(

                rootView,

                "com.netease.cloudmusic.theme.ui.NavigationTabLayout",

            ) != null

        }

        // The heartbeat probe must consume the current navigation presence,
        // including the first frame after returning from a player-only page.
        updateNeteaseHeartbeatState()

        // The 心动 page owns a full player. Its bottom navigation keeps only
        // labels; the mini-player and its independent shadow must stay hidden.
        val hidePlayerForHeartbeat = neteaseRuntime.hasNavigationBar &&
            selectedIndex == 0 && neteaseRuntime.heartbeatActive
        if (neteaseRuntime.hasNavigationBar) {
            if (maintenanceDue || neteaseRuntime.nativeBar?.isAttachedToWindow != true) {
                fun find(name: String): View? {
                    val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)
                    return if (id != 0) rootView.findViewById(id) else null
                }
                neteaseRuntime.nativeBar = find("minPlayerBar")
                neteaseRuntime.nativeBarContainer = find("minPlayerBarContainer")
                neteaseRuntime.nativeBarTitle = find("tv_music")
            }
            val nativeBar = neteaseRuntime.nativeBar
            val container = neteaseRuntime.nativeBarContainer
            val title = neteaseRuntime.nativeBarTitle as? TextView
            if (nativeBar != null) {
                NeteasePlayerAlphaGuard.install()
                NeteasePlayerAlphaGuard.ownBar(nativeBar, this)
            }
            if (hidePlayerForHeartbeat && nativeBar?.visibility != View.GONE) {
                nativeBar?.visibility = View.GONE
            } else if (!hidePlayerForHeartbeat && nativeBar?.visibility == View.GONE &&
                !title?.text.isNullOrBlank()) {
                nativeBar.visibility = View.VISIBLE
            }
            if (!hidePlayerForHeartbeat && nativeBar?.visibility == View.VISIBLE && container != null) {
                NeteasePlayerAlphaGuard.install()
                NeteasePlayerAlphaGuard.own(container, this)
                if (container.alpha != 1f) container.alpha = 1f
            }
        }

        updateNeteaseClipPaths()
        if (hidePlayerForHeartbeat) {
            neteaseRuntime.playerHitRect = null
            neteaseRuntime.playerTouchTarget = null
            neteaseRuntime.playerTouchRect = null
            neteaseRuntime.playerTouchShield?.visibility = View.INVISIBLE
            return
        }



        // Earlier builds moved the player off screen on 心动. Restore an
        // already-adopted native player regardless of the active sub-page;
        // the navigation chrome fade must never hide the player capsule.
        if (neteaseRuntime.playerHiddenByHeartbeat) {

            neteaseRuntime.miniPlayerTarget?.let { p ->

                p.translationY = neteaseRuntime.playerRestoreTranslationY

                neteaseRuntime.playerHiddenByHeartbeat = false

                neteaseRuntime.miniPlayerNativeRevive = true

            }

        }

        if (neteaseRuntime.miniPlayerNativeRevive) {

            // The throttled extraction skipped the player while it was slid off

            // on the 心动 sub-page. Force a fresh content + geometry resync on

            // the next frame so the disc/title/buttons come back intact.

            neteaseRuntime.miniPlayerNativeRevive = false

            neteaseRuntime.contentExtractPending = true

            neteaseRuntime.lastContentExtractMs = 0

        }



        var target = neteaseRuntime.miniPlayerTarget?.takeIf {

            it.isAttachedToWindow && it.isShown && it.visibility == View.VISIBLE &&

                it.width > 0 && it.height > 0

        }

        if (target == null && now - neteaseRuntime.lastMiniPlayerProbe >= NETEASE_MINI_PLAYER_PROBE_INTERVAL_MS) {

            neteaseRuntime.lastMiniPlayerProbe = now

            val root = rootView as? ViewGroup ?: return

            for (entryName in NETEASE_MINI_PLAYER_VIEW_NAMES) {

                val id = resources.getIdentifier(entryName, "id", NETEASE_PACKAGE)

                if (id == 0) continue

                val candidate = root.findViewById<View>(id) ?: continue

                if (candidate !== this && candidate.isAttachedToWindow && candidate.isShown &&

                    candidate.visibility == View.VISIBLE && candidate.width > width / 2 &&

                    candidate.height in (28f * density).roundToInt()..(120f * density).roundToInt()

                ) {

                    target = candidate

                    neteaseRuntime.miniPlayerTarget = candidate

                    break

                }

            }

        }

        target ?: return

        // A previous redraw path made the native player children invisible.
        // The glass drawable owns only the background; restore the actual
        // album, title and buttons (and their containers) before drawing it.
        if (target.alpha == 0f) target.alpha = 1f
        if (maintenanceDue || neteaseRuntime.visibleChildrenTarget !== target) {
            neteaseRuntime.visiblePlayerChildren.clear()
            neteaseRuntime.visibleChildrenTarget = target
            for (name in NETEASE_MINI_PLAYER_CHILD_IDS.values) {
                val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)
                if (id != 0) target.findViewById<View>(id)?.let(neteaseRuntime.visiblePlayerChildren::add)
            }
        }
        for (child in neteaseRuntime.visiblePlayerChildren) {
            var content: View? = child.takeIf { it.isAttachedToWindow }
            while (content != null && content !== target) {
                if (content.visibility == View.INVISIBLE) content.visibility = View.VISIBLE
                if (content.alpha == 0f) content.alpha = 1f
                content = content.parent as? View
            }
        }





        val root = rootView as? ViewGroup

        // The findViewById loop below (native plates + decorations) ran on every

        // PreDraw frame, a UI-thread tree walk that directly drove the

        // search/notes/mine scroll jank. Gate it to rebind + the maintenance

        // cadence. NeteasePlayerBackgroundGuard re-owns plates at the setter and

        // the onDraw chrome suppressor already catches rebinds at 160ms, so no

        // native chrome visually escapes. Rendering effects are untouched.

        val needChrome = maintenanceDue || neteaseRuntime.contentExtractTarget !== target



        neteaseRuntime.vipBannerRoot?.let { promo ->
            if (promo.isAttachedToWindow) {
                if (promo.visibility != View.GONE) promo.visibility = View.GONE
            } else neteaseRuntime.vipBannerRoot = null
        }
        val vipProbeInterval = if (neteaseRuntime.vipBannerRoot == null)
            NETEASE_MINI_PLAYER_PROBE_INTERVAL_MS else NETEASE_MINI_PLAYER_MAINTENANCE_INTERVAL_MS
        if (now - neteaseRuntime.lastVipBannerProbe >= vipProbeInterval) {
            neteaseRuntime.lastVipBannerProbe = now
            root?.let { suppressNeteaseVipBanner(it, target) }
        }



        // Native ci0.f rewrites the themed root background on rebinding.

        // Guard owned backgrounds at the setter, not one frame afterwards.

        NeteasePlayerBackgroundGuard.install()



        // TRAE's full redraw hid every native child and moved the navigation

        // into an enlarged coordinate space. Restore those children; their

        // own layout/animation is the authoritative source for disc, title and

        // buttons, while this module owns only the surrounding glass chrome.

        (target as? ViewGroup)?.let { group ->

            for (index in 0 until group.childCount) {

                val child = group.getChildAt(index)

                if (child.visibility == View.INVISIBLE) child.visibility = View.VISIBLE

            }

        }



        if (needChrome) {

            // The root receives our glass drawable below, but NetEase also paints

            // an opaque/translucent plate on nested mini-player content views.

            // Remove those plates only; icons, title, progress and click handling

            // remain completely native.

            for (name in NETEASE_MINI_PLAYER_NATIVE_BG_IDS) {

                val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)

                if (id == 0) continue

                root?.findViewById<View>(id)?.takeUnless { it === target }?.let { chrome ->

                    NeteasePlayerBackgroundGuard.own(chrome, null, this)

                    chrome.background = null

                    chrome.foreground = null

                    (chrome as? ImageView)?.setImageDrawable(null)

                }

            }

            for (name in NETEASE_MINI_PLAYER_DECORATION_IDS) {

                val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)

                if (id == 0) continue

                root?.findViewById<View>(id)?.takeUnless { it === target }?.let { decoration ->

                    decoration.background = null

                    decoration.foreground = null

                    (decoration as? ImageView)?.setImageDrawable(null)

                    if (decoration.visibility != View.GONE) decoration.visibility = View.GONE

                }

            }

        }



        if (target !== neteaseRuntime.miniPlayerBlurTarget) {

            neteaseRuntime.miniPlayerBlurTarget?.let(::disableOpticalSurfaceNativeBlur)

            val drawable = NeteaseMiniPlayerGlassDrawable(

                ROUNDED_RECT_REFRACTION_SHADER,

                runCatching { RuntimeShader(BLOOM_STROKE_DUAL_SHADER) }.getOrNull(),

            )

            NeteasePlayerBackgroundGuard.own(target, drawable, this)

            target.background = drawable

            target.outlineProvider = BottomBarSquircleOutlineProvider(

                radius = {

                    min(target.width.toFloat(), target.height.toFloat()) *

                        (config.cornerRadiusPercent / 100f)

                },

                smoothing = { config.cornerSmoothing },

            )

            target.clipToOutline = true

            disableOpticalSurfaceNativeBlur(target)

            neteaseRuntime.miniPlayerGlassDrawable = drawable

            neteaseRuntime.miniPlayerBlurTarget = target

        }

        // NetEase reapplies MiniNewStyleRootView's native plate whenever a tab

        // is rebound. Keep the already-created glass drawable authoritative;

        // this is an identity check and writes only when the app replaced it.

        neteaseRuntime.miniPlayerGlassDrawable?.let { glass ->

            NeteasePlayerBackgroundGuard.own(target, glass, this)

            if (target.background !== glass) {

                target.background = glass

                disableOpticalSurfaceNativeBlur(target)

            }

        }

        val dark = isDarkGlass()

        var playerPager = neteaseRuntime.miniPlayerScene?.takeIf { it.isAttachedToWindow && it.isShown }

        val probeScene = playerPager == null &&
            (maintenanceDue || neteaseRuntime.playerSceneProbeTarget !== target)
        if (probeScene) {
            val pagerId = resources.getIdentifier("mainActivityViewPager", "id", NETEASE_PACKAGE)
            playerPager = if (pagerId != 0) rootView.findViewById<View>(pagerId) else null
        }

        if (probeScene && playerPager == null) {

            // MyRecentPlayActivity has no main view pager; sample the list

            // pager (a sibling above the mini player) as the optical backdrop.

            for (name in arrayOf("commonViewPager", "pagerListview")) {

                val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)

                if (id == 0) continue

                val candidate = rootView.findViewById<View>(id)

                if (candidate != null && candidate.isShown) {

                    playerPager = candidate

                    break

                }

            }

        }

        neteaseRuntime.playerSceneProbeTarget = target
        neteaseRuntime.miniPlayerScene = playerPager
        neteaseRuntime.miniPlayerGlassDrawable?.prepareScene(
            scene = playerPager.takeUnless { config.solidBarEnabled },
            target = target,
            blurRadius = if (config.solidBarEnabled) 0f else config.blurRadius * density,
            liquidGlassEnabled = config.liquidGlassEnabled,
            density = density,
        )

        val basePlayerFill = surfaceContainerColor(dark)

        // Match the navigation panel's configured color and alpha exactly.
        // The optical backdrop still uses the same live blur radius above.
        val playerFill = if (config.solidBarEnabled) Color.argb(
            255, Color.red(basePlayerFill), Color.green(basePlayerFill), Color.blue(basePlayerFill),
        ) else basePlayerFill

        neteaseRuntime.miniPlayerGlassDrawable?.update(

            fillColor = playerFill,

            dark = dark,

            radiusPercent = config.cornerRadiusPercent,

            smoothing = config.cornerSmoothing,

            outlineStrength = if (dark) {

                0.58f * config.darkBarHighlightStrength

            } else {

                0.75f

            },

        )

        if (needChrome) {
            val outlineSignature = "${target.width}:${target.height}:${config.cornerRadiusPercent}:${config.cornerSmoothing}"
            if (neteaseRuntime.playerOutlineSignature != outlineSignature ||
                neteaseRuntime.contentExtractTarget !== target) {
                neteaseRuntime.playerOutlineSignature = outlineSignature
                target.invalidateOutline()
            }
        }

        // The baseline capsule was 1084px wide on a 1220px screen: 68px on
        // both sides. Only set the host width when the native parent can hold
        // that width plus its side margins; narrower sub-page wrappers keep
        // their own layout to avoid squeezing the title and controls.
        val playerParent = target.parent as? ViewGroup
        if (neteaseRuntime.hasNavigationBar && playerParent != null &&
            playerParent.width - width >= 32f * density &&
            target.layoutParams.width != width) {
            val hostWidth = width
            target.layoutParams = target.layoutParams.apply { this.width = hostWidth }
        }



        val hostLocation = IntArray(2)

        val playerLocation = IntArray(2)

        getLocationInWindow(hostLocation)

        target.getLocationInWindow(playerLocation)

        // Only align against the settled navigation position. Following its

        // animated window coordinates would move the otherwise independent player.

        if (neteaseRuntime.hasNavigationBar && !barVisibilityAnimating && barVisibilityTarget) {

            val horizontalCorrection = hostLocation[0].toFloat() - playerLocation[0]

            if (abs(horizontalCorrection) > 0.5f) {

                target.translationX += horizontalCorrection

            }

            val desiredBottom = hostLocation[1].toFloat() - NETEASE_MINI_PLAYER_GAP_DP * density

            val currentBottom = playerLocation[1].toFloat() + target.height

            val correction = desiredBottom - currentBottom

            if (abs(correction) > 0.5f) {

                target.translationY += correction

            }

        }



        // Re-arm touch consumption on the translated mini player. Without a

        // guard the visible capsule is inert: taps fall through to the feed

        // below and play/pause + playlist become unclickable.

        installNeteasePlayerTouchGuard(target)

        updateNeteasePlayerTouchShield(target)



        // Re-arm host-driven forwarding for taps over the translated capsule.

        // The host extends upward to cover the mini player, so the player's

        // own OnTouchListener never sees the gesture; without a hit rect here

        // the tap falls straight through the host to the feed below. Live

        // geometry lets beginNeteasePlayerTouch consume the capsule and

        // forward to the native controls (which the guard then dispatches).

        getLocationInWindow(hostLocation)

        target.getLocationInWindow(playerLocation)

        neteaseRuntime.playerHitRect = RectF(

            (playerLocation[0] - hostLocation[0]).toFloat(),

            (playerLocation[1] - hostLocation[1]).toFloat(),

            (playerLocation[0] - hostLocation[0] + target.width).toFloat(),

            (playerLocation[1] - hostLocation[1] + target.height).toFloat(),

        )



        // Disable all dormant full-host redraw state; the mini player capsule

        // is rendered by its own background drawable below the host.

        neteaseRuntime.playerBarRect = null

        neteaseRuntime.playerTouchTarget = null

        neteaseRuntime.playerTouchRect = null



        // Precise per-button dispatch needs the real native controls. Without

        // childViews/contentRects the tap path degrades to a whole-bar click

        // ("open player page"), so play/pause and playlist keys do nothing.

        // Throttle extraction to NETEASE_MINI_PLAYER_MAINTENANCE_INTERVAL_MS.

        // The expensive bitmap copy of the rotating album cover on every scrolled

        // frame was the primary cause of search/notes/mine jank. This preserves

        // all rendering/live blur/refraction/shadow effects and only throttles

        // the pure UI-thread extraction that doesn't affect the glass surface.

        val needExtract = (target !== neteaseRuntime.contentExtractTarget) ||

            (neteaseRuntime.contentExtractPending) ||

            (now - neteaseRuntime.lastContentExtractMs >= NETEASE_MINI_PLAYER_MAINTENANCE_INTERVAL_MS)

        val extractStart = System.nanoTime()

        if (needExtract && root != null) {

            extractNeteaseMiniPlayerContent(root)

            updateNeteasePlayerBarGeometry(hostLocation, playerLocation)

            neteaseRuntime.contentExtractTarget = target

            neteaseRuntime.lastContentExtractMs = now

            neteaseRuntime.contentExtractPending = false

        }

        neteaseRuntime.extractNs += System.nanoTime() - extractStart

        neteaseRuntime.adjustNs += System.nanoTime() - adjStart

        logNetAdjIfDue()

    }



internal fun GlassHostLayout.logNetAdjIfDue() {

        val now = SystemClock.uptimeMillis()

        if (now - neteaseRuntime.lastAdjustLogMs < 1000L) return

        neteaseRuntime.lastAdjustLogMs = now

        val count = neteaseRuntime.adjustCount

        val avgAdjUs = if (count > 0) (neteaseRuntime.adjustNs / count) / 1000 else 0L

        val avgExtractUs = if (count > 0) (neteaseRuntime.extractNs / count) / 1000 else 0L

        android.util.Log.i(

            "GlassPerfNetEasePlayer",

            "adjustFrames=$count avgAdjUs=$avgAdjUs avgExtractUs=$avgExtractUs " +

                "navBar=$neteaseRuntime.hasNavigationBar barVis=$barVisibilityTarget anim=$barVisibilityAnimating",

        )

        neteaseRuntime.adjustCount = 0

        neteaseRuntime.adjustNs = 0

        neteaseRuntime.extractNs = 0

    }



internal fun GlassHostLayout.updateNeteasePlayerTouchShield(target: View) {

        val hostParent = parent as? ViewGroup ?: return

        var shield = neteaseRuntime.playerTouchShield?.takeIf {

            it.isAttachedToWindow && it.parent === hostParent

        }

        if (shield == null) {

            shield = View(context).apply {

                background = null

                isClickable = true

                isFocusable = false

                elevation = 20f * density

                var downRawX = 0f

                var downRawY = 0f

                setOnTouchListener { _, event ->

                    if (isNeteaseSideDrawerOpen()) {

                        return@setOnTouchListener false

                    }

                    when (event.actionMasked) {

                        MotionEvent.ACTION_DOWN -> {

                            downRawX = event.rawX

                            downRawY = event.rawY

                            true

                        }

                        MotionEvent.ACTION_MOVE -> true

                        MotionEvent.ACTION_UP -> {

                            if (abs(event.rawX - downRawX) <= touchSlop &&

                                abs(event.rawY - downRawY) <= touchSlop

                            ) {

                                val control = listOf("play", "list")

                                    .mapNotNull(neteaseRuntime.playerChildViews::get)

                                    .firstOrNull { child ->

                                        if (!child.isAttachedToWindow || child.visibility != View.VISIBLE) false

                                        else {

                                            val loc = IntArray(2).also(child::getLocationInWindow)

                                            event.rawX >= loc[0] && event.rawX <= loc[0] + child.width &&

                                                event.rawY >= loc[1] && event.rawY <= loc[1] + child.height

                                        }

                                    }

                                (control ?: neteaseRuntime.miniPlayerTarget)?.performClick()

                            }

                            true

                        }

                        MotionEvent.ACTION_CANCEL -> true

                        else -> true

                    }

                }

            }

            hostParent.addView(shield, ViewGroup.LayoutParams(target.width, target.height))

            neteaseRuntime.playerTouchShield = shield

        }

        val parentLocation = IntArray(2).also(hostParent::getLocationInWindow)

        val targetLocation = IntArray(2).also(target::getLocationInWindow)

        val params = shield.layoutParams

        if (params.width != target.width || params.height != target.height) {

            params.width = target.width

            params.height = target.height

            shield.layoutParams = params

        }

        shield.translationX = (targetLocation[0] - parentLocation[0]).toFloat()

        shield.translationY = (targetLocation[1] - parentLocation[1]).toFloat()

        val drawerOpen = isNeteaseSideDrawerOpen()

        shield.visibility = if (!drawerOpen && selectedIndex != 0 && barVisibilityTarget) {

            View.VISIBLE

        } else {

            View.INVISIBLE

        }

    }



    /**

     * The mini player is translated above its stock slot, so its old parent

     * no longer provides a dependable hit boundary. Consume the complete

     * visible capsule at the player root and explicitly invoke the two native

     * controls when their screen rect is tapped. This preserves the app's own

     * click listeners while making every remaining pixel a true touch shield.

     */

internal fun GlassHostLayout.installNeteasePlayerTouchGuard(target: View) {

        if (neteaseRuntime.playerTouchGuards.put(target, true) != null) return

        var downRawX = 0f

        var downRawY = 0f

        target.setOnTouchListener { view, event ->

            if (isNeteaseSideDrawerOpen()) {

                return@setOnTouchListener false

            }

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    downRawX = event.rawX

                    downRawY = event.rawY

                    true

                }

                MotionEvent.ACTION_MOVE -> true

                MotionEvent.ACTION_UP -> {

                    val moved = abs(event.rawX - downRawX) > touchSlop ||

                        abs(event.rawY - downRawY) > touchSlop

                    if (!moved) {

                        val control = listOf("play", "list")

                            .mapNotNull(neteaseRuntime.playerChildViews::get)

                            .firstOrNull { child ->

                                if (!child.isAttachedToWindow || child.visibility != View.VISIBLE) false

                                else {

                                    val location = IntArray(2).also(child::getLocationInWindow)

                                    event.rawX >= location[0] && event.rawX <= location[0] + child.width &&

                                        event.rawY >= location[1] && event.rawY <= location[1] + child.height

                                }

                            }

                        (control ?: view).performClick()

                    }

                    true

                }

                MotionEvent.ACTION_CANCEL -> true

                else -> true

            }

        }

    }



internal fun GlassHostLayout.updateNeteaseSideDrawerLayer() {

        val open = isNeteaseSideDrawerOpen()

        if (open == neteaseRuntime.sideDrawerWasOpen) return

        neteaseRuntime.sideDrawerWasOpen = open

        if (open) {

            neteaseRuntime.sideDrawer?.let { drawer ->

                if (neteaseRuntime.sideDrawerOriginalElevation == null) {

                    neteaseRuntime.sideDrawerOriginalElevation = drawer.elevation

                    neteaseRuntime.sideDrawerOriginalTranslationZ = drawer.translationZ

                }

                // The player already lives below the native DrawerLayout. Put

                // the glass host in that same visual band by temporarily

                // promoting the real drawer above the host, rather than giving

                // the host a permanent window-level Z override.

                drawer.elevation = max(drawer.elevation, elevation + density)

                drawer.translationZ = max(drawer.translationZ, translationZ + density)

                (drawer.parent as? ViewGroup)?.bringChildToFront(drawer)

            }

        } else {

            neteaseRuntime.sideDrawer?.let { drawer ->

                neteaseRuntime.sideDrawerOriginalElevation?.let { drawer.elevation = it }

                neteaseRuntime.sideDrawerOriginalTranslationZ?.let { drawer.translationZ = it }

            }

            neteaseRuntime.sideDrawerOriginalElevation = null

            neteaseRuntime.sideDrawerOriginalTranslationZ = null

            (parent as? ViewGroup)?.bringChildToFront(this)

        }

    }



internal fun GlassHostLayout.installNeteaseDrawerButtonTrigger() {

        val cachedButton = neteaseRuntime.drawerButtonView

        if (cachedButton?.isAttachedToWindow == true) return

        val root = rootView as? ViewGroup ?: return

        val buttonId = resources.getIdentifier("menu_icon_container", "id", NETEASE_PACKAGE)

            .takeIf { it != 0 }

            ?: resources.getIdentifier("menu_icon", "id", NETEASE_PACKAGE)

        if (buttonId == 0) return

        val button = root.findViewById<View>(buttonId) ?: return

        neteaseRuntime.drawerButtonView = button

        if (neteaseRuntime.drawerButtonHooks.put(button, true) != null) return

        button.setOnTouchListener { _, event ->

            if (event.actionMasked == MotionEvent.ACTION_DOWN) {

                neteaseRuntime.drawerInteractionArmed = true

                neteaseRuntime.drawerOpeningUntil = SystemClock.uptimeMillis() +

                    NETEASE_DRAWER_OPENING_GRACE_MS

                neteaseRuntime.sideDrawerOpenCached = true

                // Start the standard bar exit at finger-down, before the

                // native drawer has completed its first translated frame.

                updateAnimatedVisibility(false)

                syncOpticalVisibilityCompanions()

            }

            false

        }

    }



internal fun GlassHostLayout.isNeteaseSideDrawerOpen(): Boolean {

        val stateNow = SystemClock.uptimeMillis()

        if (stateNow - neteaseRuntime.lastDrawerStateCheck < NETEASE_DRAWER_STATE_INTERVAL_MS) {

            return neteaseRuntime.sideDrawerOpenCached

        }

        neteaseRuntime.lastDrawerStateCheck = stateNow

        var drawer = neteaseRuntime.sideDrawer?.takeIf { it.isAttachedToWindow }

        if (drawer == null) {

            val now = SystemClock.uptimeMillis()

            if (now - neteaseRuntime.lastSideDrawerProbe < NETEASE_DRAWER_PROBE_INTERVAL_MS) {

                return neteaseRuntime.sideDrawerOpenCached

            }

            neteaseRuntime.lastSideDrawerProbe = now

            val root = rootView as? ViewGroup ?: return false

            // mainActivityContainer is the real AndroidX DrawerLayout. Using

            // it avoids the ambiguous always-laid-out mainDrawerContainer and

            // lets isDrawerOpen(child) report the state without tree scans.

            val drawerId = resources.getIdentifier("mainActivityContainer", "id", NETEASE_PACKAGE)

            drawer = if (drawerId != 0) root.findViewById<ViewGroup>(drawerId) else null

            if (drawer != null) {

                neteaseRuntime.sideDrawer = drawer

                neteaseRuntime.sideDrawerIsPanel = false

            }

        }

        val group = drawer ?: return SystemClock.uptimeMillis() < neteaseRuntime.drawerOpeningUntil

        val method = group.javaClass.methods.firstOrNull {

            it.name == "isDrawerOpen" && it.parameterTypes.size == 1 &&

                View::class.java.isAssignableFrom(it.parameterTypes[0])

        }

        var hasDrawerChild = false

        for (index in 0 until group.childCount) {

            val child = group.getChildAt(index)

            val gravity = runCatching {

                child.layoutParams.javaClass.getField("gravity").getInt(child.layoutParams)

            }.getOrDefault(0)

            if (gravity == 0) continue

            hasDrawerChild = true

            val reportedOpen = runCatching { method?.invoke(group, child) as? Boolean }.getOrNull()

            if (reportedOpen == true) {

                neteaseRuntime.sideDrawerOpenCached = true

                return true

            }

            if (reportedOpen == null && child.visibility == View.VISIBLE &&

                child.right > 1 && child.left < group.width - 1

            ) {

                neteaseRuntime.sideDrawerOpenCached = true

                return true

            }

        }

        val opening = SystemClock.uptimeMillis() < neteaseRuntime.drawerOpeningUntil

        // With the real DrawerLayout, a drawer child plus a false native state

        // is authoritative. The short grace only bridges the button-down frame

        // before DrawerLayout updates its own open flag.

        neteaseRuntime.sideDrawerOpenCached = opening && (neteaseRuntime.drawerInteractionArmed || !hasDrawerChild)

        if (!neteaseRuntime.sideDrawerOpenCached) neteaseRuntime.drawerInteractionArmed = false

        return opening

    }



    /**

     * RN/detail Activities do not expose mainActivityViewPager. Their page is

     * normally a large sibling of the native mini-player branch. Select that

     * sibling as the optical input so the player uses the exact same GPU glass

     * drawable as the main tabs without ever sampling itself.

     */

internal fun GlassHostLayout.findNeteaseSubpageBackdrop(player: View): View? {

        var branch: View = player

        var parent = branch.parent as? ViewGroup

        var best: View? = null

        var bestArea = 0L

        var depth = 0

        while (parent != null && depth++ < 12) {

            for (index in 0 until parent.childCount) {

                val candidate = parent.getChildAt(index)

                if (candidate === branch || candidate === this ||

                    candidate.visibility != View.VISIBLE || candidate.alpha <= 0.01f ||

                    candidate.width <= 0 || candidate.height <= 0 ||

                    isDescendantOf(candidate, player) || isDescendantOf(player, candidate)

                ) continue

                val area = candidate.width.toLong() * candidate.height.toLong()

                if (candidate.width >= player.width * 0.6f && area > bestArea) {

                    best = candidate

                    bestArea = area

                }

            }

            branch = parent

            parent = parent.parent as? ViewGroup

        }

        return best

    }



internal fun GlassHostLayout.suppressNeteaseVipBanner(root: ViewGroup, miniPlayer: View) {

        fun find(name: String): View? {

            val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)

            return if (id == 0) null else root.findViewById(id)

        }

        fun ancestors(view: View): List<View> {

            val result = ArrayList<View>(8)

            var current: View? = view

            while (current != null && result.size < 12) {

                result += current

                if (current === root) break

                current = current.parent as? View

            }

            return result

        }



        // `freeIV` is specific to the mini-player membership/free-listen

        // promotion. Generic names such as bgContainer/titleTV/actionTV occur

        // throughout NetEase and must never be used as global hide targets.

        val free = find("freeIV") ?: return

        val countdown = find("countdownView")

        if (free.visibility != View.GONE) free.visibility = View.GONE

        countdown?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }

        val freeAncestors = ancestors(free)

        val promoRoot = countdown?.let { timer ->

            val timerAncestors = ancestors(timer).toHashSet()

            freeAncestors.firstOrNull { it in timerAncestors }

        } ?: (free.parent as? View)

        promoRoot ?: return



        // Promo views are attached asynchronously and can have a zero-sized

        // rect during the frame in which they first become visible. Geometry-

        // gated suppression therefore allowed the banner to flash/reappear.

        // Its dedicated anchors are unique; suppress their compact container

        // as soon as it exists, before relying on global visible rectangles.

        val compactPromo = promoRoot.height <= max(miniPlayer.height * 2, (120f * density).roundToInt())

        val accidentallyWholePlayer = promoRoot === miniPlayer ||

            isDescendantOf(miniPlayer, promoRoot)

        if (compactPromo && !accidentallyWholePlayer) {
            neteaseRuntime.vipBannerRoot = promoRoot
            NeteasePromoVisibilityGuard.own(promoRoot)
            if (promoRoot.visibility != View.GONE) promoRoot.visibility = View.GONE
        }

    }



    /** Strip the native player's own chrome; the host redraws every element. */

internal fun GlassHostLayout.hideNeteaseMiniPlayerNativeContent(target: View) {

        if (target.background != null) target.background = null

        if (target.outlineProvider !== ViewOutlineProvider.BACKGROUND) {

            target.outlineProvider = ViewOutlineProvider.BACKGROUND

        }

        if (target.clipToOutline) target.clipToOutline = false

        (target as? ViewGroup)?.let { group ->

            for (i in 0 until group.childCount) {

                val child = group.getChildAt(i)

                if (child.visibility != View.INVISIBLE) child.visibility = View.INVISIBLE

            }

        }

    }



internal fun GlassHostLayout.extractNeteaseMiniPlayerContent(root: ViewGroup) {

        fun find(name: String): View? {

            val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)

            if (id == 0) return null

            return root.findViewById<View>(id)

        }

        find("smallAlbumDisc0")?.let { disc ->

            (disc as? ImageView)?.drawable?.let { d ->

                if (neteaseRuntime.albumBitmap == null || d.constantState != neteaseRuntime.albumDrawableState) {

                    neteaseRuntime.albumBitmap = d.toBitmapSafely(disc.width, disc.height)

                    neteaseRuntime.albumDrawableState = d.constantState

                    neteaseRuntime.marqueeStartNanos = System.nanoTime()

                }

            }

            val discRot = disc.rotation

            val diskRot = find("miniDiskContainer")?.rotation ?: 0f

            val resolvedRot = if (abs(discRot) >= abs(diskRot)) discRot else diskRot

            neteaseRuntime.albumRotation = resolvedRot

            // Measure the off-screen spin to bridge probe gaps while drawing.

            val nowMs = SystemClock.uptimeMillis()

            if (neteaseRuntime.lastDiscRotationTime > 0L) {

                val dt = (nowMs - neteaseRuntime.lastDiscRotationTime).coerceAtLeast(1L)

                var delta = resolvedRot - neteaseRuntime.lastDiscRotation

                delta = ((delta + 180f) % 360f + 360f) % 360f - 180f

                neteaseRuntime.discSpeed = delta / dt

            }

            neteaseRuntime.lastDiscRotation = resolvedRot

            neteaseRuntime.lastDiscRotationTime = nowMs

            neteaseRuntime.playerChildViews["diskArea"] = find("miniDiskContainer") ?: disc

        }

        find("tv_music")?.let { title ->

            val text = (title as? TextView)?.text?.toString() ?: ""

            if (text != neteaseRuntime.songTitle) {

                neteaseRuntime.songTitle = text

                neteaseRuntime.marqueeTitle = text

                neteaseRuntime.marqueeStartNanos = System.nanoTime()

            }

        }

        find("minPlayBtn")?.let { btn ->

            neteaseRuntime.playDrawable = (btn as? ImageView)?.drawable

            neteaseRuntime.playerChildViews["play"] = btn

        }

        // The playlist key is a container in the new mini bar style; dig out

        // its artwork and clickable child when the direct id is absent.

        find("minPlayListBtnContainer")?.let { container ->

            val direct = find("minPlayListBtn") as? ImageView

            val nested = (container as? ViewGroup)?.let { g ->

                (0 until g.childCount).mapNotNull { g.getChildAt(it) as? ImageView }

                    .firstOrNull()

            }

            neteaseRuntime.listDrawable = (direct ?: nested)?.drawable

            neteaseRuntime.playerChildViews["list"] = (direct ?: nested ?: container) as View

        }

        find("miniPlayBarLayout")?.let { neteaseRuntime.playerChildViews["songArea"] = it }

    }



internal fun GlassHostLayout.updateNeteasePlayerBarGeometry(

        hostLocation: IntArray,

        playerLocation: IntArray,

    ) {

        // Only the per-control hit rects are needed for precise button dispatch.

        // Deliberately does NOT set neteaseRuntime.playerBarRect: that would fuse the

        // player capsule into the host glass and double-render the surface on

        // top of the mini player's own background drawable.

        val rects = HashMap<String, RectF>()

        for ((key, name) in NETEASE_MINI_PLAYER_CHILD_IDS) {

            val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)

            if (id == 0) continue

            rootView?.findViewById<View>(id)?.let { v ->

                if (v.visibility == View.GONE) return@let

                val loc = IntArray(2).also(v::getLocationInWindow)

                rects[key] = RectF(

                    (loc[0] - hostLocation[0]).toFloat(),

                    (loc[1] - hostLocation[1]).toFloat(),

                    (loc[0] - hostLocation[0] + v.width).toFloat(),

                    (loc[1] - hostLocation[1] + v.height).toFloat(),

                )

            }

        }

        if (rects != neteaseRuntime.playerContentRects) neteaseRuntime.playerContentRects = rects

    }



    /** Top edge of the navigation capsule inside the extended host. */

internal fun GlassHostLayout.neteaseBarTopPx(): Float {

        neteaseRuntime.playerBarRect?.let { return it.bottom + NETEASE_MINI_PLAYER_GAP_DP * density }

        // No player bar yet: keep the capsule at native height against the

        // host bottom; the transparent upper strip lets the native bar show.

        return (height - config.barHeight * density).coerceAtLeast(0f)

    }



internal fun GlassHostLayout.updateNeteaseClipPaths() {

        val barTop = neteaseBarTopPx()

        val navHeight = (height - barTop).coerceAtLeast(1f)

        val player = neteaseRuntime.playerBarRect

        val geometrySignature = "$width/$height/$barTop/$neteaseRuntime.hasNavigationBar/" +

            "${player?.left}/${player?.top}/${player?.right}/${player?.bottom}/" +

            "${config.cornerRadiusPercent}/${config.cornerSmoothing}"

        if (geometrySignature == neteaseRuntime.clipGeometrySignature) return

        neteaseRuntime.clipGeometrySignature = geometrySignature

        if (neteaseRuntime.hasNavigationBar) {

            neteaseRuntime.bottomBarRect.set(0f, barTop, width.toFloat(), height.toFloat())

            outerRect.set(neteaseRuntime.bottomBarRect)

            val pillPath = Path()

            pillPath.setBottomBarSquircle(

                neteaseRuntime.bottomBarRect,

                min(width.toFloat(), navHeight) * (config.cornerRadiusPercent / 100f),

                config.cornerSmoothing,

            )

            clipPath.set(pillPath)

        } else {

            // No navigation capsule on this page: clear the bottom bar rect so

            // the glass surface covers only the mini-player capsule.

            neteaseRuntime.bottomBarRect.setEmpty()

            outerRect.set(neteaseRuntime.playerBarRect ?: RectF())

            clipPath.rewind()

        }

        // Once the player bar is tracked, fuse its own floating capsule into

        // the glass surface above the navigation capsule, leaving the 4dp gap

        // transparent as the design's edge gap. Both capsules share the same

        // squircle curvature so the pair reads as one continuous glass bar.

        neteaseRuntime.playerBarRect?.takeIf { !it.isEmpty }?.let { bar ->

            val playerRadius = min(bar.width(), bar.height()) *

                (config.cornerRadiusPercent / 100f)

            val playerPath = Path()

            playerPath.setBottomBarSquircle(bar, playerRadius, config.cornerSmoothing)

            clipPath.op(playerPath, Path.Op.UNION)

        }

        invalidateOutline()

    }



    /**

     * Rebuilds the mini player on the liquid glass surface: the native bar is

     * hidden by adjustNeteaseMiniPlayer, so every element (spinning disc art,

     * marquee title, play/playlist keys) is redrawn here from the geometry and

     * artwork extracted by the probe, keeping the original layout.

     */

internal fun GlassHostLayout.drawNeteaseMiniPlayerGlass(canvas: Canvas) {

        if (context.packageName != NETEASE_PACKAGE) return

        val bar = neteaseRuntime.playerBarRect ?: return

        if (bar.isEmpty || bar.width() <= 0f || bar.height() <= 0f) return

        val dark = isDarkGlass()



        // -- Glass background of the player capsule --------------------------

        neteaseRuntime.playerPath.setBottomBarSquircle(

            bar,

            min(bar.width(), bar.height()) * (config.cornerRadiusPercent / 100f),

            config.cornerSmoothing,

        )

        canvas.save()

        canvas.clipPath(neteaseRuntime.playerPath)

        if (hasUsableBackdrop() && !config.solidBarEnabled) drawOuterGlass(canvas)

        canvas.drawPath(neteaseRuntime.playerPath, fillPaint)

        // Hairline seam that separates the player capsule from the content

        // while keeping the same curvature family as the navigation capsule.

        if (config.outlineEnabled && !config.solidBarEnabled) {

            neteaseRuntime.playerStrokePaint.style = Paint.Style.STROKE

            neteaseRuntime.playerStrokePaint.strokeWidth = 1f * density

            neteaseRuntime.playerStrokePaint.color = if (dark) {

                Color.argb(0x22, 255, 255, 255)

            } else {

                Color.argb(0x14, 0, 0, 0)

            }

            canvas.drawPath(neteaseRuntime.playerPath, neteaseRuntime.playerStrokePaint)

        }

        canvas.restore()



        // -- Redrawn content -------------------------------------------------

        canvas.save()

        canvas.clipPath(neteaseRuntime.playerPath)

        drawNeteaseMiniPlayerDisc(canvas, dark)

        drawNeteaseMiniPlayerTitle(canvas, dark)

        drawNeteaseMiniPlayerButton(canvas, "play", neteaseRuntime.playDrawable)

        drawNeteaseMiniPlayerButton(canvas, "list", neteaseRuntime.listDrawable)

        canvas.restore()

    }



    /** Spinning disc: dark platter + circular album art + centre spindle. */

internal fun GlassHostLayout.drawNeteaseMiniPlayerDisc(canvas: Canvas, dark: Boolean) {

        val discRect = neteaseRuntime.playerContentRects["disc"]

            ?: neteaseRuntime.playerContentRects["diskArea"]

        val coverRect = neteaseRuntime.playerContentRects["album"]

        val bitmap = neteaseRuntime.albumBitmap?.takeUnless(Bitmap::isRecycled) ?: return

        val bar = neteaseRuntime.playerBarRect ?: return

        if (bar.isEmpty || bar.width() <= 0f || bar.height() <= 0f) return

        if (discRect == null && coverRect == null) return

        // Concentric with the player capsule's left corner, slightly smaller

        // so the platter no longer clips against the capsule edge.

        val barRadius = min(bar.width(), bar.height()) * (config.cornerRadiusPercent / 100f)

        val cx = bar.left + barRadius

        val cy = bar.centerY()

        val discRadius = barRadius * NETEASE_DISC_RADIUS_SCALE

        // Platter: the full disc area, subtly darker than the glass surface.

        neteaseRuntime.albumCirclePath.reset()

        neteaseRuntime.albumCirclePath.addCircle(cx, cy, discRadius, Path.Direction.CW)

        canvas.save()

        canvas.clipPath(neteaseRuntime.albumCirclePath)

        neteaseRuntime.playerFillPaint.color = if (dark) Color.argb(0x66, 0, 0, 0)

        else Color.argb(0x30, 0, 0, 0)

        canvas.drawRect(

            RectF(cx - discRadius, cy - discRadius, cx + discRadius, cy + discRadius),

            neteaseRuntime.playerFillPaint,

        )

        canvas.restore()

        // Album art: a concentric circle inset from the platter edge.

        val coverRadius = discRadius - 6f * density

        neteaseRuntime.albumCirclePath.reset()

        neteaseRuntime.albumCirclePath.addCircle(cx, cy, coverRadius, Path.Direction.CW)

        canvas.save()

        canvas.clipPath(neteaseRuntime.albumCirclePath)

        val rotation = currentNeteaseDiscRotation()

        canvas.save()

        canvas.rotate(rotation, cx, cy)

        canvas.drawBitmap(

            bitmap,

            null,

            RectF(cx - coverRadius, cy - coverRadius, cx + coverRadius, cy + coverRadius),

            neteaseRuntime.playerBitmapPaint,

        )

        canvas.restore()

        neteaseRuntime.playerStrokePaint.style = Paint.Style.STROKE

        neteaseRuntime.playerStrokePaint.strokeWidth = 1f * density

        neteaseRuntime.playerStrokePaint.color = Color.argb(0x30, 255, 255, 255)

        canvas.drawCircle(cx, cy, coverRadius, neteaseRuntime.playerStrokePaint)

        canvas.restore()

        // Centre spindle: a small fixed dot so the spin reads as rotation.

        neteaseRuntime.playerFillPaint.color = if (dark) Color.argb(0xCC, 0x0B, 0x0B, 0x0F)

        else Color.argb(0x99, 0xFF, 0xFF, 0xFF)

        canvas.drawCircle(cx, cy, 2.2f * density, neteaseRuntime.playerFillPaint)

    }



    /** Marquee title: static when it fits, otherwise scrolls left in cycles. */

internal fun GlassHostLayout.drawNeteaseMiniPlayerTitle(canvas: Canvas, dark: Boolean) {

        val rect = neteaseRuntime.playerContentRects["title"] ?: return

        val text = neteaseRuntime.marqueeTitle.ifEmpty { neteaseRuntime.songTitle }

        if (text.isEmpty()) return

        neteaseRuntime.playerTitlePaint.textSize = 14f * resources.displayMetrics.scaledDensity

        neteaseRuntime.playerTitlePaint.color = if (dark) {

            Color.argb(0xF2, 0xF5, 0xF5, 0xF5)

        } else {

            Color.argb(0xE6, 0x1A, 0x1A, 0x1A)

        }

        neteaseRuntime.playerTitlePaint.alpha = 255

        val fm = neteaseRuntime.playerTitlePaint.fontMetrics

        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f

        // Extend the title's right edge up to the play key, keeping a margin

        // so the marquee never overlaps the neighbouring controls.

        val playLeft = neteaseRuntime.playerContentRects["play"]?.left

        val rightLimit = (playLeft ?: rect.right) - NETEASE_TITLE_RIGHT_MARGIN_DP * density

        val right = maxOf(rect.right, rightLimit)

        val textWidth = neteaseRuntime.playerTitlePaint.measureText(text)

        val available = right - rect.left

        if (available <= 0f) return

        if (textWidth <= available) {

            canvas.drawText(text, rect.left, baseline, neteaseRuntime.playerTitlePaint)

            return

        }

        val gap = 48f * density

        val cycle = textWidth + gap

        val elapsedMs = (System.nanoTime() - neteaseRuntime.marqueeStartNanos).coerceAtLeast(0L) / 1_000_000L

        val offset = -((elapsedMs * NETEASE_MARQUEE_SPEED_PX_PER_MS).toFloat() % cycle)

        canvas.drawText(text, rect.left + offset, baseline, neteaseRuntime.playerTitlePaint)

        if (rect.left + offset + cycle < right) {

            canvas.drawText(text, rect.left + offset + cycle, baseline, neteaseRuntime.playerTitlePaint)

        }

    }



    /** Play / playlist keys drawn from the extracted artwork, centred. */

internal fun GlassHostLayout.drawNeteaseMiniPlayerButton(

        canvas: Canvas,

        key: String,

        drawable: android.graphics.drawable.Drawable?,

    ) {

        val rect = neteaseRuntime.playerContentRects[key] ?: return

        val d = drawable ?: return

        val oldBounds = Rect(d.bounds)

        val oldFilter = d.colorFilter

        val inset = 10f * density

        d.bounds = Rect(

            (rect.left + inset).roundToInt(),

            (rect.top + inset).roundToInt(),

            (rect.right - inset).roundToInt(),

            (rect.bottom - inset).roundToInt(),

        )

        try {

            d.draw(canvas)

        } finally {

            d.bounds = oldBounds

            d.colorFilter = oldFilter

        }

    }



    /** Interpolated disc angle: probe samples bridged by the measured velocity. */

internal fun GlassHostLayout.currentNeteaseDiscRotation(): Float {

        val now = SystemClock.uptimeMillis()

        val dt = now - neteaseRuntime.lastDiscRotationTime

        if (dt <= 0L) return neteaseRuntime.lastDiscRotation

        return (neteaseRuntime.lastDiscRotation + neteaseRuntime.discSpeed * dt) % 360f

    }

internal fun GlassHostLayout.neteaseHomeFade(): Float {

        if (context.packageName != NETEASE_PACKAGE || !neteaseRuntime.hasNavigationBar ||

            selectedIndex != 0 || !neteaseRuntime.heartbeatActive

        ) {

            neteaseRuntime.lastHomeSelected = selectedIndex

            neteaseRuntime.wasHeartbeatActive = neteaseRuntime.heartbeatActive

            return 1f

        }

        val now = SystemClock.uptimeMillis()

        if (neteaseRuntime.lastHomeSelected != selectedIndex || !neteaseRuntime.wasHeartbeatActive) {

            // Fresh entry into the 心动 sub-page: chrome starts visible and the

            // idle timer restarts from scratch (also when toggling back from a

            // sibling home sub-page, which never fades).

            neteaseRuntime.lastHomeSelected = selectedIndex

            neteaseRuntime.wasHeartbeatActive = true

            neteaseRuntime.homeLastInteractionMs = now

            removeCallbacks(neteaseRuntime.homeIdleHideRunnable)

            postDelayed(neteaseRuntime.homeIdleHideRunnable, NETEASE_HOME_OUTLINE_IDLE_MS + 32L)

        }

        if (neteaseRuntime.homeLastInteractionMs == 0L) {

            neteaseRuntime.homeLastInteractionMs = now

            removeCallbacks(neteaseRuntime.homeIdleHideRunnable)

            postDelayed(neteaseRuntime.homeIdleHideRunnable, NETEASE_HOME_OUTLINE_IDLE_MS + 32L)

        }

        val elapsed = now - neteaseRuntime.homeLastInteractionMs

        val fade = if (elapsed > NETEASE_HOME_OUTLINE_IDLE_MS) {

            (1f - (elapsed - NETEASE_HOME_OUTLINE_IDLE_MS) /

                NETEASE_HOME_OUTLINE_FADE_MS.toFloat()).coerceIn(0f, 1f)

        } else {

            1f

        }

        if (fade > 0f && fade < 1f) postInvalidateOnAnimation()

        return fade

    }

internal fun GlassHostLayout.drawNeteasePlayerAmbientShadow(canvas: Canvas, dark: Boolean, alphaScale: Float = 1f) {

        if (selectedIndex == 0 && neteaseRuntime.heartbeatActive) return

        val bar = neteaseRuntime.playerHitRect ?: return

        if (bar.isEmpty || !canvas.isHardwareAccelerated) return

        val playerPath = Path()

        val radius = min(bar.width(), bar.height()) * (config.cornerRadiusPercent / 100f)

        playerPath.setBottomBarSquircle(bar, radius, config.cornerSmoothing)

        panelShadowPaint.color = Color.TRANSPARENT

        panelShadowPaint.setShadowLayer(

            PANEL_SHADOW_BLUR_DP * density,

            0f,

            0f,

            Color.argb(

                ((if (dark) PANEL_SHADOW_ALPHA else PANEL_LIGHT_SHADOW_ALPHA) *

                    255f * alphaScale.coerceIn(0f, 1f)).toInt(),

                0,

                0,

                0,

            ),

        )

        canvas.save()

        canvas.clipOutPath(playerPath)

        canvas.drawPath(playerPath, panelShadowPaint)

        canvas.restore()

        panelShadowPaint.clearShadowLayer()

    }

internal fun GlassHostLayout.beginNeteasePlayerTouch(event: MotionEvent): Boolean {

        if (context.packageName != NETEASE_PACKAGE) return false

        val bar = neteaseRuntime.playerHitRect ?: neteaseRuntime.playerBarRect ?: return false

        if (bar.isEmpty || !bar.contains(event.x, event.y)) return false

        if (!neteaseRuntime.bottomBarRect.isEmpty && neteaseRuntime.bottomBarRect.contains(event.x, event.y)) {

            return false

        }

        val hit = neteaseRuntime.playerContentRects.entries.firstOrNull { entry ->

            entry.key in NETEASE_PLAYER_CLICKABLE_KEYS && entry.value.contains(event.x, event.y)

        }

        // Play/pause and playlist forward to their own hidden controls; the

        // disc, title and empty areas forward to the whole bar so its click

        // (open the player page) still works.

        val child = when (hit?.key) {

            "play" -> neteaseRuntime.playerChildViews["play"]

            "list" -> neteaseRuntime.playerChildViews["list"]

            else -> neteaseRuntime.miniPlayerTarget

        } ?: neteaseRuntime.miniPlayerTarget ?: return false

        neteaseRuntime.playerTouchTarget = child

        // Whole-bar targets dispatch in bar-local coordinates; per-control

        // targets (play/playlist) in their own child-local coordinates.

        neteaseRuntime.playerTouchRect = if (child === neteaseRuntime.miniPlayerTarget) bar else hit?.value ?: bar

        forwardNeteasePlayerTouch(event)

        return true

    }



internal fun GlassHostLayout.forwardNeteasePlayerTouch(event: MotionEvent) {

        val child = neteaseRuntime.playerTouchTarget ?: return

        val rect = neteaseRuntime.playerTouchRect ?: neteaseRuntime.playerHitRect ?: neteaseRuntime.playerBarRect ?: return

        val copy = MotionEvent.obtain(event)

        copy.offsetLocation(-rect.left, -rect.top)

        try {

            child.dispatchTouchEvent(copy)

        } catch (_: Throwable) {

        } finally {

            copy.recycle()

        }

    }

internal fun GlassHostLayout.suppressNativeNeteaseBottomChrome(source: ViewGroup?) {

        if (context.packageName != NETEASE_PACKAGE || source == null) return

        // Pages without a native tab bar (MyRecentPlayActivity) have no bottom

        // chrome to strip; the source there is the mini-player wrapper itself,

        // which must stay visible with its glass drawable.

        if (!neteaseRuntime.hasNavigationBar) return

        val now = SystemClock.uptimeMillis()
        if (neteaseRuntime.chromeSource === source &&
            now - neteaseRuntime.lastChromeSuppress < NETEASE_MINI_PLAYER_PROBE_INTERVAL_MS &&
            source.alpha == 0f && source.background == null && source.foreground == null
        ) return
        neteaseRuntime.chromeSource = source
        neteaseRuntime.lastChromeSuppress = now

        val root = rootView as? ViewGroup

        for (name in NETEASE_BOTTOM_DECORATION_IDS) {

            val id = resources.getIdentifier(name, "id", NETEASE_PACKAGE)

            if (id == 0) continue

            root?.findViewById<View>(id)?.let { decoration ->

                decoration.background = null

                decoration.foreground = null

                (decoration as? ImageView)?.setImageDrawable(null)

                decoration.elevation = 0f

                decoration.outlineProvider = null

                if (decoration.visibility != View.GONE) decoration.visibility = View.GONE

            }

        }

        if (source.alpha != 0f) source.alpha = 0f

        source.background = null

        source.foreground = null

        source.elevation = 0f

        source.outlineProvider = null



        val heightCap = max(source.height * 4, (240f * density).roundToInt())

        var shell: ViewGroup? = source.parent as? ViewGroup

        var depth = 0

        while (shell != null && depth++ < 6) {

            val shellHeight = shell.height

            if (shellHeight <= 0 || shellHeight > heightCap) break

            shell.background = null

            shell.foreground = null

            shell.elevation = 0f

            shell.outlineProvider = null

            for (index in 0 until shell.childCount) {

                val child = shell.getChildAt(index)

                if (child === source) continue

                if (child.width >= source.width * 0.8f &&

                    child.height <= heightCap

                ) {

                    child.background = null

                    child.foreground = null

                    child.elevation = 0f

                    child.outlineProvider = null

                }

            }

            shell = shell.parent as? ViewGroup

        }

    }

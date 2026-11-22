package io.github.offlineglass.hook.adapters.tiktok

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.topbar.TopBarMaterialPolicy
import io.github.offlineglass.hook.topbar.TopBarRole
import io.github.offlineglass.hook.topbar.TopBarChannelHost
import io.github.offlineglass.config.TikTokTopChannels

import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.NavigationLabelLocalization
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Layout reference: xzdw01/DouYinLiquidTab v1.7.4 (GPL-3.0).
 * Independent implementation on LiquidTab's existing accessory renderer.
 * TikTok 47.4.3 bindings verified from TopTabLayoutAbility, native strip and tab model.
 * This controller never writes page height, video geometry or bottom-control margins.
 */
internal object TikTokTopBar {
    private val strips = WeakHashMap<ViewGroup, WeakReference<ViewGroup>>()
    private val main = Handler(Looper.getMainLooper())
    private val observed = WeakHashMap<View, Boolean>()
    private val sessions = WeakHashMap<View, WeakReference<Session>>()
    private data class NativeVisual(val alpha: Float, val accessibility: Int)
    private val nativeVisuals = WeakHashMap<View, NativeVisual>()

    private fun redrawEnabled(row: View): Boolean {
        val config = HookConfigReader.read(row.context, row.context.packageName)
        return config.enabled && config.tiktokTopBar
    }

    private fun claimVisual(row: View) {
        nativeVisuals.getOrPut(row) { NativeVisual(row.alpha, row.importantForAccessibility) }
        if (row.alpha != 0f) row.alpha = 0f
        row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.ss.android.ugc.aweme.homepage.ui.view.tab.top.TopTabLayoutAbility", false, loader)
            // Constructor chaining may report the same row twice. Observe it once;
            // attach, rather than a timer, owns reconstruction and reattachment.
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val row = param.args.getOrNull(1) as? ViewGroup ?: return
                    val strip = param.args.firstOrNull() as? ViewGroup ?: return
                    strips[row] = WeakReference(strip)
                    // Claim the verified title row during construction, before
                    // attachment or a posted callback can expose its native ink.
                    if (redrawEnabled(row)) claimVisual(row)
                    if (observed.put(row, true) == true) return
                    row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            if (redrawEnabled(row)) claimVisual(row)
                            attach(row)
                            row.post { attach(row) }
                        }
                        override fun onViewDetachedFromWindow(v: View) {
                            sessions.remove(row)?.get()?.stop()
                        }
                    })
                    if (row.isAttachedToWindow) attach(row)
                    row.post { attach(row) }
                }
            })
        }.onFailure { android.util.Log.w("TikTokGlassDiag", "top bar binding unavailable; native retained", it) }
    }

    private fun attach(row: ViewGroup) {
        if (!row.isAttachedToWindow || sessions[row]?.get() != null) return
        var context: Context = row.context
        repeat(12) {
            if (context is Activity) return@repeat
            context = (context as? ContextWrapper)?.baseContext ?: return
        }
        val activity = context as? Activity ?: return
        val content = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        Session(row, content).also { sessions[row] = WeakReference(it); it.start() }
    }

    private class Overlay(context: Context) : FrameLayout(context), AdapterOwnedOverlay
    private data class Tab(val cell: View, val text: TextView, val id: String, val nativePosition: Int)
    private data class Input(val clickable: Boolean, val longClickable: Boolean, val focusable: Boolean)

    private class Session(val row: ViewGroup, val content: FrameLayout) : ViewTreeObserver.OnPreDrawListener {
        val overlay = Overlay(row.context).apply { clipChildren = false; clipToPadding = false }
        private val inputs = WeakHashMap<View, Input>()
        private val originalAlpha = nativeVisuals[row]?.alpha ?: row.alpha
        private val originalAccessibility = nativeVisuals[row]?.accessibility ?: row.importantForAccessibility
        private val tabs = ArrayList<Tab>()
        // English names have very different lengths. Keep only Following and
        // For You in the capsule in English; every other native channel stays
        // reachable from the existing "more" menu instead of shrinking its font.
        private var compactEnglishTabs = false
        private var channelSizing: io.github.offlineglass.hook.topbar.TopBarTabLayout? = null
        private var previousChannelSizing: io.github.offlineglass.hook.topbar.TopBarTabLayout? = null
        private var englishSlotWidths = emptyList<Float>()
        private var compactExtraSelected = false
        private var hostedSlotCount = -1
        // Three stable physical slots avoid rebuilding the glass host when a
        // secondary channel enters or leaves the title row.
        private val visibleCount: Int get() = shown.size.coerceIn(2, 3)
        private var shown = emptyList<Tab>()
        private var previousShown = emptyList<Tab>()
        private var shownChangedAt = 0L
        private var shownPinSpec: String? = null
        private var selected: Tab? = null
        private var nativeSelectionStrip = WeakReference<ViewGroup>(null)
        private var nativeSelectedPosition: java.lang.reflect.Method? = null
        private var left: View? = null
        private var right: View? = null
        private val hosts = ArrayList<GlassHostLayout>()
        private var sceneContent: ViewGroup? = null
        private var sceneTime = 0L
        private var sceneViews: List<View> = emptyList()
        private val sharedVideo = TikTokVideoSampler(true) {
            hosts.forEach { if (it.isAttachedToWindow && it.isShown) it.postInvalidateOnAnimation() }
        }
        private var menu: FrameLayout? = null
        private var menuCard: View? = null
        private var menuClosing = false
        private var suppressed = nativeVisuals.containsKey(row)
        private var stopped = false
        private var active = false
        private var lastPageState = ""
        private var framePulseScheduled = false
        private var lastDiscover = 0L
        private var committed: View? = null
        private var committedUntil = 0L
        private val rowRect = Rect()
        private val contentPos = IntArray(2)
        private val rowPos = IntArray(2)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }
        // Three small top panels were each forcing a full live-scene record at
        // display rate. Pace their shared-window refresh independently of the
        // feed's vsync so video playback does not trigger 180 scene recordings/s.
        private val framePulse = object : Runnable {
            override fun run() {
                framePulseScheduled = false
                if (stopped || !active || overlay.visibility != View.VISIBLE) return
                if (!TikTokTextureHooks.gpuActive && SystemClock.uptimeMillis()-shownChangedAt >= 180L) return
                hosts.forEach { if (it.isAttachedToWindow && it.isShown) it.invalidate() }
                framePulseScheduled = true
                main.postDelayed(this, if (TikTokTextureHooks.gpuActive || SystemClock.uptimeMillis()-shownChangedAt < 180L) 16L else LIVE_FRAME_INTERVAL_MS)
            }
        }

        fun start() {
            content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            row.viewTreeObserver.addOnPreDrawListener(this)
            // Prepare synchronously when an already measured row is reattached.
            onPreDraw()
            android.util.Log.i("TikTokGlassDiag", "top bar observed native=${row.javaClass.name}")
        }

        override fun onPreDraw(): Boolean {
            if (stopped) return true
            runCatching { update() }.onFailure {
                runCatching { java.io.File(row.context.getExternalFilesDir(null), "tiktok_topbar_error.txt")
                    .writeText("time=${System.currentTimeMillis()}\n${it.stackTraceToString()}") }
                closeMenu(animated = false)
                if (redrawEnabled(row)) suppress() else restore()
                android.util.Log.e("TikTokGlassDiag", "top bar update failed; ownership retained", it)
            }
            return true
        }

        private fun update() {
            val config = HookConfigReader.read(row.context, row.context.packageName)
            val requested = config.enabled && config.tiktokTopBar
            if (requested) suppress() else restore()
            val compact = config.managerEnglishNavigationLabels
            if (compact != compactEnglishTabs) {
                compactEnglishTabs = compact
                compactExtraSelected = false
                shownPinSpec = null
                closeMenu()
                if (hosts.isNotEmpty()) {
                    hosts.forEach { overlay.removeView(it) }
                    hosts.clear()
                    hostedSlotCount = -1
                }
            }
            // Alpha of the row itself is ours; all ancestor visibility/alpha remains native.
            var ancestor = row.parent as? View
            var parentAlpha = 1f
            while (ancestor != null) { parentAlpha *= ancestor.alpha; ancestor = ancestor.parent as? View }
            val root = content.rootView
            val assistantVisible = TikTokAssistantVisibility.visible(root)
            val navigation = TikTokNativeNavigation.find(root)
            val nativeSelected = navigation?.let { bar ->
                (0 until bar.childCount).firstOrNull { bar.getChildAt(it).isSelected }
            }
            val feedVisible = TikTokFeedVisibility.isVisible(root)
            val commentsVisible = TikTokCommentVisibility.visible(root)
            active = requested && row.isAttachedToWindow && content.windowVisibility == View.VISIBLE &&
                (nativeSelected == null || nativeSelected in 0..1) &&
                feedVisible != false && !commentsVisible && !assistantVisible
            val pageState = "active=$active requested=$requested attached=${row.isAttachedToWindow} nativeTab=$nativeSelected feed=$feedVisible comments=$commentsVisible assistant=$assistantVisible"
            if (pageState != lastPageState) {
                lastPageState = pageState
                runCatching { java.io.File(row.context.getExternalFilesDir(null), "tiktok_topbar_page_state.txt").writeText(pageState) }
            }
            val now = SystemClock.uptimeMillis()
            if (active && (tabs.isEmpty() || left?.isAttachedToWindow != true || right?.isAttachedToWindow != true ||
                    left?.width == 0 || right?.width == 0 || now - lastDiscover > 500L)) {
                discover(); lastDiscover = now
            }
            if (!active) {
                main.removeCallbacks(framePulse); framePulseScheduled = false
                hosts.forEach { it.updateAnimatedVisibility(false, skipExitAnimation = assistantVisible) }
                overlay.visibility = if (assistantVisible || hosts.isEmpty()) View.INVISIBLE else View.VISIBLE
                closeMenu(animated = false); return
            }
            if (tabs.size >= 2) selectTabs(now, config)
            val requiredSlots = visibleCount + 1
            if (hosts.isNotEmpty() && hostedSlotCount != requiredSlots) {
                hosts[1].updateAdapterSlotCount(requiredSlots, selectedIndex().coerceAtLeast(1))
                hostedSlotCount = requiredSlots
                closeMenu()
            }
            if (hosts.isEmpty()) {
                for (role in 0..2) {
                    val state = PanelState(this, role)
                    val panel = TopBarChannelHost.createPanel(row.context, config, if (role == 1) visibleCount + 1 else 1,
                        nonSelectableIndices = if (role == 1) setOf(0) else emptySet(),
                        nativeSelectionReliable = role == 1, navigationSource = row,
                        sliderEnabled = role == 1 && selectedIndex() >= 1, adapterOverride = PanelAdapter(state), isChannel = role == 1).apply {
                        elevation = 0f; clipChildren = false; clipToPadding = false
                        contentDescription = when(role) { 0 -> "直播"; 2 -> "搜索"; else -> "频道：更多、${shown.joinToString("、") { it.text.text }}" }
                    }
                    hosts += panel
                    overlay.addView(panel, FrameLayout.LayoutParams(1, 1))
                }
                hostedSlotCount = requiredSlots
                android.util.Log.i("TikTokGlassDiag", "top bar attached tabs=${tabs.map { it.text.text }}")
                runCatching { java.io.File(row.context.getExternalFilesDir(null), "tiktok_topbar_binding_status.txt")
                    .writeText("time=${System.currentTimeMillis()} tabs=${tabs.map { it.id }} pins=${config.tiktokPinnedTabs.replace('\n', ',')}\n") }
            }
            hosts.getOrNull(1)?.setAdapterSelectionVisible(selectedIndex() >= 1)
            suppress()
            overlay.visibility = View.VISIBLE
            hosts.forEach { it.updateAnimatedVisibility(true) }
            overlay.alpha = 1f
            if (!framePulseScheduled && (TikTokTextureHooks.gpuActive || now-shownChangedAt < 180L)) {
                framePulseScheduled = true
                main.postDelayed(framePulse, LIVE_FRAME_INTERVAL_MS)
            }
            content.getLocationOnScreen(contentPos); row.getLocationOnScreen(rowPos)
            val d = row.resources.displayMetrics.density
            // Douyin's accepted title row is 44dp (143px at density 3.25).
            // TikTok's native 58dp row must not enlarge or lower our glass panels.
            // Keep its native top anchor and input targets, with adapter-owned geometry.
            val titleHeight = (44*d).roundToInt()
            val h = (titleHeight * .9f).roundToInt().coerceIn((32*d).roundToInt(), (48*d).roundToInt())
            val size = (h * .92f).roundToInt()
            val gap = (12*d).roundToInt()
            val canvasWidth = content.width.takeIf { it > 0 } ?: row.resources.displayMetrics.widthPixels
            englishSlotWidths = measureChannelSlots(shown, d, row.resources.displayMetrics.scaledDensity, compactEnglishTabs)
            val desiredWidth = if (englishSlotWidths.isEmpty()) (visibleCount+1)*42*d
                else englishSlotWidths.sum() + 8*d
            val pill = minOf(kotlin.math.ceil(desiredWidth).toInt(), canvasWidth - 2*size - 4*gap).coerceAtLeast(h)
            val total = pill + 2*gap + 2*size
            val x = ((canvasWidth-total)/2).coerceAtLeast(0)
            val statusId = row.resources.getIdentifier("status_bar_height", "dimen", "android")
            val nativeTop = if (row.width > 0 && row.height > 0) rowPos[1]
                else if (statusId != 0) row.resources.getDimensionPixelSize(statusId) else 0
            val y = nativeTop-contentPos[1]+(titleHeight-h)/2
            place(hosts[0], x, y+(h-size)/2, size, size)
            place(hosts[1], x+size+gap, y, pill, h)
            place(hosts[2], x+size+gap+pill+gap, y+(h-size)/2, size, size)
            // The existing TikTok bottom bar owns its own foreground order.
            // Stay above our native title branch, not above every accessory;
            // otherwise top and bottom request layout by raising each other forever.
            var nativeBranch: View = row
            while (nativeBranch.parent is View && nativeBranch.parent !== content) nativeBranch = nativeBranch.parent as View
            if (nativeBranch.parent === content && content.indexOfChild(overlay) <= content.indexOfChild(nativeBranch)) overlay.bringToFront()
        }

        private fun discover() {
            tabs.clear()
            // Native cell order and visible labels are the binding contract.
            // Do not depend on the tab model's renamed methods.
            val strip = strips[row]?.get() ?: return
            val cells = strip.getChildAt(0) as? ViewGroup ?: return
            nativeSelectedPosition = runCatching {
                // TopTabLayoutAbility itself uses these stable public methods
                // to resolve its current tab. Verify the model/cell cardinality
                // before mapping that native position to a reconstructed tab.
                val count = strip.javaClass.getMethod("getTabCount").invoke(strip) as? Int
                strip.javaClass.getMethod("getSelectedTabPosition").takeIf {
                    it.returnType == Int::class.javaPrimitiveType && count == cells.childCount
                }
            }.getOrNull()
            nativeSelectionStrip = WeakReference(strip)
            for (i in 0 until cells.childCount) {
                val cell = cells.getChildAt(i)
                val label = text(cell) ?: continue
                val title = label.text.toString().trim()
                val id = when (title.lowercase()) {
                    "关注", "following" -> "Following"
                    "推荐", "for you" -> "For You"
                    else -> title
                }
                if (id.isNotEmpty()) tabs += Tab(cell, label, id, i)
            }
            val actions = (0 until row.childCount).map(row::getChildAt).filter {
                it !== strip && (it.isClickable || inputs[it]?.clickable == true) && it.width > 0 && it.height > 0
            }.sortedBy { it.left }
            left = actions.firstOrNull { it.left+it.width/2 < row.width/2 }
            right = actions.lastOrNull { it.left+it.width/2 >= row.width/2 }
        }
        private fun text(v: View): TextView? {
            if (v is TextView && v.text.isNotBlank()) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) text(v.getChildAt(i))?.let { return it }
            return null
        }

        private fun selectTabs(now: Long, config: GlassConfig) {
            val nativeStrip = nativeSelectionStrip.get()
            val position = runCatching { nativeSelectedPosition?.invoke(nativeStrip) as? Int }.getOrNull()
            val nativeCells = nativeStrip?.getChildAt(0) as? ViewGroup
            val actual = tabs.singleOrNull { it.nativePosition == position &&
                nativeCells?.getChildAt(it.nativePosition) === it.cell }
            val visible = tabs.filter { it.cell.width > 0 && it.cell.getGlobalVisibleRect(rowRect) && rowRect.width() >= it.cell.width/2 }
            val flagged = visible.firstOrNull { it.cell.isSelected || it.cell.isActivated || it.text.isSelected || it.text.isActivated }
            val brightest = visible.sortedByDescending { it.text.alpha }
            val alphaSelected = brightest.firstOrNull()?.takeIf { brightest.size == 1 || it.text.alpha > brightest[1].text.alpha+.01f }
            selected = actual ?: flagged ?: alphaSelected ?: selected?.let { old -> tabs.firstOrNull { it.cell === old.cell } }
            val primary = tabs.filter { it.id == "Following" || it.id == "For You" }
            if (primary.size != 2 || primary.map { it.id }.distinct().size != 2) return
            val extra = selected?.takeIf { it.id != "Following" && it.id != "For You" }
            val next = (primary + listOfNotNull(extra)).sortedBy { tabs.indexOf(it) }
            if (shown.map { it.cell } != next.map { it.cell }) {
                previousShown = shown
                shown = next
                shownChangedAt = now
            }
            hosts.getOrNull(1)?.let { host ->
                val description = "频道：更多、${shown.joinToString("、") { it.text.text }}"
                if (host.contentDescription != description) host.contentDescription = description
            }
        }

        private fun suppress() {
            fun walk(v: View) {
                inputs.getOrPut(v) { Input(v.isClickable, v.isLongClickable, v.isFocusable) }
                v.isClickable = false; v.isLongClickable = false; v.isFocusable = false
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            // Structural rescan is throttled; only alpha must survive native animations each frame.
            if (!suppressed || SystemClock.uptimeMillis()-lastInputScan > 500L) {
                walk(row); lastInputScan = SystemClock.uptimeMillis()
            }
            row.alpha = 0f
            row.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            suppressed = true
        }
        private var lastInputScan = 0L

        private fun restore() {
            if (!suppressed) return
            row.alpha = originalAlpha; row.importantForAccessibility = originalAccessibility
            inputs.forEach { (v, input) ->
                v.isClickable = input.clickable; v.isLongClickable = input.longClickable; v.isFocusable = input.focusable
            }
            inputs.clear(); suppressed = false
        }

        fun tap(role: Int, index: Int): Boolean {
            if (!active) return false
            if (tabs.size < 2 || left?.isAttachedToWindow != true || right?.isAttachedToWindow != true) return false
            if (role == 1 && index == 0) { toggleMenu(); return true }
            val target = when(role) { 0 -> left; 2 -> right; else -> shown.getOrNull(index-1)?.cell } ?: return false
            return click(target)
        }

        private fun click(target: View): Boolean {
            // Restore the exact saved flags for dispatch, then suppress again. Never arm ancestors.
            inputs.forEach { (v, input) -> v.isClickable = input.clickable; v.isLongClickable = input.longClickable; v.isFocusable = input.focusable }
            val success = try {
                var node: View? = target
                var handled = false
                while (node != null && node !== row && !handled) {
                    if (node.hasOnClickListeners()) handled = node.performClick()
                    node = node.parent as? View
                }
                if (!handled) {
                    val now = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, target.width/2f, target.height/2f, 0)
                    val up = MotionEvent.obtain(now, now+40L, MotionEvent.ACTION_UP, target.width/2f, target.height/2f, 0)
                    try { handled = target.dispatchTouchEvent(down); handled = target.dispatchTouchEvent(up) || handled }
                    finally { down.recycle(); up.recycle() }
                }
                handled
            } finally { inputs.keys.forEach { it.isClickable = false; it.isLongClickable = false; it.isFocusable = false } }
            if (success && tabs.any { it.cell === target }) { committed = target; committedUntil = SystemClock.uptimeMillis()+500L }
            android.util.Log.i("TikTokGlassDiag", "top tap ${target.javaClass.simpleName} handled=$success")
            return success
        }

        private fun toggleMenu() {
            if (menu != null) { closeMenu(); return }
            val config = HookConfigReader.read(row.context, row.context.packageName)
            val d = row.resources.displayMetrics.density
            val menuTop = (hosts[1].layoutParams as FrameLayout.LayoutParams).topMargin + hosts[1].height + (10*d).roundToInt()
            val bottomInset = content.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
            val availableHeight = (content.height-menuTop-bottomInset-(12*d).roundToInt()).coerceAtLeast(1)
            val menuHeight = minOf((tabs.size*44*d).roundToInt(), availableHeight)
            val root = FrameLayout(row.context).apply {
                isClickable = true; isFocusableInTouchMode = true
                setOnClickListener { closeMenu() }
                setOnKeyListener { _, key, event ->
                    if (key == android.view.KeyEvent.KEYCODE_BACK) { if (event.action == android.view.KeyEvent.ACTION_UP) closeMenu(); true } else false
                }
            }
            val card = FrameLayout(row.context).apply { isClickable = true }
            val material = GlassHostLayout(row.context, config, 1, nativeSelectionReliable = false,
                navigationSource = row, sliderEnabled = false, adapterOverride = PanelAdapter(PanelState(this, 3))).apply { isClickable = false }
            card.addView(material, FrameLayout.LayoutParams(-1, -1))
            val list = LinearLayout(row.context).apply { orientation = LinearLayout.VERTICAL }
            tabs.forEach { tab ->
                val label = TextView(row.context).apply {
                    text = tab.text.text; textSize = 15f; gravity = Gravity.CENTER
                    setTextColor(if (dark()) Color.WHITE else Color.rgb(30,30,30))
                    isSelected = tab.cell === selected?.cell
                    contentDescription = "$text${if (isSelected) "，已选中" else ""}"
                    setOnClickListener {
                        if (!menuClosing) { click(tab.cell); closeMenu() }
                    }
                }
                // Every channel gets a visible row. On shorter windows share
                // the available height instead of hiding entries in a scroller.
                list.addView(label, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            card.addView(list, FrameLayout.LayoutParams(-1, -1))
            root.addView(card, FrameLayout.LayoutParams((128*d).roundToInt(), menuHeight, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = (hosts[1].layoutParams as FrameLayout.LayoutParams).leftMargin
                topMargin = menuTop
            })
            val capsule = hosts[1]
            val pad = 4f*d
            val menuFraction = englishSlotWidths.firstOrNull()?.let { it / englishSlotWidths.sum() }
                ?: 1f / (visibleCount + 1)
            card.pivotX = pad + (capsule.width - 2f*pad).coerceAtLeast(0f) * menuFraction / 2f
            card.pivotY = capsule.top + capsule.height / 2f - menuTop
            card.scaleX = .08f
            card.scaleY = .08f
            card.alpha = 0f
            menu = root
            menuCard = card
            overlay.addView(root, FrameLayout.LayoutParams(-1, -1))
            root.requestFocus()
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(240L)
                .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f))
                .setUpdateListener { material.invalidate() }
                .withEndAction { card.animate().setUpdateListener(null) }
                .start()
        }

        private fun closeMenu(animated: Boolean = true) {
            val root = menu ?: return
            val card = menuCard
            fun remove() {
                if (menu !== root) return
                card?.animate()?.setUpdateListener(null)?.withEndAction(null)?.cancel()
                overlay.removeView(root)
                menu = null
                menuCard = null
                menuClosing = false
            }
            if (!animated || !active || card == null || !root.isAttachedToWindow) {
                remove()
                return
            }
            if (menuClosing) return
            menuClosing = true
            card.animate().withEndAction(null).cancel()
            card.animate().scaleX(.08f).scaleY(.08f).alpha(0f).setDuration(180L)
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.3f))
                .withEndAction { remove() }.start()
        }

        private fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
            val p = v.layoutParams as FrameLayout.LayoutParams
            if (p.width == w && p.height == h && p.leftMargin == x && p.topMargin == y) return
            val animate = v.width > 1 && p.width != w
            val oldX = v.x
            val oldWidth = v.width
            p.width = w; p.height = h; p.leftMargin = x; p.topMargin = y
            p.gravity = Gravity.TOP or Gravity.LEFT; p.bottomMargin = 0
            v.layoutParams = p
            if (animate) {
                v.animate().cancel()
                v.pivotX = 0f
                v.translationX = oldX-x
                v.scaleX = oldWidth.toFloat()/w.coerceAtLeast(1)
                v.animate().translationX(0f).scaleX(1f).setDuration(180L).start()
            }
        }

        fun dark(): Boolean = tabs.firstOrNull()?.text?.currentTextColor?.let {
            Color.red(it)*.299f + Color.green(it)*.587f + Color.blue(it)*.114f > 150f
        } ?: true

        fun selectedIndex(): Int = shown.indexOfFirst { it.cell === selected?.cell }.let { if (it >= 0) it+1 else -1 }

        private fun englishTitle(tab: Tab): String =
            NavigationLabelLocalization.toEnglish("tiktok", tab.text.text.toString())

        private val channelMeasure = io.github.offlineglass.hook.topbar.TopBarTabMeasure()
        private val previousChannelMeasure = io.github.offlineglass.hook.topbar.TopBarTabMeasure()
        private fun measureChannelSlots(tabs: List<Tab>, density: Float, scaledDensity: Float,
            english: Boolean, previous: Boolean = false): List<Float> {
            val titles = if (tabs.isEmpty()) {
                if (english) listOf("Following", "For You") else listOf("关注", "推荐")
            } else tabs.map { if (english) englishTitle(it) else it.text.text.toString() }
            val measure = if (previous) previousChannelMeasure else channelMeasure
            val sizing = measure.update(listOf<String?>(null) + titles, density, 14f * scaledDensity)
            if (previous) previousChannelSizing = sizing else channelSizing = sizing
            return sizing?.preferredWidths.orEmpty()
        }

        fun indicatorGeometry(center: Float, width: Float): Pair<Float, Float>? {
            val host = hosts.getOrNull(1) ?: return null
            val sizing = channelSizing ?: return null
            if (sizing.size < 2 || width <= 0f) return null
            val pad = 4f * row.resources.displayMetrics.density
            return sizing.indicator(center.coerceAtLeast(pad + 1.5f * width), width, host.width, pad)
        }

        fun uniformTouchX(x: Float, hostWidth: Int, pad: Float): Float {
            return channelSizing?.toUniform(x, hostWidth, pad) ?: x
        }

        fun draw(canvas: Canvas, frame: AdapterNavigationFrame, role: Int) {
            if (role == 3) return
            val d = frame.density
            val color = if (frame.darkGlass) Color.WHITE else Color.rgb(25,25,25)
            iconPaint.color = color; iconPaint.strokeWidth = 1.7f*d
            val cy = frame.height/2f
            fun glyph(kind: Int, cx: Float) {
                val r = 7*d
                when(kind) {
                    0 -> { labelPaint.color = color; labelPaint.textSize = 10f*frame.scaledDensity; labelPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD; val fm = labelPaint.fontMetrics; canvas.drawText("LIVE", cx, cy-(fm.ascent+fm.descent)/2, labelPaint) }
                    1 -> { val fill = Paint(iconPaint).apply { style = Paint.Style.FILL }; for(i in -1..1) canvas.drawCircle(cx+i*5*d, cy, 1.4f*d, fill) }
                    2 -> { canvas.drawCircle(cx-1.5f*d, cy-1.5f*d, r*.85f, iconPaint); canvas.drawLine(cx+3*d, cy+3*d, cx+8*d, cy+8*d, iconPaint) }
                }
            }
            if (role != 1) { glyph(if (role == 0) 0 else 2, frame.width/2f); return }
            val slot = (frame.width-8*d)/(visibleCount+1)
            val widths = englishSlotWidths
            val available = (frame.width - 8*d).coerceAtLeast(1f)
            val scale = if (widths.isEmpty()) 1f else available / widths.sum()
            val fontScale = channelSizing?.fitScale(frame.width) ?: 1f
            fun center(index: Int, slots: List<Float>, factor: Float): Float =
                if (slots.isEmpty()) 4*d + slot*(index+.5f)
                else 4*d + factor*(slots.take(index).sum() + slots[index]/2f)
            glyph(1, center(0, widths, scale))
            if (shown.isEmpty()) {
                labelPaint.color = color; labelPaint.alpha = 255
                labelPaint.typeface = android.graphics.Typeface.DEFAULT
                labelPaint.textSize = 14f*frame.scaledDensity*fontScale
                val labels = if (frame.managerEnglishLabels) listOf("Following", "For You") else listOf("关注", "推荐")
                val fm = labelPaint.fontMetrics
                labels.forEachIndexed { i, value ->
                    canvas.drawText(value, center(i+1, widths, scale), cy-(fm.ascent+fm.descent)/2, labelPaint)
                }
                return
            }
            fun drawTab(tab: Tab, i: Int, alpha: Float, slots: List<Float>, factor: Float, fontFit: Float) {
                labelPaint.color = color
                labelPaint.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
                labelPaint.typeface = if (tab.cell === selected?.cell) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                labelPaint.textSize = 14f*frame.scaledDensity*fontFit
                val nativeValue = tab.text.text.toString()
                val value = if (frame.managerEnglishLabels)
                    englishTitle(tab)
                else nativeValue
                val max = slot-6*d
                if (slots.isEmpty()) {
                    val measured = labelPaint.measureText(value)
                    if (measured > max) labelPaint.textSize *= max/measured
                }
                val fm = labelPaint.fontMetrics
                canvas.drawText(value, center(i+1, slots, factor), cy-(fm.ascent+fm.descent)/2, labelPaint)
            }
            val progress = ((SystemClock.uptimeMillis() - shownChangedAt) / 180f).coerceIn(0f, 1f)
            if (progress < 1f && previousShown.isNotEmpty()) {
                val previousWidths = measureChannelSlots(previousShown, d, frame.scaledDensity,
                    frame.managerEnglishLabels, previous = true)
                val previousScale = if (previousWidths.isEmpty()) 1f else available / previousWidths.sum()
                val previousFontFit = previousChannelSizing?.fitScale(frame.width) ?: 1f
                previousShown.forEachIndexed { i, tab -> drawTab(tab, i, 1f-progress, previousWidths, previousScale, previousFontFit) }
            }
            shown.forEachIndexed { i, tab -> drawTab(tab, i, progress, widths, scale, fontScale) }
            labelPaint.alpha = 255
        }

        fun stop() {
            sharedVideo.dispose()
            stopped = true; active = false
            main.removeCallbacks(framePulse); framePulseScheduled = false
            if (row.viewTreeObserver.isAlive) row.viewTreeObserver.removeOnPreDrawListener(this)
            if (!redrawEnabled(row)) restore()
            // Detach runs inside native child traversal: removal must be deferred.
            main.post { closeMenu(animated = false); (overlay.parent as? ViewGroup)?.removeView(overlay); hosts.clear() }
        }

        private class PanelState(val session: Session, val role: Int) : AppNavigationState {
            override fun indicatorHorizontalGeometry(center: Float, width: Float) =
                if (role == 1) session.indicatorGeometry(center, width) else null
            override fun uniformSlotTouchX(x: Float, hostWidth: Int, padding: Float) =
                if (role == 1) session.uniformTouchX(x, hostWidth, padding) else x
            override val pauseBackdropDuringBarExit = true
            private var media: TikTokNavigationState? = null
            override fun onHostFrame(host: View) {
                val glass = host as? GlassHostLayout ?: return
                val state = media ?: TikTokNavigationState(glass, { session.active }, session.sharedVideo, false).also { media = it }
                state.onHostFrame(host)
            }
            override fun retainBackdropDuringTransition(now: Long) = media?.retainBackdropDuringTransition(now) ?: false
            override fun onLiveBackdropRecorded(selectedIndex: Int) { media?.onLiveBackdropRecorded(selectedIndex) }
            override fun needsSurfaceBackdrop() = media?.needsSurfaceBackdrop() ?: false
            override fun skipLiveBackdrop(selectedIndex: Int) = media?.skipLiveBackdrop(selectedIndex) ?: false
            override fun canReuseLiveBackdrop(selectedIndex: Int) = media?.canReuseLiveBackdrop(selectedIndex) ?: false
            override fun updateBackdropRendererMode(host: GlassHostLayout): Boolean =
                media?.updateBackdropRendererMode(host) ?: true
            override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) { media?.requestVideoBackdrop(host, pageAllowed) }
            override fun videoBackdrop() = media?.videoBackdrop()
            override fun reuseOuterBackdropForSurface(surfaceActive: Boolean) = surfaceActive
            override fun opticalBlurNodeNeedsRecord(prepared: Boolean, backdrop: Bitmap?, width: Int, height: Int, effect: android.graphics.RenderEffect?) =
                media?.opticalBlurNodeNeedsRecord(prepared, backdrop, width, height, effect) ?: true
            override fun onOpticalBlurNodeRecorded(backdrop: Bitmap?, width: Int, height: Int, effect: android.graphics.RenderEffect?) {
                media?.onOpticalBlurNodeRecorded(backdrop, width, height, effect)
            }
            override fun onHostDetached() { media?.onHostDetached(); media = null }
            override val allowsSoftwareBackdropCapture = false
            override val backdropSceneViewsAreDescendants = true
            override fun resolveBackdropScene(host: View): View = session.content
            override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> {
                media?.let {
                    val now = SystemClock.uptimeMillis()
                    if (session.sceneContent === content && now-session.sceneTime < 16L) return session.sceneViews
                    return it.backdropSceneViews(content, hostIndex).also { views ->
                        session.sceneContent = content; session.sceneTime = now; session.sceneViews = views
                    }
                }
                val result = ArrayList<View>()
                fun walk(v: View, depth: Int) {
                    if (depth > 32 || v is GlassHostLayout || v is AdapterOwnedOverlay || v === session.row ||
                        v.visibility != View.VISIBLE || v.alpha <= .001f) return
                    // Keep ordinary native branches intact, descending only along overlay paths.
                    fun containsExcluded(node: View): Boolean {
                        var p: View? = session.row
                        while (p != null) { if (p === node) return true; p = p.parent as? View }
                        return node === session.content
                    }
                    if (v is ViewGroup && containsExcluded(v)) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth+1)
                    else if (v.width > 0 && v.height > 0) result += v
                }
                walk(session.content, 0)
                return result
            }
            override fun forceDarkMode(selectedIndex: Int) = session.dark()
            // Shares the homepage window with the bottom bar. Returning false
            // restores the old inset every pre-draw and fights that bar's
            // immersive policy, visibly lifting the system gesture handle.
            override fun forceNavigationBarImmersed(host: GlassHostLayout) = true
            override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true
            override fun suppressRedrawnSource() = false
            override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = if (role == 1) session.selectedIndex() else 0
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = session.active
            override fun pauseBackdropWork(host: View) = !session.active
            override fun drawsNavigationContent() = role != 3
            override fun allowsHostTouch() = role != 3
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = session.tap(role, index)
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = session.draw(canvas, frame, role)
            override fun forceLiveBackdropFrame(selectedIndex: Int) = media?.forceLiveBackdropFrame(selectedIndex) ?: false
            private var live = false
            private var texture = WeakReference<android.view.TextureView>(null)
            override fun outerBackdropNeedsRecord(backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean): Boolean =
                media?.outerBackdropNeedsRecord(backdrop, width, height, hasDisplayList) ?: true
            override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
                media?.onOuterBackdropRecorded(backdrop, width, height)
            }
        }

        private class PanelAdapter(val state: PanelState) : TargetAdapter {
            override val sceneVisibilityAnimation = true
            override val usesOpticalSurfacePipeline = true
            override val usesContentAlignedOpticalSurface = true
            override val allowsSystemBackgroundBlur = false
            override val reuseOpticalBlur = true
            override val reuseSurfaceBlurDuringBarAnimation = true
            override fun surfaceOpacityScale(config: GlassConfig) = config.shortVideoBarOpacity
            override fun glassSurfaceFillColor(dark: Boolean, selectedIndex: Int, defaultColor: Int) =
                TikTokAdapter.glassSurfaceFillColor(dark, 0, defaultColor)
            override val key = "tiktok-top"
            override val ownsNavigationDrawing = true
            // The dropdown's native channel list owns input and foreground ink.
            // Enabling this for its background also enables the shared host's
            // bringToFront repair, which covers every label with glass again.
            override val ownsNavigationTap get() = state.role != 3
            // One owner orders this compound group; individual buttons must
            // not repeatedly raise themselves above their siblings.
            override val managesHostSiblingOrder = true
            override val skipNavigationSnapshot = true
            override val keepNavigationSourceVisible = true
            override val preserveNativeSourceMetrics = true
            override val managesNativeTabVisibility = true
            override val hasPerFrameScene = true
            override val continuousFramePump = false
            override val requestedFrameRateMax = 60f
            override val skipBackdropStableDelay = true
            override val forceCompositorFrames = false
            override val allowRenderNodeRefresh = true
            override val panelHighlightFollowsAllThemes = true
            override fun createNavigationState(context: Context): AppNavigationState = state
            private val material = TopBarMaterialPolicy(
                role = when (state.role) { 1 -> TopBarRole.CHANNELS; 3 -> TopBarRole.MENU; else -> TopBarRole.ACTION },
                selectedAccent = if (state.role == 1) true else null,
                accentColor = if (state.role == 1) 0xFFFE2C55.toInt() else null,
            )
            override fun normalizeConfig(config: GlassConfig) = material.apply(config)
            override val indicatorInteractionPolicy get() = material.indicatorInteractionPolicy
        }
    }

    private const val LIVE_FRAME_INTERVAL_MS = 80L
}

package io.github.offlineglass.hook.adapters.douyin

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
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.AdapterNavigationFrame
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Layout reference: xzdw01/DouYinLiquidTab v1.7.4 (GPL-3.0).
 * Independent implementation on LiquidTab's existing accessory renderer.
 * Native 40.8.0 classes were verified from classes40.dex, not copied resource IDs.
 * This controller never writes page height, video geometry or bottom-control margins.
 */
internal object DouyinTopBar {
    private val main = Handler(Looper.getMainLooper())
    private val observed = WeakHashMap<View, Boolean>()
    private val claimedAlpha = WeakHashMap<View, Float>()
    private val sessions = WeakHashMap<View, WeakReference<Session>>()

    fun install(loader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar", false, loader)
            // Constructor chaining may report the same row twice. Observe it once;
            // attach, rather than a timer, owns reconstruction and reattachment.
            XposedBridge.hookAllConstructors(type, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val row = param.thisObject as? ViewGroup ?: return
                    if (observed.put(row, true) == true) return
                    val config = HookConfigReader.read(row.context, row.context.packageName)
                    if (config.enabled && config.douyinTopBar) {
                        claimedAlpha[row] = row.alpha
                        row.alpha = 0f
                    }
                    row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) { attach(row) }
                        override fun onViewDetachedFromWindow(v: View) {
                            sessions.remove(row)?.get()?.stop()
                        }
                    })
                    row.post { attach(row) }
                }
            })
        }.onFailure { android.util.Log.w("OfflineGlassDouyin", "top bar binding unavailable; native retained", it) }
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
    private data class Tab(val cell: View, val text: TextView, val id: String, val nativePosition: Int = -1)
    private data class Input(val clickable: Boolean, val longClickable: Boolean, val focusable: Boolean)

    private class Session(val row: ViewGroup, val content: FrameLayout) : ViewTreeObserver.OnPreDrawListener {
        val overlay = Overlay(row.context).apply { clipChildren = false; clipToPadding = false }
        private val inputs = WeakHashMap<View, Input>()
        private val originalAlpha = claimedAlpha.remove(row) ?: row.alpha
        private val originalAccessibility = row.importantForAccessibility
        private val tabs = ArrayList<Tab>()
        private var shown = emptyList<Tab>()
        private val motion = DouyinTopTabMotion()
        private var visual = motion.frame(0L)
        private val captions = HashMap<String, String>()
        private var binding: DouyinTopBarBinding? = null
        private var bindingReady = false
        private var lastError: String? = null
        private var lastBindingReport: String? = null
        private var bindingReports = 0
        private var touchX = Float.NaN
        private val channelMeasure = io.github.offlineglass.hook.topbar.TopBarTabMeasure()
        private var channelSizing: io.github.offlineglass.hook.topbar.TopBarTabLayout? = null
        private var touchAt = 0L
        private var touching = false
        private var selected: Tab? = null
        private var left: View? = null
        private var right: View? = null
        private val hosts = ArrayList<GlassHostLayout>()
        // One producer for the compound top bar, never one capture per button.
        // Uses the same native-texture-first / compositor-fallback policy as
        // the bottom bar; snapshots retain their window-space video geometry.
        private val videoSampler = DouyinVideoSampler(row.context) {}
        private var menu: FrameLayout? = null
        private var suppressed = false
        private var stopped = false
        private var active = false
        private var lastDiscover = 0L
        private val rowRect = Rect()
        private val contentPos = IntArray(2)
        private val rowPos = IntArray(2)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        }

        fun start() {
            content.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            row.viewTreeObserver.addOnPreDrawListener(this)
            android.util.Log.i("OfflineGlassDouyin", "top bar observed native=${row.javaClass.name}")
        }

        private fun reportBinding(status: String) {
            if (status == lastBindingReport || bindingReports >= 12) return
            lastBindingReport = status; bindingReports++
            val message = "time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()} $status\n"
            val file = java.io.File(row.context.getExternalFilesDir(null), "dy_topbar_native_order_status.txt")
            Thread { runCatching { file.writeText(message) } }.start()
        }

        override fun onPreDraw(): Boolean {
            if (stopped) return true
            runCatching { update() }.onFailure {
                bindingReady = false
                if (lastError != it.message) {
                    lastError = it.message
                    android.util.Log.e("OfflineGlassDouyin", "top binding pending; module ownership retained", it)
                    reportBinding("error=${it.javaClass.simpleName}:${it.message}")
                }
                // A transient native binding failure does not relinquish our
                // already claimed visuals, stop drawing, or fabricate input.
            }
            return true
        }

        private fun update() {
            val config = HookConfigReader.read(row.context, row.context.packageName)
            if (!config.enabled || !config.douyinTopBar) {
                active = false; bindingReady = false
                overlay.visibility = View.INVISIBLE; closeMenu(); restore(); return
            }
            suppress()
            // Alpha of the row itself is ours; all ancestor visibility/alpha remains native.
            var ancestor = row.parent as? View
            var parentAlpha = 1f
            while (ancestor != null) { parentAlpha *= ancestor.alpha; ancestor = ancestor.parent as? View }
            active = row.isShown && parentAlpha > .01f && row.width > 0 && row.height > 0 &&
                row.getGlobalVisibleRect(rowRect) && rowRect.width() >= row.width * .5f
            if (!active) {
                bindingReady = false; overlay.visibility = View.INVISIBLE; closeMenu(); return
            }
            val now = SystemClock.uptimeMillis()
            if (binding == null) {
                if (now-lastDiscover < 500L) return
                lastDiscover = now
                binding = DouyinTopBarBinding.shared(row.context.classLoader)
            }
            val state = binding?.state() ?: run {
                bindingReady = false; reportBinding("pending=native-current-page-contract"); return
            }
            if ((now-lastDiscover > 500L || tabs.isEmpty() ||
                tabs.any { !it.cell.isAttachedToWindow } || tabs.none { it.nativePosition == state.currentPosition }) &&
                now-lastDiscover >= 100L) {
                lastDiscover = now
                if (!discover(state)) { bindingReady = false; return }
            }
            // The native API itself resolves nested/wrapper channel IDs.
            selected = tabs.singleOrNull { it.nativePosition == state.currentPosition }
            if (selected == null || left == null || right == null) {
                bindingReady = false
                reportBinding("pending=current-page id=${state.current} position=${state.currentPosition}")
                return
            }
            selectTabs(now)
            bindingReady = true
            if (hosts.isEmpty()) {
                for (role in 0..2) {
                    val state = PanelState(this, role)
                    val panel = TopBarChannelHost.createPanel(row.context, config, if (role == 1) shown.size + 1 else 1,
                        nonSelectableIndices = if (role == 1) setOf(0) else emptySet(),
                        nativeSelectionReliable = false, navigationSource = row,
                        sliderEnabled = role == 1, adapterOverride = PanelAdapter(state), isChannel = role == 1).apply {
                        elevation = 0f; clipChildren = false; clipToPadding = false
                        contentDescription = when(role) { 0 -> "侧边栏"; 2 -> "搜索"; else -> "频道：更多、${shown.joinToString("、") { it.text.text }}" }
                    }
                    hosts += panel
                    overlay.addView(panel, FrameLayout.LayoutParams(1, 1))
                    if (role == 1) panel.setOnTouchListener { _, event ->
                        touchX = event.x; touchAt = SystemClock.uptimeMillis()
                        touching = event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
                        false
                    }
                }
                android.util.Log.i("OfflineGlassDouyin", "top bar attached tabs=${tabs.map { it.text.text }}")
                runCatching { java.io.File(row.context.getExternalFilesDir(null), "dy_topbar_binding_status.txt")
                    .writeText("time=${System.currentTimeMillis()} tabs=${tabs.map { it.id }} pins=${DouyinTopTabMotion.PINS}\n") }
            }
            hosts[1].updateAdapterSlotCount(shown.size+1, selectedIndex())
            overlay.visibility = View.VISIBLE
            overlay.alpha = parentAlpha.coerceIn(0f, 1f)
            content.getLocationOnScreen(contentPos); row.getLocationOnScreen(rowPos)
            val d = row.resources.displayMetrics.density
            val h = (row.height * .9f).roundToInt().coerceIn((32*d).roundToInt(), (48*d).roundToInt())
            val size = (h * .92f).roundToInt()
            val gap = (12*d).roundToInt()
            visual = motion.frame(now)
            channelSizing = channelMeasure.update(listOf<String?>(null) + shown.map { captions[it.id] ?: it.text.text.toString() },
                d, 14f * row.resources.displayMetrics.scaledDensity)
            val pill = minOf((channelSizing?.desiredWidth ?: visual.slots()*42*d).roundToInt(),
                content.width - 2*size - 4*gap).coerceAtLeast(h)
            val total = pill + 2*gap + 2*size
            val x = ((content.width-total)/2).coerceAtLeast(0)
            val y = rowPos[1]-contentPos[1]+(row.height-h)/2
            place(hosts[0], x, y+(h-size)/2, size, size)
            place(hosts[1], x+size+gap, y, pill, h)
            place(hosts[2], x+size+gap+pill+gap, y+(h-size)/2, size, size)
            if (visual.moving()) hosts.forEach(View::postInvalidateOnAnimation)
            if (overlay.parent === content && content.indexOfChild(overlay) != content.childCount-1) overlay.bringToFront()
        }

        private fun discover(state: DouyinTopBarBinding.State): Boolean {
            val candidateTabs = ArrayList<Tab>()
            val corners = ArrayList<View>()
            var visited = 0
            fun walk(v: View, depth: Int) {
                if (depth > 14 || visited++ >= 512 || v.visibility != View.VISIBLE) return
                val cell = binding?.cell(v)
                if (cell != null) {
                    text(cell.content)?.let { candidateTabs += Tab(v, it, cell.id) }
                    return
                }
                if (v.javaClass.simpleName == "TopCornerEntranceView") { corners += v; return }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth+1)
            }
            walk(row, 0)
            if (visited >= 512 || corners.size != 2 || candidateTabs.map { it.id }.distinct().size != candidateTabs.size) {
                reportBinding("pending=tab-structure visited=$visited corners=${corners.size} cells=${candidateTabs.size}"); return false
            }
            val positions = candidateTabs.associate { tab -> tab.id to (binding?.position(state, tab.id) ?: run {
                reportBinding("pending=native-position id=${tab.id}"); return false
            }) }
            if (positions.values.distinct().size != candidateTabs.size) {
                reportBinding("ambiguous=native-tab-positions"); return false
            }
            candidateTabs.sortBy { positions[it.id] }
            if (!candidateTabs.map { it.id }.containsAll(DouyinTopTabMotion.PINS)) {
                reportBinding("pending=required-tabs available=${candidateTabs.map { it.id }}"); return false
            }
            tabs.clear(); tabs += candidateTabs.map { it.copy(nativePosition = requireNotNull(positions[it.id])) }
            tabs.forEach { captions[it.id] = it.text.text.toString() }
            fun x(v: View): Int { v.getLocationOnScreen(rowPos); return rowPos[0] }
            corners.sortBy(::x)
            left = corners.firstOrNull(); right = corners.lastOrNull()?.takeUnless { it === left }
            return true
        }

        private fun text(v: View, depth: Int = 0): TextView? {
            if (depth > 4) return null
            if (v is TextView && v.text.isNotBlank()) return v
            if (v is ViewGroup) for (i in 0 until minOf(v.childCount,24)) text(v.getChildAt(i),depth+1)?.let { return it }
            return null
        }

        private fun selectTabs(now: Long) {
            val changed = motion.target(tabs.map { it.id }, requireNotNull(selected).id, now)
            shown = motion.visible().map { id -> tabs.single { it.id == id } }
            if (changed) reportBinding("ready native=${tabs.map { it.id to it.nativePosition }} current=${selected?.id} shown=${shown.map { it.id }}")
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
            if (row.alpha != 0f) row.alpha = 0f
            if (row.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
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
            if (!active || !bindingReady) return false
            var resolved = index
            if (role == 1 && touchX.isFinite() && SystemClock.uptimeMillis()-touchAt < 500L) {
                val host = hosts.getOrNull(1) ?: return false
                val pad = 4f*row.resources.displayMetrics.density
                val available = (host.width-2*pad).coerceAtLeast(1f)
                val fraction = ((channelSizing?.toUniform(touchX, host.width, pad) ?: touchX)-pad)/available
                if (fraction <= visual.menuCenter()+.5f/visual.slots()) resolved = 0
                else {
                    val point = visual.points().filter { it.id() in motion.visible() && it.alpha() > .35f }
                        .minByOrNull { kotlin.math.abs(it.center()-fraction) } ?: return false
                    resolved = shown.indexOfFirst { it.id == point.id() }+1
                }
            }
            if (role == 1 && resolved == 0) { toggleMenu(); return true }
            val target = when(role) { 0 -> left; 2 -> right; else -> shown.getOrNull(resolved-1)?.cell } ?: return false
            return click(target)
        }

        private fun click(target: View): Boolean {
            if (!bindingReady || !target.isAttachedToWindow || target.rootView !== row.rootView ||
                (target !== left && target !== right && tabs.none { it.cell === target })) return false
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
            android.util.Log.i("OfflineGlassDouyin", "top tap ${target.javaClass.simpleName} handled=$success")
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
                    setOnClickListener { click(tab.cell); closeMenu() }
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
            menu = root; overlay.addView(root, FrameLayout.LayoutParams(-1, -1)); root.requestFocus()
        }

        private fun closeMenu() { menu?.let { overlay.removeView(it) }; menu = null }

        private fun place(v: View, x: Int, y: Int, w: Int, h: Int) {
            val p = v.layoutParams as FrameLayout.LayoutParams
            if (p.width == w && p.height == h && p.leftMargin == x && p.topMargin == y) return
            p.width = w; p.height = h; p.leftMargin = x; p.topMargin = y
            p.gravity = Gravity.TOP or Gravity.LEFT; p.bottomMargin = 0
            v.layoutParams = p
        }

        fun dark(): Boolean = tabs.firstOrNull()?.text?.currentTextColor?.let {
            Color.red(it)*.299f + Color.green(it)*.587f + Color.blue(it)*.114f > 150f
        } ?: true

        fun selectedIndex(): Int = shown.indexOfFirst { it.cell === selected?.cell }.let { if (it >= 0) it+1 else -1 }

        fun indicatorGeometry(): Pair<Float, Float>? {
            val host = hosts.getOrNull(1) ?: return null
            val pad = 4f*row.resources.displayMetrics.density
            val available = (host.width-2*pad).coerceAtLeast(1f)
            val slot = available/visual.slots()
            val fraction = visual.selectionCenter()
            val proportion = channelSizing?.fraction(fraction) ?: fraction
            val left = fraction - .5f / visual.slots()
            val right = fraction + .5f / visual.slots()
            val width = channelSizing?.let { available * (it.fraction(right) - it.fraction(left)) }
                ?.takeIf { it > 0f } ?: slot
            val center = if (touching && touchX.isFinite()) touchX.coerceIn(pad+width/2,host.width-pad-width/2)
                else pad+available*proportion
            return center to width
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
                    0 -> for (i in -1..1) canvas.drawLine(cx-r, cy+i*5*d, cx+r, cy+i*5*d, iconPaint)
                    1 -> { val fill = Paint(iconPaint).apply { style = Paint.Style.FILL }; for(i in -1..1) canvas.drawCircle(cx+i*5*d, cy, 1.4f*d, fill) }
                    2 -> { canvas.drawCircle(cx-1.5f*d, cy-1.5f*d, r*.85f, iconPaint); canvas.drawLine(cx+3*d, cy+3*d, cx+8*d, cy+8*d, iconPaint) }
                }
            }
            if (role != 1) { glyph(if (role == 0) 0 else 2, frame.width/2f); return }
            val available = (frame.width-8*d).coerceAtLeast(1f)
            val slot = available/visual.slots()
            fun projected(f: Float) = channelSizing?.fraction(f) ?: f
            glyph(1, 4*d+available*projected(visual.menuCenter()))
            visual.points().forEach { point ->
                if (point.alpha() <= .001f) return@forEach
                labelPaint.color = color
                labelPaint.alpha = (255*point.alpha()).roundToInt().coerceIn(0,255)
                labelPaint.typeface = if (point.id() == selected?.id) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                labelPaint.textSize = 14f*frame.scaledDensity
                val value = captions[point.id()] ?: return@forEach
                val half = .5f / visual.slots()
                val max = (available * (projected(point.center() + half) - projected(point.center() - half)) - 6*d)
                    .coerceAtLeast(1f)
                val measured = labelPaint.measureText(value)
                if (measured > max) labelPaint.textSize *= max/measured
                val fm = labelPaint.fontMetrics
                canvas.drawText(value, 4*d+available*projected(point.center()), cy-(fm.ascent+fm.descent)/2, labelPaint)
            }
        }

        fun stop() {
            stopped = true; active = false
            videoSampler.dispose()
            if (row.viewTreeObserver.isAlive) row.viewTreeObserver.removeOnPreDrawListener(this)
            restore()
            // Detach runs inside native child traversal: removal must be deferred.
            main.post { closeMenu(); (overlay.parent as? ViewGroup)?.removeView(overlay); hosts.clear() }
        }

        private class PanelState(val session: Session, val role: Int) : AppNavigationState {
            override fun uniformSlotTouchX(x: Float, hostWidth: Int, padding: Float): Float =
                if (role == 1) session.channelSizing?.toUniform(x, hostWidth, padding) ?: x else x
            override fun indicatorHorizontalGeometry(center: Float, width: Float) =
                if (role == 1) session.indicatorGeometry() else null
            private var probeFrames = 0
            private var sources = ""
            override fun beforeHostDraw(host: View, source: ViewGroup?): Boolean {
                if (probeFrames < 30 && ++probeFrames == 30) {
                    val panel = host as? GlassHostLayout
                    val summary = "time=${System.currentTimeMillis()} role=$role live=${panel?.liveBackdropActiveForAdapter} " +
                        "liquid=${panel?.config?.liquidGlassEnabled} capture=${panel?.config?.backdropCapture} solid=${panel?.config?.solidBarEnabled} sources=$sources\n"
                    Thread { runCatching { java.io.File(host.context.getExternalFilesDir(null), "dy_topbar_scene_$role.txt").writeText(summary) } }.start()
                }
                return true
            }
            override val allowsSoftwareBackdropCapture = false
            override val backdropSceneViewsAreDescendants = true
            override fun resolveBackdropScene(host: View): View = session.content
            override fun backdropSceneViews(content: ViewGroup, hostIndex: Int): List<View> {
                val result = ArrayList<View>()
                // The native row has already been suppressed before these
                // panels draw. Keep the native scene intact, exactly as the
                // bottom bar does, instead of splitting every ancestor of the
                // title row and losing its own drawing/background/video layers.
                // Our overlay and the bottom host are direct content children.
                for (i in 0 until session.content.childCount) {
                    val v = session.content.getChildAt(i)
                    if (v !is GlassHostLayout && v !is AdapterOwnedOverlay &&
                        v.visibility == View.VISIBLE && v.alpha > .001f && v.width > 0 && v.height > 0) result += v
                }
                if (probeFrames < 30) sources = result.joinToString { "${it.javaClass.simpleName}:${it.width}x${it.height}" }
                return result
            }
            override fun requestVideoBackdrop(host: View, pageAllowed: Boolean) {
                if (role == 1) session.videoSampler.request(host, pageAllowed && session.active, false)
            }
            override fun videoBackdrop() = session.videoSampler.snapshot()
            override fun forceDarkMode(selectedIndex: Int) = session.dark()
            // Shares the homepage window with the bottom bar. Returning false
            // restores the old inset every pre-draw and fights that bar's
            // immersive policy, visibly lifting the system gesture handle.
            override fun forceNavigationBarImmersed(host: GlassHostLayout) =
                !DouyinCommentLayoutHooks.requiresNativeNavigationFit(host.rootView)
            override fun setNativeBarTouchable(view: View?, touchable: Boolean) = true
            override fun suppressRedrawnSource() = false
            override fun resolveSelectedIndex(source: ViewGroup?, slotCount: Int) = if (role == 1) session.selectedIndex() else 0
            override fun hostPageAllowsNavigation(host: GlassHostLayout, root: View?) = session.active
            override fun pauseBackdropWork(host: View) = !session.active
            override fun drawsNavigationContent() = role != 3
            override fun allowsHostTouch() = role != 3
            override fun performTap(host: View, source: ViewGroup?, index: Int, slotCount: Int) = session.tap(role, index)
            override fun draw(canvas: Canvas, frame: AdapterNavigationFrame) = session.draw(canvas, frame, role)
            override fun forceLiveBackdropFrame(selectedIndex: Int) = true
            private var live = false
            private var videoEpoch = Int.MIN_VALUE
            private var texture = WeakReference<android.view.TextureView>(null)
            override fun outerBackdropNeedsRecord(backdrop: Bitmap?, width: Int, height: Int, hasDisplayList: Boolean): Boolean =
                !hasDisplayList || videoEpoch != (videoBackdrop()?.epoch ?: -1) ||
                    live != session.hosts.getOrNull(role)?.liveBackdropActiveForAdapter ||
                    texture.get() !== session.hosts.firstOrNull()?.let(DouyinPlayerTextureHooks::detailTexture)
            override fun onOuterBackdropRecorded(backdrop: Bitmap?, width: Int, height: Int) {
                live = session.hosts.getOrNull(role)?.liveBackdropActiveForAdapter == true
                videoEpoch = videoBackdrop()?.epoch ?: -1
                texture = WeakReference(session.hosts.firstOrNull()?.let(DouyinPlayerTextureHooks::detailTexture))
            }
        }

        private class PanelAdapter(val state: PanelState) : TargetAdapter {
            override fun surfaceOpacityScale(config: GlassConfig) = config.shortVideoBarOpacity
            override fun glassSurfaceFillColor(dark: Boolean, selectedIndex: Int, defaultColor: Int) =
                DouyinAdapter.glassSurfaceFillColor(dark, 0, defaultColor)
            override val key = "douyin-top"
            override val ownsNavigationDrawing = true
            // The dropdown's native channel list owns input and foreground ink.
            // Enabling this for its background also enables the shared host's
            // bringToFront repair, which covers every label with glass again.
            override val ownsNavigationTap get() = state.role != 3
            // One owner orders this compound group; individual buttons must
            // not repeatedly raise themselves above their siblings.
            override val managesHostSiblingOrder = true
            override val ownsHostLayout = true
            override val skipNavigationSnapshot = true
            override val keepNavigationSourceVisible = true
            override val preserveNativeSourceMetrics = true
            override val managesNativeTabVisibility = true
            override val hasPerFrameScene = true
            override val continuousFramePump = true
            override val requestedFrameRateMax = 60f
            override val skipBackdropStableDelay = true
            override val forceCompositorFrames = true
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
}

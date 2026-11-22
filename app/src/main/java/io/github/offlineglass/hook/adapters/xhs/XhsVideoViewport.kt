package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** 9.51: the player-only layer ends above BottomBarContainerView (2448/2656
 * on the inspected phone). Extend only that layer and its player, never the
 * guideline/control siblings. Keep native aspect-ratio/render measurement.
 */
internal object XhsVideoViewport {
    private const val ACTIVITY = "com.xingin.matrix.detail.activity.DetailFeedActivity"
    private const val ITEM = "com.xingin.matrix.detail.item.video.VideoFeedItemView"
    private const val PLAYER = "com.xingin.matrix.detail.item.video.player.VideoItemPlayerView"
    private const val BAR = "com.xingin.videofeed.container.bottombar.BottomBarContainerView"
    private val sessions = WeakHashMap<Activity, Session>()
    fun install() {
        for (event in listOf("onPostResume", "onStop", "onDestroy")) {
            XposedBridge.hookAllMethods(Activity::class.java, event, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (activity.javaClass.name != ACTIVITY) return
                    when (event) {
                        "onPostResume" -> sessions.getOrPut(activity) { Session(activity) }.start()
                        "onStop" -> sessions[activity]?.stop()
                        else -> sessions.remove(activity)?.stop()
                    }
                }
            })
        }
    }

    private class Session(activity: Activity) : ViewTreeObserver.OnPreDrawListener {
        private val root = activity.window.decorView as ViewGroup
        private val players = ArrayList<View>()
        private val bars = ArrayList<View>()
        private val originals = WeakHashMap<View, ViewGroup.LayoutParams>()
        private val backgrounds = WeakHashMap<View, Drawable?>()
        private var lastScan = 0L
        private var structureDirty = true
        private var listening = false
        private val visible = Rect()
        private val buttons = XhsVideoButtons()
        private val constraints = mapOf("topToTop" to 0, "topToBottom" to -1,
            "bottomToTop" to -1, "bottomToBottom" to 0,
            "topMargin" to 0, "bottomMargin" to 0)
        private data class Accessors(val constraints: Map<String, java.lang.reflect.Field>, val bias: java.lang.reflect.Field)
        private val fields = HashMap<Class<*>, Accessors>()
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { structureDirty = true }
        fun start() {
            if (listening) return
            listening = true
            lastScan = 0L
            structureDirty = true
            root.viewTreeObserver.addOnPreDrawListener(this)
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            root.invalidate()
        }
        override fun onPreDraw(): Boolean {
            val portrait = root.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val keyboard = root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            if (!portrait || keyboard) { restore(); return true }
            val now = SystemClock.uptimeMillis()
            if (lastScan == 0L || structureDirty && now - lastScan >= 100L || now - lastScan >= 500L) {
                lastScan = now
                structureDirty = false
                players.clear(); bars.clear()
                fun visit(view: View) {
                    if (!view.isShown) return
                    if (view.javaClass.name == PLAYER) { players.add(view); return }
                    if (view.javaClass.name == BAR) { bars.add(view); return }
                    if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
                }
                visit(root)
            }
            var activePlayer: View? = null
            var activeArea = 0L
            val visiblePlayers = ArrayList<View>()
            for (player in players) {
                if (!player.isAttachedToWindow || !player.getGlobalVisibleRect(visible)) continue
                val layer = player.parent as? ViewGroup ?: continue
                val item = layer.parent as? ViewGroup ?: continue
                // This structure is the inspected player-only split layer. Do
                // not change legacy layouts sharing the player/control parent.
                if (item.javaClass.name != ITEM || layer.javaClass.name !=
                    "androidx.constraintlayout.widget.ConstraintLayout" ||
                    layer.width < item.width * .9f || item.height < root.height * .8f) continue
                if (extend(layer, item.height) && extend(player, 0)) {
                    visiblePlayers.add(player)
                    val area = visible.width().toLong() * visible.height()
                    if (area > activeArea) {
                        activeArea = area
                        activePlayer = player
                    }
                }
            }
            for (bar in bars) {
                if (activePlayer == null) continue
                if (!backgrounds.containsKey(bar)) backgrounds[bar] = bar.background
                if (bar.background != null) bar.background = null
            }
            buttons.update(root, bars, activePlayer, visiblePlayers)
            return true
        }
        private fun extend(view: View, height: Int): Boolean {
            val params = view.layoutParams ?: return false
            if (params.javaClass.name != "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams") return false
            return runCatching {
                val type = params.javaClass
                val handles = fields.getOrPut(type) {
                    Accessors(constraints.keys.associateWith(type::getField), type.getField("verticalBias"))
                }
                val accessors = handles.constraints
                val verticalBias = handles.bias
                if (params.height == height && constraints.all { (key, value) ->
                        accessors.getValue(key).getInt(params) == value } &&
                    verticalBias.getFloat(params) == 0f) return true
                if (!originals.containsKey(view)) {
                    originals[view] = type.getConstructor(ViewGroup.LayoutParams::class.java)
                        .newInstance(params) as ViewGroup.LayoutParams
                }
                for ((key, value) in constraints) accessors.getValue(key).setInt(params, value)
                // Native split-layer constraints can retain the old bottom
                // boundary during layout. Fixed full-screen height plus the
                // default .5 bias put this layer at -103..2553 on a 2656px
                // screen. Anchor overflow to the top, never center it upward.
                verticalBias.setFloat(params, 0f)
                params.height = height
                view.layoutParams = params
                true
            }.onFailure { android.util.Log.w("LiquidTabInject", "XHS video viewport unavailable", it) }.getOrDefault(false)
        }
        private fun restore() {
            buttons.restore()
            originals.forEach { (view, params) -> view.layoutParams = params }
            originals.clear()
            backgrounds.forEach { (view, background) -> if (view.background == null) view.background = background }
            backgrounds.clear()
        }
        fun stop() {
            if (listening && root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
            if (listening && root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            listening = false
            restore()
            players.clear(); bars.clear()
        }
    }
}

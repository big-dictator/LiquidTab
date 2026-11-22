package io.github.offlineglass.hook.adapters.xhs

import android.app.Dialog
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap

/** Comment sheet owns a separate window; never change the underlying video window. */
internal object XhsCommentGlass {
    private val sessions = WeakHashMap<Dialog, Session>()
    fun install() {
        XposedBridge.hookAllMethods(Dialog::class.java, "show", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val dialog = param.thisObject as? Dialog ?: return
                if (dialog.javaClass.name != "com.xingin.matrix.comment.dialog.VideoCommentListDialog") return
                android.util.Log.i("LiquidTabInject", "XHS video comment dialog attached")
                sessions.getOrPut(dialog) { Session(dialog) }.start()
            }
        })
        XposedBridge.hookAllMethods(Dialog::class.java, "dismiss", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                sessions.remove(param.thisObject as? Dialog)?.stop()
            }
        })
    }
    private class Session(val dialog: Dialog) : ViewTreeObserver.OnPreDrawListener {
        val root = dialog.window!!.decorView
        val glass = XhsVideoButtons()
        val backgrounds = LinkedHashMap<View, android.graphics.drawable.Drawable?>()
        val heights = LinkedHashMap<View, Int>()
        var list: ViewGroup? = null
        var padding = 0
        var clipping = true
        private var scannedAt = 0L
        private var nodes = emptyList<View>()
        private var navigationFiller: View? = null
        private val position = IntArray(2)
        fun start() {
            val window = dialog.window ?: return
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.navigationBarColor = Color.TRANSPARENT
            window.isNavigationBarContrastEnforced = false
            root.systemUiVisibility = root.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            root.viewTreeObserver.addOnPreDrawListener(this)
            root.requestApplyInsets()
        }
        fun clear(view: View) {
            if (!backgrounds.containsKey(view)) backgrounds[view] = view.background
            view.background = null
        }
        override fun onPreDraw(): Boolean {
            // Cache structural roles; never reflect a dialog's obfuscated field.
            val now = android.os.SystemClock.uptimeMillis()
            if (now - scannedAt >= 500L || nodes.isEmpty() || list?.isAttachedToWindow == false) {
                scannedAt = now
                val found = ArrayList<View>()
                fun walk(v: View) {
                    found.add(v)
                    val recycler = generateSequence(v.javaClass as Class<*>?) { it.superclass }
                        .any { it.name == "androidx.recyclerview.widget.RecyclerView" }
                    if (!recycler && v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(root); nodes = found
                val nav = root.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0
                root.getLocationInWindow(position)
                val bottom = position[1] + root.height
                navigationFiller = if (nav > 0) found.filter { candidate ->
                    if (candidate.javaClass != View::class.java || candidate.id != View.NO_ID || candidate.isClickable ||
                        candidate.width < root.width * .9f || kotlin.math.abs(candidate.height - nav) > 2) false
                    else {
                        candidate.getLocationInWindow(position)
                        kotlin.math.abs(position[1] + candidate.height - bottom) <= 2
                    }
                }.singleOrNull() else null
            }
            navigationFiller?.takeIf { it.isAttachedToWindow }?.let(::clear)
            dialog.window?.let { window ->
                if (window.navigationBarColor != Color.TRANSPARENT) window.navigationBarColor = Color.TRANSPARENT
                window.navigationBarDividerColor = Color.TRANSPARENT
                window.isNavigationBarContrastEnforced = false
            }
            val frame = Rect(); root.getWindowVisibleDisplayFrame(frame)
            if (root.height - frame.bottom > root.height / 4) { glass.restore(); return true }
            val all = nodes
            val comment = XhsVideoButtons.commentLayout(root)?.takeIf { it.isShown } ?: return true
            val button = XhsVideoButtons.commentPlate(comment, root.width) ?: return true
            val recycler = all.firstOrNull { it.javaClass.name.contains("RecyclerView") && it.isShown && it.height > root.height / 4 } as? ViewGroup ?: return true
            if (list !== recycler) { list = recycler; padding = recycler.paddingBottom; clipping = recycler.clipToPadding }
            recycler.clipToPadding = false
            val safe = comment.height + (root.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom ?: 0)
            if (recycler.paddingBottom != padding + safe) recycler.setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, padding + safe)
            // The native list host reserves the input row. Let its content draw behind the floating button.
            val host: View = (recycler.parent as? View)?.parent as? View ?: recycler
            if (host !== root && host.layoutParams != null && host.height > root.height / 4) {
                val lp = host.layoutParams
                if (!heights.containsKey(host)) heights[host] = lp.height
                if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) { lp.height = ViewGroup.LayoutParams.MATCH_PARENT; host.layoutParams = lp }
            }
            clear(comment)
            var ancestor = comment.parent as? View
            repeat(3) { ancestor?.let { clear(it); ancestor = it.parent as? View } }
            for (i in 0 until comment.childCount) {
                val child = comment.getChildAt(i)
                if (child.javaClass == View::class.java) clear(child)
            }
            glass.updateComment(root, button, recycler)
            return true
        }
        fun stop() {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
            glass.restore()
            backgrounds.forEach { (v, bg) -> v.background = bg }
            heights.forEach { (v, h) -> v.layoutParams = v.layoutParams.apply { height = h } }
            list?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, padding); it.clipToPadding = clipping }
        }
    }
}

package io.github.offlineglass.hook.adapters.weibo

import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.TextView
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.adapters.AdapterOwnedOverlay
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Resource relationships and native channel semantics; no private class/member names. */
internal object WeiboHomeTopBinding {
    private val rows = WeakHashMap<ViewGroup, Boolean>()
    private val ownedTouch = WeakHashMap<ViewGroup, Boolean>()
    private var installed = false
    var lastReport = "not discovered"
        private set
    data class Binding(val row: ViewGroup, val tabs: List<TextView>, val left: View,
                       val reward: View, val publish: View) {
        fun valid() = row.isAttachedToWindow && WeiboHomeTopPolicy.channels(tabs.map { it.text.toString().trim() }) && tabs.all { it.isAttachedToWindow } &&
            listOf(left, reward, publish).all { it.isAttachedToWindow }
        fun selection(): Int {
            fun score(v: TextView): Float = (if (v.isSelected || v.isActivated || (v.parent as? View)?.isSelected == true) 1000f else 0f) +
                (if (v.typeface?.isBold == true) 100f else 0f) + v.textSize
            return if (score(tabs[1]) > score(tabs[0])) 1 else 0
        }
    }
    fun install() {
        if (installed) return
        XposedBridge.hookMethod(LayoutInflater::class.java.getMethod("inflate", Int::class.javaPrimitiveType,
            ViewGroup::class.java, Boolean::class.javaPrimitiveType), object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val root = param.result as? ViewGroup ?: return
                if (root.context.packageName == "com.sina.weibo" && name(root) == "lyTitleBar") rows[root] = true
            }
        })
        XposedBridge.hookMethod(ViewGroup::class.java.getMethod("dispatchTouchEvent", android.view.MotionEvent::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    WeiboHomeRefresh.touch(param.thisObject, param.args[0] as android.view.MotionEvent)
                    if (ownedTouch[param.thisObject] == true) param.result = false
                }
            })
        installed = true
    }
    fun ownTouch(row: ViewGroup) { ownedTouch[row] = true }
    fun releaseTouch(row: ViewGroup) { ownedTouch.remove(row) }
    fun find(root: View): Binding? {
        // Handles configuration arriving after native inflation. Bounded and only on rebind.
        walk(root, 2048) { if (it is ViewGroup && name(it) == "lyTitleBar") rows[it] = true }
        val rect = Rect()
        val matches = rows.keys.toList().mapNotNull { row ->
            if (!row.isAttachedToWindow || row.rootView !== root.rootView || !row.isShown ||
                !row.getGlobalVisibleRect(rect) || rect.width() < row.width * .8f || !ancestorsVisible(row)) return@mapNotNull null
            val middle = byId(row, "home_bar_middle_layout") as? ViewGroup ?: return@mapNotNull null
            val tabs = ArrayList<TextView>()
            walk(middle, 160) { if (it is TextView && it.visibility == View.VISIBLE && it.text?.toString()?.trim() in setOf("推荐", "关注")) tabs += it }
            if (!WeiboHomeTopPolicy.channels(tabs.map { it.text.toString().trim() })) return@mapNotNull null
            val recommend = tabs.filter { it.text.toString().trim() == "推荐" }.singleOrNull() ?: return@mapNotNull null
            val follow = tabs.filter { it.text.toString().trim() == "关注" }.singleOrNull() ?: return@mapNotNull null
            val left = byId(row, "home_bar_left_tv1") ?: return@mapNotNull null
            val reward = byId(row, "rlredpacketSave") ?: return@mapNotNull null
            val publish = byId(row, "home_bar_right_tv1") ?: return@mapNotNull null
            if (listOf(left, reward, publish).any { it.width <= 0 || it.height <= 0 || it.visibility != View.VISIBLE }) return@mapNotNull null
            Binding(row, listOf(recommend, follow), left, reward, publish)
        }
        lastReport = "headerRoots=${rows.size} completeHomeCandidates=${matches.size}"
        return matches.singleOrNull()
    }
    fun ancestorsVisible(row: View): Boolean {
        var p = row.parent as? View
        while (p != null) {
            if (p.visibility != View.VISIBLE || p.alpha <= .01f) return false
            p = p.parent as? View
        }
        return true
    }
    fun click(view: View, boundary: View): Boolean {
        if (!view.isAttachedToWindow || !view.isEnabled || !view.isShown) return false
        var target: View? = view
        repeat(10) {
            val current = target ?: return false
            if (current === boundary) {
                val descendants = ArrayList<View>()
                walk(view, 40) { if (it.isShown && it.isEnabled && it.hasOnClickListeners()) descendants += it }
                return descendants.singleOrNull()?.performClick() == true
            }
            if (current.hasOnClickListeners()) return current.performClick()
            target = current.parent as? View
        }
        return false
    }
    data class Feed(val branch: View, val list: ViewGroup, val pager: ViewGroup)
    fun feedBranch(root: View, row: View): Feed? {
        val scope = row.parent as? ViewGroup ?: return null
        val pager = byId(scope, "home_view_pager") as? ViewGroup ?: return null
        val listId = root.resources.getIdentifier("view_recycler", "id", "com.sina.weibo")
        val lists = ArrayList<ViewGroup>()
        val rect = Rect()
        walk(pager, 2048) { v ->
            if (v is ViewGroup && v.id == listId && v.isShown && v.height > root.height * .35f &&
                v.getGlobalVisibleRect(rect) && rect.width() >= v.width * .9f && runCatching {
                    v.javaClass.getMethod("getAdapter").invoke(v) != null && v.javaClass.getMethod("getLayoutManager").invoke(v) != null
                }.getOrDefault(false)) lists += v
        }
        val list = lists.singleOrNull() ?: return null
        var page: View = list
        var ancestor = page.parent as? View
        while (ancestor != null && ancestor !== pager) { page = ancestor; ancestor = page.parent as? View }
        return if (ancestor === pager) Feed(page, list, pager) else null
    }
    fun byId(root: View, entry: String): View? {
        val id = root.resources.getIdentifier(entry, "id", "com.sina.weibo")
        return if (id != 0) root.findViewById(id) else null
    }
    fun name(view: View): String = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
    private fun walk(root: View, limit: Int, visit: (View) -> Unit) {
        val queue = ArrayDeque<View>(); queue += root
        var count = 0
        while (queue.isNotEmpty() && count++ < limit) {
            val view = queue.removeFirst()
            if (view is GlassHostLayout || view is AdapterOwnedOverlay) continue
            visit(view)
            // Feed item trees are data, never top-bar capabilities.
            if (view is ViewGroup && view !is AbsListView && name(view) != "view_recycler")
                for (i in 0 until view.childCount) queue += view.getChildAt(i)
        }
    }
}

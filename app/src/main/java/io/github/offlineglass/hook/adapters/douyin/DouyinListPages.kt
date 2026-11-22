package io.github.offlineglass.hook.adapters.douyin

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Native fragment arguments own page identity; resources and implementation names do not. */
internal object DouyinListPages {
    private val roots = WeakHashMap<View, String>()
    private var installed = false
    fun install(loader: ClassLoader) {
        if (installed) return
        val type = Class.forName("androidx.fragment.app.Fragment", false, loader)
        val arguments = type.getMethod("getArguments")
        val view = type.getMethod("getView")
        val created = type.getDeclaredMethod("performViewCreated")
        require(arguments.returnType == Bundle::class.java &&
            View::class.java.isAssignableFrom(view.returnType) && created.returnType == Void.TYPE)
        XposedBridge.hookMethod(created, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val args = arguments.invoke(p.thisObject) as? Bundle ?: return
                // Groupon supplies the home-tab ID; C2's home fragment supplies
                // the same page role through the native feed EVENT_TYPE contract.
                val id = args.getString("extra_tab_id")
                    ?: args.getString("com.ss.android.ugc.aweme.intent.extra.EVENT_TYPE") ?: return
                if (id != "homepage_groupon" && id != "homepage_mediumvideo" && id != "homepage_mall") return
                val root = view.invoke(p.thisObject) as? ViewGroup ?: return
                roots[root] = id
            }
        })
        installed = true
    }

    fun role(view: View): String? {
        var node: View? = view
        repeat(32) {
            node ?: return null
            roots[node]?.let { return it }
            node = node?.parent as? View
        }
        return null
    }

    fun contains(container: View): Boolean = roots.keys.any { page ->
        page.isAttachedToWindow && descendant(page, container)
    } || role(container) != null

    private fun descendant(view: View, parent: View): Boolean {
        var node: View? = view
        repeat(32) {
            if (node === parent) return true
            node = node?.parent as? View ?: return false
        }
        return false
    }

    /** One bounded viewport search per page construction; never descend into adapter items. */
    class Layout(private val loader: ClassLoader) {
        private val binding by lazy { DouyinTopBarBinding.shared(loader) }
        private val recycler by lazy { Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader) }
        private val adapter by lazy { recycler.getMethod("getAdapter") }
        private var page = WeakReference<View>(null)
        private var list = WeakReference<View>(null)
        private var searchedAt = 0L
        private var report = ""
        private var reports = 0
        var activeGrouponRoot: View? = null
            private set
        var activeMallRoot: View? = null
            private set
        private val visible = android.graphics.Rect()
        fun isRecycler(view: View): Boolean = recycler.isInstance(view)
        fun isGrouponViewport(view: View): Boolean = view === list.get() && role(view) == "homepage_groupon"
        private data class Original(val height: Int, val margin: Int?, val padding: Int, val clip: Boolean?)
        private val owned = WeakHashMap<View, Original>()

        fun update(window: ViewGroup, releaseLift: (View) -> Unit) {
            activeGrouponRoot = null
            activeMallRoot = null
            val state = runCatching { binding.state() }.getOrNull() ?: return
            if (state.current != "homepage_groupon" && state.current != "homepage_mediumvideo" && state.current != "homepage_mall") return
            val candidates = roots.entries.filter { (root, role) ->
                role == state.current && root.isAttachedToWindow && root.rootView === window && root.isShown
            }
            if (candidates.size != 1) return
            val root = candidates.single().key
            if (state.current == "homepage_mall") {
                if (root.getGlobalVisibleRect(visible) && visible.width() > window.width / 2) {
                    activeMallRoot = root
                }
                return
            }
            if (state.current == "homepage_groupon" && root.getGlobalVisibleRect(visible) &&
                visible.width() > window.width / 2) activeGrouponRoot = root
            if (page.get() !== root) { page = WeakReference(root); list.clear(); searchedAt = 0 }
            var viewport = list.get()?.takeIf { it.isAttachedToWindow && descendant(it, root) }
            val now = SystemClock.uptimeMillis()
            if (viewport == null && now - searchedAt > 1000) {
                searchedAt = now
                val found = ArrayList<View>()
                val pending = java.util.ArrayDeque<Pair<View, Int>>()
                pending.add(root to 0)
                var visits = 0
                while (pending.isNotEmpty() && visits++ < 512) {
                    val (node, depth) = pending.removeFirst()
                    if (recycler.isInstance(node)) {
                        if (adapter.invoke(node) != null && node.isShown &&
                            node.width >= root.width * .8f && node.height >= root.height * .4f) found.add(node)
                        continue
                    }
                    if (node is ViewGroup && depth < 24 && node.visibility != View.GONE)
                        for (i in 0 until node.childCount) pending.add(node.getChildAt(i) to depth + 1)
                }
                viewport = found.singleOrNull()
                list = WeakReference(viewport)
            }
            val target = viewport ?: return
            val evidence = if (reports < 8) StringBuilder("page=${state.current}\n") else null
            // Only the actual scrolling viewport and its containing layers. Headers,
            // overlays, cards and AppBar siblings are never changed.
            var node: View? = target
            repeat(32) {
                val current = node ?: return@repeat
                releaseLift(current)
                val lp = current.layoutParams
                val margin = lp as? ViewGroup.MarginLayoutParams
                evidence?.append(current.javaClass.name)?.append(" h=")?.append(current.height)
                    ?.append(" lp=")?.append(lp?.height)?.append(" mb=")?.append(margin?.bottomMargin)
                    ?.append(" pb=")?.append(current.paddingBottom)?.append('\n')
                owned.getOrPut(current) { Original(lp?.height ?: -2, margin?.bottomMargin,
                    current.paddingBottom, (current as? ViewGroup)?.clipToPadding) }
                var changed = false
                // Keep constraint-match and weight semantics intact. Positive native
                // viewport heights were measured with the old tab reservation.
                if (lp != null && lp.height > 0) { lp.height = -1; changed = true }
                if (margin != null && margin.bottomMargin > 0) { margin.bottomMargin = 0; changed = true }
                if (current is ViewGroup) {
                    if (current.paddingBottom > 0) current.setPadding(current.paddingLeft, current.paddingTop, current.paddingRight, 0)
                    if (current.clipToPadding) current.clipToPadding = false
                }
                if (changed) current.layoutParams = lp
                if (current === root) { node = null; return@repeat }
                node = current.parent as? View
            }
            val text = evidence?.toString()
            if (text != null && text != report) {
                report = text
                reports++
                runCatching { java.io.File(window.context.getExternalFilesDir(null), "dy_list_page_status.txt")
                    .writeText("time=${System.currentTimeMillis()} pid=${android.os.Process.myPid()}\n$text") }
            }
        }

        fun dispose() {
            owned.forEach { (view, old) ->
                val lp = view.layoutParams
                if (lp != null) {
                    if (lp.height == -1) lp.height = old.height
                    val margin = lp as? ViewGroup.MarginLayoutParams
                    if (margin?.bottomMargin == 0 && old.margin != null) margin.bottomMargin = old.margin
                    view.layoutParams = lp
                }
                if (view.paddingBottom == 0) view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, old.padding)
                if (view is ViewGroup && old.clip != null) view.clipToPadding = old.clip
            }
            owned.clear(); page.clear(); list.clear()
            activeGrouponRoot = null
            activeMallRoot = null
        }
    }
}

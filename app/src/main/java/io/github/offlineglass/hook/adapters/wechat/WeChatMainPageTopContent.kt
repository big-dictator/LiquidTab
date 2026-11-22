package io.github.offlineglass.hook.adapters.wechat

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import java.util.WeakHashMap

/** Move native contacts/discovery header reservation into their scrolling viewport. */
internal object WeChatMainPageTopContent {
    private class State(val contacts: Boolean, var nativeTop: Int) {
        var list = java.lang.ref.WeakReference<View>(null)
        var listTop = 0
        var appliedListTop: Int? = null
        var active = false
        val clips = WeakHashMap<ViewGroup, Pair<Boolean, Boolean>>()
    }
    private val pages = WeakHashMap<View, State>()
    private var writing = false

    // Other native-layout repairs must preserve the logical reservation rather
    // than feeding our temporary immersive zero back into the native writer.
    fun nativeTopPadding(page: View): Int = pages[page]?.nativeTop ?: page.paddingTop

    fun install(loader: ClassLoader) {
        for ((name, contacts) in listOf(
            "com.tencent.mm.ui.contact.address.BaseAddressUIFragment" to true,
            "com.tencent.mm.ui.FindMoreFriendsUI" to false,
        )) {
            val type = XposedHelpers.findClass(name, loader)
            val accessor = type.getMethod("getView")
            XposedBridge.hookMethod(accessor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!type.isInstance(param.thisObject)) return
                    val page = param.result as? ViewGroup ?: return
                    if (!WeChatLauncherPages.owns(page) || pages.containsKey(page)) return
                    val state = State(contacts, page.paddingTop)
                    pages[page] = state
                    val listener = object : android.view.ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
                        var observer: android.view.ViewTreeObserver? = null
                        fun bind() {
                            val next = page.viewTreeObserver
                            if (observer === next) return
                            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                            observer = next
                            next.addOnPreDrawListener(this)
                        }
                        override fun onPreDraw(): Boolean { update(page, state); return true }
                        override fun onViewAttachedToWindow(view: View) = bind()
                        override fun onViewDetachedFromWindow(view: View) {
                            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                            observer = null
                        }
                    }
                    page.addOnAttachStateChangeListener(listener)
                    listener.bind()
                }
            })
        }
        XposedBridge.hookAllMethods(View::class.java, "setPadding", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (writing) return
                val state = pages[param.thisObject] ?: return
                state.nativeTop = param.args[1] as Int
                if (state.active) param.args[1] = 0
            }
        })
    }

    private fun update(page: ViewGroup, state: State) {
        if (!WeChatLauncherPages.owns(page)) return
        var list = state.list.get()
        if (list == null || !descendant(list, page)) {
            val candidates = ArrayList<View>()
            val queue = ArrayDeque<Pair<View, Int>>()
            queue += page to 0
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 128) {
                val (view, depth) = queue.removeFirst()
                val matches = if (state.contacts) view.javaClass.name == "com.tencent.mm.view.recyclerview.WxRecyclerView"
                    else view is android.widget.ListView && view.id == android.R.id.list
                if (matches) { candidates += view; continue }
                if (view is android.widget.AbsListView) continue
                if (view is ViewGroup && depth < 9) for (i in 0 until view.childCount) queue += view.getChildAt(i) to depth + 1
            }
            list = candidates.singleOrNull() ?: return
            state.list = java.lang.ref.WeakReference(list)
            state.listTop = list.paddingTop
            state.appliedListTop = null
            var node: View? = list
            while (node != null) {
                if (node is ViewGroup) state.clips.putIfAbsent(node, node.clipChildren to node.clipToPadding)
                if (node === page) break
                node = node.parent as? View
            }
        }
        if (state.appliedListTop == null || list.paddingTop != state.appliedListTop) state.listTop = list.paddingTop
        val enabled = WeChatChatToolbarGradient.nativeMainHeaderVisible(page.rootView)
        writing = true
        try {
            state.active = enabled
            val pageTop = if (enabled) 0 else state.nativeTop
            val listTop = state.listTop + if (enabled) state.nativeTop else 0
            if (page.paddingTop != pageTop) page.setPadding(page.paddingLeft, pageTop, page.paddingRight, page.paddingBottom)
            if (list.paddingTop != listTop) list.setPadding(list.paddingLeft, listTop, list.paddingRight, list.paddingBottom)
            state.appliedListTop = listTop
            state.clips.forEach { (group, flags) ->
                val children = if (enabled) false else flags.first
                val padding = if (enabled) false else flags.second
                if (group.clipChildren != children) group.clipChildren = children
                if (group.clipToPadding != padding) group.clipToPadding = padding
            }
        } finally { writing = false }
    }

    private fun descendant(view: View, root: View): Boolean {
        var node: View? = view
        repeat(16) { if (node === root) return true; node = node?.parent as? View }
        return false
    }
}

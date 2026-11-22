package io.github.offlineglass.hook.adapters.tieba

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Removes only the native tab reservation that shrinks these two feeds' viewports. */
internal class TiebaFeedBottomInsets {
    private val changed = WeakHashMap<View, Int>()
    private var nextProbeAt = 0L

    fun update(host: GlassHostLayout, enabled: Boolean) {
        if (!enabled) {
            restore()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now < nextProbeAt || pageRoots.isEmpty()) return
        nextProbeAt = now + 350L
        val nativeHeight = nativeBarHeight?.invoke()?.takeIf { it > 0 } ?: return
        val active = mutableSetOf<View>()
        for ((page, role) in pageRoots) {
            if (!page.isAttachedToWindow || page.rootView !== host.rootView || !isHomePagerPage(page)) continue
            val target = when (role) {
                Role.WEB -> webInsetTarget(page, nativeHeight)
                Role.LIVE -> liveInsetTarget(page, nativeHeight)
            } ?: continue
            active.add(target)
            if (target.paddingBottom > 0) {
                changed[target] = target.paddingBottom
                setBottomPadding(target, 0)
            }
        }
        val previous = changed.keys.toList()
        for (view in previous) if (view !in active) restore(view)
    }

    private fun matchesReservation(view: View, height: Int): Boolean {
        val bottom = view.paddingBottom
        return if (bottom == 0) view in changed else bottom == height || bottom == height / 2
    }

    private fun webInsetTarget(page: ViewGroup, height: Int): View? {
        // Both eager and lazy web tabs use TbWebView. The padding owner is the
        // content root or the inflated lazy child, never the web view itself.
        val web = uniqueDescendant(page) { isType(it, WEB_VIEW) } ?: return null
        var candidate = web.parent as? View
        var target: View? = null
        while (candidate != null) {
            if (matchesReservation(candidate, height)) {
                if (target != null) return null
                target = candidate
            }
            if (candidate === page) return target
            candidate = candidate.parent as? View
        }
        return null
    }

    private fun liveInsetTarget(page: ViewGroup, height: Int): View? {
        if (page !is FrameLayout) return null
        val live = (0 until page.childCount).asSequence().map(page::getChildAt)
            .filter { isType(it, LIVE_FEED_VIEW) }.take(2).toList().singleOrNull() ?: return null
        return live.takeIf { matchesReservation(it, height) }
    }

    fun restore() {
        for (view in changed.keys.toList()) restore(view)
        nextProbeAt = 0L
    }

    private fun restore(view: View) {
        val bottom = changed.remove(view) ?: return
        if (view.paddingBottom == 0) setBottomPadding(view, bottom)
    }

    private fun setBottomPadding(view: View, bottom: Int) {
        if (view.isPaddingRelative) {
            view.setPaddingRelative(view.paddingStart, view.paddingTop, view.paddingEnd, bottom)
        } else {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom)
        }
    }

    companion object {
        private enum class Role { WEB, LIVE }
        private const val WEB_VIEW = "com.baidu.tieba.browser.TbWebView"
        private const val LIVE_FEED_VIEW = "com.baidu.live.business.LiveTabFeedView"
        private const val HOME_PAGER = "com.baidu.tbadk.widget.CustomViewPager"
        private const val HOME_HOST = "com.baidu.tieba.homepage.framework.indicator.ScrollFragmentTabHost"
        private val pageRoots = WeakHashMap<ViewGroup, Role>()
        private var nativeBarHeight: (() -> Int?)? = null

        fun install(loader: ClassLoader) {
            nativeBarHeight = runCatching {
                val app = Class.forName("com.baidu.tbadk.core.TbadkCoreApplication", false, loader)
                val getInstance = app.getMethod("getInst")
                val getHeight = app.getMethod("getMainTabBottomBarHeight")
                check(Modifier.isStatic(getInstance.modifiers) && getInstance.returnType == app)
                check(getHeight.returnType == Int::class.javaPrimitiveType)
                var instance = WeakReference<Any>(null)
                val reader: () -> Int? = {
                    runCatching {
                        val target = instance.get() ?: getInstance.invoke(null)?.also { instance = WeakReference(it) }
                        target?.let { getHeight.invoke(it) as Int }
                    }.getOrNull()
                }
                reader
            }.onFailure { Log.w("LiquidTabTieba", "Native feed bottom inset contract unavailable", it) }.getOrNull()
            if (nativeBarHeight == null) return
            register(loader, "com.baidu.tieba.homepage.tabfeed.h5.NewTabWebFragment", Role.WEB)
            register(loader, "com.baidu.tieba.homepage.tabfeed.h5.LazyNewTabWebFragment", Role.WEB)
            register(loader, "com.baidu.tieba.ala.alasquare.livefeed.AlaLiveTabFeedPageFragment", Role.LIVE)
        }

        private fun register(loader: ClassLoader, name: String, role: Role) {
            runCatching {
                val fragment = Class.forName(name, false, loader)
                val create = fragment.getDeclaredMethod(
                    "onCreateView", LayoutInflater::class.java, ViewGroup::class.java, Bundle::class.java,
                )
                check(View::class.java.isAssignableFrom(create.returnType))
                XposedBridge.hookMethod(create, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val page = param.result as? ViewGroup ?: return
                        pageRoots[page] = role
                    }
                })
            }.onFailure { Log.w("LiquidTabTieba", "Feed page lifecycle unavailable: $name", it) }
        }

        private fun isHomePagerPage(page: View): Boolean {
            val pager = page.parent as? View ?: return false
            val viewport = pager.parent as? FrameLayout ?: return false
            val host = viewport.parent as? View ?: return false
            return isType(pager, HOME_PAGER) && isType(host, HOME_HOST)
        }

        private fun isType(view: View, name: String): Boolean =
            generateSequence(view.javaClass as Class<*>) { it.superclass }.any { it.name == name }

        private fun uniqueDescendant(root: ViewGroup, matches: (View) -> Boolean): View? {
            var hit: View? = null
            val pending = ArrayDeque<View>()
            pending.add(root)
            var remaining = 96
            while (pending.isNotEmpty() && remaining-- > 0) {
                val view = pending.removeFirst()
                if (matches(view)) {
                    if (hit != null) return null
                    hit = view
                } else if (view is ViewGroup) {
                    if (pending.size + view.childCount > remaining) return null
                    for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
                }
            }
            return hit.takeIf { pending.isEmpty() }
        }
    }
}

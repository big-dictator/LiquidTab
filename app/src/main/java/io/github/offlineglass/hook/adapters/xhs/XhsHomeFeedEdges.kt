package io.github.offlineglass.hook.adapters.xhs

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.config.GlassConfig
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Trim the outside edge of the native home grids, at the decoration that adds it. */
internal object XhsHomeFeedEdges {
    private const val HOME_PAGER = "com.xingin.xhs.homepage.container.viewpager.HomeViewPager"
    private const val DISCOVERY_FEED = "com.xingin.xhs.homepage.explorefeed.mainfeed.view.FeedRecyclerView"
    private const val FOLLOW_FEED = "com.xingin.xhs.homepage.followfeed.widgets.view.FollowFeedRecyclerView"
    private const val NEARBY_PAGE = "com.xingin.xhs.homepage.localfeed.page.async.viewpage.AsyncNearByViewPageView"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binding: Binding? = null

    fun install(loader: ClassLoader, packageName: String) {
        runCatching {
            val recycler = loader.loadClass("androidx.recyclerview.widget.RecyclerView")
            val state = loader.loadClass("androidx.recyclerview.widget.RecyclerView\$State")
            val staggered = loader.loadClass("androidx.recyclerview.widget.StaggeredGridLayoutManager")
            val params = loader.loadClass("androidx.recyclerview.widget.StaggeredGridLayoutManager\$LayoutParams")
            val next = Binding(recycler, staggered, params, HookConfigReader.startupSnapshot(packageName))
            hookDecoration(loader, recycler, state, next,
                "com.xingin.redview.explorefeed.ExploreDoubleRowStaggeredDiverDecoration", nearby = false)
            hookDecoration(loader, recycler, state, next,
                "com.xingin.xhs.homepage.localfeed.page.async.recommend.NearbyDoubleRowStaggeredDiverDecoration", nearby = true)
            binding = next
        }.onFailure { XposedBridge.log("LiquidTab XHS home feed edges: $it") }
    }

    fun configChanged(config: GlassConfig) {
        val active = binding ?: return
        main.post { active.applyConfig(config) }
    }

    private fun hookDecoration(loader: ClassLoader, recycler: Class<*>, state: Class<*>, binding: Binding,
        name: String, nearby: Boolean) {
        runCatching {
            val method = loader.loadClass(name)
                .getDeclaredMethod("getItemOffsets", Rect::class.java, View::class.java, recycler, state)
            check(method.returnType == Void.TYPE)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val offsets = param.args[0] as? Rect ?: return
                    val item = param.args[1] as? View ?: return
                    val feed = param.args[2] as? ViewGroup ?: return
                    binding.trim(feed, item, offsets, nearby)
                }
            })
        }.onFailure { XposedBridge.log("LiquidTab XHS home feed decoration $name: $it") }
    }

    private class Binding(recycler: Class<*>, private val staggered: Class<*>,
        private val params: Class<*>, config: GlassConfig?) {
        private data class Scope(val root: WeakReference<View>, val insideHome: Boolean, val insideNearby: Boolean)
        private val scopes = WeakHashMap<ViewGroup, Scope>()
        private val tracked = WeakHashMap<ViewGroup, Unit>()
        private val layoutManager = recycler.getMethod("getLayoutManager")
        private val invalidateDecorations = recycler.getMethod("invalidateItemDecorations")
        private val spanCount = staggered.getMethod("getSpanCount")
        private val orientation = staggered.getMethod("getOrientation")
        private val spanIndex = params.getMethod("getSpanIndex")
        private val fullSpan = params.getMethod("isFullSpan")
        private var enabled = wantsEdges(config)

        private fun wantsEdges(config: GlassConfig?) = config?.let { it.enabled && it.xhsEdgeToEdgeFeed } == true

        fun applyConfig(config: GlassConfig) {
            val wanted = wantsEdges(config)
            if (enabled == wanted) return
            enabled = wanted
            for (feed in tracked.keys.toList()) {
                // Posted outside RecyclerView's layout pass; detached pages pick
                // up the new insets when they next attach.
                feed.post {
                    runCatching { invalidateDecorations.invoke(feed) }
                        .onFailure { XposedBridge.log("LiquidTab XHS refresh feed edges: $it") }
                }
            }
        }

        fun trim(feed: ViewGroup, item: View, offsets: Rect, nearby: Boolean) {
            if (!nearby && feed.javaClass.name != DISCOVERY_FEED && feed.javaClass.name != FOLLOW_FEED) return
            val scope = scopeOf(feed)
            if (!scope.insideHome || (nearby && !scope.insideNearby)) return
            if (!tracked.containsKey(feed)) tracked[feed] = Unit
            if (!enabled || (offsets.left <= 0 && offsets.right <= 0)) return
            val layout = item.layoutParams
            if (!params.isInstance(layout)) return
            val manager = layoutManager.invoke(feed) ?: return
            if (!staggered.isInstance(manager) || orientation.invoke(manager) != 1 || spanCount.invoke(manager) != 2) return
            if (fullSpan.invoke(layout) == true) {
                offsets.left = offsets.left.coerceAtMost(0)
                offsets.right = offsets.right.coerceAtMost(0)
                return
            }
            // StaggeredGridLayoutManager numbers vertical spans from the
            // physical left in both LTR and RTL.
            when (spanIndex.invoke(layout)) {
                0 -> offsets.left = offsets.left.coerceAtMost(0)
                1 -> offsets.right = offsets.right.coerceAtMost(0)
            }
        }

        private fun scopeOf(feed: ViewGroup): Scope {
            val root = feed.rootView
            scopes[feed]?.takeIf { it.root.get() === root }?.let { return it }
            var nearPage = false
            var home = false
            var ancestor = feed.parent as? View
            while (ancestor != null) {
                when (ancestor.javaClass.name) {
                    NEARBY_PAGE -> nearPage = true
                    HOME_PAGER -> { home = true; break }
                }
                ancestor = ancestor.parent as? View
            }
            val result = Scope(WeakReference(root), home, nearPage)
            if (feed.isAttachedToWindow) {
                scopes[feed] = result
                feed.removeOnAttachStateChangeListener(scopeReset)
                feed.addOnAttachStateChangeListener(scopeReset)
            }
            return result
        }

        private val scopeReset = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { scopes.remove(v) }
        }
    }
}

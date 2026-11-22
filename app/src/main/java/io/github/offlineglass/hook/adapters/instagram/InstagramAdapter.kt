package io.github.offlineglass.hook.adapters.instagram

import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/**
 * Instagram 底部导航是原生 LinearLayout(tab_bar)，无需自有绘制：图标由共享
 * 快照投影到玻璃行。这里只负责清理原生底栏自身与容器的像素，避免与玻璃层
 * 叠加出现实色底和分隔线鬼影。
 */
internal object InstagramAdapter : TargetAdapter {
    override val key = "instagram"
    override val supportsNativeEnglishLocale = true
    override val usesContentDarkMode = true
    override val ownsNavigationFinding = true

    // IG 原生 tab 的选中态把白色圆角色块画在 tab 容器的背景 drawable 上，
    // 会随图标一起被快照投影进玻璃行。启用共享的选中面清理
    // （clearTabSelectionSurfaces：清 slot 子树的容器表面背景，保留图标
    // 与文字），消除选中图标的底色色块。
    override val clearSourceSelectionSurfaces = true

    // IG 的框架会在 rebind 后重设 tab_bar 状态（alpha/背景），rebind 路径
    // 必须重新执行 suppress（BilibiliIn 同款开关）。
    override val suppressSourceOnHostRebind = true

    // Spotify 式滑块渲染（见 InstagramNavigationState）：滑块内无论原生
    // 状态渲染选中态并参与液态玻璃折射；滑块外无论状态渲染非选中态。
    override val ownsNavigationDrawing = true
    override val drawsNavigationInOptics = true
    override val usesNavigationOpticalMix = true
    // 静止选中态同样走玻璃折射路径：mix 恒为 1（Spotify 同款），否则松手
    // 后 mix 回落为 0，滑块内会退回前景的非选中轮廓。
    override val persistentNavigationOpticalMix = true
    override val foregroundNavigationOverSelection = true
    override val nativeSelectionReliable = true
    override fun createNavigationState(context: android.content.Context): io.github.offlineglass.hook.adapters.AppNavigationState =
        InstagramNavigationState()

    /** 最近一次 suppress 的 tab_bar，供触摸后重压使用。 */
    private var navigationRef: java.lang.ref.WeakReference<ViewGroup>? = null
    private var lastSelectionClearAt = 0L

    /**
     * IG 在 tab 切换后会把选中 tab 的背景 drawable 恢复（install 时的一次
     * 性清理不够，其余页面的选中色块由此复现）。窗口触摸抬起后延迟重压：
     * 重新 enforce alpha=0 并清一遍 slot 子树的容器表面。清理幂等且有界
     * （5 槽 × 深度 5），250ms 防抖避免信息流滑动的高频触发。
     */
    override fun onWindowTouch(host: View, event: android.view.MotionEvent) {
        if (event.actionMasked != android.view.MotionEvent.ACTION_UP) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSelectionClearAt < 250L) return
        lastSelectionClearAt = now
        val navigation = navigationRef?.get() ?: return
        host.postDelayed({
            val current = navigationRef?.get() ?: return@postDelayed
            runCatching { suppressNativeBottomChrome(current) }
        }, 150L)
    }

    // 反编译证据：451.0.0.53.80 全部 23 个 dex 中 19 个引用 SurfaceView、
    // 8 个引用 Fresco com/facebook/imagepipeline——图片（hardware bitmap）
    // 与视频（Surface 合成）内容大量位于 GPU 层，默认 root.draw() 软件采样
    // 无法捕获（captureBackdrop 的 GPU-only 盲区），玻璃栏对这部分内容失去
    // 液态玻璃与模糊。切换到 root-surface PixelCopy 直采管线；知乎/YouTube/
    // 京东已用同方案验证（YouTube 对应视频 SurfaceView，京东对应 GPU 图片）。
    // 该管线要求 adapter 提供 opticalCaptureGeometry（GlassHostLayout 对
    // direct 模式无 geometry 即跳过采样，玻璃栏会因此不渲染），沿用 YouTube
    // 的标准几何：host 边界外扩 padding 并 clamp 到 root，保留原始分辨率。
    override val usesDirectOpticalBackdropMode = true

    override fun opticalCaptureGeometry(
        rootWidth: Int, rootHeight: Int, left: Int, top: Int, width: Int, height: Int, padding: Int,
    ): io.github.offlineglass.hook.adapters.OpticalCaptureGeometry? {
        val rect = android.graphics.Rect(
            (left - padding).coerceAtLeast(0),
            (top - padding).coerceAtLeast(0),
            (left + width + padding).coerceAtMost(rootWidth),
            (top + height + padding).coerceAtMost(rootHeight),
        )
        if (rect.isEmpty) return null
        val w = rect.width()
        val h = rect.height()
        return io.github.offlineglass.hook.adapters.OpticalCaptureGeometry(
            rect, w, h, android.graphics.Rect(0, 0, w, h))
    }

    @Volatile private var feedPagerId = 0
    @Volatile private var mainContainerId = 0

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val bar = findTabBar(root) ?: return null
        val slots = countSlots(bar)
        if (slots < 2) return null
        return NavigationCandidate(bar, slots, 1000)
    }

    override fun suppressNativeBottomChrome(navigation: ViewGroup) {
        navigationRef = java.lang.ref.WeakReference(navigation)
        // IG 的 UI 框架会在自身布局 pass 里把 tab_bar 的 alpha 恢复为 1，
        // 导致原生图标与玻璃行的投影图标同时显示；每次 suppress 都重新
        // 压制（美团 TitleIndicator 同款问题：install 时的 alpha=0 不会被
        // 再次断言，必须逐 pass enforce）。
        if (navigation.alpha != 0f) navigation.alpha = 0f
        navigation.background = null
        clearTabSelectionSurfaces(navigation)
        val parent = navigation.parent as? ViewGroup ?: return
        parent.background = null
        // tab_bar 的兄弟节点 tab_bar_shadow 是 1px 分隔线，位于玻璃底栏上沿
        // 之上，采样后会留下横条鬼影，需隐藏。
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child === navigation) continue
            val idName = runCatching { navigation.resources.getResourceEntryName(child.id) }
                .getOrNull().orEmpty()
            if (idName.contains("shadow", ignoreCase = true)) {
                child.visibility = View.GONE
                child.background = null
            }
        }
        extendFeedUnderGlassBar(navigation)
    }

    /**
     * 信息流容器（swipeable_tab_view_pager / layout_container_main）被原生
     * tab_bar 的占位截断在其上沿（实测止于 y=2435）：底栏自身虽已透明，但
     * 保留的占位区露出窗口背景，形成遮挡信息流的实色块。参照 taobao 的
     * extendContentThroughNativeInset 方案：从信息流容器沿父链设
     * MATCH_PARENT 并清 bottomMargin，让内容铺满到屏幕物理底边；链上容器
     * 以小 paddingBottom 承载的手势区 inset（小白条抬高区）一并清除，使
     * 内容延伸到小白条之下、消除系统手势区的实色背景。仅到达根容器或
     * tab 容器即停；幂等：仅在参数实际变化时写回，避免每帧 requestLayout。
     */
    private fun extendFeedUnderGlassBar(navigation: ViewGroup) {
        val root = navigation.rootView as? ViewGroup ?: return
        val resources = navigation.resources
        if (feedPagerId == 0) feedPagerId = runCatching {
            resources.getIdentifier("swipeable_tab_view_pager", "id", "com.instagram.android")
        }.getOrDefault(0)
        if (mainContainerId == 0) mainContainerId = runCatching {
            resources.getIdentifier("layout_container_main", "id", "com.instagram.android")
        }.getOrDefault(0)
        if (feedPagerId != 0) {
            root.findViewById<View>(feedPagerId)?.let { extendChainToBottom(it, root) }
        }
        if (mainContainerId != 0) {
            root.findViewById<View>(mainContainerId)?.let { extendChainToBottom(it, root) }
        }
    }

    private fun extendChainToBottom(start: View, root: ViewGroup) {
        // 覆盖原生 tab 占位（实测 156px≈48dp）与手势区 inset（65px=20dp）。
        val maxInsetPx = (60f * root.resources.displayMetrics.density).toInt()
        var current: View = start
        var steps = 0
        while (steps++ < 12) {
            val parent = current.parent as? ViewGroup ?: return
            val params = current.layoutParams
            var changed = false
            if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                params.height = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (params is ViewGroup.MarginLayoutParams && params.bottomMargin != 0) {
                params.bottomMargin = 0
                changed = true
            }
            if (changed) current.layoutParams = params
            // 链上容器以小 paddingBottom 表达原生 tab 占位或系统手势区
            // inset（小白条抬高区），清除后子内容才能铺到屏幕物理底边；
            // 仅动 ≤60dp 的 inset 量级值，避免误伤业务自身的大留白。
            if (parent !== root && parent.paddingBottom in 1..maxInsetPx) {
                parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, 0)
            }
            // "满高即停"仅在布局完成后生效：首次 suppress 可能发生在布局前
            // （各高度为 0），不能据此提前终止。注意不可按"tab 父容器"停止：
            // IG 的 tab_bar 与内容 pager 同挂 layout_container_main_wrapper 下，
            // 该条件会在第一轮就截断父链（曾导致延伸止于 2591）。
            if (parent === root ||
                (parent.height > 0 && parent.height >= root.height - 1)
            ) return
            current = parent
        }
    }

    override fun suppressNativeBottomChromeOnRebind(navigation: ViewGroup) =
        suppressNativeBottomChrome(navigation)

    /**
     * 复刻 GlassInstaller.clearTabSelectionSurfaces 的 IG 版：tab_bar 的直接
     * 子项即 5 个 tab 槽，清每个槽子树（深度≤5）的容器表面——背景/tint/
     * foreground/stateListAnimator，保留 ImageView/TextView（图标与文字）。
     * 有界遍历，不做全树递归；幂等，可反复执行。
     */
    private fun clearTabSelectionSurfaces(navigation: ViewGroup) {
        for (index in 0 until navigation.childCount) {
            val item = navigation.getChildAt(index)
            val stack = ArrayDeque<Pair<View, Int>>()
            stack += item to 0
            while (stack.isNotEmpty()) {
                val (view, depth) = stack.removeLast()
                val isContainerSurface = view is ViewGroup ||
                    (view !is android.widget.ImageView && view !is android.widget.TextView &&
                        view.width >= item.width * 0.55f && view.height >= item.height * 0.45f)
                if (isContainerSurface) {
                    view.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    view.backgroundTintList = null
                    view.foreground = null
                    view.stateListAnimator = null
                }
                if (view is ViewGroup && depth < 5) {
                    for (childIndex in 0 until view.childCount) {
                        stack += view.getChildAt(childIndex) to (depth + 1)
                    }
                }
            }
        }
    }

    private fun findTabBar(root: View): ViewGroup? {
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 4_000) {
            val (view, depth) = stack.removeLast()
            if (view is ViewGroup && depth <= 14) {
                val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
                if (name == "tab_bar" && view.isShown && view.width > 0 && view.height > 0) return view
                for (i in view.childCount - 1 downTo 0) stack += view.getChildAt(i) to (depth + 1)
            }
        }
        return null
    }

    private fun countSlots(bar: ViewGroup): Int {
        val density = bar.resources.displayMetrics.density
        val threshold = 30f * density
        val centers = ArrayList<Int>()
        val barLoc = IntArray(2)
        bar.getLocationInWindow(barLoc)
        for (index in 0 until bar.childCount) {
            val child = bar.getChildAt(index)
            if (!child.isShown || child.width < 32f * density) continue
            val loc = IntArray(2)
            child.getLocationInWindow(loc)
            centers += loc[0] - barLoc[0] + child.width / 2
        }
        centers.sort()
        var distinct = 0
        var last = Int.MIN_VALUE
        centers.forEach { center ->
            if (distinct == 0 || kotlin.math.abs(center - last) >= threshold) {
                distinct++
                last = center
            }
        }
        return distinct
    }
}

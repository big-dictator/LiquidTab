package io.github.offlineglass.hook.adapters.tiktok

import android.view.Gravity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.adapters.AdapterNavigationSearch

/** Normalize actual player view placements without renamed renderer classes. */
internal object TikTokPlayerLayout {
    private var installed = false
    private var coverId = 0
    private val modal = TikTokPageLayout()
    private val media = java.util.WeakHashMap<View, Boolean>()
    private val diagnosed = java.util.WeakHashMap<View, Boolean>()
    fun owns(view: View) = media.containsKey(view)

    fun install(loader: ClassLoader) {
        if (installed) return
        TikTokAdaptationCommit.install(loader)
        val cell = Class.forName("com.ss.android.ugc.aweme.feed.adapter.VideoViewCell", false, loader)
        check(View::class.java.isAssignableFrom(cell.getDeclaredMethod("getVideoView").returnType))
        XposedBridge.hookAllMethods(cell, "getVideoView", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.result as? View ?: return
                media[view] = true
                if (view is TextureView) TikTokTextureHooks.observe(view)
                TikTokVideoCenter.prepare(view)
                val root = view.rootView
                if (root.isAttachedToWindow && diagnosed.put(root, true) == null) view.postDelayed({
                    runCatching {
                        val lines = ArrayList<String>()
                        var node: View? = view
                        for (depth in 0..20) {
                            val current = node ?: break
                            val xy = IntArray(2).also(current::getLocationInWindow)
                            val p = current.layoutParams
                            lines += "${current.javaClass.name}/${AdapterNavigationSearch.resourceEntryName(current)} xy=${xy.toList()} size=${current.width}x${current.height} lp=${p?.width}x${p?.height} pad=${current.paddingTop}/${current.paddingBottom} margin=${(p as? ViewGroup.MarginLayoutParams)?.topMargin}/${(p as? ViewGroup.MarginLayoutParams)?.bottomMargin}"
                            node = current.parent as? View
                        }
                        java.io.File(view.context.getExternalFilesDir(null), "tiktok_media_geometry.txt").writeText(lines.joinToString("\n"))
                    }
                }, 1500L)
            }
        })
        XposedBridge.hookAllMethods(View::class.java, "setLayoutParams", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (!owns(view) && view !is SurfaceView && view !is TextureView) {
                    if (coverId == 0) coverId = view.resources.getIdentifier("cover", "id", "com.zhiliaoapp.musically")
                    if (coverId == 0 || view.id != coverId) return
                }
                val parent = view.parent as? ViewGroup ?: return
                if (!owns(view) && AdapterNavigationSearch.resourceEntryName(parent) != "player_view" &&
                    view.id != coverId) return
                val original = param.args.firstOrNull() as? FrameLayout.LayoutParams ?: return
                if (original.width <= 0 || original.height <= 0 || modal.overlayVisible(view.rootView)) return
                val viewport = TikTokVideoCenter.feedViewport(view) ?: return
                param.args[0] = fit(original, viewport.first, viewport.second, tallVideoFill(view))
            }
        })
        installed = true
    }

    fun update(parent: ViewGroup) {
        if (modal.overlayVisible(parent.rootView)) return
        if (parent.clipBounds != null) parent.clipBounds = null
        for (i in 0 until parent.childCount) {
            val surface = parent.getChildAt(i)
            if (surface !is SurfaceView && surface !is TextureView) continue
            if (surface.clipBounds != null) surface.clipBounds = null
            // Native transitions own the transforms; placement is committed by setLayoutParams.
        }
    }

    fun tallVideoFill(view: View): Boolean {
        val config = io.github.offlineglass.hook.HookConfigReader.read(view.context, "com.zhiliaoapp.musically")
        return config.enabled && config.tiktokTallVideoFill
    }

    fun fit(original: FrameLayout.LayoutParams, width: Int, height: Int,
            fillTallVideo: Boolean = false): FrameLayout.LayoutParams {
        val atLeastNineSixteen = original.height.toLong() * 9 + 16 >= original.width.toLong() * 16
        val fillHeight = fillTallVideo && atLeastNineSixteen
        val scale = if (fillHeight) height.toDouble() / original.height
            else minOf(width.toDouble()/original.width, height.toDouble()/original.height)
        val h = if (fillHeight) height.coerceAtLeast(1) else (original.height*scale).toInt().coerceAtLeast(1)
        val w = (original.width*scale).toInt().coerceAtLeast(1)
        return FrameLayout.LayoutParams(original).apply {
            this.width = w; this.height = h
            gravity = Gravity.CENTER
            setMargins(0, 0, 0, 0)
        }
    }
}

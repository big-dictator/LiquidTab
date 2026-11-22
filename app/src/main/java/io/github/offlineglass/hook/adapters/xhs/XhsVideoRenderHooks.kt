package io.github.offlineglass.hook.adapters.xhs

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.WindowManager
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers

/** Use the app's own TextureView factory for this page's live glass input.
 * RedPlayerView.initAndAddRenderView branches on 1=SurfaceView, 2=TextureView.
 * A separate SurfaceView has no video pixels in a Canvas/RenderNode recording.
 * No decoder, player lifecycle, video scale or unrelated page policy is changed.
 */
internal object XhsVideoRenderHooks {
    fun install(loader: ClassLoader) {
        runCatching {
            val player = XposedHelpers.findClass("com.xingin.redplayer.ui.RedPlayerView", loader)
            check(player.getDeclaredMethod("getRenderViewSurfaceType").returnType == Int::class.javaPrimitiveType)
            XposedBridge.hookAllMethods(player, "getRenderViewSurfaceType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != 1 || !XhsVideoButtons.needsVideoPixels()) return
                    val view = param.thisObject as? View ?: return
                    if (view.javaClass.name != "com.xingin.matrix.detail.item.video.widget.VideoViewV2") return
                    val activity = activity(view.context) ?: return
                    if (activity.javaClass.name != "com.xingin.matrix.detail.activity.DetailFeedActivity") return
                    if (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) return
                    // The native factory creates/binds the TextureView itself,
                    // so existing surface callbacks and pooling stay in charge.
                    param.result = 2
                }
            })
        }.onFailure { android.util.Log.w("LiquidTabInject", "XHS texture factory unavailable", it) }
    }
    private fun activity(context: Context): Activity? {
        var current = context
        repeat(10) {
            if (current is Activity) return current as Activity
            val base = (current as? ContextWrapper)?.baseContext ?: return null
            if (base === current) return null
            current = base
        }
        return null
    }
}

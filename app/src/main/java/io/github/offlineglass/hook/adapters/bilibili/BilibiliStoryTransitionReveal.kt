package io.github.offlineglass.hook.adapters.bilibili

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** Keep native return controls behind the same reveal boundary as its video. */
internal object BilibiliStoryTransitionReveal {
    fun install(loader: ClassLoader) {
        val type = loader.loadClass("com.bilibili.video.story.StoryTransparentActivity")
        XposedBridge.hookAllMethods(type, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val root = activity.window.decorView
                fun find(name: String): View? = root.findViewById(
                    root.resources.getIdentifier(name, "id", activity.packageName))
                val pager = find("story_view_pager") ?: return
                val video = find("video_view") ?: return
                // Native onCreate hides only video, while controls can render
                // several times before its capture/reveal callback completes.
                if (video.alpha > 0f) return
                val originalAlpha = pager.alpha
                pager.alpha = 0f
                val deadline = SystemClock.uptimeMillis() + 600L
                var released = false
                val listener = object : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
                    fun release() {
                        if (released) return
                        released = true
                        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
                        root.removeOnAttachStateChangeListener(this)
                        pager.alpha = originalAlpha
                    }
                    override fun onPreDraw(): Boolean {
                        val videoReady = video.alpha > 0f && video.width > 0 && video.height > 0 &&
                            !video.isLayoutRequested && !pager.isLayoutRequested
                        if (videoReady || SystemClock.uptimeMillis() >= deadline || activity.isFinishing)
                            release()
                        return true
                    }
                    override fun onViewAttachedToWindow(view: View) = Unit
                    override fun onViewDetachedFromWindow(view: View) { release() }
                }
                root.viewTreeObserver.addOnPreDrawListener(listener)
                root.addOnAttachStateChangeListener(listener)
                // Guarantees release even for paused/static video and cancelled
                // transitions. No repeating frame pump or draw cancellation.
                root.postDelayed({ listener.release() }, 600L)
            }
        })
    }
}

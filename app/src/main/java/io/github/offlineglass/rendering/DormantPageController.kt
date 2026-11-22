package io.github.offlineglass.rendering

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Application-neutral UI dormancy; never owns/destroys a business page or worker. */
internal class DormantPageController {
    private data class Paused(val view: WeakReference<View>, val resume: Method)
    private data class Lease(val ownsLayoutPause: Boolean, val animations: List<Paused>)
    private val sleeping = WeakHashMap<ViewGroup, Lease>()

    fun sleep(root: ViewGroup) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (sleeping.containsKey(root)) return
        val paused = ArrayList<Paused>()
        val queue = ArrayDeque<View>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1024) {
            val view = queue.removeFirst()
            // Stable Lottie capability, not app class names or obfuscated fields.
            runCatching {
                val type = view.javaClass
                val running = type.getMethod("isAnimating")
                val progress = type.getMethod("getProgress")
                val pause = type.getMethod("pauseAnimation")
                val resume = type.getMethod("resumeAnimation")
                if (running.returnType == Boolean::class.javaPrimitiveType &&
                    progress.returnType == Float::class.javaPrimitiveType &&
                    pause.returnType == Void.TYPE && resume.returnType == Void.TYPE &&
                    running.invoke(view) == true) {
                    pause.invoke(view); paused += Paused(WeakReference(view), resume)
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        val owns = !root.isLayoutSuppressed
        if (owns) root.suppressLayout(true)
        sleeping[root] = Lease(owns, paused)
    }

    /** Native owner calls before foreground activation; only undo our own changes. */
    fun wake(root: ViewGroup, resumeAnimations: Boolean = true): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lease = sleeping.remove(root) ?: return false
        if (lease.ownsLayoutPause) root.suppressLayout(false)
        if (resumeAnimations) for (animation in lease.animations) {
            animation.view.get()?.takeIf { it.isAttachedToWindow }?.let {
                runCatching { animation.resume.invoke(it) }
            }
        }
        return true
    }
    fun releaseAll() { sleeping.keys.toList().forEach { wake(it) } }
}

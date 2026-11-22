package io.github.offlineglass.hook.adapters.meituan

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import io.github.offlineglass.hook.HookConfigReader
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Resource/ownership contract from the native welcome layout and skip action.
 * No application implementation class or member is used to discover or exit ads. */
internal object MeituanTakeoutSplash {
    private var installed = false
    private var bootstrapped = false
    private val sessions = WeakHashMap<Activity, WeakReference<Session>>()
    fun bootstrap() {
        if (bootstrapped) return
        bootstrapped = true
        io.github.offlineglass.hook.compat.XposedBridge.hookAllMethods(Application::class.java, "onCreate",
            object : io.github.offlineglass.hook.compat.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val application = param.thisObject as? Application ?: return
                    if (HookConfigReader.startupSnapshot(application.packageName)?.enabled == true) install(application)
                }
            })
    }
    fun install(context: Context) {
        if (!installed) {
            val application = context as? Application ?: context.applicationContext as? Application ?: return
            installed = true
            application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityPostCreated(activity: Activity, state: Bundle?) = inspect(activity)
                override fun onActivityPostResumed(activity: Activity) = inspect(activity)
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) { sessions.remove(activity)?.get()?.release() }
            })
            report(context, "lifecycle installed; awaiting verified native splash")
        }
        (context as? Activity)?.let(::inspect)
    }

    private fun inspect(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || sessions[activity]?.get() != null) return
        val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        val nodes = MeituanTakeoutBinding.tree(root, 200, 10) ?: return
        fun unique(name: String) = nodes.filter { MeituanTakeoutBinding.name(it) == name }.singleOrNull()
        val skip = unique("ll_skip") as? ViewGroup ?: return
        val timer = unique("tv_remain_time") as? TextView ?: return
        val icon = unique("img_ad_icon") as? ImageView ?: return
        val container = unique("splash_ad_container") as? ViewGroup ?: return
        // These are siblings in the native welcome merge layout; the countdown
        // and the native skip label belong to that action, not an unrelated ad.
        if (timer.parent !== skip || skip.parent !== icon.parent || skip.parent !== container.parent) return
        val labelId = activity.resources.getIdentifier("takeout_welcome_skip", "string", activity.packageName)
        if (labelId == 0) return
        val label = activity.getString(labelId)
        if ((0 until skip.childCount).map(skip::getChildAt).filterIsInstance<TextView>()
                .count { it !== timer && it.text.toString() == label } != 1) return
        val session = Session(activity, root, skip, timer, icon, container)
        sessions[activity] = WeakReference(session)
        session.start()
    }

    private class Session(activity: Activity, val root: ViewGroup, val skip: ViewGroup,
        val timer: TextView, val icon: ImageView, val container: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
        private val owner = WeakReference(activity)
        private val observer = root.viewTreeObserver
        private val handler = Handler(Looper.getMainLooper())
        private var finished = false
        private var frames = 0
        private val timeout = Runnable {
            owner.get()?.let { report(it, "native splash skip action unavailable within bounded wait") }
            release()
        }
        fun start() {
            observer.addOnPreDrawListener(this)
            handler.postDelayed(timeout, 2000L)
            attempt()
        }
        private fun attempt() {
            if (finished) return
            val activity = owner.get() ?: run { release(); return }
            if (activity.isFinishing || activity.isDestroyed ||
                !HookConfigReader.read(activity, activity.packageName).enabled) { release(); return }
            if (timer.parent !== skip || skip.parent !== icon.parent || skip.parent !== container.parent ||
                !MeituanTakeoutBinding.descendant(skip, root)) { release(); return }
            if (!skip.hasOnClickListeners() || !skip.isEnabled) return
            // performClick dispatches the existing native skip listener even
            // before its countdown becomes visible; it owns cleanup and routing.
            release()
            val clicked = skip.performClick()
            report(activity, "native splash skip dispatched=$clicked finishing=${activity.isFinishing}")
        }
        override fun onPreDraw(): Boolean {
            attempt()
            if (++frames >= 16 && !finished) timeout.run()
            return true
        }
        fun release() {
            if (finished) return
            finished = true
            if (observer.isAlive) observer.removeOnPreDrawListener(this)
            handler.removeCallbacks(timeout)
        }
    }

    private fun report(context: Context, status: String) {
        runCatching { java.io.File(context.getExternalFilesDir(null), "liquidtab-takeout-splash.txt")
            .writeText("pid=${android.os.Process.myPid()} $status\n") }
    }
}

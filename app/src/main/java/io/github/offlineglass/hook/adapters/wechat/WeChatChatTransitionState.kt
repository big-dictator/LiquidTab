package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.offlineglass.hook.GlassInstaller
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap

/** WeChat-only chat transition state. Kept dormant until its hooks are installed. */
internal object WeChatChatTransitionState {
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private const val WECHAT_CHATTING_CLASS_PREFIX = "com.tencent.mm.ui.chatting."
    private const val WECHAT_CHATTING_CONTAINER_CLASS = "com.tencent.mm.ui.tools.TestTimeForChatting"
    private val weChatChatPageProbe = WeakHashMap<Activity, WeChatChatPageProbe>()
    /**
     * A durable exit latch. A time-only arm is unsafe because WeChat can keep
     * TestTimeForChatting visible until its 234 ms animation has completed;
     * a later probe then mistakes that stale container for a fresh chat page
     * and restores the navigation inset for one frame. Keep the list immersive
     * until a ChattingUIFragment actually resumes for a new/returned chat.
     */
    private val weChatChatExiting = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private data class WeChatChatPageProbe(
        var checkedAt: Long = 0L,
        var visible: Boolean = false,
        var viaFragment: Boolean = false,
    )

    /**
     * While set (uptimeMillis), the chat page is considered gone even though
     * its exiting view may still be attached under the exit animation. Armed
     * the instant an in-app chat exit starts so the list is laid out
     * full-bleed before its first revealed frame.
     */
    private var weChatChatExitArmedUntil = 0L

    /**
     * While set (uptimeMillis), the chat page was just entered but its entry
     * animation is still bridging the transition, so the immersive (list)
     * layout is held and the lift applies only once the page has settled.
     */
    private var weChatChatEntryHoldUntil = 0L

    /**
     * LauncherUI keeps the four main tabs and the active conversation in one
     * Activity (verified on device: ChattingUIFragment in a visible
     * TestTimeForChatting container next to MainUI in a CustomViewPager).
     * Prefer WeChat's own current Fragment, with its visible TestTimeForChatting
     * container as a fallback for embedded-chat builds.
     *
     * Both transition directions are bridged so the window fitting never
     * changes mid-animation:
     * - Entry: a fresh sight of the chat page holds the immersive list layout
     *   for the entry animation; the lift applies once the page has settled.
     * - Exit: when the fragment stack switches away from the chat page, the
     *   immersive layout is re-enabled at once, while the exiting chat page
     *   still covers the list under the exit animation.
     */
    private fun isWeChatChatPageVisible(activity: Activity): Boolean {
        if (activity.javaClass.name.startsWith(WECHAT_CHATTING_CLASS_PREFIX)) return true
        val now = SystemClock.uptimeMillis()
        val probe = weChatChatPageProbe.getOrPut(activity) { WeChatChatPageProbe() }
        if (weChatChatExiting.contains(activity)) {
            probe.visible = false
            probe.viaFragment = false
            probe.checkedAt = now
            return false
        }
        if (now < weChatChatExitArmedUntil) {
            probe.visible = false
            probe.checkedAt = now
            return false
        }
        if (now - probe.checkedAt < WECHAT_CHAT_PAGE_PROBE_INTERVAL_MS) {
            return probe.visible && now >= weChatChatEntryHoldUntil
        }
        probe.checkedAt = now

        var detected = false
        var viaFragment = false
        val currentFragment = runCatching {
            XposedHelpers.callMethod(activity, "getCurrentFragmet")
        }.getOrNull()
        if (currentFragment != null && classHierarchyContainsWeChatChat(currentFragment.javaClass)) {
            detected = true
            viaFragment = true
        } else if (currentFragment != null && probe.visible && probe.viaFragment) {
            // The fragment stack switched away from the chat page: the exit
            // transaction committed at most one probe interval ago, and the
            // chat page is still covering the list under the exit animation.
            // Re-enable the immersive layout before the list's first
            // revealed frame.
            weChatChatExitArmedUntil = now + WECHAT_CHAT_EXIT_ARM_MS
            probe.visible = false
            logWeChatChatTransition("exit armed (fragment=${currentFragment.javaClass.name})")
            return false
        }
        if (!detected) {
            val root = activity.window?.decorView
            if (root != null) {
                val stack = ArrayDeque<Pair<View, Int>>()
                stack.add(root to 0)
                var visited = 0
                while (stack.isNotEmpty() && visited++ < WECHAT_CHAT_PAGE_MAX_VIEW_COUNT) {
                    val (candidate, depth) = stack.removeLast()
                    if (candidate.visibility != View.VISIBLE || candidate.alpha <= 0.01f) continue
                    if (candidate.javaClass.name == WECHAT_CHATTING_CONTAINER_CLASS &&
                        candidate.isShown && candidate.width > 0 && candidate.height > 0
                    ) {
                        detected = true
                        break
                    }
                    if (candidate is ViewGroup && depth < WECHAT_CHAT_PAGE_MAX_DEPTH) {
                        for (index in 0 until candidate.childCount) {
                            stack.add(candidate.getChildAt(index) to depth + 1)
                        }
                    }
                }
            }
        }

        if (detected && !probe.visible) {
            // First sight of the chat page — the entry animation is still
            // bridging the list-to-chat transition. Hold the immersive
            // layout; the lift applies once the page has settled.
            weChatChatEntryHoldUntil = now + WECHAT_CHAT_ENTRY_HOLD_MS
            logWeChatChatTransition(
                "entry hold armed (${if (viaFragment) "fragment" else "view"})",
            )
        }
        probe.visible = detected
        probe.viaFragment = viaFragment
        return detected && now >= weChatChatEntryHoldUntil
    }

    private fun classHierarchyContainsWeChatChat(initial: Class<*>): Boolean {
        var current: Class<*>? = initial
        repeat(12) {
            val type = current ?: return false
            if (type.name.startsWith(WECHAT_CHATTING_CLASS_PREFIX) ||
                type.name.contains("ChattingUIFragment")
            ) {
                return true
            }
            current = type.superclass
        }
        return false
    }

    /**
     * An in-app exit from the chat page is starting (fragment transaction
     * committed / system back dispatched). Switch the window back to the
     * immersive full-bleed layout NOW, before the chat list's first revealed
     * frame, so the exit animation covers the layout change instead of the
     * list flashing in the lifted position and dropping a beat later.
     */
    @Synchronized
    internal fun notifyWeChatChatExiting(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.packageName != WECHAT_PACKAGE) return
        // ChatFooter now owns the chat-only lift. Never toggle the Activity's
        // navigation fitting while LauncherUI moves between its embedded chat
        // and conversation list; doing so remeasures the whole ViewPager and
        // can leave the horizontal transition at an intermediate position.
        GlassInstaller.ensureNavigationBarImmersion(activity)
    }

    /**
     * A chat page just became active again (its fragment resumed at the
     * transaction commit). Only clears the exit arm; the scanner's own
     * transition handling arms the entry hold when it first sights the page,
     * so the lift never applies mid-animation.
     */
    @Synchronized
    internal fun notifyWeChatChatEntered(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activity.packageName != WECHAT_PACKAGE) return
        val wasExiting = weChatChatExiting.remove(activity)
        if (weChatChatExitArmedUntil != 0L) {
            weChatChatExitArmedUntil = 0L
            XposedBridge.log(
                "[OfflineGlass][WeChatChatExit] disarmed (chat re-entered, latched=$wasExiting)",
            )
        }
        // Force the next visibility pass to treat this as a fresh chat entry,
        // preserving the existing entry hold before restoring the lift.
        weChatChatPageProbe[activity]?.let { probe ->
            probe.visible = false
            probe.viaFragment = false
            probe.checkedAt = 0L
        }
    }

    private fun isWeChatImeVisible(activity: Activity): Boolean = runCatching {
        (activity.window?.decorView?.rootWindowInsets
            ?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0) > 0
    }.getOrDefault(false)

    /**
     * A back is being triggered while the chat page still holds the lifted
     * navigation fitting, and no exit is in progress yet. True at most once
     * per exit: the exit arm set by the sequencing itself (and by every other
     * exit signal) suppresses re-interception, so the re-dispatched back
     * always passes straight through to WeChat.
     */
    @Synchronized
    internal fun shouldSequenceWeChatChatBack(activity: Activity): Boolean {
        // The chat input lift no longer depends on window insets, so a delayed
        // back/inset sequence is both unnecessary and layout-destructive.
        return false
    }

    private val weChatBackRedispatchHandler = Handler(Looper.getMainLooper())
    private val weChatBackPending = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val weChatBackRedispatching = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    /**
     * Cancels the chat-page lift NOW — while the chat window itself is still
     * on screen — and re-dispatches the real back only after the dropped
     * fitting has settled, so the return-to-list animation always starts
     * from the already-cancelled lift instead of racing against it.
     */
    @Synchronized
    internal fun sequenceWeChatChatBack(activity: Activity, redispatchBack: () -> Unit) {
        if (!weChatBackPending.add(activity)) return
        val decor = activity.window.decorView
        val observer = decor.viewTreeObserver
        var completed = false
        var frameQueued = false
        var drawListener: ViewTreeObserver.OnPreDrawListener? = null
        lateinit var finish: Runnable
        finish = Runnable {
            if (completed) return@Runnable
            completed = true
            drawListener?.let { if (observer.isAlive) observer.removeOnPreDrawListener(it) }
            if (observer.isAlive) observer.unregisterFrameCommitCallback(finish)
            weChatBackRedispatchHandler.removeCallbacks(finish)
            decor.removeCallbacks(finish)
            weChatBackPending.remove(activity)
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            weChatChatExitArmedUntil = SystemClock.uptimeMillis() + WECHAT_CHAT_EXIT_ARM_MS
            weChatBackRedispatching.add(activity)
            try {
                redispatchBack()
            } finally {
                weChatBackRedispatching.remove(activity)
            }
        }
        drawListener = ViewTreeObserver.OnPreDrawListener {
            // Insets can request another layout during traversal. Wait until that
            // layout settles, let this frame draw, then return on the next vsync.
            if (!frameQueued && !decor.isLayoutRequested) {
                frameQueued = true
                if (decor.isHardwareAccelerated) {
                    observer.registerFrameCommitCallback(finish)
                } else {
                    decor.postOnAnimation(finish)
                }
            }
            true
        }
        observer.addOnPreDrawListener(drawListener)
        notifyWeChatChatExiting(activity)
        // Arm unconditionally so the re-dispatched back can never be
        // re-intercepted, even if a guard inside the notify bailed out.
        weChatChatExitArmedUntil = SystemClock.uptimeMillis() + WECHAT_CHAT_EXIT_ARM_MS
        XposedBridge.log(
            "[OfflineGlass][WeChatChatExit] sequenced back cls=${activity.javaClass.simpleName}",
        )
        decor.requestApplyInsets()
        decor.requestLayout()
        decor.invalidate()
        // Fail open if the window stops drawing; never trap the user's back.
        weChatBackRedispatchHandler.postDelayed(finish, WECHAT_CHAT_BACK_SEQUENCE_DELAY_MS)
    }

    private var lastWeChatChatTransitionMessage: String? = null
    private var lastWeChatChatTransitionTime = 0L

    /**
     * Per-activity timestamp of the last app-level onPause. Backgrounding or
     * covering the window with another activity pauses LauncherUI itself,
     * which then dispatches a fragment pause — that is NOT a chat exit and
     * the lift must survive it. An in-app back only pauses the fragment.
     */
    private val weChatHostPausedAt = WeakHashMap<Activity, Long>()

    @Synchronized
    internal fun markWeChatActivityPaused(activity: Activity) {
        if (activity.packageName != WECHAT_PACKAGE) return
        weChatHostPausedAt[activity] = SystemClock.uptimeMillis()
    }

    @Synchronized
    internal fun wasWeChatHostPausedRecently(activity: Activity): Boolean {
        val at = weChatHostPausedAt[activity] ?: return false
        return SystemClock.uptimeMillis() - at < WECHAT_HOST_PAUSE_GRACE_MS
    }

    private fun logWeChatChatTransition(message: String) {
        val now = SystemClock.uptimeMillis()
        if (message == lastWeChatChatTransitionMessage &&
            now - lastWeChatChatTransitionTime < 4000L
        ) return
        lastWeChatChatTransitionMessage = message
        lastWeChatChatTransitionTime = now
        XposedBridge.log("[OfflineGlass][WeChatChatExit] $message")
    }

    internal fun isExiting(activity: Activity): Boolean = weChatChatExiting.contains(activity)

    private const val WECHAT_CHAT_PAGE_PROBE_INTERVAL_MS = 48L
    private const val WECHAT_CHAT_PAGE_MAX_VIEW_COUNT = 2_048
    private const val WECHAT_CHAT_PAGE_MAX_DEPTH = 24
    private const val WECHAT_CHAT_EXIT_ARM_MS = 700L
    private const val WECHAT_CHAT_BACK_SEQUENCE_DELAY_MS = 300L
    private const val WECHAT_CHAT_ENTRY_HOLD_MS = 400L
    private const val WECHAT_HOST_PAUSE_GRACE_MS = 500L
}

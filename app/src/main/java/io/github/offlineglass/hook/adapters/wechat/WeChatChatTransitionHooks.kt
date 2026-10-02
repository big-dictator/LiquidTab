package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/** Preserved, currently dormant chat-exit hooks. Do not install without a separate regression pass. */
internal object WeChatChatTransitionHooks {
    @Volatile private var weChatSwallowNextBackKeyUp = false
    private const val WECHAT_TARGET_PACKAGE = "com.tencent.mm"
    private const val WECHAT_CHATTING_CONTAINER_CLASS = "com.tencent.mm.ui.tools.TestTimeForChatting"
    /**
     * Chat-page transition signals for WeChat's embedded chat screen.
     * Verified on device: the chat page is the androidx fragment
     * ChattingUIFragment inside LauncherUI (container = the visible
     * TestTimeForChatting), beside the MainUI list fragment.
     *
     * Back from the chat page is sequenced — the lift is cancelled while
     * the chat window is still on screen, and only after that settled is
     * the real back re-dispatched — through three interception points:
     * - Activity.onBackPressed: legacy / gesture back dispatch.
     * - The androidx OnBackPressedDispatcher path via
     *   HookCoordinator/GlassInstaller.notifyBackPressed (predictive back).
     * - Activity.dispatchKeyEvent for KEYCODE_BACK: WeChat's chat toolbar
     *   arrow and other injected back key events can bypass
     *   onBackPressed entirely.
     *
     * Exit detection is layered, because WeChat has several exit paths:
     * - The real back gesture (OnBackInvokedDispatcher / animated exit)
     *   pauses ChattingUIFragment at the exit commit — performPause carries
     *   the signal (an Activity-pause mark discriminates backgrounding, and
     *   isRemoving must NOT be used: WeChat never marks the chat fragment
     *   removing, verified on device).
     * - The arrow / injected keyevent paths exit silently (fragment stays
     *   RESUMED, container simply flips to GONE) — TestTimeForChatting
     *   .setVisibility(GONE) carries the signal there.
     * Its performResume clears the exit arm; the androidx Fragment class is
     * only loadable after the host app bootstraps, so its hooks are retried
     * on every WeChat activity resume.
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installWeChatNativeCloseHooks(lpparam)
        // App-level pause marker: backgrounding (or another window covering
        // LauncherUI, e.g. a profile page) pauses the Activity itself, which
        // then dispatches the fragment pause. That is not a chat exit.
        XposedBridge.hookAllMethods(Activity::class.java, "onPause", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != WECHAT_TARGET_PACKAGE) return
                WeChatChatTransitionState.markWeChatActivityPaused(activity)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onBackPressed", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != WECHAT_TARGET_PACKAGE) return
                if (WeChatChatTransitionState.shouldSequenceWeChatChatBack(activity)) {
                    // Consume this back: cancel the chat lift first (the chat
                    // window stays on screen), and re-dispatch the real back
                    // once the dropped fitting has settled.
                    param.setResult(null)
                    WeChatChatTransitionState.sequenceWeChatChatBack(activity) {
                        activity.onBackPressed()
                    }
                    return
                }
                WeChatChatTransitionState.notifyWeChatChatExiting(activity)
            }
        })
        // WeChat's chat toolbar arrow (and other injected back key events)
        // can bypass Activity.onBackPressed entirely. Intercept the
        // synthesized KEYCODE_BACK at the dispatch entry so the same
        // lift-first sequencing applies before WeChat starts its exit.
        XposedBridge.hookAllMethods(Activity::class.java, "dispatchKeyEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val event = param.args.firstOrNull() as? KeyEvent ?: return
                if (event.keyCode != KeyEvent.KEYCODE_BACK) return
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != WECHAT_TARGET_PACKAGE) return
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount != 0) return
                    weChatSwallowNextBackKeyUp = false
                    if (!WeChatChatTransitionState.shouldSequenceWeChatChatBack(activity)) return
                    param.setResult(true)
                    weChatSwallowNextBackKeyUp = true
                    WeChatChatTransitionState.sequenceWeChatChatBack(activity) {
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    }
                } else if (event.action == KeyEvent.ACTION_UP && weChatSwallowNextBackKeyUp) {
                    // The UP of the DOWN we consumed above arrives during the
                    // sequencing delay; swallow it so WeChat never sees half a
                    // back press.
                    weChatSwallowNextBackKeyUp = false
                    param.setResult(true)
                }
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (activity.packageName != WECHAT_TARGET_PACKAGE) return
                maybeHookWeChatFragmentLifecycle(activity)
            }
        })
        // TestTimeForChatting does not override setVisibility, so the hook sits
        // on the base View method and filters by the exact class name.
        XposedBridge.hookAllMethods(View::class.java, "setVisibility", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val visibility = param.args.firstOrNull() as? Int ?: return
                if (visibility != View.GONE && visibility != View.INVISIBLE) return
                val view = param.thisObject as? View ?: return
                if (view.javaClass.name != WECHAT_CHATTING_CONTAINER_CLASS) return
                var context = view.context
                while (context is android.content.ContextWrapper && context !is Activity) {
                    context = context.baseContext
                }
                val activity = context as? Activity ?: return
                if (activity.packageName != WECHAT_TARGET_PACKAGE) return
                logWeChatFragmentEvent("container setVisibility GONE")
                WeChatChatTransitionState.notifyWeChatChatExiting(activity)
            }
        })
    }

    @Volatile
    private var weChatFragmentLifecycleHooked = false

    private fun installWeChatNativeCloseHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Verified against the local APK: NewChattingTabUI.f(boolean) invokes
        // onExitBegin/onExitEnd BEFORE startAnimation. Delay the whole method,
        // not startAnimation alone (by then the list has already resumed).
        val entries = listOf(
            "com.tencent.mm.ui.LauncherUI" to "closeChatting",
            "com.tencent.mm.ui.NewChattingTabUI" to "f",
            "com.tencent.mm.ui.conversation.BaseConversationUI" to "closeChatting",
        )
        for ((className, methodName) in entries) {
            val type = runCatching { XposedHelpers.findClass(className, lpparam.classLoader) }
                .getOrNull() ?: continue
            val method = type.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)) &&
                    (it.returnType == Void.TYPE || it.returnType == Boolean::class.javaPrimitiveType)
            } ?: continue
            XposedBridge.hookAllMethods(type, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size != 1 || param.args[0] !is Boolean) return
                    val owner = param.thisObject ?: return
                    val activity = (owner as? Activity) ?: type.declaredFields.firstNotNullOfOrNull { field ->
                        if (!Activity::class.java.isAssignableFrom(field.type)) null
                        else runCatching { field.isAccessible = true; field.get(owner) as? Activity }.getOrNull()
                    } ?: return
                    if (!WeChatChatTransitionState.shouldSequenceWeChatChatBack(activity)) return
                    val args = param.args.clone()
                    param.setResult(if (method.returnType == Void.TYPE) null else true)
                    WeChatChatTransitionState.sequenceWeChatChatBack(activity) {
                        // The sequence's redispatch guard bypasses this hook;
                        // keep WeChat's exact method and original needAnim flag.
                        method.isAccessible = true
                        method.invoke(owner, *args)
                    }
                }
            })
        }
    }

    private fun maybeHookWeChatFragmentLifecycle(activity: Activity) {
        if (weChatFragmentLifecycleHooked) return
        val fragmentClass = runCatching {
            XposedHelpers.findClass("androidx.fragment.app.Fragment", activity.classLoader)
        }.getOrNull() ?: return
        weChatFragmentLifecycleHooked = true
        val hookedCounts = mutableListOf<String>()
        for (methodName in listOf("performPause", "performResume")) {
            val isPause = methodName == "performPause"
            val hooks = XposedBridge.hookAllMethods(
                fragmentClass,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fragment = param.thisObject ?: return
                        if (!fragment.javaClass.name.contains("ChattingUIFragment")) return
                        val host = runCatching {
                            XposedHelpers.callMethod(fragment, "getActivity") as? Activity
                        }.getOrNull() ?: return
                        if (isPause) {
                            // WeChat's own back handling pauses the chat fragment
                            // WITHOUT marking it removing (verified on device),
                            // so isRemoving cannot be the exit discriminator.
                            // An app-level pause (fresh mark below) instead means
                            // backgrounding or a covered window — keep the lift.
                            if (WeChatChatTransitionState.wasWeChatHostPausedRecently(host)) {
                                logWeChatFragmentEvent("performPause skipped (host paused)")
                                return
                            }
                            logWeChatFragmentEvent("performPause exit signal")
                            WeChatChatTransitionState.notifyWeChatChatExiting(host)
                        } else {
                            WeChatChatTransitionState.notifyWeChatChatEntered(host)
                        }
                    }
                },
            )
            if (hooks.isNotEmpty()) hookedCounts.add("$methodName=${hooks.size}")
        }
        XposedBridge.log(
            "[OfflineGlass][WeChatChatExit] fragment hooks installed: $hookedCounts",
        )
    }

    private var lastWeChatFragmentEventMessage: String? = null
    private var lastWeChatFragmentEventTime = 0L

    private fun logWeChatFragmentEvent(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (message == lastWeChatFragmentEventMessage &&
            now - lastWeChatFragmentEventTime < 4000L
        ) return
        lastWeChatFragmentEventMessage = message
        lastWeChatFragmentEventTime = now
        XposedBridge.log("[OfflineGlass][WeChatChatExit] event $message")
    }

}

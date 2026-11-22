package io.github.offlineglass.hook.adapters.bilibili_in

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers
import io.github.offlineglass.hook.adapters.bilibili.BilibiliCommentGlass
import io.github.offlineglass.hook.adapters.bilibili.BilibiliStoryCommentHooks
import io.github.offlineglass.hook.adapters.bilibili.BilibiliDetailInsetsHooks

/** International ABI entry points; optics and animation remain in the shared renderer. */
internal object BilibiliInCommentHooks {
    private val composing = ThreadLocal.withInitial { false }
    private val textBackground = ThreadLocal.withInitial { false }

    fun install(loader: ClassLoader) {
        BilibiliDetailInsetsHooks.install(loader, BilibiliInAdapter.PACKAGE_NAME)
        BilibiliStoryCommentHooks.install(loader, BilibiliInAdapter.PACKAGE_NAME, nativeActivityComments = true,
            nativeCommentLayout = BilibiliInCommentConstraints::extend)
        runCatching {
            val input = loader.loadClass("kntr.common.comment.page.ui.M")
            val entry = input.declaredMethods.single { it.name == "a" && it.parameterTypes.size == 10 }
            check(entry.parameterTypes[4].name == "androidx.compose.ui.j" &&
                entry.parameterTypes[8].name == "androidx.compose.runtime.Composer")
            val colors = loader.loadClass("Tt1.a")
            val keys = loader.loadClass("kntr.common.comment.common.theme.CommentColorKey")
            val inputKey = keys.getField("INPUT_BAR_BACKGROUND").get(null)
            val transparent = listOf("SECTION_BACKGROUND", "INPUT_BAR_BACKGROUND", "CARD_DIVIDER")
                .map { keys.getField(it).get(null) }
            val page = keys.getField("PAGE_BACKGROUND").get(null)
            val foreground = keys.getField("SORT").get(null)
            BilibiliCommentGlass.install(loader, international = true)
            XposedBridge.hookAllMethods(colors, "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (composing.get() != true) return
                    val key = param.args.firstOrNull()
                    if (key === inputKey) textBackground.set(true)
                    if (transparent.any { it === key }) param.result = 0L
                    else if (key === foreground) BilibiliCommentGlass.foregroundColor()?.let { param.result = it }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() === page) (param.result as? Long)?.let(BilibiliCommentGlass::pageColor)
                }
            })
            XposedBridge.hookAllMethods(input, entry.name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    composing.set(true)
                    BilibiliCommentGlass.begin(param.args)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    BilibiliCommentGlass.end(); composing.remove(); textBackground.remove()
                }
            })
            XposedBridge.hookAllMethods(loader.loadClass("androidx.compose.foundation.r"), "b", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (composing.get() != true || textBackground.get() != true || param.args.size != 3) return
                    textBackground.set(false)
                    param.result = BilibiliCommentGlass.decorateInternational(param.args[0], false) ?: param.args[0]
                }
            })
            val size = loader.loadClass("d0.K0")
            XposedBridge.hookAllMethods(size, "s", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (composing.get() == true && param.args.size == 2 && param.args[1] == 44f)
                        param.result = BilibiliCommentGlass.decorateInternational(param.result, true) ?: param.result
                }
            })
            XposedBridge.hookAllMethods(size, "k", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (composing.get() == true && param.args.size == 4 && param.args[0] == 30f) param.args[0] = 44f
                }
            })
            XposedBridge.hookAllMethods(loader.loadClass("d0.w0"), "m", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (composing.get() == true && param.args.size == 6 && param.args[1] == 12f && param.args[5] == 10) {
                        param.args[1] = 0f; param.args[3] = 0f
                    }
                }
            })
            // Scope the live backdrop to the comment list, never the player or input branch.
            XposedBridge.hookAllMethods(loader.loadClass("kntr.common.compose.pulltorefresh.g"), "a", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size != 9) return
                    val view = BilibiliCommentGlass.internationalView(param.args[6]) ?: return
                    var ancestor: View? = view
                    while (ancestor != null) {
                        if (runCatching { ancestor!!.resources.getResourceEntryName(ancestor!!.id) }.getOrNull() == "comment_container") {
                            param.args[1] = BilibiliCommentGlass.viewport(view, param.args[1]); return
                        }
                        ancestor = ancestor.parent as? View
                    }
                }
            })
            XposedBridge.hookAllMethods(Activity::class.java, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    if (activity.javaClass.name == "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity")
                        BilibiliCommentGlass.touchActivity(activity, param.args[0] as MotionEvent)
                }
            })
        }.onFailure { XposedBridge.log("LiquidTab international comments: $it") }
    }
}

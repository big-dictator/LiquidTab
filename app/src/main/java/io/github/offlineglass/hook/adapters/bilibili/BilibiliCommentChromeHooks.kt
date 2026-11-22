package io.github.offlineglass.hook.adapters.bilibili

import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** Domestic 9.13 Compose comment input: keep only its native text and emoji button. */
internal object BilibiliCommentChromeHooks {
    private val inputCompositionDepth = ThreadLocal.withInitial { 0 }

    fun install(loader: ClassLoader) {
        BilibiliCommentGlass.install(loader)
        runCatching {
            val binding = BilibiliCommentBinding.current ?: return@runCatching
            if (!BilibiliCommentGlass.available()) return@runCatching
            val composition = binding.input
            val colorReader = binding.colors
            val keyClass = colorReader.parameterTypes.single()
            val backdropKeys = listOf("SECTION_BACKGROUND", "INPUT_BAR_BACKGROUND", "CARD_DIVIDER").map {
                keyClass.getDeclaredField(it).apply { isAccessible = true }.get(null)
            }
            val pageKey = keyClass.getDeclaredField("PAGE_BACKGROUND").apply { isAccessible = true }.get(null)
            val foregroundKey = keyClass.getDeclaredField("SORT").apply { isAccessible = true }.get(null)
            XposedBridge.hookMethod(colorReader, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.firstOrNull() === pageKey) (param.result as? Long)?.let(BilibiliCommentGlass::pageColor)
                }
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((inputCompositionDepth.get() ?: 0) <= 0 || param.args.size != 1) return
                    if (backdropKeys.any { it === param.args[0] }) {
                        // Compose's packed sRGB transparent color; text/icon tokens are untouched.
                        param.result = 0L
                    } else if (param.args[0] === foregroundKey) {
                        BilibiliCommentGlass.foregroundColor()?.let { param.result = it }
                    }
                }
            })
            XposedBridge.hookMethod(composition, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    inputCompositionDepth.set((inputCompositionDepth.get() ?: 0) + 1)
                    BilibiliCommentGlass.begin(param.args)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    BilibiliCommentGlass.end()
                    val depth = (inputCompositionDepth.get() ?: 0) - 1
                    if (depth <= 0) inputCompositionDepth.remove() else inputCompositionDepth.set(depth)
                }
            })
            XposedBridge.log("[LiquidTab/BilibiliCommentChrome] input backdrop hooks installed")
        }.onFailure {
            XposedBridge.log("[LiquidTab/BilibiliCommentChrome] unsupported comment UI: $it")
        }
    }
}

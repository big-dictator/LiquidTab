package io.github.offlineglass.hook.adapters.bilibili

import android.content.Context
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import io.github.offlineglass.hook.compat.XposedBridge

/** All domestic Compose-comment fixes consume this one verified native call graph. */
internal object BilibiliCommentBinding {
    data class Binding(val input: Method, val colors: Method, val capsule: Method, val emoji: Method, val list: Method)
    var current: Binding? = null; private set
    private var prepared = false
    fun prepare(context: Context, loader: ClassLoader) {
        if (prepared) return
        prepared = true
        runCatching {
            val ref = BilibiliCommentIndex.resolve(context.applicationInfo.sourceDir,
                java.io.File(context.codeCacheDir, "liquidtab-bilibili-comments-v2.properties").path)
            fun type(name: String): Class<*> = when (name) {
                "V" -> Void.TYPE; "I" -> Int::class.javaPrimitiveType!!
                "J" -> Long::class.javaPrimitiveType!!; "F" -> Float::class.javaPrimitiveType!!
                "Z" -> Boolean::class.javaPrimitiveType!!
                else -> Class.forName(name.removePrefix("L").removeSuffix(";").replace('/', '.'), false, loader)
            }
            fun resolve(method: BilibiliCommentIndex.Ref): Method {
                val owner = type(method.owner())
                return owner.getDeclaredMethod(method.name(), *method.args().map(::type).toTypedArray()).apply {
                    check(returnType == type(method.result()) && !Modifier.isAbstract(modifiers))
                    isAccessible = true
                }
            }
            val binding = Binding(resolve(ref.input()), resolve(ref.colors()), resolve(ref.capsule()), resolve(ref.emoji()), resolve(ref.list()))
            check(Modifier.isStatic(binding.input.modifiers) && Modifier.isStatic(binding.capsule.modifiers) && Modifier.isStatic(binding.emoji.modifiers))
            val keys = binding.colors.parameterTypes.single()
            check(keys.isEnum && setOf("SECTION_BACKGROUND", "INPUT_BAR_BACKGROUND", "CARD_DIVIDER", "SORT", "PAGE_BACKGROUND")
                .all { name -> keys.getDeclaredField(name).type == keys })
            // AOT may inline the small color/modifier helpers into the native composition.
            // Only these two verified callers need to see their hooked callees again.
            XposedBridge.framework().deoptimize(binding.input)
            XposedBridge.framework().deoptimize(binding.list)
            current = binding
            XposedBridge.log("[LiquidTab/BilibiliCommentBinding] verified input, color, buttons and list call graph")
            context.getExternalFilesDir(null)?.resolve("liquidtab-comment-binding.txt")?.writeText(binding.toString())
        }.onFailure {
            XposedBridge.log("[LiquidTab/BilibiliCommentBinding] capability mismatch: $it")
            context.getExternalFilesDir(null)?.resolve("liquidtab-comment-binding.txt")?.writeText(it.stackTraceToString())
        }
    }
}

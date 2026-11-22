package io.github.offlineglass.hook.adapters.bilibili_in

import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.reflect.Method

/** Use the target's own ConstraintSet record reader/writer; never name private layout fields. */
internal object BilibiliInCommentConstraints {
    private data class Api(val set: Class<*>, val connect: Method, val getters: List<Method>, val read: Method, val write: Method)
    private val bindings = HashMap<Pair<Class<*>, Class<*>>, Api?>()
    fun extend(main: View, bar: ViewGroup) {
        val parent = main.parent as? ViewGroup ?: return
        if (bar.parent !== parent || main.id == View.NO_ID) return
        if (main.height > 0 && main.bottom == parent.height) return
        val params = main.layoutParams ?: return
        if (params is RelativeLayout.LayoutParams) {
            // Native reply pages use Android's public relative-layout protocol:
            // retain BELOW(title), remove only ABOVE(input), align to the parent end.
            params.removeRule(RelativeLayout.ABOVE)
            params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE)
            main.layoutParams = params
            return
        }
        val key = parent.javaClass to params.javaClass
        if (!bindings.containsKey(key)) bindings[key] = runCatching {
            val setter = parent.javaClass.methods.single { it.name == "setConstraintSet" && it.parameterTypes.size == 1 }
            val set = setter.parameterTypes.single()
            val ints = Array(5) { Int::class.javaPrimitiveType }
            val connect = set.methods.single { !java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.returnType == Void.TYPE && it.parameterTypes.contentEquals(ints) }
            // Native record ownership is established by both inverse LayoutParams contracts.
            val getters = set.methods.filter { method ->
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                    method.returnType.declaredMethods.any { it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(params.javaClass)) } &&
                    method.returnType.declaredMethods.any { it.returnType == Void.TYPE &&
                        it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, params.javaClass)) }
            }
            check(getters.isNotEmpty() && getters.map { it.returnType }.distinct().size == 1)
            val record = getters.first().returnType
            val read = record.declaredMethods.single { it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, params.javaClass)) }.apply { isAccessible = true }
            val write = record.declaredMethods.single { it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(params.javaClass)) }.apply { isAccessible = true }
            Api(set, connect, getters, read, write)
        }.onFailure { XposedBridge.log("LiquidTab international native comment constraints unavailable: $it") }.getOrNull()
        val api = bindings[key] ?: return
        runCatching {
            val set = api.set.getDeclaredConstructor().newInstance()
            // Native ConstraintSet protocol: BOTTOM=4, PARENT_ID=0. Seed a record
            // before calling read-only getters, then require identical ownership.
            api.connect.invoke(set, main.id, 4, 0, 4, 0)
            val records = api.getters.map { it.invoke(set, main.id) }
            val record = records.first() ?: error("Missing constraint record")
            check(records.all { it === record })
            api.read.invoke(record, main.id, params)
            api.connect.invoke(set, main.id, 4, 0, 4, 0)
            api.write.invoke(record, params)
            main.layoutParams = params
        }.onFailure {
            bindings[key] = null
            XposedBridge.log("LiquidTab international native comment constraints rejected: $it")
        }
    }
}

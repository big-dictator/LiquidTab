package io.github.offlineglass.hook.adapters.bilibili_in

import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** Coordinator's behavior contract survives R8 even when its accessor names do not. */
internal class BilibiliInBehaviorAccess private constructor(
    private val field: Field, private val setter: Method
) {
    fun get(params: ViewGroup.LayoutParams): Any? = field.get(params)
    fun set(params: ViewGroup.LayoutParams, value: Any?) {
        val previous = writing.get()
        writing.set(true)
        try { setter.invoke(params, value) } finally {
            if (previous == null) writing.remove() else writing.set(previous)
        }
    }

    fun observeNativeWrites() {
        if (setter in observed) return
        XposedBridge.hookMethod(setter, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (writing.get() != true) BilibiliInHomeLayoutHooks.nativeBehaviorChanged(param.thisObject, param.args[0])
            }
        })
        observed += setter
    }

    companion object {
        private val cache = HashMap<Class<*>, BilibiliInBehaviorAccess?>()
        private val observed = HashSet<Method>()
        private val writing = ThreadLocal<Boolean>()
        fun resolve(params: ViewGroup.LayoutParams): BilibiliInBehaviorAccess? {
            if (cache.containsKey(params.javaClass)) return cache[params.javaClass]
            val result = discover(params)
            cache[params.javaClass] = result
            return result
        }
        private fun discover(params: ViewGroup.LayoutParams): BilibiliInBehaviorAccess? {
            fun behavior(type: Class<*>) = type.methods.any { m ->
                m.name == "onLayoutChild" && m.returnType == Boolean::class.javaPrimitiveType &&
                    m.parameterCount == 3 && ViewGroup::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    View::class.java.isAssignableFrom(m.parameterTypes[1]) && m.parameterTypes[2] == Int::class.javaPrimitiveType
            } && type.methods.any { m ->
                m.name == "onMeasureChild" && m.returnType == Boolean::class.javaPrimitiveType &&
                    m.parameterCount == 6 && ViewGroup::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                    View::class.java.isAssignableFrom(m.parameterTypes[1]) &&
                    m.parameterTypes.drop(2).all { it == Int::class.javaPrimitiveType }
            }
            val fields = generateSequence(params.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !Modifier.isStatic(it.modifiers) && behavior(it.type) }.toList()
            val field = fields.singleOrNull() ?: return null
            val setters = params.javaClass.methods.filter { m ->
                !Modifier.isStatic(m.modifiers) && !m.isBridge && !m.isSynthetic &&
                    m.returnType == Void.TYPE && m.parameterCount == 1 && m.parameterTypes[0] == field.type
            }
            // Invoke the native setter: it also clears behavior-owned tags and resolves lifecycle state.
            val setter = setters.singleOrNull() ?: return null
            return BilibiliInBehaviorAccess(field.apply { isAccessible = true }, setter.apply { isAccessible = true })
        }
    }
}

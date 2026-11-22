package io.github.offlineglass.hook.adapters.reddit

import android.view.View
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Composite-layer ownership for a verified Reddit-only unary navigation branch. */
internal object RedditNativeLayer {
    private data class Mask(val coordinator: Any,val field: java.lang.reflect.Field,val setter: java.lang.reflect.Method,
        val functionType: Class<*>,val alpha: java.lang.reflect.Method,var original: Any?,var proxy: Any?=null)
    private val guarded=WeakHashMap<Any,java.lang.ref.WeakReference<Mask>>()
    private val setterHooks=HashSet<java.lang.reflect.Method>()
    private fun guard(mask: Mask) {
        guarded[mask.coordinator]=java.lang.ref.WeakReference(mask)
        if(!setterHooks.add(mask.setter))return
        io.github.offlineglass.hook.compat.XposedBridge.hookMethod(mask.setter,object: io.github.offlineglass.hook.compat.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val active=guarded[param.thisObject]?.get() ?: return
                val proxy=active.proxy ?: return
                if(param.args[0]!==proxy) { active.original=param.args[0];param.args[0]=proxy }
            }
        })
    }
    private val masks=java.util.IdentityHashMap<Any,Mask>()
    private val retired=ArrayList<Pair<java.lang.ref.WeakReference<Any>,java.lang.ref.WeakReference<Mask>>>()
    private val alphaSetters=HashMap<Class<*>,java.lang.reflect.Method>()
    private fun apply(mask: Mask) {
        val current=mask.field.get(mask.coordinator)
        if(mask.proxy!=null && current===mask.proxy)return
        mask.original=current
        val invoke=mask.functionType.getMethod("invoke",Any::class.java)
        val proxy=java.lang.reflect.Proxy.newProxyInstance(mask.functionType.classLoader,arrayOf(mask.functionType)) { self,method,args ->
            when(method.name) {
                "invoke" -> {
                    val scope=args?.firstOrNull()
                    val result=mask.original?.let { invoke.invoke(it,scope) }
                    // Native rendering is permanently disabled. Business state
                    // comes from raw semantics; artwork is recorded independently.
                    if(scope!=null)mask.alpha.invoke(scope,0f)
                    result
                }
                "equals" -> self===args?.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "RedditNativeBarMask"
                else -> null
            }
        }
        mask.proxy=proxy
        guard(mask)
        mask.setter.invoke(mask.coordinator,proxy,false)
    }
    private fun mask(node: Any,type: Class<*>,drawParams: Array<Class<*>>): Mask? {
        retired.removeAll { it.first.get()==null || it.second.get()==null }
        retired.firstOrNull { it.first.get()===node }?.second?.get()?.let {
            retired.removeAll { entry -> entry.first.get()===node };masks[node]=it;return it
        }
        masks[node]?.let { return it }
        fun isCoordinator(value: Any)=!type.isInstance(value) && value.javaClass.fields.any { it.type==type } &&
            value.javaClass.methods.any { it.returnType==Void.TYPE && it.parameterTypes.contentEquals(drawParams) }
        fun owner(value: Any)=value.javaClass.fields.singleOrNull { it.type==type }?.get(value)
        val candidates=Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
        for(field in type.fields.filterNot { Modifier.isStatic(it.modifiers) }) {
            val value=field.get(node) ?: continue
            if(value is View || type.isInstance(value))continue
            if(isCoordinator(value) && owner(value)===node)candidates+=value
            for(nested in value.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) }) {
                val child=runCatching { nested.get(value) }.getOrNull() ?: continue
                if(isCoordinator(child) && owner(child)===node)candidates+=child
            }
        }
        val coordinator=if(candidates.size==1)candidates.first() else candidates.singleOrNull { candidate ->
            candidate.javaClass.fields.filterNot { Modifier.isStatic(it.modifiers) }.any { field ->
                val value=runCatching { field.get(candidate) }.getOrNull()
                value!=null && isCoordinator(value) && owner(value)!==node
            }
        } ?: return null
        val setter=coordinator.javaClass.methods.singleOrNull { method ->
            method.returnType==Void.TYPE && method.parameterCount==2 &&
                method.parameterTypes[1]==Boolean::class.javaPrimitiveType &&
                method.parameterTypes[0].isInterface && method.parameterTypes[0].methods.any {
                    it.name=="invoke" && it.parameterTypes.contentEquals(arrayOf(Any::class.java)) }
        } ?: return null
        // The Placeable base also owns two Function1 measurement callbacks.
        // Only NodeCoordinator, which declares this setter, owns layerBlock.
        val field=setter.declaringClass.declaredFields.singleOrNull {
            !Modifier.isStatic(it.modifiers) && it.type==setter.parameterTypes[0]
        }?.apply { isAccessible=true } ?: return null
        // Resolve GraphicsLayerScope structurally. Its alpha setter marks bit 4;
        // probe fresh scope objects, never the live global scope or app state.
        val scopeType=coordinator.javaClass.fields.filter { Modifier.isStatic(it.modifiers) }.map { it.type }.distinct().singleOrNull { t ->
            t.fields.count { it.type==Float::class.javaPrimitiveType }>=8 &&
                t.methods.count { it.returnType==Void.TYPE && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType)) }>=8
        } ?: return null
        val alpha=alphaSetters[scopeType] ?: scopeType.methods.filter { method ->
            method.returnType==Void.TYPE && method.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))
        }.singleOrNull { method ->
            val fresh=scopeType.getConstructor().newInstance()
            method.invoke(fresh,0f)
            scopeType.fields.any { it.type==Int::class.javaPrimitiveType && it.getInt(fresh)==4 }
        }?.also { alphaSetters[scopeType]=it } ?: return null
        return Mask(coordinator,field,setter,field.type,alpha,field.get(coordinator)).also { masks[node]=it }
    }
    fun bind(node: Any, type: Class<*>, drawParams: Array<Class<*>>): Boolean {
        val value=mask(node,type,drawParams) ?: return false
        apply(value)
        return true
    }
    fun clear(node: Any) {
        val value=masks.remove(node) ?: return
        guarded.remove(value.coordinator)
        if(value.field.get(value.coordinator)===value.proxy)
            value.setter.invoke(value.coordinator,value.original,false)
    }
    fun isMasked(node: Any): Boolean = masks[node]?.let { it.proxy!=null && it.field.get(it.coordinator)===it.proxy } == true
    fun retire(node: Any) {
        val value=masks.remove(node) ?: return
        retired.removeAll { it.first.get()==null || it.second.get()==null }
        retired+=java.lang.ref.WeakReference(node) to java.lang.ref.WeakReference(value)
    }
    fun restoreRetired() {
        retired.forEach { (_,ref) -> ref.get()?.let { value ->
            guarded.remove(value.coordinator)
            if(value.field.get(value.coordinator)===value.proxy)
                value.setter.invoke(value.coordinator,value.original,false)
        } }
        retired.clear()
    }
}

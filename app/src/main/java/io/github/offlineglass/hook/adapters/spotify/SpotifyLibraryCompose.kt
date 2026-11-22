package io.github.offlineglass.hook.adapters.spotify

import android.view.View
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/** Resolve the live Compose nodes from Android virtual IDs, without R8 names. */
internal object SpotifyLibraryCompose {
    private data class Mask(val coordinator: Any,val field: java.lang.reflect.Field,val setter: java.lang.reflect.Method,
        val functionType: Class<*>,val alpha: java.lang.reflect.Method,var original: Any?,var proxy: Any?=null,
        val consumers: WeakHashMap<Any,Boolean> = WeakHashMap())
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
    fun invalidate(owner: View) {
        runCatching {
            val root=owner.javaClass.getMethod("getRoot").invoke(owner) ?: return
            owner.javaClass.methods.singleOrNull { method ->
                Modifier.isStatic(method.modifiers) && method.returnType==Void.TYPE &&
                    method.parameterTypes.contentEquals(arrayOf(root.javaClass))
            }?.invoke(null,root)
            owner.invalidate()
        }
    }
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
                    // Compose's native coordinator treats alpha <= 0 as an
                    // invisible semantics subtree (verified in the target APK).
                    // Keep logical visibility while producing zero framebuffer
                    // contribution: this positive normal float is far below even
                    // a 16-bit alpha quantum. No unmask/re-mask or draw hooks.
                    if(scope!=null)mask.alpha.invoke(scope,java.lang.Float.MIN_NORMAL)
                    result
                }
                "equals" -> self===args?.firstOrNull()
                "hashCode" -> System.identityHashCode(self)
                "toString" -> "SpotifyLibraryHeaderMask"
                else -> null
            }
        }
        mask.proxy=proxy
        guard(mask)
        mask.setter.invoke(mask.coordinator,proxy,false)
    }
    fun maintain(nodes: List<Any>) { nodes.forEach { masks[it]?.let { mask -> apply(mask) } } }
    // A replaced header stays masked until collected; dropping bookkeeping does
    // not briefly repaint that old native header during Compose's transition.
    fun forget(nodes: List<Any>,consumer: Any) {
        retired.removeAll { it.first.get()==null || it.second.get()==null }
        nodes.forEach { node -> masks[node]?.let { mask ->
            mask.consumers.remove(consumer)
            if(mask.consumers.isEmpty()) {
                masks.remove(node)
                retired+=java.lang.ref.WeakReference(node) to java.lang.ref.WeakReference(mask)
            }
        } }
    }
    fun clearRetired() {
        retired.forEach { (_,ref) -> ref.get()?.let { mask -> runCatching {
            guarded.remove(mask.coordinator)
            if(mask.field.get(mask.coordinator)===mask.proxy)mask.setter.invoke(mask.coordinator,mask.original,false)
        } } }
        retired.clear()
    }
    fun clear(nodes: List<Any>,consumer: Any) {
        nodes.forEach { node -> masks[node]?.let { mask ->
            mask.consumers.remove(consumer)
            if(mask.consumers.isNotEmpty())return@forEach
            masks.remove(node)
            runCatching {
            guarded.remove(mask.coordinator)
            if(mask.field.get(mask.coordinator)===mask.proxy)mask.setter.invoke(mask.coordinator,mask.original,false)
        } } }
    }
    private fun mask(node: Any,type: Class<*>,drawParams: Array<Class<*>>): Mask? {
        retired.firstOrNull { it.first.get()===node }?.second?.get()?.let {
            masks[node]=it;retired.removeAll { entry -> entry.first.get()===node };return it
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
    private data class NodeContract(val parent: java.lang.reflect.Method?,val draw: java.lang.reflect.Method)
    private val nodeContracts=android.util.LruCache<Class<*>,NodeContract>(24)
    private fun contract(type: Class<*>): NodeContract? {
        nodeContracts[type]?.let { return it }
        val methods=type.methods
        val draw=methods.singleOrNull { method ->
            method.returnType==Void.TYPE && method.parameterCount==2 && method.parameterTypes[0].isInterface &&
                method.parameterTypes[0].methods.any { it.parameterTypes.contentEquals(arrayOf(FloatArray::class.java)) } &&
                method.parameterTypes[0].methods.any { it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Int::class.javaPrimitiveType)) }
        } ?: return null
        return NodeContract(methods.singleOrNull { it.parameterCount==0 && it.returnType==type },draw)
            .also { nodeContracts.put(type,it) }
    }
    fun bindVisual(owner: View,ids: List<Int>,consumer: Any): List<Any>? = runCatching {
        val snapshot=SpotifyComposeNodes.snapshot(owner) ?: return null
        val root=snapshot.root;val type=root.javaClass
        val targets=ids.distinct().map { snapshot.node(it) ?: return null }
        if(targets.isEmpty() || targets.any { it===root })return null
        val draw=contract(type)?.draw ?: return null
        val added=targets.filter { !masks.containsKey(it) }
        if(targets.any { mask(it,type,draw.parameterTypes)==null }) { clear(added,consumer);return null }
        targets.forEach { masks[it]?.consumers?.put(consumer,true) }
        maintain(targets)
        if(added.isNotEmpty())invalidate(owner)
        targets
    }.onFailure { android.util.Log.w("SpotifyGlass","Compose visual binding failed",it) }.getOrNull()
    fun bind(data: SpotifyLibraryBinding.Result,consumer: Any): List<Any>? = runCatching {
        val owner=data.owner
        val snapshot=SpotifyComposeNodes.snapshot(owner) ?: return null
        val root=snapshot.root
        val type=root.javaClass
        fun node(id: Int)=snapshot.node(id)
        val binding=contract(type) ?: return null
        val parent=binding.parent ?: return null
        fun chain(node: Any): List<Any> {
            val result=ArrayList<Any>();var current: Any?=node
            while(current!=null && result.size<64 && result.none { it===current }) { result+=current;current=parent.invoke(current) }
            return result
        }
        val header=node(data.headerId) ?: return null
        val filters=node(data.filtersId) ?: return null
        val feed=if(data.feedId!=View.NO_ID)node(data.feedId) else null
        val headerChain=chain(header);val filterChain=chain(filters);val feedChain=feed?.let { chain(it) }.orEmpty()
        if(feed!=null)SpotifyLibraryViewport.bind(owner,feed,feedChain,data.feedBounds)
        // Missing scrolling semantics during filtering is not a page exit.
        // Keep the verified page inset until a live measure rebinds the list;
        // explicit disable/landscape/activity teardown still clear it.
        val common=if(feed==null)null else headerChain.lastOrNull { h -> filterChain.any { it===h } && feedChain.none { it===h } }
        val targets=if(common!=null && common!==root)listOf(common) else listOf(header,filters).distinct()
        val draw=binding.draw
        val added=targets.filter { !masks.containsKey(it) }
        try {
            if(targets.any { mask(it,type,draw.parameterTypes)==null }) { clear(added,consumer);return null }
            targets.forEach { masks[it]?.consumers?.put(consumer,true) }
            maintain(targets)
            if(added.isNotEmpty())invalidate(owner)
        } catch(error: Throwable) { clear(added,consumer);throw error }
        targets
    }.onFailure { android.util.Log.w("SpotifyGlass","Library layer binding failed",it) }.getOrNull()
}

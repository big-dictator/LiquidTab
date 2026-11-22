package io.github.offlineglass.hook.adapters.spotify

import android.view.View
import java.lang.reflect.*
import java.util.Collections
import kotlin.math.abs

/** Keep the verified native top list constraint beneath reconstructed controls. */
internal object SpotifyLibraryViewport {
    private data class Ref(val target: Any,val field: Field,val value: Any)
    private data class Change(val ref: Ref,val proxy: Any)
    private data class State(var feed: Any,val owner: View,val changes: MutableList<Change>,
        val outer: Ref,val top: Method,val safe: Float,val paddingNode: Any?,var lifted: Boolean=false)
    private val traces=linkedSetOf<String>()
    private fun trace(owner: View,message: String) {
        if(traces.size>=24 || !traces.add(message))return
        runCatching { java.io.File(owner.context.getExternalFilesDir(null),"spotify_library_scope.txt").writeText(traces.joinToString("\n")) }
    }
    private val states=java.util.IdentityHashMap<Any,State>()
    private data class Failure(val owner: View,val top: Int)
    private val failures=java.util.IdentityHashMap<Any,Failure>()
    private val fieldCache=android.util.LruCache<Class<*>,List<Field>>(96)
    private fun fields(type: Class<*>): List<Field> {
        fieldCache[type]?.let { return it }
        val result=ArrayList<Field>();var current: Class<*>?=type
        while(current!=null && current!=Any::class.java && !current.name.startsWith("java.")) {
            result+=current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.onEach { runCatching { it.isAccessible=true } }
            current=current.superclass
        }
        fieldCache.put(type,result);return result
    }
    private fun padding(type: Class<*>)=type.isInterface && type.methods.count { it.returnType==Float::class.javaPrimitiveType && it.parameterCount==0 }==2 &&
        type.methods.count { it.returnType==Float::class.javaPrimitiveType && it.parameterCount==1 && it.parameterTypes[0].isEnum }==2
    private fun refs(target: Any)=fields(target.javaClass).filter { padding(it.type) }.mapNotNull { field ->
        runCatching { field.get(target)?.let { Ref(target,field,it) } }.getOrNull()
    }
    private fun modifierBase(type: Class<*>): Class<*>? {
        var current: Class<*>?=type
        while(current!=null && current!=Any::class.java) {
            val declared=current.declaredFields
            if(Modifier.isAbstract(current.modifiers) && declared.count { it.type==current }>=3 && declared.count { it.type==Int::class.javaPrimitiveType }>=2 &&
                declared.any { it.type==Boolean::class.javaPrimitiveType })return current
            current=current.superclass
        }
        return null
    }
    private fun modifierRefs(node: Any,nodeType: Class<*>): List<Ref> {
        val queue=java.util.ArrayDeque<Any>();val seen=Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
        for(field in fields(nodeType)) {
            val value=runCatching { field.get(node) }.getOrNull() ?: continue
            if(value is View || nodeType.isInstance(value))continue
            if(modifierBase(value.javaClass)!=null)queue.add(value)
            else for(child in fields(value.javaClass)) {
                val item=runCatching { child.get(value) }.getOrNull() ?: continue
                if(modifierBase(item.javaClass)!=null)queue.add(item)
            }
        }
        val found=ArrayList<Ref>()
        while(queue.isNotEmpty() && seen.size<128) {
            val value=queue.removeFirst();if(!seen.add(value))continue
            if(value.javaClass.methods.any { it.parameterCount==3 && it.parameterTypes[2]==Long::class.javaPrimitiveType && !it.returnType.isPrimitive })found+=refs(value)
            val base=modifierBase(value.javaClass) ?: continue
            for(field in fields(value.javaClass).filter { base.isAssignableFrom(it.type) })
                runCatching { field.get(value) }.getOrNull()?.let { queue.add(it) }
        }
        return found
    }
    private fun request(owner: View,node: Any) {
        if(!owner.isAttachedToWindow || node.javaClass.fields.none { field ->
            !Modifier.isStatic(field.modifiers) && field.type.isInterface && runCatching { field.get(node)===owner }.getOrDefault(false)
        })return
        owner.javaClass.methods.singleOrNull { it.returnType==Void.TYPE && it.parameterTypes.contentEquals(
            arrayOf(node.javaClass,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType))
        }?.invoke(owner,node,false,true,true)
        trace(owner,"request node="+System.identityHashCode(node)+" methods="+owner.javaClass.methods.count { it.returnType==Void.TYPE && it.parameterTypes.contentEquals(arrayOf(node.javaClass,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)) })
        owner.requestLayout();owner.invalidate()
    }
    private fun proxy(ref: Ref,top: Method,value: Float): Any=Proxy.newProxyInstance(ref.field.type.classLoader,arrayOf(ref.field.type)) { self,method,args ->
        when {
            method.name=="equals" -> self===args?.firstOrNull()
            method.name=="hashCode" -> System.identityHashCode(self)
            method.name=="toString" -> "SpotifyLibraryScrollingInsets"
            method.name==top.name && method.parameterCount==0 -> value
            else -> method.invoke(ref.value,*(args ?: emptyArray()))
        }
    }
    fun bind(owner: View,feed: Any,chain: List<Any>,bounds: android.graphics.Rect): Boolean {
        // Filtering recreates the lazy list inside the same page/outer inset.
        // Retain its verified safe distance after the viewport has already lifted.
        val retained=states.values.singleOrNull { it.owner===owner }
        if(retained!=null && retained.feed!==feed && retained.feed.javaClass==feed.javaClass &&
            retained.outer.field.get(retained.outer.target)===retained.changes[0].proxy) {
            states.remove(retained.feed)
            retained.changes.drop(1).forEach { old -> runCatching {
                if(old.ref.field.get(old.ref.target)===old.proxy)old.ref.field.set(old.ref.target,old.ref.value)
            } }
            while(retained.changes.size>1)retained.changes.removeAt(retained.changes.lastIndex)
            retained.feed=feed;states[feed]=retained
            owner.post { if(states[feed]===retained && owner.isAttachedToWindow)request(owner,feed) }
        }
        states.entries.filter { !it.value.owner.isAttachedToWindow || (it.value.owner===owner && it.key!==feed) }.toList().forEach { (key,old) ->
            old.changes.forEach { change -> runCatching {
                if(change.ref.field.get(change.ref.target)===change.proxy)change.ref.field.set(change.ref.target,change.ref.value)
            } };states.remove(key)
        }
        failures.entries.removeAll { !it.value.owner.isAttachedToWindow || (it.value.owner===owner && it.key!==feed) }
        states[feed]?.let { state ->
            if(state.outer.field.get(state.outer.target)===state.changes[0].proxy) {
                val inner=state.changes.getOrNull(1)
                if(inner!=null && inner.ref.field.get(inner.ref.target)!==inner.proxy) {
                    state.changes.removeAt(1)
                    owner.post { if(states[feed]===state && owner.isAttachedToWindow)request(owner,feed) }
                }
                return true
            }
            clear(owner)
        }
        failures[feed]?.let { if(it.top==bounds.top)return false }
        return runCatching {
        val location=IntArray(2);owner.getLocationOnScreen(location)
        val expected=(bounds.top-location[1])/owner.resources.displayMetrics.density
        if(expected<=0f)return@runCatching false
        val outer=chain.flatMap { modifierRefs(it,feed.javaClass) }.distinctBy { it.target to it.field }.mapNotNull { ref ->
            ref.field.type.methods.filter { it.parameterCount==0 && it.returnType==Float::class.javaPrimitiveType }
                .singleOrNull { abs((it.invoke(ref.value) as Float)-expected)*owner.resources.displayMetrics.density<=2f }?.let { ref to it }
        }.singleOrNull() ?: return@runCatching false
        trace(owner,"outer="+outer.first.target.javaClass.name+" pad="+outer.first.value.javaClass.name+" values="+outer.first.field.type.methods.filter { it.parameterCount==0 && it.returnType==Float::class.javaPrimitiveType }.map { it.name+"="+it.invoke(outer.first.value) })
        val top=outer.second
        val topValue=top.invoke(outer.first.value) as Float
        val density=owner.resources.displayMetrics.density
        val status=owner.rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())?.top ?: 0
        // Preserve the real native list constraint. Never remove it in advance
        // of an unverified captured-lambda/content-padding substitution.
        val safeContentTop=maxOf(topValue,(status+118f*density-location[1])/density)
        val changes=mutableListOf(Change(outer.first,proxy(outer.first,top,safeContentTop)))
        try { changes.forEach { it.ref.field.set(it.ref.target,it.proxy) } }
        catch(error: Throwable) { changes.forEach { runCatching { it.ref.field.set(it.ref.target,it.ref.value) } };throw error }
        val paddingOwner=fields(outer.first.target.javaClass).mapNotNull { field ->
            val coordinator=runCatching { field.get(outer.first.target) }.getOrNull() ?: return@mapNotNull null
            coordinator.javaClass.fields.singleOrNull { it.type==feed.javaClass }?.let { runCatching { it.get(coordinator) }.getOrNull() }
        }.firstOrNull()
        states[feed]=State(feed,owner,changes,outer.first,top,safeContentTop,paddingOwner)
        SpotifyLazyMeasureHooks.ensure(owner,outer.first.field.type) {
            if(owner.isAttachedToWindow) {
                if(paddingOwner!=null)request(owner,paddingOwner)
                request(owner,feed)
            }
        }
        if(paddingOwner!=null)request(owner,paddingOwner)
        request(owner,feed)
        android.util.Log.i("SpotifyGlass","Library native top safety retained: $topValue -> $safeContentTop dp")
        true
        }.onSuccess { success ->
            if(!success) {
                failures[feed]=Failure(owner,bounds.top)
                android.util.Log.i("SpotifyGlass","Library viewport shape unresolved; keep native constraints")
            }
        }.onFailure {
            failures[feed]=Failure(owner,bounds.top)
            android.util.Log.w("SpotifyGlass","Library viewport unavailable",it)
        }.getOrDefault(false)
    }
    /** Called only from the verified native lazy measurement, never from draw. */
    fun measuring(target: Any,field: Field,scope: Any?): (() -> Unit)? {
        scope ?: return null
        if(states.values.any { state -> state.changes.getOrNull(1)?.let { change ->
            change.ref.target===target && change.ref.field==field && field.get(target)===change.proxy
        }==true })return null
        val matches=states.values.mapNotNull { state ->
            if(!state.owner.isAttachedToWindow || field.type!=state.outer.field.type)return@mapNotNull null
            val type=state.feed.javaClass
            val values=listOf(scope)+fields(scope.javaClass).mapNotNull { runCatching { it.get(scope) }.getOrNull() }
            val nodes=Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
            // Native measure scope owns a SubcomposeMeasureScope, which owns
            // SubcomposeLayoutState -> LayoutNode (verified native call chain).
            val scopes=values.filter { value -> value !is View && value.javaClass.methods.any { method ->
                method.returnType==List::class.java && method.parameterCount==2 &&
                    method.parameterTypes[0]==Any::class.java && method.parameterTypes[1].isInterface &&
                    method.parameterTypes[1].methods.any { it.name=="invoke" && it.parameterTypes.contentEquals(arrayOf(Any::class.java,Any::class.java)) && it.returnType==Any::class.java }
            } }
            scopes.forEach { nativeScope ->
                val holders=listOf(nativeScope)+fields(nativeScope.javaClass).mapNotNull { runCatching { it.get(nativeScope) }.getOrNull() }
                holders.filterNot { it is View }.forEach { holder ->
                    fields(holder.javaClass).filter { it.type==type }.forEach { f ->
                        runCatching { f.get(holder) }.getOrNull()?.let { nodes.add(it) }
                    }
                }
            }
            val node=nodes.singleOrNull() ?: return@mapNotNull null
            val parent=type.methods.singleOrNull { it.parameterCount==0 && it.returnType==type } ?: return@mapNotNull null
            val oldAttached=fields(type).any { f -> f.type.isInterface && runCatching { f.get(state.feed)===state.owner }.getOrDefault(false) }
            if(node!==state.feed && oldAttached)return@mapNotNull null
            var current: Any?=node;var contained=false;var depth=0
            while(current!=null && depth++<32) {
                if(current===state.feed || (!oldAttached && state.paddingNode!=null && current===state.paddingNode)) { contained=true;break }
                current=parent.invoke(current)
            }
            if(contained)state to node else null
        }
        val (state,node)=matches.singleOrNull() ?: return null
        if(state.feed!==node) {
            states.remove(state.feed)
            state.feed=node;states[node]=state
        }
        val active=state.changes.drop(1).singleOrNull()
        if(active?.ref?.target===target && active.ref.field==field && field.get(target)===active.proxy)return null
        // The padding we change is proved by this scope -> coordinator -> feed
        // relationship, not merely its presence somewhere in a captured graph.
        val native=field.get(target) ?: return null
        val ref=Ref(target,field,native)
        val inside=proxy(ref,state.top,(state.top.invoke(native) as Float)+state.safe)
        field.set(target,inside)
        state.changes.drop(1).forEach { old ->
            if(old.ref.field.get(old.ref.target)===old.proxy)old.ref.field.set(old.ref.target,old.ref.value)
        }
        while(state.changes.size>1)state.changes.removeAt(state.changes.lastIndex)
        state.changes+=Change(ref,inside)
        return {
            if(!state.lifted && states[state.feed]===state && state.owner.isAttachedToWindow) {
                val outside=proxy(state.outer,state.top,0f)
                state.outer.field.set(state.outer.target,outside)
                state.changes[0]=Change(state.outer,outside);state.lifted=true
                trace(state.owner,"commit outer="+state.top.invoke(state.outer.field.get(state.outer.target))+" paddingNode="+state.paddingNode?.javaClass?.name+" sameFeed="+(state.paddingNode===state.feed)+" measureNodeSame="+(node===state.feed))
                runCatching { java.io.File(state.owner.context.getExternalFilesDir(null),"spotify_library_viewport.txt")
                    .writeText("time=${System.currentTimeMillis()} native_measure_committed safeTopDp=${state.safe} measure=${target.javaClass.name}") }
                state.owner.post {
                    if(states[state.feed]===state && state.owner.isAttachedToWindow) {
                        state.paddingNode?.let { request(state.owner,it) }
                        request(state.owner,node)
                    }
                }
            } else state.owner.post {
                if(states[state.feed]===state && state.owner.isAttachedToWindow)request(state.owner,node)
            }
        }
    }
    fun clear(owner: View) {
        failures.entries.removeAll { it.value.owner===owner }
        states.entries.filter { it.value.owner===owner }.toList().forEach { (key,state) ->
            state.changes.forEach { change -> runCatching {
                if(change.ref.field.get(change.ref.target)===change.proxy)change.ref.field.set(change.ref.target,change.ref.value)
            } }
            states.remove(key);runCatching { request(owner,state.feed) }
        }
    }
}

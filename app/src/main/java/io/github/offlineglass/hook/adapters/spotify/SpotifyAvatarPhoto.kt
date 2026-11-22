package io.github.offlineglass.hook.adapters.spotify

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.content.Context
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/** Bounded inspection of the verified avatar's own draw modifiers, not a page screenshot. */
internal object SpotifyAvatarPhoto {
    var failure="not probed";private set
    private val fieldCache=android.util.LruCache<Class<*>,List<Field>>(96)
    private fun fields(type: Class<*>): List<Field> {
        fieldCache[type]?.let { return it }
        val result=ArrayList<Field>();var current: Class<*>?=type
        while(current!=null && current!=Any::class.java && !current.name.startsWith("java.")) {
            result+=current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.type.isPrimitive || it.type==String::class.java }
                .filter { runCatching { it.isAccessible=true;true }.getOrDefault(false) }
            current=current.superclass
        }
        fieldCache.put(type,result);return result
    }
    private fun values(value: Any)=fields(value.javaClass).mapNotNull { runCatching { it.get(value) }.getOrNull() }
    private data class ModifierKind(val base: Class<*>?)
    private val modifierKinds=android.util.LruCache<Class<*>,ModifierKind>(192)
    private fun modifier(type: Class<*>): Class<*>? {
        modifierKinds[type]?.let { return it.base }
        var current: Class<*>?=type
        while(current!=null && current!=Any::class.java) {
            val f=current.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            if(Modifier.isAbstract(current.modifiers) && f.count { it.type==current }>=3 && f.count { it.type==Int::class.javaPrimitiveType }>=2 &&
                f.any { it.type==Boolean::class.javaPrimitiveType }) {
                modifierKinds.put(type,ModifierKind(current));return current
            }
            current=current.superclass
        }
        modifierKinds.put(type,ModifierKind(null))
        return null
    }
    private val painterKinds=android.util.LruCache<Class<*>,ModifierKind>(192)
    private fun painterPhoto(value: Any): Pair<Boolean,Bitmap?> {
        val kind=painterKinds[value.javaClass] ?: run {
            var current: Class<*>?=value.javaClass;var base: Class<*>?=null
            while(current!=null && current!=Any::class.java) {
                if(Modifier.isAbstract(current.modifiers) && current.declaredMethods.any { it.returnType==Void.TYPE && it.parameterCount==1 &&
                    it.parameterTypes[0].name=="androidx.compose.ui.graphics.drawscope.DrawScope" })base=current
                current=current.superclass
            }
            ModifierKind(base).also { painterKinds.put(value.javaClass,it) }
        }
        val painter=kind.base ?: return false to null
        val queue=java.util.ArrayDeque<Any>();queue.add(value)
        val seen=Collections.newSetFromMap(IdentityHashMap<Any,Boolean>())
        val images=Collections.newSetFromMap(IdentityHashMap<Bitmap,Boolean>())
        while(queue.isNotEmpty() && seen.size<16) {
            val current=queue.removeFirst();if(!seen.add(current))continue
            for(child in values(current)) {
                when {
                    child is Bitmap -> images+=child
                    child is BitmapDrawable -> images+=child.bitmap
                    painter.isInstance(child) -> queue.add(child)
                    else -> {
                        // Read the state accessor used by AsyncImagePainter.draw;
                        // never descend through its request/loader/coroutine graph.
                        val stateMethods=child.javaClass.methods
                        val mutableState=stateMethods.any { it.name=="setValue" && it.returnType==Void.TYPE &&
                            it.parameterTypes.contentEquals(arrayOf(Any::class.java)) }
                        val getter=stateMethods.singleOrNull { mutableState &&
                            it.name=="getValue" && it.parameterCount==0 && it.returnType==Any::class.java
                        }
                        val active=runCatching { getter?.invoke(child) }.getOrNull()
                        if(active!=null && painter.isInstance(active))queue.add(active)
                        val wrapper=fields(child.javaClass)
                        wrapper.singleOrNull()?.takeIf { it.type==Bitmap::class.java }?.let { field ->
                            (runCatching { field.get(child) }.getOrNull() as? Bitmap)?.let { images+=it }
                        }
                    }
                }
            }
        }
        return true to images.filterNot { it.isRecycled }.singleOrNull()
    }
    fun read(avatar: SpotifyHeaderBinding.Button): Bitmap? {
        val snapshot=SpotifyComposeNodes.snapshot(avatar.source) ?: run { failure=SpotifyComposeNodes.failure;return null }
        val action=snapshot.node(avatar.virtualId) ?: run { failure="avatar action semantics id missing";return null }
        val anchor=if(avatar.profileHeaderOwner)snapshot.root else snapshot.node(avatar.artworkId) ?: action
        val nodeLimit=if(avatar.profileHeaderOwner)64 else 16
        val type=snapshot.root.javaClass
        val nodes=Collections.newSetFromMap(IdentityHashMap<Any,Boolean>())
        val queue=java.util.ArrayDeque<Any>();queue.add(anchor)
        val pictures=Collections.newSetFromMap(IdentityHashMap<Bitmap,Boolean>())
        val seenModifiers=Collections.newSetFromMap(IdentityHashMap<Any,Boolean>())
        val inspected=Collections.newSetFromMap(IdentityHashMap<Any,Boolean>())
        fun picture(bitmap: Bitmap) {
            if(!bitmap.isRecycled && bitmap.width>=24 && bitmap.height>=24 &&
                bitmap.width.toFloat()/bitmap.height in .5f..2f)pictures+=bitmap
        }
        val ordered=ArrayList<Any>()
        while(queue.isNotEmpty() && nodes.size<nodeLimit) {
            val node=queue.removeFirst();if(!nodes.add(node))continue
            ordered+=node
            val members=values(node)
            // LayoutNode -> folded-child holder -> mutable vector.
            // Validate count, live node types and each child's parent backlink.
            // The holder is not the vector itself (the previous code missed it).
            val containers=members.filterNot { type.isInstance(it) || it is View || it is Context }
            SpotifyAvatarChildren.read(node,type,containers).forEach { queue.add(it) }
        }
        // Inspect draw-owned fields, leaf first. Modifier.Node's inherited
        // lifecycle/coordinator/coroutine graph is not image content.
        for(node in ordered.asReversed()) {
            val members=values(node)
            val modifiers=java.util.ArrayDeque<Any>()
            members.filter { !type.isInstance(it) && modifier(it.javaClass)!=null }.forEach { modifiers.add(it) }
            members.filterNot { type.isInstance(it) || it is View || it is Context }.forEach { wrapper ->
                values(wrapper).filter { !type.isInstance(it) && modifier(it.javaClass)!=null }.forEach { modifiers.add(it) }
            }
            while(modifiers.isNotEmpty() && seenModifiers.size<96 && inspected.size<384) {
                val item=modifiers.removeFirst();if(!seenModifiers.add(item))continue
                val base=modifier(item.javaClass) ?: continue
                values(item).filter { base.isInstance(it) }.forEach { modifiers.add(it) }
                val owned=fields(item.javaClass).filter { it.declaringClass!=base && base.isAssignableFrom(it.declaringClass) }
                    .mapNotNull { runCatching { it.get(item) }.getOrNull() }
                // Native Image uses a PainterModifier with a direct Painter
                // field. Do not scan arbitrary payload/request/text-style graphs:
                // besides unrelated pixels, those exhausted the budget before
                // the actual image modifier in this small verified header.
                for(value in owned) {
                    if(base.isInstance(value) || !inspected.add(value))continue
                    val painter=painterPhoto(value)
                    if(painter.first)painter.second?.let { picture(it) }
                }
            }
        }
        failure="photo candidates=${pictures.size}, profile owner=${avatar.profileHeaderOwner}, avatar nodes=${nodes.size}, draw modifiers=${seenModifiers.size}, objects=${inspected.size}"
        if(pictures.isEmpty()) {
            val shape=values(anchor).filterNot { it is View || it is Context || type.isInstance(it) }.take(20).joinToString { holder ->
                val nested=listOf(holder)+values(holder).filterNot { it is View || it is Context || type.isInstance(it) }.take(8)
                holder.javaClass.simpleName+":"+nested.joinToString("/") { value ->
                    val arrays=fields(value.javaClass).mapNotNull { field ->
                        runCatching { field.get(value) }.getOrNull()?.takeIf { it.javaClass.isArray }?.let {
                            it.javaClass.componentType?.simpleName+"["+java.lang.reflect.Array.getLength(it)+"]"
                        }
                    }
                    value.javaClass.simpleName+arrays.joinToString("+")
                }
            }
            failure+="; anchor="+anchor.javaClass.simpleName+"; children="+shape+"; modifiers="+
                seenModifiers.take(24).joinToString { it.javaClass.simpleName }+"; painters="+
                inspected.filter { painterKinds[it.javaClass]?.base!=null }.take(8).joinToString { it.javaClass.simpleName }
        }
        return pictures.singleOrNull()
    }
}

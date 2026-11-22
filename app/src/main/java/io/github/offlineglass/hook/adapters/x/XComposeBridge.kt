package io.github.offlineglass.hook.adapters.x

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Reflection is restricted to the DEX-verified Haze draw contract and typed Compose APIs. */
internal class XComposeBridge(val bindings: XBindingResolver.Bindings) {
    val drawMethod=requireNotNull(bindings.methods["hazeDraw"])
    private val boundsMethod=requireNotNull(bindings.methods["coordinateBounds"])
    val coordinateType=boundsMethod.parameterTypes[0]
    private val scopeType=drawMethod.parameterTypes.single()
    val contentMethod=requireNotNull(bindings.methods["drawContent"]) { "drawContent: semantic draw-pass contract missing" }.also {
        require(it.declaringClass==scopeType && it.parameterCount==0 && it.returnType==Void.TYPE)
    }
    private val scopeNodeField=scopeType.declaredFields.single {
        !Modifier.isStatic(it.modifiers) && it.type.isInterface && it.type.isAssignableFrom(drawMethod.declaringClass)
    }.apply { isAccessible=true }
    private val relativeTransform=coordinateType.methods.single {
        it.parameterTypes.contentEquals(arrayOf(coordinateType,FloatArray::class.java)) && it.returnType==Void.TYPE
    }
    private val coordinateRoot=requireNotNull(bindings.methods["coordinateRoot"])
    private val coordinateSize=requireNotNull(bindings.methods["coordinateSize"]) { "coordinateSize: bounds call relationship missing" }.also {
        require(it.declaringClass==coordinateType && it.parameterCount==0 && it.returnType==Long::class.javaPrimitiveType)
    }
    private val stateType=requireNotNull(bindings.methods["replySurface"]) { "reply Haze surface contract missing" }.parameterTypes.single {
        it.name.startsWith("dev.chrisbanes.haze.")
    }
    private val replyStates=java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any,Boolean>())
    private val stateFields by lazy { fields(drawMethod.declaringClass).filter { it.type==stateType }.also {
        require(it.isNotEmpty()) { "Haze effect has no reply-state dependency" }
    } }
    fun rememberReplyState(state: Any) { require(stateType.isInstance(state));replyStates.add(state) }
    fun ownsReply(node: Any): Boolean = stateFields.any { it.get(node)?.let(replyStates::contains)==true }
    fun size(coordinate: Any): Pair<Int,Int> {
        val size=coordinateSize.invoke(coordinate) as Long
        return (size shr 32).toInt() to size.toInt()
    }
    fun overlayTransform(coordinate: Any,owner: View,overlayHost: View): Matrix {
        val root=coordinateRoot.invoke(null,coordinate) ?: error("Detached root coordinates")
        val native=transform(coordinate,root)
        val viewGlobal=Matrix().also(owner::transformMatrixToGlobal)
        val hostGlobal=Matrix().also(overlayHost::transformMatrixToGlobal)
        val inverse=Matrix()
        require(hostGlobal.invert(inverse))
        val viewToHost=Matrix().apply { setConcat(inverse,viewGlobal) }
        return Matrix().apply { setConcat(viewToHost,native) }
    }
    private val fieldCache=ConcurrentHashMap<Class<*>,List<Field>>()
    private var drawAccess: DrawAccess?=null
    private val rectFields=mutableMapOf<Class<*>,List<Field>>()
    private val coordinateField=fields(drawMethod.declaringClass).filter { coordinateType.isAssignableFrom(it.type) }
        .singleOrNull { it.declaringClass.name.startsWith("androidx.compose.ui.") }
        ?: error("No unique Modifier.Node coordinator")
    private val shapeType=bindings.methods["backgroundFactory"]?.parameterTypes?.getOrNull(2)
    private val outlineMethod=shapeType?.methods?.singleOrNull { it.parameterCount==3 && it.parameterTypes[0]==Long::class.javaPrimitiveType }
    private var cornerKey: Pair<Any,Long>?=null
    private var cornerRadius=0f
    data class DrawAccess(val canvas: List<Field>,val size: List<Field>)
    data class Frame(val canvas: Canvas,val width: Float,val height: Float,val size: Long)
    data class Shape(val path: Path,val radius: Float)
    fun fields(type: Class<*>): List<Field> = fieldCache.getOrPut(type) {
        generateSequence(type) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .filter { !Modifier.isStatic(it.modifiers) }.onEach { it.isAccessible=true }.toList()
    }
    private fun value(target: Any,path: List<Field>): Any? {
        var current: Any?=target
        for(field in path) current=current?.let(field::get)
        return current
    }
    fun frame(scope: Any): Frame? {
        if(drawAccess==null) {
            data class Entry(val target: Any,val path: List<Field>)
            val queue=ArrayDeque<Entry>();queue.add(Entry(scope,emptyList()))
            val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
            while(queue.isNotEmpty() && seen.size<64) {
                val item=queue.removeFirst()
                if(!seen.add(item.target) || item.path.size>5) continue
                val all=fields(item.target.javaClass)
                val canvas=all.singleOrNull { Canvas::class.java.isAssignableFrom(it.type) }
                if(canvas!=null) {
                    val parentPath=item.path.dropLast(1)
                    val params=value(scope,parentPath) ?: continue
                    val size=fields(params.javaClass).singleOrNull { it.type==Long::class.javaPrimitiveType }
                    if(size!=null) { drawAccess=DrawAccess(item.path+canvas,parentPath+size);break }
                }
                all.filter { !it.type.isPrimitive && it.type.name.startsWith("androidx.compose.ui.graphics.") }
                    .forEach { f -> f.get(item.target)?.let { queue.add(Entry(it,item.path+f)) } }
            }
        }
        val access=drawAccess ?: return null
        val canvas=value(scope,access.canvas) as? Canvas ?: return null
        val size=value(scope,access.size) as? Long ?: return null
        val w=Float.fromBits((size ushr 32).toInt());val h=Float.fromBits(size.toInt())
        return if(w.isFinite() && h.isFinite() && w>0 && h>0) Frame(canvas,w,h,size) else null
    }
    fun coordinator(node: Any): Any? = coordinateField.get(node)
    fun isDrawing(scope: Any,node: Any): Boolean = scopeNodeField.get(scope)===node
    fun shape(node: Any,scope: Any,frame: Frame): Shape {
        val outline=outlineMethod ?: return Shape(Path(),0f)
        val start=coordinator(node) ?: return Shape(Path(),0f)
        val ownerField=fields(start.javaClass).singleOrNull { field ->
            field.type.name.startsWith("androidx.compose.ui.node.") && field.type.declaredMethods.any {
                it.parameterCount==0 && it.returnType.name.startsWith("androidx.compose.runtime.collection.")
            }
        } ?: return Shape(Path(),0f)
        val owner=ownerField.get(start)
        val key=start to frame.size
        if(cornerKey==key) return Shape(Path(),cornerRadius)
        val queue=ArrayDeque<Pair<Any,Int>>();queue.add(start to 0)
        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
        while(queue.isNotEmpty() && seen.size<24) {
            val (position,depth)=queue.removeFirst()
            if(!seen.add(position) || depth>3) continue
            for(field in fields(position.javaClass)) {
                val value=field.get(position) ?: continue
                if(coordinateType.isAssignableFrom(field.type)) {
                    if(ownerField.declaringClass.isInstance(value) && ownerField.get(value)===owner) queue.add(value to depth+1)
                    continue
                }
                if(field.type!=shapeType) continue
                val direction=scope.javaClass.methods.singleOrNull { it.parameterCount==0 && it.returnType==outline.parameterTypes[1] }?.invoke(scope) ?: continue
                val nativeOutline=runCatching { outline.invoke(value,frame.size,direction,scope) }.getOrNull() ?: continue
                for(part in fields(nativeOutline.javaClass)) {
                    if(!part.type.name.startsWith("androidx.compose.ui.geometry.")) continue
                    val roundRect=part.get(nativeOutline) ?: continue
                    val corners=fields(roundRect.javaClass).filter { it.type==Long::class.javaPrimitiveType }.map { it.getLong(roundRect) }
                    if(corners.size!=4) continue
                    val radii=corners.flatMap { listOf(Float.fromBits((it ushr 32).toInt()),Float.fromBits(it.toInt())) }
                    if(radii.all { it.isFinite() && it>0 && kotlin.math.abs(it-radii.first())<.1f }) {
                        cornerKey=key;cornerRadius=radii.first()
                        return Shape(Path(),cornerRadius)
                    }
                }
            }
        }
        return Shape(Path(),0f)
    }
    fun bounds(coordinate: Any): RectF? {
        val rect=runCatching { boundsMethod.invoke(null,coordinate,false) }.getOrNull() ?: return null
        var ordered=rectFields[rect.javaClass]
        if(ordered==null) {
            val numbers=Regex("[-+]?[0-9]+(?:\\.[0-9]+)?").findAll(rect.toString()).map { it.value.toFloat() }.toList()
            val candidates=fields(rect.javaClass).filter { it.type==Float::class.javaPrimitiveType }
            if(numbers.size!=4 || candidates.size!=4) return null
            val selected=numbers.map { number -> candidates.filter { kotlin.math.abs(it.getFloat(rect)-number)<.11f }.singleOrNull() }
            if(selected.any { it==null } || selected.filterNotNull().toSet().size!=4) return RectF(numbers[0],numbers[1],numbers[2],numbers[3])
            ordered=selected.filterNotNull();rectFields[rect.javaClass]=ordered
        }
        return RectF(ordered[0].getFloat(rect),ordered[1].getFloat(rect),ordered[2].getFloat(rect),ordered[3].getFloat(rect))
    }
    fun transform(from: Any,to: Any): Matrix {
        val m=FloatArray(16);m[0]=1f;m[5]=1f;m[10]=1f;m[15]=1f
        relativeTransform.invoke(to,from,m)
        require(m.all { it.isFinite() })
        return Matrix().apply { setValues(floatArrayOf(m[0],m[4],m[12],m[1],m[5],m[13],m[3],m[7],m[15])) }
    }
    fun invalidate(node: Any) { bindings.methods["backgroundInvalidation"]?.invoke(null,node) }
    fun resetScene() { cornerKey=null;cornerRadius=0f }
}

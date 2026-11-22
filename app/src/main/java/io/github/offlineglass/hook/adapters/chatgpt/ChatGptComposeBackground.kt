package io.github.offlineglass.hook.adapters.chatgpt

import android.graphics.*
import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Native draw replacement driven exclusively by the shared verified capability graph. */
internal object ChatGptComposeBackground {
    data class Shape(val path:Path,val radii:FloatArray,val width:Float,val height:Float,var contentClip:Path?=null)
    private val b get()=ChatGptBindings
    private val renderers=WeakHashMap<Any,(Any,Canvas,Shape)->Boolean>()
    private val extras=WeakHashMap<Any,java.lang.ref.WeakReference<Any>>()
    private val ownedExtras=WeakHashMap<Any,List<Any>>()
    private val shadows=WeakHashMap<Any,java.lang.ref.WeakReference<Any>>()
    private val painters=WeakHashMap<Any,java.lang.ref.WeakReference<Any>>()
    private val painterHooks=HashSet<java.lang.reflect.Method>()
    private data class Cached(val factory:Any,val size:Long,val direction:Any?,val shape:Shape)
    private val shapes=WeakHashMap<Any,Cached>()
    private val suspended=WeakHashMap<XC_MethodHook.MethodHookParam,List<Pair<Any,Boolean>>>()
    private var installed=false
    private var nativeShadowInstalled = false
    private val renderedGlass = WeakHashMap<Any, Unit>()
    fun isGlassActive(owner: Any): Boolean =
        renderedGlass.containsKey(owner) && ChatGptPageSource.hasBackdrop(owner) && b.attached(owner)

    private fun suppressShadow(owner: Any) =
        nativeShadowInstalled && renderedGlass.containsKey(owner) && ChatGptPageSource.hasBackdrop(owner)

    fun hasSkippedBorder(owner:Any)=false
    fun hasSkippedShadow(owner:Any)=shadows.values.any {it.get()===owner}
    private fun painter(value:Any,owner:Any){
        painters[value]=java.lang.ref.WeakReference(owner)
        val api=b.method("shadow.paint")
        val actual=value.javaClass.getMethod(api.name,*api.parameterTypes)
        if(painterHooks.add(actual))XposedBridge.hookMethod(actual,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val owner=painters[param.thisObject]?.get()?:return
                if(suppressShadow(owner))param.result=null
            }
        })
    }
    private fun bindPainter(node:Any,owner:Any){
        b.read("shadow.painter",node)?.let {painter(it,owner)}
        val registry=b.read("shadow.registryNode",node)?:return
        val map=b.read("shadow.registry",registry) as? Map<*,*>?:return
        val entry=map[node]?:return
        b.read("shadow.entryPainter",entry)?.let {painter(it,owner)}
    }
    fun installNativeShadow(){
        XposedBridge.hookMethod(b.method("shadow.draw"),object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val owner=shadows[param.thisObject]?.get()?:return
                if(!suppressShadow(owner))return
                bindPainter(param.thisObject,owner)
                b.call("scope.content",param.args[0]);param.result=null
            }
        })
        XposedBridge.hookMethod(b.method("shadow.registryDraw"),object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val map=b.read("shadow.registry",param.thisObject) as? Map<*,*>?:return
                val changed=mutableListOf<Pair<Any,Boolean>>()
                shadows.entries.toList().forEach { (node,reference)->
                    val owner=reference.get()
                    if(owner!=null&&suppressShadow(owner)&&b.read("shadow.registryNode",node)===param.thisObject){
                        val entry=map[node]?:return@forEach
                        bindPainter(node,owner)
                        changed.add(entry to (b.read("shadow.entryActive",entry) as Boolean))
                        b.field("shadow.entryActive").setBoolean(entry,false)
                    }
                }
                if(changed.isNotEmpty())suspended[param]=changed
            }
            override fun afterHookedMethod(param:MethodHookParam){suspended.remove(param)?.forEach {(entry,active)->b.field("shadow.entryActive").setBoolean(entry,active)}}
        })
        nativeShadowInstalled = true
    }
    fun installResolved(){
        check(!installed)
        ChatGptPageSource.installResolved()
        XposedBridge.hookMethod(b.method("background.draw"),object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val owner=param.thisObject
                val renderer=renderers[owner]
                if(renderer==null){
                    val primary=extras[owner]?.get()?:return
                    if(renderedGlass.containsKey(primary)&&ChatGptPageSource.hasBackdrop(primary)){b.call("scope.content",param.args[0]);param.result=null}
                    return
                }
                var painted = false
                try {
                    val scope=param.args[0]
                    val parameters=b.read("scope.parameters",b.read("scope.canvasScope",scope)!!)!!
                    val wrapper=b.read("parameters.canvas",parameters)!!
                    val canvas=b.read("canvas.native",wrapper) as Canvas
                    if(!canvas.isHardwareAccelerated)return
                    val size=b.read("parameters.size",parameters) as Long
                    val width=Float.fromBits((size ushr 32).toInt());val height=Float.fromBits(size.toInt())
                    if(width<=0||height<=0)return
                    val factory=b.read("background.shape",owner)!!
                    val direction=b.read("parameters.direction",parameters)
                    val cached=shapes[owner]
                    val shape=if(cached!=null&&cached.factory===factory&&cached.size==size&&cached.direction==direction)cached.shape else {
                        val outline=b.call("shape.outline",factory,size,direction,b.read("parameters.density",parameters))!!
                        val path=Path();val radii=FloatArray(8)
                        when {
                            b.type("outline.round").isInstance(outline)->{
                                val round=b.read("outline.roundValue",outline)!!
                                listOf("topLeft","topRight","bottomRight","bottomLeft").forEachIndexed {i,role->
                                    val packed=b.read("round.$role",round) as Long
                                    radii[2*i]=Float.fromBits((packed ushr 32).toInt());radii[2*i+1]=Float.fromBits(packed.toInt())
                                }
                                path.addRoundRect(b.rect(round,"round"),radii,Path.Direction.CW)
                            }
                            b.type("outline.generic").isInstance(outline)->path.set(b.read("path.native",b.read("outline.path",outline)!!) as Path)
                            else->path.addRect(b.rect(b.call("outline.bounds",outline)!!),Path.Direction.CW)
                        }
                        Shape(path,floatArrayOf(radii[0],radii[2],radii[4],radii[6]),width,height).also {shapes[owner]=Cached(factory,size,direction,it)}
                    }
                    if(!renderer(owner,canvas,shape))return
                    val save=canvas.save()
                    try{shape.contentClip?.let(canvas::clipPath);b.call("scope.content",scope)}finally{canvas.restoreToCount(save)}
                    param.result=null
                    painted = true
                } catch (error: Exception) {
                    XposedBridge.log("[LiquidTab][ChatGPT] native draw contract: $error")
                } finally {
                    val changed = if (painted) renderedGlass.put(owner, Unit) == null else renderedGlass.remove(owner) != null
                    if (changed) {
                        val view = b.layout(owner)?.let { b.read("layout.owner", it) } as? View
                        view?.post {
                            invalidate(ownedExtras[owner].orEmpty().toSet())
                            invalidate(shadows.filterValues { it.get() === owner }.keys)
                            ChatGptDockMasks.invalidate(owner)
                        }
                    }
                }
            }
        })
        installed=true
    }
    fun find(
        anchor: View, semanticsId: Int, bounds: Rect, composer: Boolean = false, dock: Boolean = false,
    ): Set<Any> = runCatching {
        val start = b.semanticLayout(anchor, semanticsId) ?: return emptySet()
        val layouts=linkedSetOf<Any>();val queue=java.util.ArrayDeque<Pair<Any,Int>>();queue.add(start to 0)
        while(queue.isNotEmpty()&&layouts.size<128){val (layout,depth)=queue.removeFirst();if(!layouts.add(layout)||depth>=6)continue;b.children(layout).forEach {queue.add(it to depth+1)}}
        var current:Any?=start
        val ancestors = linkedSetOf<Any>()
        repeat(12) { current?.let { ancestors.add(it); current = b.parent(it) } }
        layouts.addAll(ancestors)
        val maskCandidates = ArrayList<Triple<ChatGptDockMasks.Kind, Any, Any>>()
        val backgroundType = b.type("background.type")
        val shadowType = if (composer && nativeShadowInstalled) b.method("shadow.draw").declaringClass else null
        val backgrounds=linkedSetOf<Any>();val nativeShadows=linkedSetOf<Any>()
        val visited=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
        for(layout in layouts){
            val chain=b.read("layout.chain",layout)!!
            val pending=java.util.ArrayDeque<Any>()
            b.read("chain.head",chain)?.takeIf(b.type("node.type")::isInstance)?.let(pending::add)
            while(pending.isNotEmpty()&&visited.size<4096){
                val node=pending.removeFirst();if(!visited.add(node))continue
                if(b.field("node.delegate").declaringClass.isInstance(node))b.read("node.delegate",node)?.let(pending::add)
                if (dock) ChatGptDockMasks.kindOf(node)?.let { kind ->
                    maskCandidates.add(Triple(kind, node, layout))
                }
                val background = backgroundType.isInstance(node)
                val shadow = shadowType?.isInstance(node) == true
                if (background || shadow) {
                    val rect = screenBounds(node, anchor)
                    if(rect!=null&&abs(rect.left-bounds.left)<=3&&abs(rect.top-bounds.top)<=3&&abs(rect.width()-bounds.width())<=3&&abs(rect.height()-bounds.height())<=3){
                        if(background)backgrounds.add(node)
                        if(shadow)nativeShadows.add(node)
                    }
                }
                b.read("node.next",node)?.let(pending::add)
            }
        }
        backgrounds.take(1).toSet().also { owners->owners.forEach {owner->
            nativeShadows.forEach { node ->
                runCatching {
                    bindPainter(node, owner)
                    shadows[node] = java.lang.ref.WeakReference(owner)
                    b.invalidate(node)
                }.onFailure { ChatGptDiagnostics.report(anchor.context, "nativeShadowBindingFailure", it.toString()) }
            }
            if (dock) runCatching { ChatGptDockMasks.bind(anchor, owner, bounds, maskCandidates) }
                .onFailure { ChatGptDiagnostics.report(anchor.context, "dockGradientBindingFailure", it.toString()) }
            if(composer)ownedExtras[owner]=backgrounds.filter {it!==owner}
            ChatGptDiagnostics.report(anchor.context,"nativeDecoration","backgrounds=${backgrounds.size}; shadows=${nativeShadows.size}")
        }}
    }.getOrElse {ChatGptDiagnostics.report(anchor.context,"findFailure",it.toString());emptySet()}
    fun screenBounds(node:Any,anchor:View):Rect?=runCatching {coordinateBounds(b.read("node.coordinate",node)!!,anchor)}.getOrNull()
    fun coordinateBounds(coordinates:Any,anchor:View):Rect?=runCatching {
        val size=b.call("coordinate.size",coordinates) as Long
        val width=(size ushr 32).toInt().toFloat();val height=size.toInt().toFloat()
        val matrix=coordinateMatrix(coordinates);val rect=RectF(0f,0f,width,height);matrix.mapRect(rect)
        Rect(rect.left.roundToInt(),rect.top.roundToInt(),rect.right.roundToInt(),rect.bottom.roundToInt())
    }.getOrNull()
    fun relativeMatrix(from:Any,to:Any,output:Matrix=Matrix(),values:FloatArray=FloatArray(16)):Matrix {
        values.fill(0f);values[0]=1f;values[5]=1f;values[10]=1f;values[15]=1f
        b.call("coordinate.relativeMatrix",to,from,values)
        // Keep a reusable 3x3 conversion buffer for the UI-thread-only adapter.
        matrixValues[0]=values[0];matrixValues[1]=values[4];matrixValues[2]=values[12]
        matrixValues[3]=values[1];matrixValues[4]=values[5];matrixValues[5]=values[13]
        matrixValues[6]=values[3];matrixValues[7]=values[7];matrixValues[8]=values[15]
        output.setValues(matrixValues);return output
    }
    private val matrixValues=FloatArray(9)
    fun coordinateMatrix(coordinates:Any,role:String="coordinate.screen",output:Matrix=Matrix(),values:FloatArray=FloatArray(9)):Matrix {
        fun point(x:Float,y:Float):Long {val packed=(x.toRawBits().toLong() shl 32)or(y.toRawBits().toLong()and 0xffffffffL);return b.call(role,coordinates,packed) as Long}
        val origin=point(0f,0f);val x=point(1f,0f);val y=point(0f,1f)
        val ox=Float.fromBits((origin ushr 32).toInt());val oy=Float.fromBits(origin.toInt())
        values[0]=Float.fromBits((x ushr 32).toInt())-ox;values[1]=Float.fromBits((y ushr 32).toInt())-ox;values[2]=ox
        values[3]=Float.fromBits(x.toInt())-oy;values[4]=Float.fromBits(y.toInt())-oy;values[5]=oy
        values[6]=0f;values[7]=0f;values[8]=1f;output.setValues(values);return output
    }
    fun invalidate(nodes:Set<Any>){nodes.forEach {runCatching {b.invalidate(it)}}}
    fun replace(old:Set<Any>,next:Set<Any>,renderer:((Any,Canvas,Shape)->Boolean)?=null){
        (old-next).forEach {owner->
            ChatGptDockMasks.clear(owner)
            renderers.remove(owner);renderedGlass.remove(owner);shapes.remove(owner);ownedExtras.remove(owner)?.forEach {runCatching {b.invalidate(it)}};ChatGptPageSource.unbind(owner)}
        next.forEach {if(renderer!=null)renderers[it]=renderer}
        shadows.entries.removeAll {
            val removed = it.value.get()?.let(renderers::containsKey) != true
            if (removed) runCatching { b.invalidate(it.key) }
            removed
        }
        painters.entries.removeAll {it.value.get()?.let {owner->!renderers.containsKey(owner)}!=false}
        extras.clear();ownedExtras.forEach {(owner,values)->if(renderers.containsKey(owner))values.forEach {extras[it]=java.lang.ref.WeakReference(owner);runCatching {b.invalidate(it)}}}
        invalidate(old+next)
    }
}

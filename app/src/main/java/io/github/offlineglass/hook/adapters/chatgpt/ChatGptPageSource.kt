package io.github.offlineglass.hook.adapters.chatgpt

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RenderNode
import android.view.View
import io.github.offlineglass.hook.adapters.RetainedNavigationBackdrop
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Captures one disjoint branch during normal native traversal, never a root replay. */
internal object ChatGptPageSource {
    private val b get()=ChatGptBindings
    private class Source(layout:Any,parent:Any,anchor:View){
        val layout=WeakReference(layout);val parent=WeakReference(parent);val view=WeakReference(anchor)
        val node=RenderNode("ChatGPT-disjoint-page").apply {setClipToBounds(false)}
        val toScreen=Matrix()
        val backdrop = RetainedNavigationBackdrop(node, toScreen)
        val matrixValues=FloatArray(9);var recording=false;var ready=false;var dirty=true;var pending=false
        var requestedAt=android.os.SystemClock.uptimeMillis()
        var wrapper: Any? = null
    }
    private data class Frame(val source:Source,val original:Any,val canvas:Canvas)
    private val sources=WeakHashMap<Any,Source>()
    private val owners=WeakHashMap<Any,WeakReference<View>>()
    private val consumers=WeakHashMap<Any,Source>()
    private val navigationDecor = WeakHashMap<Any, List<Any>>()
    private fun layoutFor(key: Any): Any? = if (navigationDecor.containsKey(key)) key else b.layout(key)
    private fun isPlaced(key: Any): Boolean = if (navigationDecor.containsKey(key)) b.placed(key) else b.attached(key)

    fun bindNavigation(row: Any, anchor: View, decor: List<Any>) {
        navigationDecor[row] = decor
        owners[row] = WeakReference(anchor)
        bindAll(emptySet(), anchor)
    }

    fun navigationBackdrop(row: Any): RetainedNavigationBackdrop? =
        consumers[row]?.takeIf(::ready)?.backdrop

    fun onPageChanged(row: Any, anchor: View) {
        bindAll(emptySet(), anchor)
        consumers[row]?.let(::request)
    }
    private class Sampling {
        var source:Source?=null;var revision=0L
        val consumer=Matrix();val inverse=Matrix();val transform=Matrix()
        val values=FloatArray(9);val previous=FloatArray(9){Float.NaN}
        var translatedSource: RenderNode? = null
    }
    private val transforms=WeakHashMap<Any,Sampling>()
    private var samplingRevision=0L
    private val frames=WeakHashMap<XC_MethodHook.MethodHookParam,Frame>()
    private data class NavigationFrame(val original: Any, val draw: ChatGptBottomBar.NativeDraw)
    private val navigationFrames = WeakHashMap<XC_MethodHook.MethodHookParam, NavigationFrame>()
    private var passes=0
    private fun parentCoordinate(source:Source):Any?=source.parent.get()?.let {b.read("chain.inner",b.read("layout.chain",it)!!)}
    private fun retire(source: Source) {
        source.layout.get()?.let { if (sources[it] === source) sources.remove(it) }
        source.ready = false
        // The native parent's current display list may still reference this
        // node. Let it replay until the parent replaces that list.
        parentCoordinate(source)?.let { b.call("coordinate.invalidate", it) }
    }

    private fun request(source:Source){
        source.dirty=true
        if(source.pending)return
        val view=source.view.get()?:return
        source.pending=true
        view.post {
            source.pending=false
            if(consumers.values.none {it===source})return@post
            parentCoordinate(source)?.let {b.call("coordinate.invalidate",it)}
            view.invalidate()
        }
    }
    fun installResolved(){
        val wrapper=b.type("canvas.type").getDeclaredConstructor().apply {isAccessible=true}
        val canvasField=b.field("canvas.native")
        val coordinateLayout=b.field("coordinate.layout")
        XposedBridge.hookMethod(b.method("coordinate.invalidate"),object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(sources.isEmpty())return
                sources[coordinateLayout.get(param.thisObject)]?.let {if(!it.dirty)request(it)}
            }
        })
        XposedBridge.hookMethod(b.method("layout.draw"),object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if (ChatGptBottomBar.suppressesChrome(param.thisObject)) {
                    param.result = null
                    return
                }
                if (ChatGptBottomBar.capturesNativeLayout(param.thisObject)) {
                    val original = param.args[0]
                    val canvas = canvasField.get(original) as? Canvas ?: return
                    if (!canvas.isHardwareAccelerated) return
                    val replacement = runCatching { wrapper.newInstance() }.getOrNull()
                    val draw = if (replacement != null) runCatching {
                        ChatGptBottomBar.beginNativeDraw(param.thisObject, canvas)
                    }.getOrNull() else null
                    if (draw == null) {
                        if (ChatGptBottomBar.ownsNativeRowDrawing(param.thisObject)) param.result = null
                        return
                    }
                    try {
                        canvasField.set(replacement, draw.scope)
                        navigationFrames[param] = NavigationFrame(original, draw)
                        param.args[0] = replacement
                    } catch (error: Exception) {
                        draw.close(false)
                        if (ChatGptBottomBar.ownsNativeRowDrawing(param.thisObject)) param.result = null
                        else throw error
                    }
                    return
                }
                val source=sources[param.thisObject]?:return
                if(source.recording)return
                runCatching {
                    val view=source.view.get()?:return
                    val canvas=canvasField.get(param.args[0]) as Canvas
                    if(!canvas.isHardwareAccelerated)return
                    val coordinate=parentCoordinate(source)?:return
                    ChatGptComposeBackground.coordinateMatrix(coordinate,output=source.toScreen,values=source.matrixValues)
                    val packedSize = b.call("coordinate.size", coordinate) as Long
                    val width = maxOf(view.width, (packedSize ushr 32).toInt(), 1)
                    val height = maxOf(view.height, packedSize.toInt(), 1)
                    val replacement = source.wrapper ?: wrapper.newInstance().also { source.wrapper = it }
                    if (!source.node.hasDisplayList()) source.ready = false
                    // A parent can redraw this branch without invalidating its own
                    // coordinator. Substitute the recording on every actual draw
                    // of the selected branch so the page and glass share its list.
                    source.node.setPosition(0, 0, width, height)
                    val recording=source.node.beginRecording(width,height)
                    try {
                        canvasField.set(replacement, recording)
                    } catch (error: Exception) {
                        source.node.endRecording()
                        source.ready = false
                        throw error
                    }
                    source.dirty=false;source.recording=true
                    frames[param]=Frame(source,param.args[0],canvas);param.args[0]=replacement
                }.onFailure {error->source.dirty=true;source.view.get()?.let {ChatGptDiagnostics.report(it.context,"pageSourceFailure",error.toString())}}
            }
            override fun afterHookedMethod(param:MethodHookParam){
                navigationFrames.remove(param)?.let {
                    param.args[0] = it.original
                    it.draw.close(!param.hasThrowable())
                    return
                }
                val frame=frames.remove(param)
                if(frame!=null){
                    param.args[0]=frame.original
                    try{
                        frame.source.node.endRecording()
                        if (param.hasThrowable()) {
                            frame.source.ready = false
                            return
                        }
                        frame.canvas.drawRenderNode(frame.source.node)
                        if(!frame.source.ready){frame.source.ready=true;frame.source.view.get()?.post {
                            invalidateConsumers(consumers.entries.filter {it.value===frame.source}.map {it.key}.toSet())
                        }}
                    }finally{frame.source.recording=false}
                }
                param.args.firstOrNull()?.let {ChatGptControlShadow.drawForLayout(param.thisObject,it)}
            }
        })
    }
    private fun usable(source:Source,anchor:View):Boolean {
        val layout=source.layout.get();val parent=source.parent.get()
        return b.placed(layout)&&b.placed(parent)&&b.parent(layout!!)===parent&&ChatGptWindows.sameActivity(source.view.get(),anchor)&&
            (!source.ready||source.node.hasDisplayList())&&(source.ready||android.os.SystemClock.uptimeMillis()-source.requestedAt<250L)
    }
    fun bindingsHealthy(nodes:Set<Any>,anchor:View)=nodes.all {node->consumers[node]?.let {usable(it,anchor)}==true}
    fun bindAll(nodes:Set<Any>,anchor:View):Boolean {
        passes++
        val previous=consumers.toMap();nodes.forEach {owners[it]=WeakReference(anchor)}
        val live=owners.entries.toList().mapNotNull {(node,ref)->val view=ref.get()
            if(view==null||!view.isAttachedToWindow||!isPlaced(node)){owners.remove(node);consumers.remove(node);transforms.remove(node);null}
            else if(ChatGptWindows.sameActivity(view,anchor))node to view else null
        }
        val forbidden = live.flatMap { (key, _) -> listOfNotNull(layoutFor(key)) + navigationDecor[key].orEmpty() }
        sources.values.toList().forEach {source->
            if(ChatGptWindows.sameActivity(source.view.get(),anchor)&&(!usable(source,anchor)||source.layout.get()?.let {p->forbidden.any {b.contains(p,it)}}==true)){
                consumers.entries.filter {it.value===source}.map {it.key}.forEach {consumers.remove(it);transforms.remove(it)}
                retire(source)
            }
        }
        live.sortedByDescending {it.second.width.toLong()*it.second.height}.forEach {(node,view)->bind(node,view,forbidden)}
        sources.values.toList().filter { source -> consumers.values.none { it === source } }.forEach(::retire)
        val changed=live.map {it.first}.filter {previous[it]!==consumers[it]}.toSet()
        invalidateConsumers(changed)
        ChatGptDiagnostics.report(anchor.context,"sourceLifecycle","bindingPasses=$passes; sources=${sources.size}; consumers=${consumers.size}")
        return changed.isNotEmpty()
    }
    private fun invalidateConsumers(keys: Set<Any>) {
        ChatGptComposeBackground.invalidate(keys.filterNot(navigationDecor::containsKey).toSet())
        keys.forEach(ChatGptDockMasks::invalidate)
        keys.filter(navigationDecor::containsKey).forEach(ChatGptBottomBar::onBackdropReady)
    }

    private fun bind(consumer:Any,anchor:View,forbidden:List<Any>){
        consumers[consumer]?.let {if(usable(it,anchor))return else consumers.remove(consumer)}
        sources.values.firstOrNull {usable(it,anchor)&&it.layout.get()?.let {p->forbidden.none {b.contains(p,it)}}==true}?.let {consumers[consumer]=it;return}
        var branch=layoutFor(consumer)?:return
        var best:Triple<Any,Any,Long>?=null
        repeat(24){
            val parent=b.parent(branch)?:return@repeat
            for(sibling in b.children(parent)){
                if(sibling===branch||!b.placed(sibling)||forbidden.any {b.contains(sibling,it)})continue
                val rect=ChatGptComposeBackground.coordinateBounds(b.outer(sibling),anchor)?:continue
                if(rect.width()<anchor.width*.7f||rect.height()<1)continue
                val area=rect.width().toLong()*rect.height()
                if(area>(best?.third?:0))best=Triple(sibling,parent,area)
            }
            branch=parent
        }
        val selected=best?:return
        val source=sources.getOrPut(selected.first){Source(selected.first,selected.second,anchor)}
        consumers[consumer]=source;source.requestedAt=android.os.SystemClock.uptimeMillis();request(source)
        ChatGptDiagnostics.report(anchor.context,"pageSource","disjoint semantic branch; area=${selected.third}")
    }
    fun unbind(consumer:Any){
        navigationDecor.remove(consumer)
        owners.remove(consumer);transforms.remove(consumer)
        val source=consumers.remove(consumer)?:return
        if (consumers.values.none { it === source }) retire(source)
    }
    fun hasBackdrop(consumer: Any): Boolean =
        consumers[consumer]?.let(::ready) == true

    private fun ready(source: Source) =
        source.ready && !source.recording && source.node.hasDisplayList()

    fun recordingKey(consumer:Any, retainTranslation: Boolean = false):Long? {
        val source=consumers[consumer]?:return null
        if (!ready(source)) return null
        val coordinate=b.read("node.coordinate",consumer)?:return null
        val state=transforms.getOrPut(consumer){Sampling()}
        ChatGptComposeBackground.coordinateMatrix(coordinate,output=state.consumer,values=state.values)
        if(!state.consumer.invert(state.inverse))return null
        state.transform.setConcat(state.inverse,source.toScreen);state.transform.getValues(state.values)
        val affine = retainTranslation && state.transform.isAffine
        val translationX = state.values[Matrix.MTRANS_X]
        val translationY = state.values[Matrix.MTRANS_Y]
        if (affine) {
            state.values[Matrix.MTRANS_X] = 0f
            state.values[Matrix.MTRANS_Y] = 0f
        }
        val previousNode = state.translatedSource
        if (state.source !== source || !state.values.contentEquals(state.previous) || affine != (previousNode != null)) {
            if (affine) {
                val node = RenderNode("ChatGPT-composer-source-transform").apply { setClipToBounds(false) }
                node.setPosition(0, 0, source.node.width, source.node.height)
                val recording = node.beginRecording()
                try {
                    state.transform.setValues(state.values)
                    recording.concat(state.transform)
                    recording.drawRenderNode(source.node)
                } finally {
                    node.endRecording()
                }
                state.translatedSource = node
            } else state.translatedSource = null
            state.source = source
            state.revision = ++samplingRevision
            state.values.copyInto(state.previous)
        }
        state.translatedSource?.let {
            // The sampled scene follows IME translation through RenderNode
            // properties while the filtered consumer retains its display list.
            it.setTranslationX(translationX)
            it.setTranslationY(translationY)
        }
        return state.revision
    }
    fun draw(canvas:Canvas,consumer:Any,screen:Rect,width:Float,height:Float,pad:Int):Int {
        val source=consumers[consumer]?:return 0
        if (!ready(source)) return 0
        val sampling = transforms[consumer] ?: return 0
        val save = canvas.save()
        try {
            canvas.translate(pad.toFloat(), pad.toFloat())
            val translated = sampling.translatedSource
            if (translated != null) canvas.drawRenderNode(translated)
            else {
                canvas.concat(sampling.transform)
                canvas.drawRenderNode(source.node)
            }
        } finally {
            canvas.restoreToCount(save)
        }
        return 1
    }
}

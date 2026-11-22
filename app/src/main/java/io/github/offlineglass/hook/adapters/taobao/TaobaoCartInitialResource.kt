package io.github.offlineglass.hook.adapters.taobao

import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import org.json.JSONObject

/** Cart lifecycle and public engine contracts only; never replaces its program or initial data. */
internal object TaobaoCartInitialResource {
    private class Binding(owner: Any, val id: String, engine: Any, root: ViewGroup) {
        val owner=WeakReference(owner)
        val engine=WeakReference(engine)
        private val root=WeakReference(root)
        val action: (String,String)->Unit = { kind,phase -> this.root.get()?.let {
            runCatching { dispatch(it,kind,phase) }
        } }
        var attempted=false
        var active=false
        val rootLocation=IntArray(2);val hostLocation=IntArray(2)
        var lastWidth=-1;var lastHeight=-1;var lastButtonTop=Float.NaN;var lastGap=Float.NaN
    }
    private val roots=WeakHashMap<ViewGroup,Binding>()
    private var installed=false
    private var lastMissing=0L
    private var statusMethod: java.lang.reflect.Method?=null
    private var executeMethod: java.lang.reflect.Method?=null
    private var dispatchMethod: java.lang.reflect.Method?=null
    private var jsonFactory: java.lang.reflect.Constructor<*>?=null
    private var valueFactory: java.lang.reflect.Method?=null
    private var windowTarget: Any?=null
    private var controllerSource: String?=null
    private var recover: ((ViewGroup)->Unit)?=null
    private val recoveryTimes=WeakHashMap<ViewGroup,Long>()
    fun install(loader: ClassLoader) {
        if(installed || !TaobaoCartNativeBridge.install(loader))return
        runCatching {
            val type=Class.forName("com.taobao.android.icart.weex.instance.BaseHybridInstance",false,loader)
            val engine=Class.forName("com.taobao.android.weex.WeexInstance",false,loader)
            val target=Class.forName("com.taobao.android.weex.WeexEventTarget",false,loader)
            val value=Class.forName("com.taobao.android.weex.WeexValue",false,loader)
            val impl=Class.forName("com.taobao.android.weex.WeexValueImpl",false,loader)
            val json=Class.forName("com.alibaba.fastjson.JSONObject",false,loader)
            statusMethod=engine.getMethod("getInstanceStatus")
            executeMethod=engine.getMethod("execute",ByteArray::class.java,String::class.java)
            dispatchMethod=engine.getMethod("dispatchEvent",target,String::class.java,value)
            jsonFactory=json.getConstructor();valueFactory=impl.getMethod("ofJSON",json)
            windowTarget=target.getField("WINDOW_TARGET").get(null)
            val field=type.declaredFields.single { !Modifier.isStatic(it.modifiers)&&it.type==engine }.apply{isAccessible=true}
            val rootMethod=type.getMethod("getRootView")
            val idMethod=type.getMethod("getInstanceId")
            require(ViewGroup::class.java.isAssignableFrom(rootMethod.returnType)&&idMethod.returnType==String::class.java)
            fun associate(owner:Any, root:ViewGroup) {
                val instance=field.get(owner)?:return
                val id=idMethod.invoke(owner) as? String?:return
                synchronized(roots){if(roots[root]?.engine?.get()!==instance)roots[root]=Binding(owner,id,instance,root)}

            }
            val registryType=Class.forName("com.taobao.android.icart.weex.call.CartMegaManager",false,loader)
            val registryField=registryType.declaredFields.single {
                Modifier.isStatic(it.modifiers)&&java.util.concurrent.ConcurrentHashMap::class.java.isAssignableFrom(it.type)
            }.apply{isAccessible=true}
            recover={ wanted ->
                val registry=registryField.get(null) as? Map<*,*>
                if(registry!=null&&registry.size<=32){
                    val candidates=ArrayList<Any>()
                    for((key,value) in registry){
                        if(key !is String||value==null)continue
                        val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
                        fun visit(item:Any,depth:Int){
                            if(depth>3||seen.size>=128||!seen.add(item))return
                            if(type.isInstance(item)){
                                if(idMethod.invoke(item)==key&&rootMethod.invoke(item)===wanted)candidates.add(item)
                                return
                            }
                            // Only the registry's native call -> wrapper -> hybrid ownership chain.
                            if(!item.javaClass.name.startsWith("tb.")&&!item.javaClass.name.startsWith("com.taobao.android.icart."))return
                            var clazz:Class<*>?=item.javaClass
                            while(clazz!=null&&clazz!=Any::class.java){
                                for(f in clazz.declaredFields){
                                    if(Modifier.isStatic(f.modifiers)||f.type.isPrimitive||f.type==String::class.java)continue
                                    runCatching{f.isAccessible=true;f.get(item)?.let{visit(it,depth+1)}}
                                }
                                clazz=clazz.superclass
                            }
                        }
                        visit(value,0)
                    }
                    val unique=candidates.distinctBy{System.identityHashCode(it)}
                    if(unique.size==1)associate(unique.single(),wanted)
                }
            }
            XposedBridge.hookAllMethods(type,"create",object: XC_MethodHook(){
                override fun afterHookedMethod(param: MethodHookParam){runCatching {
                    val owner=param.thisObject?:return
                    val root=rootMethod.invoke(owner) as? ViewGroup?:return
                    associate(owner,root)
                }.onFailure{android.util.Log.e("LiquidTabCart","Create association failed",it)}}
            })
            XposedBridge.hookAllMethods(type,"getRootView",object: XC_MethodHook(){
                override fun afterHookedMethod(param: MethodHookParam){runCatching {
                    val owner=param.thisObject?:return
                    val root=param.result as? ViewGroup?:return
                    associate(owner,root)
                }.onFailure{android.util.Log.e("LiquidTabCart","Root association failed",it)}}
            })
            XposedBridge.hookAllMethods(type,"destroy",object: XC_MethodHook(){
                override fun beforeHookedMethod(param: MethodHookParam){synchronized(roots){roots.entries.removeAll {it.value.owner.get()===param.thisObject}}}
            })
            installed=true
        }.onFailure{android.util.Log.e("LiquidTabCart", "Lifecycle contract failed", it)}
    }
    fun prepareRoot(root:ViewGroup,host:GlassHostLayout){
        if(synchronized(roots){roots[root]}==null){
            val time=SystemClock.uptimeMillis()
            if(time-(recoveryTimes[root]?:0L)>=1000){recoveryTimes[root]=time;runCatching{recover?.invoke(root)}
                .onFailure{android.util.Log.e("LiquidTabCart","Registry recovery failed",it)}}
        }
        val binding=synchronized(roots){roots[root]}?:run { if(lastMissing==0L){lastMissing=SystemClock.uptimeMillis(); record(root,"no-root-binding:installed=$installed:ability=${TaobaoCartNativeBridge.installed}")}; return }
        if(binding.attempted)return
        val engine=binding.engine.get()?:return
        if(!installed)return
        // This is a cached public status getter, not capability discovery.
        // A one-second probe backoff here delayed the first cart takeover even
        // after the native renderer became ready. Observe the next native draw;
        // the attempted flag below still permits exactly one script execution.
        if(statusMethod?.invoke(engine).toString()!="RENDERED")return
        if(!root.isAttachedToWindow||!root.isShown)return
        val config=host.config
        if(!config.enabled||config.classicNavigation)return
        // Mark before dispatch so a repeated completion callback cannot schedule another execution.
        binding.attempted=true
        runCatching {
            val rootLocation=IntArray(2).also(root::getLocationInWindow)
            val hostLocation=IntArray(2).also(host::getLocationInWindow)
            val density=root.resources.displayMetrics.density
            val options=JSONObject().put("id",binding.id).put("viewportWidth",root.width).put("viewportHeight",root.height)
                .put("buttonTop",hostLocation[1]-rootLocation[1]-io.github.offlineglass.hook.globalGlassBottomGapPx(host)/2f-48f*density).put("gap",8f*density)
            val source=controllerSource ?: host.context.createPackageContext("io.github.offlineglass", 0).assets
                .open("taobao/cart-controller.js").bufferedReader().use{it.readText()}.also { controllerSource=it }
            val script=source.replace("__LIQUIDTAB_OPTIONS__",options.toString())
            binding.active=true
            bindNativeButtons(root,host)
            executeMethod?.invoke(engine,script.toByteArray(),"liquidtab-cart-controller")
            record(root,"controller-dispatched")
        }.onFailure{binding.active=false;record(root,"controller-contract-failed:${it.javaClass.simpleName}")}
    }
    private fun dispatch(root:ViewGroup,kind:String,phase:String){
        val b=synchronized(roots){roots[root]}?:return
        val engine=b.engine.get()?:return
        @Suppress("UNCHECKED_CAST")
        val data=jsonFactory?.newInstance() as? MutableMap<String,Any> ?: return
        data["kind"]=kind;data["phase"]=phase
        val payload=valueFactory?.invoke(null,data) ?: return
        dispatchMethod?.invoke(engine,windowTarget,"__liquidtabCartAction",payload)
    }
    fun bindNativeButtons(root:ViewGroup,host:GlassHostLayout){
        val b=synchronized(roots){roots[root]}?:return
        root.getLocationInWindow(b.rootLocation);host.getLocationInWindow(b.hostLocation)
        val density=root.resources.displayMetrics.density
        val buttonTop=b.hostLocation[1]-b.rootLocation[1]-io.github.offlineglass.hook.globalGlassBottomGapPx(host)/2f-48f*density
        val gap=8f*density
        if(b.lastWidth!=root.width||b.lastHeight!=root.height||b.lastButtonTop!=buttonTop||b.lastGap!=gap){
            b.lastWidth=root.width;b.lastHeight=root.height;b.lastButtonTop=buttonTop;b.lastGap=gap
            val geometry=JSONObject().put("viewportWidth",root.width).put("viewportHeight",root.height).put("buttonTop",buttonTop).put("gap",gap)
            runCatching{dispatch(root,"__geometry",geometry.toString())}
        }
        if(TaobaoCartNativeBridge.bind(b.id,root,host,b.action))
            runCatching{dispatch(root,"__publish","ready")}
    }
    fun owns(root:ViewGroup):Boolean=synchronized(roots){roots[root]?.active==true}
    private fun record(root:ViewGroup,state:String){runCatching {
        val folder=root.context.getExternalFilesDir(null)?:return
        java.io.File(folder,"liquidtab-cart-initial-resource.json").writeText(JSONObject().put("state",state)
            .put("pid",android.os.Process.myPid()).put("updatedAt",System.currentTimeMillis()).toString())
    }}
}

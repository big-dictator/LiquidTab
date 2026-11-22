package io.github.offlineglass.hook.adapters.taobao

import android.view.View
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XposedHelpers

/** Hide only LauncherBootImage's visual root, then use its native close handler.
 * The original onStart/onShowing and startup completion notifications still run. */
internal object TaobaoSplashHooks {
    private var installed=false
    fun install(loader:ClassLoader){
        val app=runCatching{Class.forName("com.taobao.tao.Globals",false,loader).getMethod("getApplication").invoke(null) as? android.app.Application}.getOrNull()
        if(app!=null){installBound(loader,app);return}
        XposedBridge.hookAllMethods(android.app.Application::class.java,"attach",object:XC_MethodHook(){
            override fun afterHookedMethod(param:MethodHookParam){
                val application=param.thisObject as? android.app.Application?:return
                if(application.packageName=="com.taobao.taobao")installBound(loader,application)
            }
        })
    }
    private fun installBound(loader:ClassLoader,app:android.app.Application){
        if(installed)return
        val apk=app.applicationInfo.sourceDir
        val cache=java.io.File(app.cacheDir,"liquidtab-splash-contract.properties").path
        runCatching {
            val info = Class.forName("com.taobao.bootimage.data.BootImageInfo", false, loader)
            val bizType = info.getField("bizType")
            val binding=TaobaoSplashIndex.resolveCached(apk,cache)
            val visual=Class.forName(binding.visual(),false,loader)
            val model=visual.declaredFields.single { !java.lang.reflect.Modifier.isStatic(it.modifiers)&&it.type==info }.apply{isAccessible=true}
            val root=visual.declaredFields.single { !java.lang.reflect.Modifier.isStatic(it.modifiers)&&it.type==android.widget.FrameLayout::class.java }.apply{isAccessible=true}
            val launcher=Class.forName(binding.launcher(),false,loader)
            check(launcher.getDeclaredMethod(binding.showing(),String::class.java).returnType==Void.TYPE)
            check(launcher.getDeclaredMethod(binding.close(),Int::class.javaPrimitiveType).returnType==Void.TYPE)
            check(launcher.getDeclaredMethod(binding.delay()).returnType==Long::class.javaPrimitiveType)
            // Native data getter is shared by cached and live startup business selection.
            // Keep list identity and let the original no-candidate lifecycle finish normally.
            val flow=Class.forName(binding.dataFlow(),false,loader)
            check(flow.declaredFields.count{it.type==launcher}==1)
            val getter=flow.getDeclaredMethod(binding.dataList(),String::class.java)
            check(List::class.java.isAssignableFrom(getter.returnType))
            check(getter.genericReturnType.typeName.contains("com.alibaba.fastjson.JSONObject"))
            val visualTypes=info.fields.filter{java.lang.reflect.Modifier.isStatic(it.modifiers)&&
                it.type==String::class.java&&it.name.startsWith("BIZ_TYPE_")}.mapNotNull{it.get(null) as? String}.toSet()
            check(visualTypes.isNotEmpty())
            XposedBridge.hookAllMethods(flow,binding.dataList(),object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    val sections=param.result as? List<*>?:return
                    runCatching{TaobaoSplashDataPolicy.withoutVisualSplash(sections,visualTypes)}
                        .onFailure{android.util.Log.w("LiquidTab-Taobao","Splash section contract unavailable: ${it.javaClass.simpleName}")}
                }
            })
            XposedBridge.hookAllConstructors(visual, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val data = model.get(param.thisObject) ?: return
                        if (bizType.get(data) in visualTypes) {
                            val view = root.get(param.thisObject) as? View ?: return
                            view.alpha = 0f
                            // Its native fade can assign alpha again after creation.
                            val hide=android.view.ViewTreeObserver.OnPreDrawListener {
                                if (view.alpha != 0f) view.alpha = 0f
                                true
                            }
                            view.viewTreeObserver.addOnPreDrawListener(hide)
                            view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
                                override fun onViewAttachedToWindow(v:View)=Unit
                                override fun onViewDetachedFromWindow(v:View){
                                    if(v.viewTreeObserver.isAlive)v.viewTreeObserver.removeOnPreDrawListener(hide)
                                    v.removeOnAttachStateChangeListener(this)
                                }
                            })
                        }
                    }
                }
            })
            XposedBridge.hookAllMethods(launcher, binding.delay(), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isEmpty()) param.result = 0L
                }
            })
            XposedBridge.hookAllMethods(launcher, binding.showing(), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.args.size == 1 && param.args[0] is String)
                        runCatching { XposedHelpers.callMethod(param.thisObject, binding.close(), 1112) }
                }
            })
            installed=true
            android.util.Log.i("LiquidTab-Taobao", "Launcher semantic visual suppression installed")
        }.onFailure {
            XposedBridge.log("LiquidTab-Taobao: optional launcher visual contract unavailable: ${it.javaClass.simpleName}")
        }
    }
}

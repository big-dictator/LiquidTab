package io.github.offlineglass.hook.adapters.quora

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** Keeps native sidebar content above navigation insets while its background fills the window. */
internal object QuoraSidebarInsets {
    private data class Saved(val bottom: Int,var applied: Int)
    private val panels=java.util.WeakHashMap<View,Saved>()
    private val nativeBottoms=java.util.WeakHashMap<ViewGroup,Int>()
    private var installed=false
    fun compensate(side: ViewGroup,bottom: Int) {
        val panel=QuoraViews.child(side,"sidebar_container") ?: return
        val saved=panels.getOrPut(panel){Saved(panel.paddingBottom,panel.paddingBottom)}
        nativeBottoms[side]=bottom
        val target=saved.bottom+bottom
        if(panel.paddingBottom!=target)panel.setPadding(panel.paddingLeft,panel.paddingTop,panel.paddingRight,target)
        saved.applied=target
    }
    fun restore(side: ViewGroup) {
        for(i in 0 until side.childCount) {
            val panel=side.getChildAt(i);val saved=panels.remove(panel) ?: continue
            if(panel.paddingBottom==saved.applied)panel.setPadding(panel.paddingLeft,panel.paddingTop,panel.paddingRight,saved.bottom)
        }
        nativeBottoms.remove(side)?.let { bottom ->
            if(side.paddingBottom==0)side.setPadding(side.paddingLeft,side.paddingTop,side.paddingRight,bottom)
        }
    }
    fun install(context: Context,loader: ClassLoader) {
        if(installed)return
        val app=context.applicationContext ?: context
        runCatching {
            val util=loader.loadClass("com.quora.android.util.EdgeToEdgeUtil")
            require(util.getDeclaredMethod("applyWindowInsetsPadding",View::class.java,Context::class.java,java.lang.Boolean.TYPE).returnType==java.lang.Void.TYPE)
            val compat=loader.loadClass("androidx.core.view.WindowInsetsCompat")
            // The lambda's generated name is deliberately irrelevant. Discover
            // the unique verified (includeTop, View, Insets)->Insets callback.
            val callback=QuoraInsetsContract.callback(util,View::class.java,compat)
                ?: error("missing or ambiguous native Insets callback signature")
            val getInsets=compat.getMethod("getInsets",Integer.TYPE)
            val insetType=getInsets.returnType
            val of=insetType.getMethod("of",Integer.TYPE,Integer.TYPE,Integer.TYPE,Integer.TYPE)
            val left=insetType.getField("left");val top=insetType.getField("top")
            val right=insetType.getField("right");val bottom=insetType.getField("bottom")
            val types=loader.loadClass("androidx.core.view.WindowInsetsCompat\$Type")
            val mask=(types.getMethod("systemBars").invoke(null) as Int) or
                (types.getMethod("displayCutout").invoke(null) as Int)
            val builderType=loader.loadClass("androidx.core.view.WindowInsetsCompat\$Builder")
            val constructor=builderType.getConstructor(compat)
            val set=builderType.getMethod("setInsets",Integer.TYPE,insetType)
            val build=builderType.getMethod("build")
            XposedBridge.hookMethod(callback,object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val side=param.args[1] as? ViewGroup ?: return
                    if(QuoraAdapter.name(side)!="sidebar"||QuoraAdapter.name(side.parent as? View ?: return)!="root_hook")return
                    if(!HookConfigReader.read(app,"com.quora.android").enabled){restore(side);return}
                    val value=getInsets.invoke(param.args[2],mask)
                    val b=bottom.getInt(value)
                    compensate(side,b)
                    val builder=constructor.newInstance(param.args[2])
                    set.invoke(builder,mask,of.invoke(null,left.getInt(value),top.getInt(value),right.getInt(value),0))
                    param.args[2]=build.invoke(builder)
                }
            })
            installed=true
            XposedBridge.log("[QuoraSidebar] Insets callback bound structurally; content inset preserved")
        }.onFailure { XposedBridge.log("[QuoraSidebar] Insets contract unavailable: $it") }
    }
}

package io.github.offlineglass.hook.adapters.x

import android.content.Context
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.HookConfigReader

/** The outer hazeAlpha footer is distinct from the capsule's Haze material. */
internal object XReplyBackdropMask {
    var removed=0L
        private set
    fun install(context: Context,bindings: XBindingResolver.Bindings) {
        val scope=requireNotNull(bindings.methods["replyMaskScope"])
        val factory=requireNotNull(bindings.methods["replyMaskFactory"])
        val stateType=factory.parameterTypes[1]
        val stateIndex=scope.parameterTypes.indices.single { it.let { index -> scope.parameterTypes[index]==stateType } }
        require(factory.parameterCount==12 && factory.returnType==factory.parameterTypes[0])
        data class Frame(val parameter: XC_MethodHook.MethodHookParam,val state: Any?)
        val owner=bindings.methods["replyMaskOwner"]
        val ownerStates=ThreadLocal.withInitial { ArrayDeque<Frame>() }
        if(owner!=null) {
            val ownerStateIndex=owner.parameterTypes.indices.single { owner.parameterTypes[it]==stateType }
            hook(owner,object: XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    ownerStates.get()?.addLast(Frame(p,p.args[ownerStateIndex]))
                }
                override fun afterHookedMethod(p: MethodHookParam) {
                    val frames=ownerStates.get() ?: return
                    if(frames.lastOrNull()?.parameter===p) frames.removeLast()
                }
            })
        }
        val stack=ThreadLocal.withInitial { ArrayDeque<Frame>() }
        hook(scope,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val state=p.args[stateIndex] ?: return
                if(owner!=null && ownerStates.get()?.lastOrNull()?.state!==state) return
                stack.get()?.addLast(Frame(p,state))
            }
            override fun afterHookedMethod(p: MethodHookParam) {
                val frames=stack.get() ?: return
                if(frames.lastOrNull()?.parameter===p) frames.removeLast()
            }
        })
        hook(factory,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val frame=stack.get()?.lastOrNull() ?: return
                if(frame.state==null || p.args[1]!==frame.state) return
                // Exact outer-mask call shape. Nested capsule/autocomplete calls
                // use explicit blur/noise values and must keep their Haze sources.
                if(p.args[4]!=0f || p.args[5]!=false || p.args[6]!=0f || p.args[8]==null) return
                val config=HookConfigReader.read(context,XAdapter.PACKAGE_NAME)
                if(!config.enabled || !config.backdropCapture) return
                p.setResult(p.args[0])
                removed++
            }
        })
    }
    private fun hook(method: java.lang.reflect.Method,callback: XC_MethodHook) {
        XposedBridge::class.java.getMethod("hookMethod",java.lang.reflect.Member::class.java,XC_MethodHook::class.java)
            .invoke(null,method,callback)
    }
}

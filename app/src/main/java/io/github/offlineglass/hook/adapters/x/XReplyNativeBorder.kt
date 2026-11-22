package io.github.offlineglass.hook.adapters.x

import android.content.Context
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.HookConfigReader

/** Remove only the border constructed by the semantic reply surface for its own
 * native shape. Returning the incoming Modifier omits the border element entirely;
 * width=0 would still produce Compose's one-pixel hairline. */
internal object XReplyNativeBorder {
    var removed=0L
        private set
    fun install(context: Context,bindings: XBindingResolver.Bindings) {
        val surface=requireNotNull(bindings.methods["replyBorderScope"])
        val factory=requireNotNull(bindings.methods["replyBorderFactory"])
        val shapeType=factory.parameterTypes[3]
        val shapeIndex=surface.parameterTypes.indices.single { shapeType.isAssignableFrom(surface.parameterTypes[it]) }
        require(factory.returnType==factory.parameterTypes[0])
        data class Scope(val parameter: XC_MethodHook.MethodHookParam,val shape: Any?)
        val stack=ThreadLocal.withInitial { ArrayDeque<Scope>() }
        hook(surface,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) { stack.get()?.addLast(Scope(p,p.args[shapeIndex])) }
            override fun afterHookedMethod(p: MethodHookParam) {
                val scopes=stack.get() ?: return
                if(scopes.lastOrNull()?.parameter===p) scopes.removeLast()
            }
        })
        hook(factory,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val scope=stack.get()?.lastOrNull() ?: return
                if(scope.shape==null || p.args[3]!==scope.shape) return
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

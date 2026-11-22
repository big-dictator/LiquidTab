package io.github.offlineglass.hook.adapters.spotify

import android.os.Handler
import android.os.Looper
import android.view.View
import java.lang.reflect.Modifier
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.XC_MethodHook

/** Resolve native measure entry points once, using the verified PaddingValues type. */
internal object SpotifyLazyMeasureHooks {
    private val pending=HashSet<Class<*>>()
    private val ready=HashSet<Class<*>>()
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    fun ensure(owner: View,padding: Class<*>,done: ()->Unit): Boolean {
        if(padding in ready)return true
        if(!pending.add(padding))return false
        val source=owner.context.applicationInfo.sourceDir
        val loader=padding.classLoader
        worker.execute {
            val candidates=runCatching {
                SpotifyNavigationIndex.holdersOfField(source,"L"+padding.name.replace('.','/')+";").mapNotNull { name ->
                    val type=runCatching { Class.forName(name,false,loader) }.getOrNull() ?: return@mapNotNull null
                    val field=runCatching { type.declaredFields }.getOrDefault(emptyArray()).singleOrNull { !Modifier.isStatic(it.modifiers) && it.type==padding }
                        ?.apply { isAccessible=true } ?: return@mapNotNull null
                    val method=runCatching { type.declaredMethods }.getOrDefault(emptyArray()).singleOrNull { method ->
                        !Modifier.isStatic(method.modifiers) && method.parameterCount==2 &&
                        method.parameterTypes[1]==Long::class.javaPrimitiveType && method.returnType.isInterface &&
                        method.returnType.methods.any { it.name=="getWidth" && it.parameterCount==0 } &&
                        method.returnType.methods.any { it.name=="getHeight" && it.parameterCount==0 } &&
                        method.returnType.methods.any { it.returnType==Map::class.java && it.parameterCount==0 }
                    } ?: return@mapNotNull null
                    field to method
                }.also { check(it.isNotEmpty() && it.size<=8) }
            }
            main.post {
                runCatching {
                    candidates.getOrThrow().forEach { (field,method) ->
                        val stack=ThreadLocal.withInitial { java.util.ArrayDeque<()->Unit>() }
                        XposedBridge.hookMethod(method,object: XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val after=if(Looper.myLooper()==Looper.getMainLooper())
                                    runCatching { SpotifyLibraryViewport.measuring(param.thisObject,field,param.args[0]) }.getOrNull() else null
                                stack.get().addLast(after ?: {})
                            }
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val queue=stack.get()
                                if(queue.isNotEmpty()) {
                                    val after=queue.removeLast()
                                    if(!param.hasThrowable())runCatching { after() }.onFailure {
                                        android.util.Log.w("SpotifyGlass","Library measured inset commit failed",it)
                                    }
                                }
                            }
                        })
                    }
                    ready+=padding;done()
                }.onFailure { android.util.Log.w("SpotifyGlass","Native lazy measurement contract unresolved; retain top safety",it) }
            }
        }
        return false
    }
}

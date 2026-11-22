package io.github.offlineglass.hook.adapters.chatgpt

import android.app.Activity
import android.graphics.RectF
import android.util.AtomicFile
import android.os.Handler
import android.os.Looper
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Properties
import java.util.WeakHashMap
import io.github.offlineglass.hook.adapters.chatgpt.ChatGptNativeContract.Capability

/** One verified native capability set for drawing, geometry, sampling and lifecycle. */
internal object ChatGptBindings {
    private class Handles {
        val methods = mutableMapOf<String, Method>()
        val fields = mutableMapOf<String, Field>()
        val types = mutableMapOf<String, Class<*>>()
    }
    private data class Discovery(
        val handles: Map<Capability, Result<Handles>>,
        val cacheError: Throwable?,
    )

    private val handles = Handles()
    private val waiting = WeakHashMap<Activity, Unit>()
    private val main = Handler(Looper.getMainLooper())
    var ready = false
        private set
    var exteriorShadow = false
        private set
    private var started = false
    private var finished = false
    private const val SCHEMA = "chatgpt-semantic-contract-7"

    fun ensure(activity: Activity): Boolean {
        if (ready) return true
        if (!finished) waiting[activity] = Unit
        if (started) return false
        started = true
        val app = activity.applicationContext
        val loader = activity.classLoader
        Thread({
            val result = runCatching {
                val info = app.applicationInfo
                val apks = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty().sorted()
                val identity = SCHEMA + apks.joinToString { path ->
                    val file = File(path)
                    "$path/${file.length()}/${file.lastModified()}"
                }
                val cache = AtomicFile(File(app.codeCacheDir, "liquidtab-chatgpt-contract.properties"))
                val cached = runCatching { cache.openRead().use { input -> Properties().apply { load(input) } } }.getOrNull()
                if (cached?.getProperty("identity") == identity) {
                    val resolved = resolve(cached, loader)
                    if (resolved.getValue(Capability.GLASS).isSuccess) return@runCatching Discovery(resolved, null)
                }
                val properties = ChatGptNativeContract.inspect(apks.toTypedArray()).apply { setProperty("identity", identity) }
                val resolved = resolve(properties, loader)
                val cacheError = if (resolved.getValue(Capability.GLASS).isSuccess) runCatching {
                    val stream = cache.startWrite()
                    try {
                        properties.store(stream, SCHEMA)
                        cache.finishWrite(stream)
                    } catch (error: Exception) {
                        cache.failWrite(stream)
                        throw error
                    }
                }.exceptionOrNull() else null
                Discovery(resolved, cacheError)
            }
            main.post {
                finished = true
                val activities = waiting.keys.toList()
                waiting.clear()
                result.onSuccess { discovery ->
                    discovery.cacheError?.let { ChatGptDiagnostics.report(app, "contractCacheFailure", it.toString()) }
                    val glass = discovery.handles.getValue(Capability.GLASS)
                    runCatching {
                        publish(glass.getOrThrow())
                        ChatGptComposeBackground.installResolved()
                        ready = true
                    }.onFailure { ChatGptDiagnostics.report(app, "contractFailure", it.toString()) }
                    if (ready) {
                        val exterior = discovery.handles.getValue(Capability.EXTERIOR_SHADOW)
                        exterior.onSuccess { publish(it); exteriorShadow = true }
                        ChatGptDiagnostics.report(app, "exteriorShadow", exterior.exceptionOrNull()?.toString() ?: "verified")
                        val native = discovery.handles.getValue(Capability.NATIVE_SHADOW).mapCatching {
                            publish(it)
                            ChatGptComposeBackground.installNativeShadow()
                        }
                        ChatGptDiagnostics.report(app, "nativeShadow", native.exceptionOrNull()?.toString() ?: "verified")
                        val gradient = discovery.handles.getValue(Capability.SEARCH_GRADIENT).mapCatching {
                            publish(it)
                            ChatGptDockMasks.install(ChatGptDockMasks.Kind.ELEVATED, "searchGradient.draw")
                        }
                        ChatGptDiagnostics.report(app, "searchGradientContract", gradient.exceptionOrNull()?.toString() ?: "verified")
                        val scaffold = discovery.handles.getValue(Capability.SCAFFOLD_GRADIENT).mapCatching {
                            publish(it)
                            ChatGptDockMasks.install(ChatGptDockMasks.Kind.SCAFFOLD, "scaffoldGradient.draw")
                        }
                        ChatGptDiagnostics.report(app, "scaffoldGradientContract", scaffold.exceptionOrNull()?.toString() ?: "verified")
                        ChatGptDiagnostics.report(app, "contract", "verified glass; methods=${handles.methods.size}; fields=${handles.fields.size}")
                        activities.filterNot { it.isDestroyed || it.isFinishing }.forEach(ChatGptComposerGlass::scan)
                    }
                }.onFailure { ChatGptDiagnostics.report(app, "contractFailure", it.toString()) }
            }
        }, "ChatGPT-contract-discovery").start()
        return false
    }

    private fun resolve(properties: Properties, loader: ClassLoader) = Capability.values().associateWith { capability ->
        runCatching {
            val absent = capability.missing(properties)
            require(absent.isEmpty()) {
                "Unresolved ${capability.name} contracts: " +
                    absent.joinToString { "$it(${properties.getProperty("$it.candidates", "0")})" }
            }
            val result = Handles()
            capability.roles.forEach { role ->
                val parts = properties.getProperty(role).split('|')
                try {
                    val owner = type(parts[0], loader)
                    when (parts.size) {
                        1 -> result.types[role] = owner
                        3 -> result.fields[role] = owner.getDeclaredField(parts[1]).apply {
                            require(type == type(parts[2], loader))
                            isAccessible = true
                        }
                        4 -> {
                            val args = parts[3].split(',').filter(String::isNotEmpty).map { type(it, loader) }
                            result.methods[role] = owner.getDeclaredMethod(parts[1], *args.toTypedArray()).apply {
                                require(returnType == type(parts[2], loader))
                                isAccessible = true
                            }
                        }
                        else -> error("Invalid member descriptor")
                    }
                } catch (error: Exception) {
                    throw IllegalStateException("Invalid ${capability.name} contract $role", error)
                }
            }
            if (capability == Capability.GLASS) {
                require(result.methods.getValue("background.draw").parameterTypes.single() == result.types.getValue("scope.type"))
                require(result.fields.getValue("node.coordinate").type == result.types.getValue("coordinate.type"))
                require(result.methods.getValue("layout.draw").parameterTypes[0].isAssignableFrom(result.types.getValue("canvas.type")))
                require(result.fields.getValue("chain.head") != result.fields.getValue("chain.inner"))
            }
            result
        }
    }

    private fun publish(resolved: Handles) {
        handles.methods.putAll(resolved.methods)
        handles.fields.putAll(resolved.fields)
        handles.types.putAll(resolved.types)
    }

    private fun type(descriptor:String,loader:ClassLoader):Class<*> = when(descriptor){
        "V"->Void.TYPE;"Z"->Boolean::class.javaPrimitiveType!!;"B"->Byte::class.javaPrimitiveType!!
        "I"->Int::class.javaPrimitiveType!!;"J"->Long::class.javaPrimitiveType!!;"F"->Float::class.javaPrimitiveType!!
        "D"->Double::class.javaPrimitiveType!!;"S"->Short::class.javaPrimitiveType!!;"C"->Char::class.javaPrimitiveType!!
        else->Class.forName(if(descriptor.startsWith("L"))descriptor.substring(1,descriptor.length-1).replace('/','.') else descriptor.replace('/','.'),false,loader)
    }
    fun method(role:String)=handles.methods.getValue(role)
    fun field(role:String)=handles.fields.getValue(role)
    fun type(role:String)=handles.types.getValue(role)
    fun read(role:String,target:Any):Any?=field(role).get(target)
    fun call(role:String,target:Any?,vararg args:Any?):Any?=method(role).invoke(target,*args)
    fun layout(node:Any):Any?=read("node.coordinate",node)?.let { read("coordinate.layout",it) }
    fun outer(layout:Any):Any=call("layout.outer",layout)!!
    @Suppress("UNCHECKED_CAST") fun children(layout:Any):List<Any> = call("layout.children",layout) as List<Any>
    // Compose flattens virtual nodes in getChildren. Use its matching parent
    // traversal, which skips those nodes as well.
    fun parent(layout: Any): Any? = call("layout.parent", layout)
    fun inner(layout: Any): Any? = read("layout.chain", layout)?.let { read("chain.inner", it) }
    fun invalidateParent(layout: Any) {
        parent(layout)?.let(::inner)?.let { call("coordinate.invalidate", it) }
    }
    fun semanticLayout(anchor: android.view.View, id: Int): Any? {
        val nodes = call("owner.nodes", anchor) ?: return null
        return listOf(id, id - 2_000_000_000, id - 1_000_000_000).firstNotNullOfOrNull { candidate ->
            if (candidate < 0) null else call("semantics.lookup", nodes, candidate)
        }
    }
    fun contains(ancestor:Any,child:Any,onLimit:Boolean=true):Boolean {
        var current:Any?=child
        repeat(96){if(current===ancestor)return true;current=current?.let(::parent)?:return false};return onLimit
    }
    fun placed(layout:Any?):Boolean = layout!=null && read("layout.owner",layout)!=null && read("layout.deactivated",layout)!=true && call("layout.placed",layout)==true
    fun attached(node:Any):Boolean=runCatching {read("node.attached",node)==true&&placed(layout(node))}.getOrDefault(false)
    fun rect(target:Any,role:String="rectangle")=RectF(read("$role.left",target) as Float,read("$role.top",target) as Float,read("$role.right",target) as Float,read("$role.bottom",target) as Float)
    fun invalidate(node:Any){read("node.coordinate",node)?.let { call("coordinate.invalidate",it) }}
}

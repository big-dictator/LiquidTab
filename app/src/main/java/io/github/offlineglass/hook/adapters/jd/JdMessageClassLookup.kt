package io.github.offlineglass.hook.adapters.jd

import android.app.Activity
import android.os.Looper
import dalvik.system.BaseDexClassLoader
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import io.github.libxposed.api.XposedInterface
import io.github.offlineglass.hook.GlassHostLayout
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Message bundle's own immutable DEX membership; native delegation remains intact. */
internal object JdMessageClassLookup {
    private class LookupMiss(name: String) : ClassNotFoundException(name) {
        override fun fillInStackTrace(): Throwable = this
    }
    private val indices = WeakHashMap<ClassLoader, JdBundleDexIndex>()
    private val hookedLoaders = HashSet<Method>()
    private var host = WeakReference<GlassHostLayout>(null)
    private var activeLoader: ClassLoader? = null
    @Volatile private var activeThread: Thread? = null
    private var installed = false
    private var findHook: XposedInterface.HookHandle? = null
    private var skippedOwnDex = 0L
    private data class Prepared(val loader: WeakReference<ClassLoader>, val host: WeakReference<GlassHostLayout>,
        val index: JdBundleDexIndex)
    private val ready = java.util.concurrent.ConcurrentLinkedQueue<Prepared>()
    private val mainHandler = android.os.Handler(Looper.getMainLooper())
    // Main-thread admission/publication only. Metadata work never touches Views.
    private val preparing = java.util.Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    private val metadataWorker by lazy {
        java.util.concurrent.ThreadPoolExecutor(0, 1, 10L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue()) { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "LiquidTab-JdMessageIndex").apply { isDaemon = true }
        }
    }

    fun bindHost(value: GlassHostLayout) {
        if (host.get() !== value) host = WeakReference(value)
    }

    private fun uniqueField(owner: Class<*>, role: (Class<*>) -> Boolean): Field {
        val candidates = owner.declaredFields.filter { !Modifier.isStatic(it.modifiers) && role(it.type) }
        check(candidates.size == 1) { "message DEX field candidates=${candidates.size}" }
        return candidates.single().apply { isAccessible = true }
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        runCatching {
            val fragment = Class.forName("androidx.fragment.app.Fragment", false, loader)
            val tab = Class.forName("com.jingdong.common.unification.navigationbar.JDTabFragment", false, loader)
            val mainActivity = Class.forName("com.jingdong.app.mall.MainFrameActivity", false, loader)
            val activityMethod = fragment.getMethod("getActivity")
            val pathField = uniqueField(BaseDexClassLoader::class.java) { it.name == "dalvik.system.DexPathList" }
            val elementsField = uniqueField(pathField.type) { type ->
                type.isArray && type.componentType?.declaredFields?.any { it.type == DexFile::class.java } == true
            }
            val dexField = uniqueField(checkNotNull(elementsField.type.componentType)) { it == DexFile::class.java }
            val sharedFields = BaseDexClassLoader::class.java.declaredFields.filter {
                !Modifier.isStatic(it.modifiers) && it.type == Array<ClassLoader>::class.java
            }.onEach { it.isAccessible = true }
            check(sharedFields.size == 2) { "shared-library delegation requires review" }
            val baseFind = BaseDexClassLoader::class.java.getDeclaredMethod("findClass", String::class.java)
            check(baseFind.returnType == Class::class.java)
            val framework = XposedBridge.framework()
            val mainThread = Looper.getMainLooper().thread

            fun unchanged(value: ClassLoader, index: JdBundleDexIndex): Boolean {
                if (sharedFields.any { (it.get(value) as? Array<*>)?.isNotEmpty() == true }) return false
                val path = pathField.get(value)
                return index.ownsGeneration(path, elementsField.get(path))
            }
            fun publishPrepared() {
                if (Thread.currentThread() !== mainThread) return
                while (true) {
                    val prepared = ready.poll() ?: return
                    val receiver = prepared.loader.get() ?: continue
                    val live = prepared.host.get() ?: continue
                    if (host.get() === live && live.config.enabled && live.isAttachedToWindow &&
                        runCatching { unchanged(receiver, prepared.index) }.getOrDefault(false)) {
                        indices[receiver] = prepared.index
                        android.util.Log.i("JdColdInit", "message DEX membership published, definitions=${prepared.index.size()}")
                    }
                }
            }
            fun prepareMetadata(definingLoader: ClassLoader, anchor: String, expectedHost: WeakReference<GlassHostLayout>) {
                val type = definingLoader.javaClass
                val binding = JdMessageLoaderContract.resolve(type, DexClassLoader::class.java)
                val nativeLoad = binding.load()
                if (nativeLoad !in hookedLoaders && hookedLoaders.size >= 8) return
                val bundle = binding.bundle().invoke(definingLoader) ?: return
                val location = binding.location().invoke(bundle) as? String ?: return
                check(location == "com.jd.lib.message") { "different bundle location" }
                check(binding.owner().invoke(bundle) === definingLoader) { "bundle/defining loader mismatch" }
                if (sharedFields.any { (it.get(definingLoader) as? Array<*>)?.isNotEmpty() == true }) return
                val path = pathField.get(definingLoader)
                val elements = elementsField.get(path) as? Array<*> ?: return
                if (elements.size !in 1..8) return
                val names = HashSet<String>()
                var bytes = 0
                for (element in elements) {
                    val dex = dexField.get(element) as? DexFile ?: continue
                    @Suppress("DEPRECATION") // Platform's own complete DEX class inventory; no APK copy or disk scan.
                    val entries = dex.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement()
                        bytes += name.length
                        if (names.size >= 4096 || bytes > 524288) return
                        names.add(name)
                    }
                }
                // A complete own-DEX index must include this defining class.
                check(anchor in names) { "defining class absent from DEX index" }
                val index = JdBundleDexIndex(path, elements, names, anchor)
                check(unchanged(definingLoader, index))
                var addedLoadHook: XposedInterface.HookHandle? = null
                try {
                    if (nativeLoad !in hookedLoaders) {
                        val originalLoad = framework.getInvoker(nativeLoad).setType(XposedInterface.Invoker.Type.ORIGIN)
                        addedLoadHook = framework.hook(nativeLoad).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                            val receiver = chain.thisObject as ClassLoader
                            // Consume completed metadata at the next native load
                            // too: posting alone would miss the whole first
                            // synchronous onCreate/onCreateView transaction.
                            if (Thread.currentThread() === mainThread && ready.isNotEmpty()) publishPrepared()
                            val liveHost = host.get()
                            val current = if (Thread.currentThread() === mainThread && liveHost?.config?.enabled == true &&
                                liveHost.isAttachedToWindow && liveHost.hasWindowFocus()) indices[receiver] else null
                            // Validate generation only when findClass would be
                            // shortened, not again for every successful loadClass.
                            if (current == null) chain.proceed()
                            else {
                                val previous = activeLoader
                                activeLoader = receiver
                                activeThread = mainThread
                                try { chain.proceed() }
                                catch (miss: LookupMiss) {
                                    // If a future bundle implementation lets an
                                    // internal miss escape, retry its COMPLETE native
                                    // delegation outside our scope, not just findClass.
                                    // Never expose a stackless failure to business code.
                                    indices.remove(receiver)
                                    activeLoader = null
                                    activeThread = null
                                    try { originalLoad.invoke(receiver, chain.getArg(0), chain.getArg(1)) }
                                    catch (error: InvocationTargetException) { throw (error.targetException ?: error) }
                                } finally {
                                    activeLoader = previous
                                    if (previous == null) activeThread = null
                                }
                            }
                        }
                    }
                    // Install lazily, after startup/cart loading. No Throwable or
                    // bootstrap-loader hooks and no changed delegation order.
                    if (findHook == null) findHook = framework.hook(baseFind)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                            if (activeThread === Thread.currentThread() && activeLoader === chain.thisObject) {
                                val current = indices[activeLoader]
                                val name = chain.getArg(0) as String
                                if (current != null && !current.contains(name) &&
                                    runCatching { unchanged(activeLoader!!, current) }.getOrDefault(false)) {
                                    skippedOwnDex++
                                    if (android.os.Trace.isEnabled()) android.os.Trace.setCounter("LiquidTab.JD.messageOwnDexSkipped", skippedOwnDex)
                                    throw LookupMiss(name)
                                }
                            }
                            chain.proceed()
                        }
                    hookedLoaders.add(nativeLoad)
                    ready.add(Prepared(WeakReference(definingLoader), expectedHost, index))
                    mainHandler.post { publishPrepared() }
                } catch (error: Throwable) {
                    addedLoadHook?.let { runCatching { it.unhook() } }
                    throw error
                }
                XposedBridge.log("LiquidTab JD message DEX membership bound: classes=${index.size()}")
            }
            fun bind(value: Any) {
                if (Thread.currentThread() !== mainThread || !tab.isInstance(value) ||
                    !value.javaClass.name.startsWith("com.jd.lib.message.")) return
                val owner = host.get() ?: return
                if (!owner.config.enabled || !owner.isAttachedToWindow || !owner.hasWindowFocus()) return
                val activity = activityMethod.invoke(value) as? Activity ?: return
                if (!mainActivity.isInstance(activity) || owner.activityForAdapter() !== activity) return
                val definingLoader = value.javaClass.classLoader ?: return
                indices[definingLoader]?.let { if (unchanged(definingLoader, it)) return else indices.remove(definingLoader) }
                if (indices.size + preparing.size >= 4 || !preparing.add(definingLoader)) return
                val anchor = value.javaClass.name
                val expectedHost = WeakReference(owner)
                // The first Fragment attach is part of the native destination
                // transaction. Do not enumerate DEX/install hooks on that frame.
                // Until publication the complete native lookup stays in charge.
                try {
                    metadataWorker.execute {
                        try {
                            prepareMetadata(definingLoader, anchor, expectedHost)
                        } catch (error: Throwable) {
                            XposedBridge.log("LiquidTab JD message metadata preparation unavailable: $error")
                        } finally {
                            mainHandler.post { preparing.remove(definingLoader) }
                        }
                    }
                } catch (error: Throwable) {
                    preparing.remove(definingLoader)
                    throw error
                }
            }
            framework.hook(fragment.getDeclaredMethod("performAttach"))
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    runCatching { bind(chain.thisObject) }.onFailure {
                        XposedBridge.log("LiquidTab JD message DEX membership unavailable: $it")
                    }
                    chain.proceed()
                }
            installed = true
        }.onFailure { XposedBridge.log("LiquidTab JD message loader capability unavailable: $it") }
    }
}

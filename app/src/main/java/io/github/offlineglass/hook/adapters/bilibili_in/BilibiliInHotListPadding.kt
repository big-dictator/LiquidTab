package io.github.offlineglass.hook.adapters.bilibili_in

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import dalvik.system.DexFile
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.adapters.bilibili.BILIBILI_HOME_TOP_CLEARANCE_DP
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** Add content padding to the native vertical list in the Hot composition. */
internal object BilibiliInHotListPadding {
    private const val COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val FUNCTION2 = "kotlin.jvm.functions.Function2"
    private const val LAZY = "androidx.compose.foundation.lazy."
    private const val PAGER = "androidx.viewpager2.widget.ViewPager2"
    private const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
    private val composers = WeakHashMap<Any, WeakReference<View>>()
    private val composingHot = ThreadLocal<View>()
    private val hotScreenOwner = ThreadLocal<View>()
    private val hotScreenCall = ThreadLocal<XC_MethodHook.MethodHookParam>()
    private val hotStates = WeakHashMap<Any, WeakReference<View>>()
    private val topGetters = HashMap<Class<*>, Method?>()
    private val wrapped = WeakHashMap<View, Padding>()
    private val pagerPositions = HashMap<Class<*>, Method?>()
    private val diagnosticStages = HashSet<String>()
    private var resolved = false
    private data class Padding(val native: Any, val value: Float, val proxy: Any)
    private data class NativeCall(val method: Method, val composer: Int, val padding: Int, val reverse: Int, val orientation: Int?)

    fun install(loader: ClassLoader) {
        val host = loader.loadClass(COMPOSE_VIEW)
        val content = host.getMethod("setContent", loader.loadClass(FUNCTION2))
        XposedBridge.hookMethod(content, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (entryName(view) != "compose_view") return
                val activity = activity(view.context) ?: return
                if (activity.javaClass.name != "tv.danmaku.bili.MainActivityV2") return
                val original = param.args[0] ?: return
                if (!resolved) {
                    resolved = true
                    runCatching { installListHook(loader, activity.applicationInfo.sourceDir) }
                        .onSuccess { note(view, "contract resolved") }
                        .onFailure { note(view, "contract unavailable: $it") }
                }
                param.args[0] = Proxy.newProxyInstance(loader, arrayOf(content.parameterTypes[0])) { proxy, method, args ->
                    when (method.name) {
                        "invoke" -> {
                            val composer = args?.getOrNull(0)
                            if (composer == null || !belongsToHot(view)) {
                                invoke(method, original, args)
                            } else {
                                val block = runCatching {
                                    original.javaClass.declaredFields.single { !Modifier.isStatic(it.modifiers) && it.type == Any::class.java }
                                        .apply { isAccessible = true }.get(original)
                                }.getOrNull()
                                note(view, "Hot composition bound: ${block?.javaClass?.name ?: original.javaClass.name}")
                                composers[composer] = WeakReference(view)
                                val previous = composingHot.get()
                                composingHot.set(view)
                                try { invoke(method, original, args) }
                                finally { if (previous == null) composingHot.remove() else composingHot.set(previous) }
                            }
                        }
                        "equals" -> proxy === args?.getOrNull(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        else -> invoke(method, original, args)
                    }
                }
            }
        })
    }

    private fun installListHook(loader: ClassLoader, source: String) {
        val methods = ArrayList<NativeCall>()
        val stateFactories = ArrayList<Method>()
        val listWrappers = ArrayList<Method>()
        val dex = DexFile(source)
        try {
            val entries = dex.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement()
                if (!name.startsWith(LAZY) || name.substring(LAZY.length).removePrefix("grid.").contains('.')) continue
                runCatching { loader.loadClass(name).declaredMethods }.getOrNull()
                    ?.let { declared ->
                        declared.mapNotNullTo(methods, ::nativeCall)
                        declared.filterTo(stateFactories) { method ->
                            Modifier.isStatic(method.modifiers) && method.returnType.name == "${LAZY}LazyListState" &&
                                method.parameterTypes.map { it.name } == listOf("int", "int", COMPOSER, "int", "int")
                        }
                        declared.filterTo(listWrappers) { method ->
                            val p = method.parameterTypes
                            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE &&
                                p.size == 13 && p[1].name == "${LAZY}LazyListState" &&
                                p[10].name == COMPOSER && p[2].isInterface
                        }
                    }
            }
        } finally {
            dex.close()
        }
        val targets = methods.distinct().groupBy { it.composer }.values.mapNotNull { it.singleOrNull() }
        check(targets.isNotEmpty())
        targets.forEach { target -> hookList(loader, target) }
        bindScreenState(loader, stateFactories.distinct().single())
        listWrappers.distinct().forEach { XposedBridge.framework().deoptimize(it) }
    }

    private fun bindScreenState(loader: ClassLoader, factory: Method) {
        val screen = loader.loadClass("tv.danmaku.bili.hottopic.HotTopicScreenKt").declaredMethods.single { method ->
            val p = method.parameterTypes.map { it.name }
            Modifier.isStatic(method.modifiers) && method.returnType == Void.TYPE && p.size == 10 &&
                p[0].startsWith("tv.danmaku.bili.hottopic.viewModel.") &&
                p[1].startsWith("tv.danmaku.bili.hottopic.compose.") &&
                p.subList(3, 6) == listOf("boolean", "boolean", "java.lang.String") &&
                p.subList(7, 10) == listOf(COMPOSER, "int", "int")
        }
        XposedBridge.hookMethod(screen, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val owner = composingHot.get() ?: composers[param.args[7]]?.get() ?: return
                if (hotScreenOwner.get() != null || !belongsToHot(owner)) return
                hotScreenCall.set(param)
                hotScreenOwner.set(owner)
                note(owner, "Hot screen entered")
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (hotScreenCall.get() === param) {
                    hotScreenCall.remove()
                    hotScreenOwner.remove()
                }
            }
        })
        XposedBridge.hookMethod(factory, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val owner = hotScreenOwner.get() ?: return
                val state = param.result ?: return
                hotStates[state] = WeakReference(owner)
                note(owner, "Hot native list state bound")
            }
        })
        XposedBridge.framework().deoptimize(screen)
    }

    private fun hookList(loader: ClassLoader, target: NativeCall) {
        val paddingType = target.method.parameterTypes[target.padding]
        XposedBridge.hookMethod(target.method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Both native wrappers converge here. The fourth argument is
                // reverseLayout and the fifth is orientation (true for vertical).
                val composer = param.args[target.composer] ?: return
                val view = hotStates[param.args[1]]?.get() ?: return
                note(view, "Hot native list: ${target.method.declaringClass.name}")
                if (param.args[target.reverse] != false || target.orientation?.let { param.args[it] != true } == true) return
                if (!belongsToHot(view)) return
                composers[composer] = WeakReference(view)
                val native = param.args[target.padding] ?: return
                val top = verifiedTopGetter(native, paddingType) ?: run {
                    note(view, "native padding contract did not validate")
                    return
                }
                val clearance = clearanceDp(view)
                if (clearance <= 0f) return
                note(view, "native content padding applied: ${clearance.toInt()}dp")
                val cached = wrapped[view]
                param.args[target.padding] = if (cached?.native == native && cached.value == clearance) cached.proxy else {
                    Proxy.newProxyInstance(loader, arrayOf(paddingType)) { proxy, method, args ->
                        when {
                            method == top -> maxOf(clearance, invoke(method, native, args) as Float)
                            method.name == "equals" -> proxy === args?.getOrNull(0)
                            method.name == "hashCode" -> System.identityHashCode(proxy)
                            else -> invoke(method, native, args)
                        }
                    }.also { wrapped[view] = Padding(native, clearance, it) }
                }
            }
        })
    }

    private fun nativeCall(method: Method): NativeCall? {
        val p = method.parameterTypes
        if (!Modifier.isStatic(method.modifiers) || method.returnType != Void.TYPE ||
            p.size !in setOf(14, 18) || !p[0].name.startsWith("androidx.compose.ui.") || !p[0].isInterface) return null
        val target = when {
            p.size == 18 && p[1].name == "${LAZY}LazyListState" &&
                p[4] == Boolean::class.javaPrimitiveType && p[8] == Int::class.javaPrimitiveType ->
                NativeCall(method, 14, 2, 3, 4)
            p.size == 14 && p[1].name == "${LAZY}grid.LazyGridState" &&
                p[2].name.startsWith("${LAZY}grid.") -> NativeCall(method, 11, 3, 4, null)
            else -> return null
        }
        return target.takeIf {
            p[it.padding].isInterface &&
                p[it.padding].methods.count { getter -> getter.returnType == Float::class.javaPrimitiveType && getter.parameterCount == 0 } == 2 &&
                p[it.reverse] == Boolean::class.javaPrimitiveType && p[it.composer - 1].name == "kotlin.jvm.functions.Function1" &&
                p[it.composer].name == COMPOSER && p.drop(it.composer + 1).all { tail -> tail == Int::class.javaPrimitiveType }
        }
    }

    private fun verifiedTopGetter(native: Any, paddingType: Class<*>): Method? = topGetters.getOrPut(native.javaClass) {
        runCatching {
            val float = Float::class.javaPrimitiveType!!
            val ctor = native.javaClass.getDeclaredConstructor(float, float, float, float).apply { isAccessible = true }
            // Native PaddingValues puts the four edges in its description.
            // Verify the top getter with distinct values independent of names.
            val probe = ctor.newInstance(11f, 13f, 17f, 19f)
            val description = probe.toString()
            check(description.startsWith("PaddingValues(") || description.startsWith("PaddingValues.Absolute("))
            check(Regex("(?:^|[,(]\\s*)top=13\\.0(?:\\.dp)?(?:,|\\))").containsMatchIn(description))
            paddingType.methods.single {
                it.parameterCount == 0 && it.returnType == float && it.invoke(probe) == 13f
            }
        }.getOrNull()
    }

    private fun belongsToHot(view: View): Boolean {
        var child = view
        var parent = child.parent as? ViewGroup
        while (parent != null) {
            val recycler = parent
            val pager = recycler.parent as? ViewGroup
            if (pager?.javaClass?.name == PAGER && entryName(pager) == "pager" &&
                entryName(pager.parent as? View) == "primary_multi_page_layout" &&
                recycler.accessibilityClassName == RECYCLER) {
                if (!pagerPositions.containsKey(recycler.javaClass)) {
                    pagerPositions[recycler.javaClass] = runCatching {
                        recycler.javaClass.getMethod("getChildAdapterPosition", View::class.java)
                    }.getOrNull()?.takeIf { it.returnType == Int::class.javaPrimitiveType }
                }
                val position = runCatching { pagerPositions[recycler.javaClass]?.invoke(recycler, child) as? Int }.getOrNull()
                return position == 1
            }
            child = recycler
            parent = child.parent as? ViewGroup
        }
        return false
    }

    private fun clearanceDp(view: View): Float {
        val activity = activity(view.context) ?: return 0f
        val config = HookConfigReader.read(activity, activity.packageName)
        if (!config.enabled || !config.bilibiliTopBar) return 0f
        val insets = view.rootWindowInsets?.getInsets(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar(),
        ) ?: return 0f
        val position = IntArray(2).also(view::getLocationInWindow)
        val density = view.resources.displayMetrics.density
        return ((insets.top - position[1]) / density + 44f + BILIBILI_HOME_TOP_CLEARANCE_DP).coerceAtLeast(0f)
    }

    private fun activity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper && current !is Activity) {
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
        return current as? Activity
    }

    private fun entryName(view: View?): String? =
        view?.takeIf { it.id != View.NO_ID }?.let { runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull() }

    private fun note(view: View, stage: String) {
        if (!io.github.offlineglass.BuildConfig.DEBUG || diagnosticStages.size >= 12 || !diagnosticStages.add(stage)) return
        runCatching {
            java.io.File(view.context.getExternalFilesDir(null), "liquidtab-intl-native-hot-padding.txt")
                .appendText("$stage\n")
        }
    }

    private fun invoke(method: Method, target: Any, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) }
        catch (error: InvocationTargetException) { throw error.targetException }
}

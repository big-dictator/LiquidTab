package io.github.offlineglass.hook.adapters.x

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.ViewTreeObserver
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.HookConfigReader
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/** Runtime semantic bindings; no obfuscated class or method name is a build-time contract.
 * Native navigation remains usable until a real callback has been captured. Optional
 * action discovery failures fall back on the affected page, not the whole adapter.
 */
internal object XBottomBarHooks {
    private const val TAG = "OfflineGlassX"
    private var activityRef: WeakReference<Activity>? = null
    private var bootstrapInstalled = false
    private var initialized = false
    @Volatile var ready = false
        private set
    @Volatile internal var fabClick: (() -> Unit)? = null
        private set
    @Volatile internal var chatClick: (() -> Unit)? = null
        private set
    @Volatile internal var tabTypes: Array<Any>? = null
        private set
    @Volatile internal var selectedIndex = 0
        private set
    private var tabClick: Any? = null
    private var tabInvoke: Method? = null
    private var postHookReady = false
    private var chatHookReady = false
    private var report = "not initialized"
    private var diagnosticsFile: java.io.File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val details = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    private val restoreDetail = Runnable { if (details.isEmpty()) XAdapter.onDetailPageChanged(false) }

    val replacesCurrentPage: Boolean
        get() = ready && tabClick != null
    val floatingActionReady: Boolean
        get() = replacesCurrentPage && if (selectedIndex == 4) chatHookReady && chatClick != null else selectedIndex != 2 && postHookReady && fabClick != null
    private fun portrait(): Boolean = activityRef?.get()?.resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT
    internal fun diagnostic() = "$report; tab=$selectedIndex callback=${tabClick != null} post=$postHookReady chat=$chatHookReady"

    fun install(param: XC_LoadPackage.LoadPackageParam) {
        if (bootstrapInstalled || param.processName != param.packageName) return
        bootstrapInstalled = true
        // Context supplies the installed base/split APK identities and the app-private cache.
        // Installation still finishes before the first Activity and Compose navigation.
        XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val context = p.args.firstOrNull() as? Context ?: return
                if (context.packageName != XAdapter.PACKAGE_NAME || initialized) return
                if (HookConfigReader.readStartupConfig(context, XAdapter.PACKAGE_NAME)?.enabled != true) return
                initialize(context, param.classLoader)
            }
        })
    }

    fun onConfigurationReady(context: Context, loader: ClassLoader) {
        if (context is Activity) activityRef = WeakReference(context)
        if (!initialized) initialize(context, loader)
        // Configuration may arrive after this Activity's first onPostResume.
        (context as? Activity)?.let(XPageSemantics::onHostReady)
    }

    private fun initialize(context: Context, loader: ClassLoader) {
        initialized=true
        optional("page semantics") { XPageSemantics.install() }
        optional("Grok viewport") { XGrokViewport.install() }
        runCatching {
            val bindings = XBindingResolver.resolve(context, loader)
            report = bindings.report
            optional("navigation") { installNavigation(bindings) }
            // Resolve/install optional capabilities independently.
            postHookReady = installAction(bindings.methods["postFab"], false)
            chatHookReady = installAction(bindings.methods["chatFab"], true)
            bindings.methods["toggle"]?.let { toggle -> optional("auto-hide") {
                hook(toggle, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        XAdapter.onNativeOverlaysVisibility(p.args[1] as Boolean)
                    }
                })
            } }
            optional("post-detail lifecycle") { installDetails(bindings) }
            optional("drawer visibility") { installDrawerState(bindings, "drawer") }
            optional("gesture drawer visibility") { installDrawerState(bindings, "gestureDrawer") }
            optional("reply composer") {
                XReplyComposerGlass.install(bindings)
                report += "; reply=native-Haze"
                optional("reply native border") { XReplyNativeBorder.install(context,bindings) }
                optional("reply footer mask") { XReplyBackdropMask.install(context,bindings) }
            }
        }.onFailure {
            ready = false
            report = "native fallback: ${it.javaClass.simpleName}: ${it.message}"
            android.util.Log.e(TAG, report, it)
        }
        android.util.Log.i(TAG, diagnostic())
        // Small, bounded support record: no page text, account data, or credentials.
        diagnosticsFile = java.io.File(context.getExternalFilesDir(null) ?: context.cacheDir, "liquidtab-x-bindings.txt")
        writeDiagnostics()
    }

    private fun writeDiagnostics() { runCatching { diagnosticsFile?.writeText(diagnostic()) } }

    private fun installNavigation(bindings: XBindingResolver.Bindings) {
            val landing = bindings.methods.getValue("landing")
            val bottom = bindings.methods.getValue("bottom")
            val callbackIndices=landing.parameterTypes.indices.filter { landing.parameterTypes[it].name=="kotlin.jvm.functions.Function1" }
            val tabIndex=landing.parameterTypes.indices.single { landing.parameterTypes[it].isEnum && XApkIndex.TAB_NAMES.all { name -> runCatching { landing.parameterTypes[it].getField(name).type==landing.parameterTypes[it] }.getOrDefault(false) } }
            val function1 = landing.parameterTypes[callbackIndices.first()]
            val tabType = landing.parameterTypes[tabIndex]
            tabTypes = arrayOf("HOME", "EXPLORE", "GROK", "NOTIFICATIONS", "DM")
                .map { tabType.getField(it).get(null)!! }.toTypedArray()
            tabInvoke = function1.getMethod("invoke", Any::class.java)
            // A callable reference retains its source operation name even when its
            // generated class and target member are renamed by the optimizer.
            val referenceNames = if(callbackIndices.size>1) listOf(
                "kotlin.jvm.internal.AdaptedFunctionReference", "kotlin.jvm.internal.CallableReference"
            ).mapNotNull { name ->
                runCatching { Class.forName(name,false,landing.declaringClass.classLoader)
                    .getDeclaredField("name").apply { isAccessible=true } }.getOrNull()
            } else emptyList()
            XposedBridge.hookAllMethods(Activity::class.java, "onCreate", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val activity = p.thisObject as? Activity ?: return
                    if (activity.packageName == XAdapter.PACKAGE_NAME) activityRef = WeakReference(activity)
                }
            })
            hook(landing, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val index = tabTypes?.indexOfFirst { it === p.args[tabIndex] }?.takeIf { it >= 0 } ?: return
                    val callback=if(callbackIndices.size==1) p.args[callbackIndices.single()]
                    else callbackIndices.mapNotNull { p.args[it] }.singleOrNull { candidate ->
                        referenceNames.any { it.declaringClass.isInstance(candidate) && it.get(candidate)=="onTabClicked" }
                    }
                    val bindingChanged = (tabClick == null) != (callback == null)
                    val tabChanged = selectedIndex != index
                    tabClick = callback
                    selectedIndex = index
                    if (bindingChanged) writeDiagnostics()
                    if (tabChanged || bindingChanged) {
                        XAdapter.onSelectedTabChanged()
                    }
                }
            })
            val booleans=bottom.parameterTypes.indices.filter { bottom.parameterTypes[it]==Boolean::class.javaPrimitiveType }
            require(booleans.size==3) { "bottom:hidden flag group absent/ambiguous" }
            val hiddenIndex=booleans[1]
            val dirtyIndex=bottom.parameterCount-1
            require(bottom.parameterTypes[dirtyIndex]==Int::class.javaPrimitiveType)
            hook(bottom, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!portrait() || !replacesCurrentPage) return
                    p.args[hiddenIndex] = true
                    // The anchored, fully checked prototype's z2 flag excludes bottom chrome.
                    val shift=hiddenIndex*3+1
                    p.args[dirtyIndex] = ((p.args[dirtyIndex] as Int) and (3 shl shift).inv()) or (2 shl shift)
                }
            })
            ready = true
    }

    private fun installAction(method: Method?, chat: Boolean): Boolean {
        if (method == null) return false
        return runCatching {
            val argument = method.parameterTypes.indices.single { method.parameterTypes[it].name=="kotlin.jvm.functions.Function0" }
            val invoke = method.parameterTypes[argument].getMethod("invoke")
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val callback = p.args[argument] ?: return
                    val first = if (chat) chatClick == null else fabClick == null
                    val action: () -> Unit = {
                        runCatching { invoke.invoke(callback) }
                            .onFailure { android.util.Log.w(TAG, "native action failed", it) }
                    }
                    if (chat) chatClick = action else fabClick = action
                    if (first) XAdapter.onSelectedTabChanged()
                    if (portrait() && replacesCurrentPage) p.setResult(null)
                }
            })
            true
        }.onFailure { android.util.Log.w(TAG, "action hook unavailable chat=$chat", it) }.getOrDefault(false)
    }

    private fun installDrawerState(bindings: XBindingResolver.Bindings, role: String) {
        val container=bindings.methods["${role}Container"] ?: return
        val renderer=bindings.methods["${role}Renderer"] ?: return
        val isOpen=bindings.methods["${role}Open"] ?: return
        val stateIndex=renderer.parameterTypes.indices.single { renderer.parameterTypes[it]==isOpen.declaringClass }
        require(isOpen.parameterCount==0 && isOpen.returnType==Boolean::class.javaPrimitiveType)
        var depth=0
        var state=WeakReference<Any>(null)
        var owner=WeakReference<Activity>(null)
        var observer: ViewTreeObserver?=null
        fun syncDrawer() {
            val nativeState=state.get() ?: return
            (runCatching { isOpen.invoke(nativeState) }.getOrNull() as? Boolean)?.let(XAdapter::onDrawerChanged)
        }
        // The gesture drawer can animate a retained composition without
        // calling the renderer or the state reader again from native code.
        val onFrame=ViewTreeObserver.OnPreDrawListener { syncDrawer(); true }
        fun detachObserver() {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(onFrame)
            observer=null
        }
        hook(container,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) { if(Looper.myLooper()==Looper.getMainLooper()) depth++ }
            override fun afterHookedMethod(p: MethodHookParam) { if(Looper.myLooper()==Looper.getMainLooper()) depth-- }
        })
        hook(renderer,object: XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if(Looper.myLooper()!=Looper.getMainLooper()) return
                val nativeState=p.args[stateIndex] ?: return
                if(depth>0) {
                    state=WeakReference(nativeState)
                    owner=WeakReference(activityRef?.get())
                    if(role=="gestureDrawer") {
                        val tree=owner.get()?.window?.decorView?.viewTreeObserver
                        if(tree!==observer) {
                            detachObserver()
                            observer=tree
                            tree?.addOnPreDrawListener(onFrame)
                        }
                    }
                }
                if(state.get()!==nativeState) return
                syncDrawer()
            }
        })
        hook(isOpen,object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if(Looper.myLooper()==Looper.getMainLooper() && state.get()===p.thisObject)
                    (p.result as? Boolean)?.let(XAdapter::onDrawerChanged)
            }
        })
        XposedBridge.hookAllMethods(Activity::class.java,"onDestroy",object: XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if(owner.get()!==p.thisObject) return
                detachObserver()
                owner.clear(); state.clear()
                XAdapter.onDrawerChanged(false)
            }
        })
    }

    private fun installDetails(bindings: XBindingResolver.Bindings) {
        val detail = bindings.detailClass ?: return
        val destroy = bindings.methods["detailDestroy"] ?: return
        val lifecycle = detail.getMethod("getLifecycle")
        val state = lifecycle.returnType.methods.single { method ->
            method.parameterCount == 0 && method.returnType.isEnum &&
                method.returnType.enumConstants?.any { (it as? Enum<*>)?.name == "DESTROYED" } == true
        }
        XposedBridge::class.java.getMethod("hookAllConstructors", Class::class.java,
            XC_MethodHook::class.java).invoke(null, detail, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if ((state.invoke(lifecycle.invoke(p.thisObject)) as? Enum<*>)?.name == "DESTROYED") return
                handler.removeCallbacks(restoreDetail)
                details.add(p.thisObject)
                XAdapter.onDetailPageChanged(true)
            }
        })
        hook(destroy, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                details.remove(p.thisObject)
                handler.removeCallbacks(restoreDetail)
                if (details.isEmpty()) handler.postDelayed(restoreDetail, 400L)
            }
        })
    }
    private inline fun optional(name: String, action: () -> Unit) {
        runCatching(action).onFailure {
            report += "; $name unavailable: ${it.javaClass.simpleName}: ${it.message}"
            android.util.Log.w(TAG, "$name unavailable", it)
        }
    }
    internal fun invokeTabClick(tabType: Any): Boolean {
        val click = tabClick ?: return false
        val invoke = tabInvoke ?: return false
        return runCatching { invoke.invoke(click, tabType); true }
            .onFailure { android.util.Log.w(TAG, "tab callback failed", it) }.getOrDefault(false)
    }
    private fun hook(method: Method, callback: XC_MethodHook) {
        XposedBridge::class.java.getMethod("hookMethod", java.lang.reflect.Member::class.java,
            XC_MethodHook::class.java).invoke(null, method, callback)
    }
}

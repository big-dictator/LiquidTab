package io.github.offlineglass.hook.adapters.taobao

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import io.github.offlineglass.hook.GlassHostLayout
import java.lang.ref.WeakReference


/** Only consumes our marked visual messages through the existing Cart ability.
 * Native business methods, initial data and engine lifecycle stay untouched. */
internal object TaobaoCartNativeBridge {
    private val main = Handler(Looper.getMainLooper())
    private class Binding(root: ViewGroup, host: GlassHostLayout,
                          val action: (String, String) -> Unit) {
        val root = WeakReference(root)
        val host = WeakReference(host)
        var widget = WeakReference<TaobaoCartNativeButtons>(null)
        var ready = false
        var owned = false
        var requested = false
        var requestedAt = 0L
        var readyAt = Long.MAX_VALUE
        var lastRecord = ""
        var activation = 0L
    }
    private val bindings = LinkedHashMap<String, Binding>()
    private var suspended=true
    private var activeCartId:String?=null
    private var persistentWidget=WeakReference<TaobaoCartNativeButtons>(null)
    @Volatile private var frameTarget = WeakReference<TaobaoCartNativeButtons>(null)
    private var lastState:TaobaoCartNativeButtons.State?=null
    var installed = false
        private set

    fun install(loader: ClassLoader): Boolean {
        if (installed) return true
        return runCatching {
            val type = Class.forName("com.taobao.android.icart.weex.ability.CartAbility", false, loader)
            val method = type.declaredMethods.single { it.name == "callNative" && it.parameterCount == 3 }
            val params = method.parameterTypes[1]
            val contract=TaobaoCartAbilityContract.resolve(params)
            val name=contract.method();val data=contract.data();val instance=contract.instance()
            val success = method.parameterTypes[2].getMethod("onSuccess", Map::class.java)
            XposedBridge.hookAllMethods(type, "callNative", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val model = param.args.getOrNull(1) ?: return
                    if (name.get(model) != "saveInstanceState") return
                    val values = data.get(model) as? Map<*, *> ?: return
                    val state = values["__liquidtabCartState"] as? Map<*, *>
                    val ready = values["__liquidtabCartReady"] == true
                    if (state == null && !ready) return
                    val id = instance.get(model) as? String ?: return
                    val activation=(values["__liquidtabCartEpoch"] as? Number)?.toLong() ?: return
                    val callback = param.args.getOrNull(2) ?: return
                    // Our envelope must never enter the real save-state handler.
                    param.result = null
                    val snapshot = state?.let { TaobaoCartNativeButtons.State.from(it) }
                    main.post {
                        if(suspended||activeCartId!=id||bindings[id]?.activation!=activation){runCatching{success.invoke(callback,mapOf("accepted" to false))};return@post}
                        val binding = bindings[id]
                        val root = binding?.root?.get()
                        val host = binding?.host?.get()
                        var accepted = false
                        runCatching {
                            if (binding != null && root != null && host != null &&
                                root.isAttachedToWindow && (host.appNavigationState as? TaobaoNavigationState)?.cartPageActive() == true &&
                                TaobaoCartInitialResource.owns(root)) {
                                if (ready) {
                                    binding.ready = true
                                    binding.owned = true
                                    binding.widget.get()?.setInputEnabled(true)
                                    binding.readyAt = android.os.SystemClock.uptimeMillis()
                                    binding.widget.get()?.visibility = View.VISIBLE
                                    accepted = binding.widget.get() != null
                                } else if (snapshot != null && snapshot.available) {
                                    val widget = ensureWidget(binding,root,host)
                                    lastState=snapshot
                                    widget.setInputEnabled(true)
                                    widget.update(snapshot)
                                    if (binding.ready || binding.owned) widget.visibility = View.VISIBLE
                                    accepted = true
                                    if (!binding.ready) binding.action("__visualReady", "ready")
                                } else {
                                    binding.widget.get()?.setInputEnabled(false)
                                    if(binding.owned)binding.widget.get()?.visibility=View.VISIBLE
                                    accepted=binding.owned
                                }
                            } else if (binding != null) {
                                binding.ready = false
                                binding.requested = false
                                binding.widget.get()?.setInputEnabled(false)
                                if(binding.owned&&host!=null&&(host.appNavigationState as? TaobaoNavigationState)?.cartPageActive()==true)
                                    binding.widget.get()?.visibility=View.VISIBLE
                                else binding.widget.get()?.visibility=View.GONE
                            }
                        }.onFailure {
                            binding?.widget?.get()?.setInputEnabled(false)
                            binding?.ready = false
                        }
                        if (!accepted && binding != null && !binding.owned) runCatching { binding.action("__visualFallback", "ready") }
                        runCatching { success.invoke(callback, mapOf("accepted" to accepted,
                            "ready" to (binding?.ready == true))) }
                        val recordKey = "$accepted:${binding?.ready}:${snapshot?.available}"
                        if (root != null && binding != null && binding.lastRecord != recordKey) runCatching {
                            binding.lastRecord = recordKey
                            val app = root.context.applicationContext as? android.app.Application
                            val folder = app?.getExternalFilesDir(null)
                            if (folder != null) java.io.File(folder, "liquidtab-cart-native-buttons.json")
                                .writeText(org.json.JSONObject().put("accepted", accepted)
                                    .put("pid", android.os.Process.myPid())
                                    .put("updatedAt", System.currentTimeMillis())
                                    .put("ready", binding?.ready == true)
                                    .put("available", snapshot?.available).put("contract", state?.get("contract")).toString())
                        }
                    }
                }
            })
            installed = true
            true
        }.onFailure { android.util.Log.e("LiquidTabCart", "Ability contract failed", it) }.getOrDefault(false)
    }

    fun onNativeContentFrame() {
        frameTarget.get()?.onNativeContentFrame()
    }

    private fun ensureWidget(binding:Binding,root:ViewGroup,host:GlassHostLayout):TaobaoCartNativeButtons{
        val parent=host.parent as? ViewGroup?:root
        val previous=binding.widget.get()?:persistentWidget.get()
        if(previous!=null&&(previous.parent!==parent||!previous.belongsTo(host))){(previous.parent as? ViewGroup)?.removeView(previous);previous.release()}
        val widget=previous?.takeIf{it.parent===parent&&it.belongsTo(host)}
            ?:TaobaoCartNativeButtons(root.context,host,binding.action).also{
                parent.addView(it,FrameLayout.LayoutParams(-1,(88f*root.resources.displayMetrics.density).toInt()))
                it.visibility=View.INVISIBLE
            }
        widget.rebindAction(binding.action)
        binding.widget=WeakReference(widget);persistentWidget=WeakReference(widget)
        frameTarget=WeakReference(widget)
        return widget
    }
    fun bind(id: String, root: ViewGroup, host: GlassHostLayout,
             action: (String, String) -> Unit): Boolean {
        val resuming=suspended||activeCartId!=id
        if(!suspended&&activeCartId!=id) bindings[activeCartId]?.let {
            it.activation++
            runCatching { it.action("__pause","page") }
            it.widget.get()?.setInputEnabled(false)
        }
        suspended=false
        activeCartId=id
        bindings.entries.removeAll { it.value.root.get() == null }
        val existing = bindings[id]?.takeIf { it.root.get() === root && it.host.get() === host }
        val binding = existing ?: Binding(root, host, action)
            .also { bindings[id] = it }
        // Root discovery can outlive an engine upgrade. Keep a bounded registry;
        // the action captures only a weak root, never the Activity/view graph.
        while(bindings.size>8){
            val stale=bindings.entries.firstOrNull{it.key!=id}?:break
            runCatching{stale.value.action("__pause","retired")}
            bindings.remove(stale.key)
        }
        if(binding.widget.get()==null&&lastState!=null){
            val widget=ensureWidget(binding,root,host);widget.update(lastState!!);widget.setInputEnabled(false)
            binding.owned=true;widget.visibility=View.VISIBLE
        }
        binding.widget.get()?.let {
            frameTarget=WeakReference(it)
            if (binding.ready || binding.owned) it.visibility = View.VISIBLE
            it.updateGeometry()
        }
        val now = android.os.SystemClock.uptimeMillis()
        if(resuming&&existing!=null){
            binding.widget.get()?.setInputEnabled(false)
            binding.requested=true
            binding.requestedAt=now
            runCatching{binding.action("__resume",binding.activation.toString())}
            return false
        }
        if (binding.requested && (binding.ready || now - binding.requestedAt < 500L)) return false
        binding.requested = true
        binding.requestedAt = now
        return true
    }

    fun invalidateFrames(copyStartedAt: Long, changed: Boolean) {
        bindings[activeCartId]?.let { binding -> binding.widget.get()?.takeIf { widget -> widget.visibility == View.VISIBLE }
            ?.let { widget ->
                val firstClean = binding.ready && copyStartedAt >= binding.readyAt && widget.acceptCleanBackdrop()
                // Changed frames are already scheduled directly at publication.
                // Only the first clean/ready handoff still needs main-owned state.
                if (firstClean) widget.invalidate()
            } }
    }

    /** Do not read the main-thread binding map from the optical worker. */
    fun onBackdropFramePublished() {
        frameTarget.get()?.onBackdropFramePublished()
    }

    fun suspend() { if(suspended)return; frameTarget=WeakReference(null); suspended=true; bindings[activeCartId]?.let {
        it.activation++
        runCatching { it.action("__pause","page") }
        it.widget.get()?.visibility = View.GONE
        it.widget.get()?.setInputEnabled(false)
        // Native cart reuses its engine/root across detach/attach. Keep the
        // clean visual handshake; resume refreshes business state before input.
    } }
    fun clear() {
        frameTarget=WeakReference(null)
        bindings.values.forEach { runCatching { it.action("__dispose", "ready") } }
        val widgets=(bindings.values.mapNotNull{it.widget.get()}+listOfNotNull(persistentWidget.get())).distinct()
        widgets.forEach{widget->(widget.parent as? ViewGroup)?.removeView(widget);widget.release()}
        bindings.clear()
        persistentWidget.clear();lastState=null;activeCartId=null
    }
}

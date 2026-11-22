package io.github.offlineglass.hook.adapters.mi_market

import android.app.Activity
import android.util.Log
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge
import org.json.JSONObject

/** Filter recommendation payloads without bypassing native page-loading callbacks. */
internal object MiMarketUpgradeRecommendations {
    fun install(loader: ClassLoader) {
        val modern = "com.xiaomi.market.ui.update.UpdateListV2Fragment"
        val requestApi = runCatching {
            Class.forName(modern, false, loader).getMethod("getPageRequestApi").also {
                require(it.returnType == String::class.java)
            }
        }.onFailure { Log.w("LiquidTabMarketBinding", "Upgrade recommendation endpoint unavailable", it) }
            .getOrNull()
        if (requestApi != null) {
            bind(loader, modern, "onRefreshPageData",
                List::class.java, Boolean::class.javaPrimitiveType!!, JSONObject::class.java) {
                // The fragment builds ordinary updates separately from local app state.
                if (runCatching { requestApi.invoke(it.thisObject) }.getOrNull() == "recommend/upgradepage") {
                    it.args[0] = ArrayList<Any>()
                }
            }
        }
        val legacy = "com.xiaomi.market.ui.UpdateListFragment"
        runCatching {
            val result = Class.forName("com.xiaomi.market.data.ShallowCloneable", false, loader)
            val grid = Class.forName("com.xiaomi.market.viewmodels.RecommendationGridResult", false, loader)
            val groups = Class.forName("com.xiaomi.market.viewmodels.RecommendGroupResult", false, loader)
            require(result.isAssignableFrom(grid) && result.isAssignableFrom(groups))
            bind(loader, legacy, "onLoadFinished", Int::class.javaPrimitiveType!!, result) { param ->
                val kind = param.args[0]
                val payload = param.args[1]
                // This callback calls stopLoadingInternal() even with a null payload.
                if ((kind == 0 && grid.isInstance(payload)) || (kind == 1 && groups.isInstance(payload))) {
                    param.args[1] = null
                }
            }
        }.onFailure { Log.w("LiquidTabMarketBinding", "Legacy upgrade recommendation result contract unavailable", it) }
        // This separate per-app request only fetches recommendations for an expanded row.
        bind(loader, legacy, "loadRelatedApps", String::class.java) { it.result = null }
    }

    private fun bind(loader: ClassLoader, owner: String, name: String, vararg args: Class<*>,
        filter: (XC_MethodHook.MethodHookParam) -> Unit) {
        runCatching {
            val type = Class.forName(owner, false, loader)
            val activityGetter = type.getMethod("getActivity")
            require(Activity::class.java.isAssignableFrom(activityGetter.returnType))
            val method = type.getDeclaredMethod(name, *args)
            require(method.returnType == Void.TYPE)
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = runCatching { activityGetter.invoke(param.thisObject) as? Activity }.getOrNull()
                        ?: return
                    if (activity.javaClass.name == "com.xiaomi.market.ui.UpdateListActivity" &&
                        HookConfigReader.read(activity, activity.packageName).enabled) filter(param)
                }
            })
        }.onFailure { Log.w("LiquidTabMarketBinding", "Upgrade recommendation contract unavailable: $owner.$name", it) }
    }
}

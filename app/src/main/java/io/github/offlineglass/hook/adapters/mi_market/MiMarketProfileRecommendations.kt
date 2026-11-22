package io.github.offlineglass.hook.adapters.mi_market

import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.offlineglass.hook.HookConfigReader
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** The profile's recommendation feed is a separate child, added after the native tools. */
internal object MiMarketProfileRecommendations {
    fun install(loader: ClassLoader) {
        runCatching {
            val profile = Class.forName(
                "com.xiaomi.market.business_ui.main.mine.NativeMinePagerFragment", false, loader,
            )
            val bindAds = profile.getDeclaredMethod("bindAdFragmentView", Int::class.javaPrimitiveType!!)
            val hideAds = profile.getMethod("onAdModuleHidden")
            val getHeader = profile.getMethod("getExpandCollapseHeaderLayout")
            val getActivity = profile.getMethod("getActivity")
            require(bindAds.returnType == Void.TYPE && hideAds.returnType == Void.TYPE)
            require(LinearLayout::class.java.isAssignableFrom(getHeader.returnType))
            require(Activity::class.java.isAssignableFrom(getActivity.returnType))

            XposedBridge.hookMethod(bindAds, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val page = param.thisObject
                    val activity = getActivity.invoke(page) as? Activity ?: return
                    val containerId = activity.resources.getIdentifier("mine_ad_container", "id", activity.packageName)
                    if (containerId == View.NO_ID || containerId == 0 ||
                        !HookConfigReader.read(activity, activity.packageName).enabled
                    ) return

                    val header = getHeader.invoke(page) as? LinearLayout ?: return
                    val existing = header.findViewById<View>(containerId)
                    if (existing != null) {
                        if (existing !is FrameLayout || existing.parent !== header) {
                            Log.w("LiquidTabMarketBinding", "Unexpected profile recommendation container")
                            return
                        }
                        hideAds.invoke(page)
                    }
                    param.result = null
                }
            })
        }.onFailure {
            Log.w("LiquidTabMarketBinding", "Profile recommendation binding unavailable", it)
        }
    }
}

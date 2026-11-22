package io.github.offlineglass.hook.adapters.taobao

import android.os.Looper
import android.widget.TabHost
import io.github.offlineglass.hook.compat.XC_MethodHook
import io.github.offlineglass.hook.compat.XposedBridge

/** Batch only the original TBFragmentTabHost transaction. Cart's own Weex
 * proxy already retains its root. Never replace detach/attach or lifecycle. */
internal object TaobaoNativePageTransactions {
    private val owner = ThreadLocal<TabHost>()
    private var installed = false
    private var batches = 0

    fun <T> click(native: TabHost?, action: () -> T): T {
        if (!installed || native == null || Looper.myLooper()!=Looper.getMainLooper()) return action()
        val previous = owner.get()
        owner.set(native)
        return try { action() } finally {
            if (previous == null) owner.remove() else owner.set(previous)
        }
    }

    fun install(loader: ClassLoader) {
        if (installed) return
        runCatching {
            val host = Class.forName("com.taobao.tao.navigation.TBFragmentTabHost", false, loader)
            val transaction = Class.forName("androidx.fragment.app.FragmentTransaction", false, loader)
            require(TabHost::class.java.isAssignableFrom(host))
            val change=host.getDeclaredMethod("doTabChanged",String::class.java,transaction)
            require(change.returnType==transaction)
            val reorder=transaction.getMethod("setReorderingAllowed", Boolean::class.javaPrimitiveType)
            require(transaction.isAssignableFrom(reorder.returnType))
            XposedBridge.hookMethod(change, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (Looper.myLooper()!=Looper.getMainLooper() || owner.get() !== param.thisObject) return
                    val result = param.result ?: return
                    if(!transaction.isInstance(result))return
                    runCatching {
                        // Native onTabChanged commits this returned transaction
                        // immediately next. Mark it here; no global hook on
                        // every Fragment transaction or second discovery map.
                        reorder.invoke(result,true)
                        batches++
                    }
                }
            })
            installed = true
        }.onFailure { XposedBridge.log("LiquidTab-Taobao: native transaction batching unavailable: ${it.javaClass.simpleName}") }
    }

    fun status(): String = "nativeBatching=$installed batches=$batches nativeStateCache=original"
}

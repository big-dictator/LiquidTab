package io.github.offlineglass.hook.adapters.xhs

import android.view.View
import java.lang.reflect.Method

/** Reads native channel selection even while its RecyclerView has zero height. */
internal class XhsTopChannel {
    private var tabs: View? = null
    private var selected: Method? = null
    private var item: Method? = null
    private var previous: Any? = null
    private var title: String? = null
    private var attempted = false

    fun bind(row: View?) {
        val next = row?.let { XhsTopBinding.find(it) { view ->
            view.javaClass.name == "com.xingin.matrix.widgets.NewTabLayout"
        } }
        if (next === tabs && (attempted || !XhsNativeBindings.ready)) return
        tabs = next; selected = null; item = null; previous = null; title = null
        attempted = XhsNativeBindings.ready
        if (next == null || !attempted) return
        runCatching {
            selected = next.javaClass.getMethod("getCurrentSelectedTabPosition")
            // Native E(int)->ItemBean in 9.52; derive its signature, not its name.
            item = next.javaClass.methods.single { method ->
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) &&
                    method.returnType == XhsNativeBindings.field("channelTitle")?.declaringClass
            }
        }.onFailure { android.util.Log.w("LiquidTabXhsTop", "native channel title binding unavailable", it) }
    }

    fun currentTitle(): String? {
        val view = tabs?.takeIf { it.isAttachedToWindow } ?: return null
        return runCatching {
            val index = selected?.invoke(view) as? Int ?: return null
            if (index < 0) return null
            val model = item?.invoke(view, index) ?: return null
            if (model !== previous) {
                title = (XhsNativeBindings.field("channelTitle")?.get(model) as? String)?.trim()?.takeIf(String::isNotEmpty)
                previous = model
            }
            title
        }.getOrNull()
    }
}

package io.github.offlineglass.hook.adapters.tieba

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.reflect.Method

/** Children of the verified pager strip own their tab click and native label. */
internal class TiebaChannelContract {
    data class Channel(val view: View, val title: TextView)

    private val labelAccessors = mutableMapOf<Class<*>, Method?>()

    fun read(strip: ViewGroup): List<Channel>? {
        val channels = ArrayList<Channel>(7)
        for (index in 0 until strip.childCount) {
            val tab = strip.getChildAt(index)
            if (tab.visibility != View.VISIBLE) continue
            // The shared glass host supports up to seven selectable slots.
            if (channels.size == 7 || tab !is ViewGroup || !tab.hasOnClickListeners()) return null
            val title = readTitle(tab) ?: return null
            channels.add(Channel(tab, title))
        }
        return channels.takeIf { it.isNotEmpty() }
    }

    private fun readTitle(tab: ViewGroup): TextView? {
        val type = tab.javaClass
        if (!labelAccessors.containsKey(type)) {
            if (labelAccessors.size >= 8) labelAccessors.clear()
            labelAccessors[type] = runCatching { type.getMethod("getTextView") }.getOrNull()
                ?.takeIf { TextView::class.java.isAssignableFrom(it.returnType) }
        }
        val getter = labelAccessors[type] ?: return null
        val title = runCatching { getter.invoke(tab) as? TextView }.getOrNull() ?: return null
        return title.takeIf { it.parent === tab }
    }
}

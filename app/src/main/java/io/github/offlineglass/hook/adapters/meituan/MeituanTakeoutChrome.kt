package io.github.offlineglass.hook.adapters.meituan

import android.view.View
import android.view.ViewGroup

/** Only confirmed navigation owners are changed; never sweep sibling page backgrounds. */
internal class MeituanTakeoutChrome {
    private var binding: MeituanTakeoutBinding? = null
    private var paints = emptyList<View>()

    fun update(value: MeituanTakeoutBinding) {
        if (binding !== value) {
            release(); binding = value
            MeituanTakeoutOwnership.alpha(value.row, 1f)
            MeituanTakeoutOwnership.alpha(value.shell)
            listOf(value.row, value.shell).forEach { MeituanTakeoutOwnership.paint(it) }
            value.decoration?.let { MeituanTakeoutOwnership.visibility(it) }
            MeituanTakeoutOwnership.layout(value.pageContainer)
            paints = listOf(value.row, value.shell)
        }
        if (!value.valid()) { maintain(); return }
        value.row.alpha = 0f
        value.shell.alpha = 0f
        paints.forEach { if (it.background != null) it.background = null; if (it.foreground != null) it.foreground = null }
        value.decoration?.let { if (it.visibility != View.INVISIBLE) it.visibility = View.INVISIBLE }
        val params = value.pageContainer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.height != ViewGroup.LayoutParams.MATCH_PARENT || params.bottomMargin != 0) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT; params.bottomMargin = 0
            value.pageContainer.layoutParams = params
        }
    }
    fun maintain() {
        binding?.let { value ->
            if (value.shell.isAttachedToWindow) value.shell.alpha = 0f
            if (value.row.isAttachedToWindow) value.row.alpha = 0f
            value.decoration?.let { if (it.isAttachedToWindow) it.visibility = View.INVISIBLE }
        }
    }
    fun release() {
        binding = null; paints = emptyList()
    }
}

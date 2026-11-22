package io.github.offlineglass.hook.adapters.apple_music

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.View

/** Native feed layers can contain only text and artwork over the activity background. */
internal fun appleMusicBackdropBase(scene: View, fallback: Int): Int {
    var view: View? = scene
    repeat(4) {
        val current = view ?: return@repeat
        val color = (current.background as? ColorDrawable)?.color
        if (color != null && Color.alpha(color) == 255) return color
        view = current.parent as? View
    }
    // Apple Music's activity theme uses an opaque color for windowBackground.
    val value = TypedValue()
    if (scene.context.theme.resolveAttribute(android.R.attr.windowBackground, value, true) &&
        value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT &&
        Color.alpha(value.data) == 255) return value.data
    return fallback or 0xff000000.toInt()
}

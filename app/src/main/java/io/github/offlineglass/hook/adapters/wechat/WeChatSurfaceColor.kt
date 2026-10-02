package io.github.offlineglass.hook.adapters.wechat

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View

internal object WeChatSurfaceColor {
    fun resolve(activity: Activity?, source: View?, dark: Boolean): Int {

        val windowColor = activity?.window?.navigationBarColor ?: Color.TRANSPARENT

        if (Color.alpha(windowColor) >= 224) {

            return Color.rgb(Color.red(windowColor), Color.green(windowColor), Color.blue(windowColor))

        }

        var current = source?.parent as? View

        repeat(6) {

            val background = current?.background as? ColorDrawable

            if (background != null && Color.alpha(background.color) >= 224) {

                val color = background.color

                return Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

            }

            current = current?.parent as? View

        }

        return if (dark) Color.rgb(24, 24, 24) else Color.rgb(245, 245, 245)

    }
}


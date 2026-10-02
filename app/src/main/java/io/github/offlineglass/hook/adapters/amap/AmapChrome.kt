package io.github.offlineglass.hook.adapters.amap

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup

/** Native Amap pill, cell selection and indicator chrome; icon leaves remain intact. */
internal object AmapChrome {
    fun suppress(navigation: ViewGroup) {
        if (navigation.width <= 0) return
        clearRecursive(navigation, 0)
        navigation.postDelayed({ clearRecursive(navigation, 0) }, 250)
        navigation.postDelayed({ clearRecursive(navigation, 0) }, 700)
    }

    private fun clearRecursive(root: View, depth: Int) {
        if (depth > 16) return
        val idName = runCatching {
            if (root.id == View.NO_ID) "" else root.resources.getResourceEntryName(root.id)
        }.getOrDefault("") ?: ""
        val isContainer = root is ViewGroup
        val bgHolderId = idName.contains("bg", ignoreCase = true) ||
            idName.contains("background", ignoreCase = true) ||
            idName.contains("layer", ignoreCase = true) ||
            idName.contains("indicator", ignoreCase = true)
        if (isContainer || bgHolderId) {
            runCatching { root.background = ColorDrawable(Color.TRANSPARENT) }
            runCatching { root.backgroundTintList = null }
            runCatching { root.backgroundTintMode = null }
            runCatching { root.elevation = 0f }
            runCatching { root.stateListAnimator = null }
        }
        if (isContainer) {
            runCatching { root.foreground = null }
            runCatching { root.foregroundTintList = null }
            runCatching { root.foregroundTintMode = null }
            val group = root as ViewGroup
            for (i in 0 until group.childCount) clearRecursive(group.getChildAt(i), depth + 1)
        }
    }
}

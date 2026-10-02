package io.github.offlineglass.hook.adapters.mi_community

import android.content.Context
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter

internal object MiCommunityAdapter : TargetAdapter {
    override val key = "mi_community"
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val skipNavigationSnapshot = true
    override val usesContentDarkMode = true
    override fun createNavigationState(context: Context): AppNavigationState =
        MiCommunityNavigationState(context)
}

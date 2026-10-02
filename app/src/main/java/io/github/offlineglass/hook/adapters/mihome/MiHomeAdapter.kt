package io.github.offlineglass.hook.adapters.mihome

import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.hook.adapters.AppNavigationState
import android.content.Context

/** Mi Home owns its navigation metadata and shared-veil enrollment here. */
internal object MiHomeAdapter : TargetAdapter {
    override val key = "mihome"
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    // Initializes native tab artwork and the host used by chrome/edit-mode handling.
    override val hasPerFrameScene = true
    override val usesContentDarkMode = true
    override fun createNavigationState(context: Context): AppNavigationState = MiHomeNavigationState()
    override fun hookSignals() = io.github.offlineglass.hook.adapters.mihome.hookSignals()
}

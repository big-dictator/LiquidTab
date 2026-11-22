package io.github.offlineglass.hook.adapters.pdd

import android.content.Context
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter

/** Pinduoduo-owned policies; shared renderer behavior is selected through adapter hooks. */
internal object PddAdapter : TargetAdapter {
    override val key = "pdd"
    override val ownsNavigationDrawing = true
    override val usesCommittedSelection = true
    override val updatesSceneDuringTapTransition = true
    override val retainsExistingBackdropRenderNode = true
    override fun createNavigationState(context: Context): AppNavigationState = PddNavigationState()
}

internal const val PDD_PROMOTION_INDEX = 2

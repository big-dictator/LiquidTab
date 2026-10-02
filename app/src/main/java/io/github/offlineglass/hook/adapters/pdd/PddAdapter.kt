package io.github.offlineglass.hook.adapters.pdd

import android.content.Context
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter

/** Pinduoduo-owned policies; shared renderer behavior is selected through adapter hooks. */
internal object PddAdapter : TargetAdapter {
    override val key = "pdd"
    override val ownsNavigationDrawing = true
    override val usesCommittedSelection = true
    override val usesOpticalSurfacePipeline = true
    override val usesNativeOpticalBlur = true
    override fun isOpticalSurfaceTab(selectedIndex: Int) = selectedIndex == PROMOTION_INDEX
    override fun needsContinuousOpticalSurfaceFrames(selectedIndex: Int) = selectedIndex == PROMOTION_INDEX
    override fun createNavigationState(context: Context): AppNavigationState = PddNavigationState()

    private const val VIDEO_INDEX = 1
    private const val PROMOTION_INDEX = 2
}

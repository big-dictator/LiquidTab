package io.github.offlineglass.hook.adapters.xhs

import android.content.Context
import android.app.Activity
import android.view.ViewGroup
import android.view.WindowInsets
import android.graphics.Insets
import io.github.offlineglass.hook.adapters.AppNavigationState
import io.github.offlineglass.hook.adapters.TargetAdapter

internal object XhsAdapter : TargetAdapter {
    override val key = "xhs"
    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val commitSelectionAfterTap = true
    override val usesCommittedSelection = true
    override val usesContentDarkMode = true
    override val hasPerFrameScene = true
    override fun scrollStopHideTab(selectedIndex: Int): Boolean = false
    override fun resetScrollStopHideOnOtherTabs(): Boolean = true
    override fun isBackPeekTab(selectedIndex: Int): Boolean = false
    override fun isBackPeekActivity(activity: Activity): Boolean = activity.javaClass.name in MAIN_TAB_ACTIVITIES
    override val backPeekDurationMs = 1500L
    override fun createNavigationState(context: Context): AppNavigationState = XhsNavigationState()

    override fun prepareContentForInstall(content: ViewGroup) {
        // Remove only navigation fitting. Keep status/cutout and IME insets intact.
        content.setOnApplyWindowInsetsListener { _, insets ->
            val type = WindowInsets.Type.navigationBars()
            val navigation = insets.getInsets(type)
            WindowInsets.Builder(insets)
                .setInsets(type, Insets.of(navigation.left, navigation.top, navigation.right, 0))
                .setInsetsIgnoringVisibility(type, Insets.of(navigation.left, navigation.top, navigation.right, 0))
                .build()
        }
        content.requestApplyInsets()
    }

    private val MAIN_TAB_ACTIVITIES = setOf(
        "com.xingin.xhs.index.v2.IndexActivityV2",
        "com.xingin.xhs.IndexActivity",
        "com.xingin.xhs.activity.LauncherActivity",
    )
}

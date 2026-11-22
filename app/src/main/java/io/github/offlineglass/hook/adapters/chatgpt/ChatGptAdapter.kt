package io.github.offlineglass.hook.adapters.chatgpt

import android.view.View
import io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** The adapter binds Compose controls and delegates the tab bar to the shared host. */
internal object ChatGptAdapter : TargetAdapter {
    override val key = "chatgpt"
    override val ownsNavigationFinding = true

    override val ownsNavigationDrawing = true
    override val ownsNavigationTap = true
    override val foregroundNavigationOverSelection = true
    override val skipNavigationSnapshot = true
    override val hasPerFrameScene = true
    override val ownsHostLayout = true
    override val skipBackdropStableDelay = true
    override val allowsSystemBackgroundBlur = false
    override fun createNavigationState(context: android.content.Context, host: io.github.offlineglass.hook.GlassHostLayout) =
        ChatGptBottomBar.state(host)

    override fun installHooks(lpparam: XC_LoadPackage.LoadPackageParam, spec: TargetSpec) {
        ChatGptComposerGlass.install(lpparam.classLoader)
    }

    override fun findNavigation(root: View, spec: TargetSpec) = null

    override fun beforeNavigationScan(activity: android.app.Activity) { ChatGptComposerGlass.scan(activity) }
}

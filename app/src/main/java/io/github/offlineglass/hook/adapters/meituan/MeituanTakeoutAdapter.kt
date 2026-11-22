package io.github.offlineglass.hook.adapters.meituan

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.github.offlineglass.hook.NavigationCandidate
import io.github.offlineglass.hook.adapters.TargetAdapter
import io.github.offlineglass.targets.TargetSpec

/** Native identity comes from a verified resource/role/input graph. */
internal object MeituanTakeoutAdapter : TargetAdapter {
    override val key = "meituan"
    override val ownsNavigationFinding = true
    override val hasPerFrameScene = true
    override val ownsNavigationTap = true
    override val commitSelectionAfterTap = true
    override val nativeSelectionReliable = true
    override val trackSourceDrawableIdentity = true
    override val stagedNavigationSnapshot = true
    override val preserveNativeParentBackground = true
    // This is the declared settings slot, not a native binding locator.
    // Availability itself still follows the shared semantic binding.
    override fun normalizeConfig(config: io.github.offlineglass.config.GlassConfig) =
        config.copy(hiddenMask = targetSpec.automaticEntryIndices.fold(config.hiddenMask) { mask, index ->
            mask and (1 shl index).inv()
        })
    override fun resetScrollStopHideOnOtherTabs() = true
    override fun installBootstrapHooks(param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        if (param.processName == param.packageName) MeituanTakeoutSplash.bootstrap()
    }
    override fun onConfigurationReady(context: Context,
        param: io.github.offlineglass.hook.compat.callbacks.XC_LoadPackage.LoadPackageParam) {
        if (param.processName == param.packageName) MeituanTakeoutSplash.install(context)
    }
    override fun createNavigationState(context: Context) = MeituanTakeoutNavigationState()
    override fun createNavigationState(context: Context, host: io.github.offlineglass.hook.GlassHostLayout) =
        MeituanTakeoutNavigationState(host)

    override fun estimateSlotCount(group: ViewGroup, spec: TargetSpec): Int? {
        return MeituanTakeoutBinding.forRow(group)?.slots?.size
    }

    override fun findNavigation(root: View, spec: TargetSpec): NavigationCandidate? {
        val binding = MeituanTakeoutBinding.discover(root) ?: return null
        return NavigationCandidate(binding.row, binding.slots.size, 1000)
    }

}

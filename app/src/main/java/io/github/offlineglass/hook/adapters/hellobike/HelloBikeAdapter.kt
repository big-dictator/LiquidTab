package io.github.offlineglass.hook.adapters.hellobike

import io.github.offlineglass.hook.adapters.AppNativeChromeController
import io.github.offlineglass.hook.adapters.TargetAdapter

internal object HelloBikeAdapter : TargetAdapter {
    override val key = "hellobike"
    override val nativeSelectionReliable = false
    override val drawsNavigationInOptics = true
    override fun createNativeChromeController(): AppNativeChromeController = HelloBikeChromeController()
}

package io.github.offlineglass.hook.adapters

internal object TargetHookSignalsRegistry {
    fun forKey(key: String): List<TargetHookSignal> {
        TargetAdapterRegistry.forKey(key)?.hookSignals()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        // Deferred adapters retain their existing signal providers unchanged.
        when (key) {
            "mi_file_manager" -> return io.github.offlineglass.hook.adapters.mi_file_manager.hookSignals()
            "mi_gallery" -> return io.github.offlineglass.hook.adapters.mi_gallery.hookSignals()
            "mi_theme" -> return io.github.offlineglass.hook.adapters.mi_theme.hookSignals()
        }
        return io.github.offlineglass.targets.TargetSpecRegistry.all
            .firstOrNull { it.key == key }?.hookSignals ?: emptyList()
    }
}

internal fun commonMiuixSignals(): List<TargetHookSignal> = listOf(
    TargetHookSignal("miuix.navigator.bottomnavigation.BottomNavigationView", listOf("onAttachedToWindow", "setSelectedItemId")),
    TargetHookSignal("miuix.navigator.navigation.NavigationBarView", listOf("onAttachedToWindow", "inflateMenu", "show", "hide")),
)

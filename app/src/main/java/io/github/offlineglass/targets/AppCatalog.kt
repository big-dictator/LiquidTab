package io.github.offlineglass.targets

enum class AdapterSource {
    DEDICATED,
    GENERIC_FALLBACK,
}

data class HideOption(val label: String, val index: Int)

data class HookSignal(val className: String, val methods: List<String>)

data class TargetSpec(
    val key: String,
    val packageName: String,
    val displayName: String,
    val source: AdapterSource,
    val activityHints: Set<String> = emptySet(),
    val idHints: Set<String> = emptySet(),
    val classHints: Set<String> = emptySet(),
    val textHints: Set<String> = emptySet(),
    val preferredSlots: IntRange = 2..6,
    val hideOptions: List<HideOption> = emptyList(),
    val supportsPostButton: Boolean = false,
    val supportsChannel: Boolean = false,
    val defaultIconOnly: Boolean = false,
    /** Initial hidden slots for targets without a saved per-app override. */
    val defaultHiddenMask: Int = 0,
    val defaultAccentColor: Int = -30208,
    val entryLabels: List<String> = emptyList(),
    /** Settings slots controlled by native capability detection, without manual switches. */
    val automaticEntryIndices: Set<Int> = emptySet(),
    val hookSignals: List<HookSignal> = emptyList(),
    val drawsNavigationInOptics: Boolean = false,
    val glassAccentColor: Int? = null,
    val sceneVisibilityAnimation: Boolean = false,
    // Extra processes that host this app's UI but keep their own package name
    // (e.g. Theme Store delegates its home UI to the system theme manager
    // process). They share the primary package's config and appear in the
    // module UI only under the primary entry.
    val aliasPackages: Set<String> = emptySet(),
    /** Exact package-relative UI process suffixes; empty preserves main-process-only injection. */
    val uiProcessSuffixes: Set<String> = emptySet(),
    /** Optional non-UI hooks, owned by the target directory. */
    val processHookInstaller: ((android.content.Context, ClassLoader) -> Unit)? = null,
    val processHooksAlwaysEnabled: Boolean = false,
    val installUiHooks: Boolean = true,
    /** Visual targets whose own installer manages non-Activity floating windows. */
    val ownsFloatingWindowHooks: Boolean = false,
    val hooksAllProcesses: Boolean = false,
    /** Keep externally bridged system-app controls reachable even when PM hides the package. */
    val alwaysShowInManager: Boolean = false,
    /** Per-target initial value for the independent publish action. */
    val defaultDetachedPublish: Boolean = false,
) {
    fun covers(packageName: String): Boolean =
        packageName == this.packageName || packageName in aliasPackages
}

object AppCatalog {
    private val archivedKeys = setOf(
        "mi_phone", "mi_calendar", "mi_notes",
        "mi_messages", "hellobike",
        "mi_file_manager", "mi_theme", "system_picker",
    )
    // Stable display order for supported targets. Dedicated adapters take precedence.
    private val allTargets: List<TargetSpec> = TargetSpecRegistry.all
    val targets: List<TargetSpec> = allTargets.filterNot { it.key in archivedKeys }
    // Network-only hooks remain registered, but are not visual app adaptations.
    val uiTargets: List<TargetSpec> = targets.filter { it.installUiHooks }
    private val byPackage = targets.associateBy(TargetSpec::packageName)
    private val byAlias = targets.flatMap { spec ->
        spec.aliasPackages.map { pkg -> pkg to spec }
    }.toMap()
    fun forPackage(packageName: String): TargetSpec? =
        byPackage[packageName] ?: byAlias[packageName]
}

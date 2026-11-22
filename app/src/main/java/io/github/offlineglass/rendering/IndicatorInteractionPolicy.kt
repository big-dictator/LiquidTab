package io.github.offlineglass.rendering

/** Per-component interaction contract. Default retains every bottom-bar effect. */
internal enum class IndicatorInteractionPolicy(
    val expandsOnInteraction: Boolean,
    val rendersSelectionLens: Boolean,
) {
    EXPANDING(expandsOnInteraction = true, rendersSelectionLens = true),
    /** Move/settle the selection over the ordinary glass panel; no extra lens scene. */
    FIXED_SIZE(expandsOnInteraction = false, rendersSelectionLens = false),
}

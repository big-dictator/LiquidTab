package io.github.offlineglass.rendering

/** Shared by injected navigation bars and the module's Compose navigation bar. */
internal fun indicatorHighlightAlpha(progress: Float, dark: Boolean): Float =
    progress.coerceIn(0f, 1f) * (if (dark) 0.48f else 1f) * 0.17f

/** Subtle active dispersion; preserve each pipeline's resting value and blend on release. */
internal fun indicatorChromaticAberration(progress: Float, resting: Float = 0.5f, active: Float = 0.40f): Float =
    resting + (active - resting) * progress.coerceIn(0f, 1f)

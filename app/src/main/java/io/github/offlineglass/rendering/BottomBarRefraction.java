package io.github.offlineglass.rendering;

/** Bar/hidden-row geometry only; no adapter identity or optical-strength policy. */
public final class BottomBarRefraction {
    private BottomBarRefraction() {}

    public static float depthPx(float requestedPx, float heightPx, float density) {
        // Leave the manager's 1dp neutral band on each side of the centre.
        // Otherwise opposing SDF normals meet inside the active lens: at y=0
        // their zero vector cannot be normalized, exposing a horizontal line.
        return Math.max(.5f, Math.min(requestedPx, heightPx * .5f - density));
    }
}

package io.github.offlineglass.rendering;
/** Dimensionless velocity deformation shared by injected and manager bars. */
public final class BottomBarDynamics {
    private BottomBarDynamics() {}
    public static float indicatorOutset(float restingHeight, float verticalScale) {
        return Math.max(0f, restingHeight * .5f * (verticalScale - 1f));
    }
    /** Reduced finger distance from the navigation centre with a smooth,
     * symmetric physical-pixel limit. Gesture ownership gates this to dragging. */
    public static float dragVerticalOffset(float deltaY, float density, float limitDp) {
        return dragVerticalOffset(deltaY, density, limitDp, 0f);
    }
    public static float dragVerticalOffset(float deltaY, float density, float limitDp, float speedDpPerSec) {
        if (!Float.isFinite(deltaY) || !Float.isFinite(density) || !Float.isFinite(limitDp)
                || density <= 0f || limitDp <= 0f) return 0f;
        float limit = density * limitDp;
        if (!Float.isFinite(limit)) return 0f;
        float speed = Float.isFinite(speedDpPerSec) ? Math.max(0f, speedDpPerSec) : 0f;
        float gain = .2f + .35f * (speed / (speed + 500f));
        return limit * (deltaY / (Math.abs(deltaY) + limit / gain));
    }
    public static float stretchX(float velocity) {
        return 1f / (1f - Math.max(-.2f, Math.min(.2f, velocity / 10f * .75f)));
    }
    public static float stretchY(float velocity) {
        return 1f - Math.max(-.2f, Math.min(.2f, velocity / 10f * .25f));
    }
}

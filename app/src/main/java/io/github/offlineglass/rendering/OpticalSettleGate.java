package io.github.offlineglass.rendering;

/** A Surface needs the terminal resting frame even after its animation flag falls. */
public final class OpticalSettleGate {
    private boolean wasAnimating;
    public boolean shouldRender(boolean animating, boolean dirty) {
        boolean settled = wasAnimating && !animating;
        wasAnimating = animating;
        return animating || dirty || settled;
    }
    public void reset() { wasAnimating = false; }
}

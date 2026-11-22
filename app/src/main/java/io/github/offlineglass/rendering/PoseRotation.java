package io.github.offlineglass.rendering;

/** Exact sensor coordinate transform into caller-owned storage. No filtering. */
public final class PoseRotation {
    private PoseRotation() {}
    public static void write(int rotation, float x, float y, float z, float strength, float[] out) {
        switch (rotation) {
            case 1: { float previous=x; x=-y; y=previous; break; }
            case 2: x=-x; y=-y; break;
            case 3: { float previous=x; x=y; y=-previous; break; }
            default: break;
        }
        out[0]=-x*strength; out[1]=-y*strength; out[2]=z;
    }
}

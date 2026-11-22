package io.github.offlineglass.rendering;

/** Conservative acceleration data, never a substitute for the original contour. */
public final class ContourSearchBounds {
    public static final int POINT_COUNT = 64;
    public static final int GROUP_COUNT = 8;
    public static final int GROUP_SIZE = 8;
    private final float[] boxes = new float[GROUP_COUNT * 4];
    private boolean enabled;

    public float[] getBoxes() { return boxes; }
    public boolean isEnabled() { return enabled; }

    public boolean update(float[] points) {
        enabled = false;
        java.util.Arrays.fill(boxes, 0f);
        if (points == null || points.length != POINT_COUNT * 2) return false;
        for (float point : points) {
            if (!Float.isFinite(point) || Math.abs(point) > 1_000_000f) return false;
        }
        for (int group = 0; group < GROUP_COUNT; group++) {
            float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
            // Eight segments have NINE endpoints. The last group's endpoint is point zero.
            for (int endpoint = 0; endpoint <= GROUP_SIZE; endpoint++) {
                int index = ((group * GROUP_SIZE + endpoint) % POINT_COUNT) * 2;
                float x = points[index], y = points[index + 1];
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
            float padX = Math.max(0.001f, 16f * Math.ulp(Math.max(1f, Math.max(Math.abs(left), Math.abs(right)))));
            float padY = Math.max(0.001f, 16f * Math.ulp(Math.max(1f, Math.max(Math.abs(top), Math.abs(bottom)))));
            int index = group * 4;
            boxes[index] = left - padX; boxes[index + 1] = top - padY;
            boxes[index + 2] = right + padX; boxes[index + 3] = bottom + padY;
        }
        enabled = true;
        return true;
    }
}

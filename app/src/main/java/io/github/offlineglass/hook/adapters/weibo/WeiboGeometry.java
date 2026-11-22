package io.github.offlineglass.hook.adapters.weibo;

public final class WeiboGeometry {
    private WeiboGeometry() {}
    public static float centerOffset(float height, float top, float bottom) {
        return height * .5f - (top + bottom) * .5f;
    }
    public static int bottomPadding(float listBottom, float barTop, float gap, int original) {
        return Math.max(original, (int) Math.ceil(listBottom - barTop + gap));
    }
}

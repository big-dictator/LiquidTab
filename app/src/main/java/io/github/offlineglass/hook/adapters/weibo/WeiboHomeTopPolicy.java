package io.github.offlineglass.hook.adapters.weibo;

import java.util.List;

/** Home-specific layout/capability contract. No native type names or version gates. */
public final class WeiboHomeTopPolicy {
    private WeiboHomeTopPolicy() {}
    public record Layout(float left, float tabs, float tabWidth, float reward, float publish, float size, float top) {}
    public static boolean channels(List<String> labels) {
        return labels.size() == 2 && labels.stream().filter("推荐"::equals).count() == 1 &&
            labels.stream().filter("关注"::equals).count() == 1;
    }
    public static boolean ownsBelow(int anchor, int nativeHeader) {
        return anchor != 0 && anchor == nativeHeader;
    }
    public static Layout layout(float width, float density, float statusInset, float contentTop) {
        return layout(width, density, statusInset, contentTop, 144 * density);
    }
    public static Layout layout(float width, float density, float statusInset, float contentTop, float preferredTabs) {
        float size = 40 * density, margin = 12 * density, gap = 8 * density;
        float available = width - 2 * margin - 48 * density - 2 * size - 3 * gap;
        float tabs = Math.max(1, Math.min(preferredTabs, available));
        float groupWidth = 48 * density + tabs + 2 * size + 3 * gap;
        float left = (width - groupWidth) * .5f;
        float channelX = left + 48 * density + gap;
        float rewardX = channelX + tabs + gap;
        return new Layout(left, channelX, tabs, rewardX, rewardX + size + gap,
            size, Math.max(0, statusInset - contentTop));
    }
}

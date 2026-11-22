package io.github.offlineglass.hook.adapters.meituan;

import java.util.List;

/** Pure semantic contract, deliberately independent of application symbol names. */
public final class MeituanTakeoutContract {
    public enum Role { HOME, ORDERS, PROFILE, PROMOTION, OTHER }
    /** The reserved campaign has its own outer action and no ordinary icon.
     * Visibility is delivery state, not the identity of the reserved slot. */
    public static boolean promotionSlot(boolean active, boolean promotionImage,
                                        boolean ordinaryArtwork, boolean outerAction) {
        return promotionImage && (active || (!ordinaryArtwork && outerAction));
    }
    public static Role role(String label, boolean promotion) {
        if (promotion) return Role.PROMOTION;
        String text = label == null ? "" : label.trim();
        if (text.equals("首页")) return Role.HOME;
        if (text.equals("订单")) return Role.ORDERS;
        if (text.equals("我的")) return Role.PROFILE;
        return Role.OTHER;
    }
    public static int uniqueRole(List<Role> roles, Role role) {
        int result = -1;
        for (int i = 0; i < roles.size(); i++) if (roles.get(i) == role) {
            if (result >= 0) return -1;
            result = i;
        }
        return result;
    }
    public static boolean validCell(int index, Object businessTag, boolean titleText,
                                    boolean normalGroup, boolean businessClick) {
        return businessTag instanceof Integer && ((Integer) businessTag) == index &&
                titleText && normalGroup && businessClick;
    }
    public static <T> T unique(List<T> values) { return values.size() == 1 ? values.get(0) : null; }
    private MeituanTakeoutContract() {}
}

package io.github.offlineglass.hook.adapters.meituan_main;

/** No obfuscated symbols: the public artwork interface and Android's selected
 * property must belong to the same native tab implementation. Class identity
 * scopes the caller's cache to the APK/class loader that supplied the contract. */
public final class MeituanMainTabContract {
    // Logical host slots stay stable when the native preference removes Video.
    public static final int HOME = 0;
    public static final int VIDEO = 1;
    public static final int XIAOTUAN = 2;
    public static final int CART = 3;
    public static final int PROFILE = 4;
    public static final int SLOT_COUNT = 5;
    private static final int REQUIRED = (1 << HOME) | (1 << XIAOTUAN) | (1 << CART) | (1 << PROFILE);

    public static int role(String title) {
        if (title == null) return -1;
        return switch (title.trim()) {
            case "首页", "推荐" -> HOME;
            case "视频" -> VIDEO;
            case "小团" -> XIAOTUAN;
            case "购物车" -> CART;
            case "我的" -> PROFILE;
            default -> -1;
        };
    }

    public static boolean validRoles(java.util.List<Integer> roles) {
        int mask = 0;
        for (int role : roles) {
            if (role < 0 || role >= SLOT_COUNT || (mask & (1 << role)) != 0) return false;
            mask |= 1 << role;
        }
        return (mask & REQUIRED) == REQUIRED;
    }

    public static boolean matches(Class<?> type, Class<?> view, Class<?> drawable) {
        try {
            if (!view.isAssignableFrom(type) || !nativeType(type)) return false;
            var icon = type.getMethod("getIcon");
            var title = type.getMethod("getTitle");
            var selected = type.getMethod("setSelected", boolean.class);
            return drawable.isAssignableFrom(icon.getReturnType()) && title.getReturnType() == String.class
                && selected.getReturnType() == void.class && nativeType(selected.getDeclaringClass())
                && nativeType(icon.getDeclaringClass()) && nativeType(title.getDeclaringClass());
        } catch (ReflectiveOperationException | SecurityException failure) { return false; }
    }
    public static <T> T unique(java.util.List<T> candidates) {
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
    private static boolean nativeType(Class<?> type) {
        return type.getName().startsWith("com.meituan.android.pt.homepage.tab.");
    }
}

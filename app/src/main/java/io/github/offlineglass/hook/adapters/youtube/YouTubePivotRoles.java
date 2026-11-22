package io.github.offlineglass.hook.adapters.youtube;

import java.util.Locale;

/** Stable settings slots, independent of the native row's count and order. */
final class YouTubePivotRoles {
    static int slot(String label) {
        String name = label.strip().toLowerCase(Locale.ROOT).split("[,，、:：]", 2)[0].strip();
        return switch (name) {
            case "home", "首页", "首頁", "主頁", "主页" -> 0;
            case "shorts" -> 1;
            case "create", "创作", "創作", "创建", "建立", "发布", "發佈", "新增" -> 2;
            case "subscriptions", "订阅", "订阅内容", "訂閱", "訂閱內容" -> 3;
            case "you", "你", "您", "我" -> 4;
            default -> -1;
        };
    }

    static boolean complete(boolean[] present) {
        int count = 0;
        for (boolean entry : present) if (entry) count++;
        return present[0] && count >= 4;
    }
}

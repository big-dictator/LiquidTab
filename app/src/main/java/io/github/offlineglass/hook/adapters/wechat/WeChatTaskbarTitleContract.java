package io.github.offlineglass.hook.adapters.wechat;

import java.util.List;

/** Pure structure contract, independent of native internal symbols. */
public final class WeChatTaskbarTitleContract {
    /** Every ownership transition must retire the previous native display list. */
    public static final class PaintState {
        private Boolean suppressed;
        public boolean update(boolean mainHeaderVisible, boolean desktopExpanded) {
            boolean next = mainHeaderVisible && !desktopExpanded;
            boolean changed = suppressed == null || suppressed != next;
            suppressed = next;
            return changed;
        }
        public boolean isSuppressed() { return Boolean.TRUE.equals(suppressed); }
    }
    public interface Tree<T> {
        T parent(T node);
        int width(T node);
        int height(T node);
    }

    public static <T> T select(T root, List<T> titles, List<T> plus, List<T> back,
                               Tree<T> tree, float minimumWidth, float maximumHeight) {
        if (titles.size() != 1 || plus.size() != 1) return null;
        T candidate = tree.parent(titles.get(0));
        for (int depth = 0; candidate != null && candidate != root && depth < 16; depth++) {
            if (inside(candidate, plus.get(0), root, tree)) {
                int matchingBack = 0;
                for (T item : back) if (inside(candidate, item, root, tree)) matchingBack++;
                if (matchingBack != 1) return null;
                return tree.height(candidate) > 0 && tree.height(candidate) <= maximumHeight &&
                        tree.width(candidate) >= minimumWidth ? candidate : null;
            }
            candidate = tree.parent(candidate);
        }
        return null;
    }

    private static <T> boolean inside(T parent, T child, T root, Tree<T> tree) {
        for (int depth = 0; child != null && child != root && depth < 16; depth++) {
            if (child == parent) return true;
            child = tree.parent(child);
        }
        return false;
    }
}

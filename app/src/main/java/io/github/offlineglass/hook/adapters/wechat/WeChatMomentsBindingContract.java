package io.github.offlineglass.hook.adapters.wechat;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Relations, rather than resource numbers or private controller symbols. */
public final class WeChatMomentsBindingContract {
    public record Binding<T>(T frost, T source, T header, T toolbar) {}

    public static <T> Binding<T> resolve(List<T> views, Function<T, T> parent,
            Predicate<T> frost, Predicate<T> feed, Predicate<T> overlay,
            Predicate<T> header, Predicate<T> toolbar) {
        Binding<T> result = null;
        for (T surface : views) {
            if (!frost.test(surface)) continue;
            List<T> feeds = views.stream().filter(feed).filter(v -> below(v, surface, parent)).toList();
            if (feeds.size() != 1) continue;
            T source = feeds.get(0);
            for (int depth = 0; parent.apply(source) != surface; depth++) {
                if (depth >= 24 || parent.apply(source) == null) return null;
                source = parent.apply(source);
            }
            T root = parent.apply(surface);
            for (int depth = 0; root != null && !overlay.test(root); depth++) {
                if (depth >= 24) return null;
                root = parent.apply(root);
            }
            if (root == null) continue;
            T owner = root;
            List<T> headers = views.stream().filter(header).filter(v -> parent.apply(v) == owner).toList();
            if (headers.size() != 1) return null;
            T bar = headers.get(0);
            List<T> bars = views.stream().filter(toolbar).filter(v -> parent.apply(v) == bar).toList();
            if (bars.size() != 1 || result != null) return null;
            result = new Binding<>(surface, source, bar, bars.get(0));
        }
        return result;
    }

    private static <T> boolean below(T view, T ancestor, Function<T, T> parent) {
        for (int depth = 0; view != null && depth < 24; depth++, view = parent.apply(view))
            if (view == ancestor) return true;
        return false;
    }
}

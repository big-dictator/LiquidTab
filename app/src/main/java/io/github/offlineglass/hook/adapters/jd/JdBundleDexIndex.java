package io.github.offlineglass.hook.adapters.jd;

import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Set;

/** Negative membership is valid only for the same complete own-DEX generation. */
public final class JdBundleDexIndex {
    private final WeakReference<Object> path;
    private final WeakReference<Object> elements;
    private final Set<String> names;
    public JdBundleDexIndex(Object path, Object elements, Collection<String> names, String anchor) {
        if (path == null || elements == null || names.isEmpty() || names.size() > 4096 || !names.contains(anchor))
            throw new IllegalArgumentException("incomplete own-DEX membership");
        long chars = 0;
        for (String name : names) {
            if (name == null || name.isEmpty() || (chars += name.length()) > 524288)
                throw new IllegalArgumentException("invalid/oversized own-DEX membership");
        }
        this.path = new WeakReference<>(path);
        this.elements = new WeakReference<>(elements);
        this.names = Set.copyOf(names);
    }
    public boolean ownsGeneration(Object currentPath, Object currentElements) {
        return currentPath != null && currentElements != null &&
                currentPath == path.get() && currentElements == elements.get();
    }
    public boolean contains(String name) { return names.contains(name); }
    public int size() { return names.size(); }
}

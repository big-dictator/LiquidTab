package io.github.offlineglass.hook.adapters.bilibili;

/** Bounded structural contract, independent of concrete/obfuscated implementation names. */
public final class BilibiliWebStructure {
    public interface Tree<T> {
        T parent(T node);
        int children(T node);
        T child(T node, int index);
        boolean web(T node);
    }
    public static <T> T bind(T content, T frame, T wrapper, T toolbar, Tree<T> tree) {
        if (content == null || frame == null || wrapper == null || toolbar == null ||
                tree.parent(frame) != content || tree.parent(toolbar) != content || tree.parent(wrapper) != frame) return null;
        return uniqueWeb(wrapper, tree);
    }
    public static <T> T bindAuthor(T content, T frame, T wrapper, T loading, Tree<T> tree) {
        if (content == null || frame == null || wrapper == null || loading == null || wrapper == loading ||
                tree.parent(frame) != content || tree.parent(wrapper) != frame || tree.parent(loading) != frame) return null;
        return uniqueWeb(wrapper, tree);
    }
    private static <T> T uniqueWeb(T wrapper, Tree<T> tree) {
        Search<T> search = new Search<>();
        scan(wrapper, 0, tree, search);
        return !search.exceeded && search.count == 1 ? search.found : null;
    }
    private static final class Search<T> { int visited, count; boolean exceeded; T found; }
    private static <T> void scan(T node, int depth, Tree<T> tree, Search<T> search) {
        if (search.exceeded) return;
        if (++search.visited > 64 || depth > 8) { search.exceeded = true; return; }
        if (tree.web(node)) { search.count++; search.found = node; return; }
        for (int i = 0; i < tree.children(node); i++) scan(tree.child(node, i), depth + 1, tree, search);
    }
}

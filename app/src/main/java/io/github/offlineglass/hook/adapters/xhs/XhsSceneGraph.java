package io.github.offlineglass.hook.adapters.xhs;

import java.lang.ref.WeakReference;
import java.util.*;

/** Cache structure, never pixels or visibility. Mutations invalidate only their ancestor path. */
public final class XhsSceneGraph<T> {
    public interface Access<T> {
        int count(T view);
        T child(T view, int index);
        boolean blocked(T view);
        boolean visible(T view);
        void watch(T view);
    }
    private final class Node {
        final T view;
        Node parent;
        List<Node> children = List.of();
        boolean dirty = true, split;
        Node(T view, Node parent) { this.view = view; this.parent = parent; }
    }
    private final Access<T> access;
    private final Map<T, WeakReference<Node>> nodes = new WeakHashMap<>();
    private final List<T> result = new ArrayList<>();
    private List<T> published = List.of();
    private Node root;
    private boolean reading;
    private int budget;
    public XhsSceneGraph(Access<T> access) { this.access = access; }
    public void reset(T view) {
        nodes.clear(); result.clear(); published = List.of(); root = view == null ? null : node(view, null);
    }
    private Node node(T view, Node parent) {
        WeakReference<Node> reference = nodes.get(view);
        Node node = reference == null ? null : reference.get();
        if (node == null) {
            node = new Node(view, parent); nodes.put(view, new WeakReference<>(node)); access.watch(view);
        } else node.parent = parent;
        return node;
    }
    public void changed(T view) {
        WeakReference<Node> reference = nodes.get(view);
        for (Node node = reference == null ? null : reference.get(); node != null; node = node.parent)
            node.dirty = true;
    }
    private void update(Node node, int depth) {
        if (!node.dirty) return;
        if (--budget < 0 || depth > 128) throw new IllegalStateException("XHS scene topology limit");
        if (access.blocked(node.view)) {
            node.children = List.of(); node.split = true; node.dirty = false; return;
        }
        int count = access.count(node.view);
        List<Node> children = new ArrayList<>(count);
        boolean split = false;
        for (int i = 0; i < count; i++) {
            Node child = node(access.child(node.view, i), node);
            update(child, depth + 1); split |= child.split; children.add(child);
        }
        node.children = children; node.split = split; node.dirty = false;
    }
    private void emit(Node node) {
        if (!access.visible(node.view) || access.blocked(node.view)) return;
        if (!node.split) result.add(node.view);
        else for (Node child : node.children) emit(child);
    }
    public List<T> scene() {
        if (reading || root == null) return List.of();
        reading = true;
        try {
            budget = 16384; update(root, 0); result.clear();
            if (access.visible(root.view)) for (Node child : root.children) emit(child);
            // Native display-list refresh can re-enter another material after
            // this call returns. Never mutate the list held by that outer draw.
            if (!result.equals(published)) published = List.copyOf(result);
            return published;
        } catch (RuntimeException error) {
            // A partial tree must never enter a recording containing its own material.
            result.clear(); throw error;
        } finally { reading = false; }
    }
}

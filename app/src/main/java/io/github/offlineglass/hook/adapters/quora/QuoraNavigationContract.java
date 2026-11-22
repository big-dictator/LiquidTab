package io.github.offlineglass.hook.adapters.quora;

import java.util.ArrayDeque;
import java.util.List;

/** Class names and reflected members deliberately have no representation here. */
public final class QuoraNavigationContract {
    public interface Node {
        String resource();
        Node parent();
        List<Node> children();
        boolean vertical();
        boolean clickable();
        boolean selected();
        boolean measured();
        boolean image();
    }

    private static final String[] CUSTOM_NAMES={"icon","badge","badge_dot","badge_wrapper"};
    private static final String[] TOP_NAMES={"navBarProfileButton","navBarProfileImage","titleView","searchButtonWrapper","search_button_icon","askButtonWrapper","ask_button_icon"};
    private static Node[] uniqueAll(Node root, String[] names) {
        ArrayDeque<Node> queue = new ArrayDeque<>();
        queue.add(root);
        Node[] found = new Node[names.length];
        int budget = 128;
        while (!queue.isEmpty() && budget-- > 0) {
            Node node = queue.removeFirst();
            String resource=node.resource();
            for(int i=0;i<names.length;i++) if (names[i].equals(resource)) {
                if (found[i] != null) return null;
                found[i] = node;
            }
            queue.addAll(node.children());
        }
        if(!queue.isEmpty())return null;
        for(Node node:found)if(node==null)return null;
        return found;
    }
    private static Node unique(Node root, String name) {
        Node[] found=uniqueAll(root,new String[]{name});
        return found==null?null:found[0];
    }

    public static Node row(Node bar) {
        if (!"tab_bar_tabs".equals(bar.resource()))return null;
        List<Node> barChildren=bar.children();
        if(barChildren.size()!=1)return null;
        Node parent = bar.parent();
        if (parent == null || !parent.vertical() || !"tab_container".equals(parent.resource())) return null;
        if (parent.children().stream().filter(n -> "content_wrapper".equals(n.resource())).count() != 1) return null;
        Node row = barChildren.get(0);
        List<Node> slots = row.children();
        if (slots.size() < 3 || slots.size() > 6) return null;
        int selected = 0;
        for (Node slot : slots) {
            if (!slot.clickable() || !slot.measured()) return null;
            if (slot.selected()) selected++;
            Node custom = unique(slot, "tab");
            if (custom == null) return null;
            Node[] controls=uniqueAll(custom,CUSTOM_NAMES);
            if(controls==null||!controls[0].image())return null;
        }
        return selected == 1 ? row : null;
    }

    public static <T> T only(List<T> candidates, boolean complete) {
        return complete && candidates.size() == 1 ? candidates.get(0) : null;
    }
    /** Only the verified native system-bottom reservation, never IME/business padding. */
    public static boolean isNavigationPadding(int padding, int visibleBottom, int stableBottom) {
        return padding > 0 && (padding == visibleBottom || padding == stableBottom);
    }
    public static boolean selectedArtwork(float mix, boolean opticalSource, int index, int selectedIndex) {
        return mix > 0f ? opticalSource : index == selectedIndex;
    }
    public static Node[] topControls(Node bar) {
        if (!"q_navbar".equals(bar.resource())) return null;
        Node[] nodes=uniqueAll(bar,TOP_NAMES);
        if(nodes==null)return null;
        for(int i:new int[]{0,3,5})if(!nodes[i].clickable())return null;
        for(int i:new int[]{1,4,6})if(!nodes[i].image()||nodes[i].parent()==null||!TOP_NAMES[i-1].equals(nodes[i].parent().resource()))return null;
        return nodes;
    }
    private QuoraNavigationContract() {}
}

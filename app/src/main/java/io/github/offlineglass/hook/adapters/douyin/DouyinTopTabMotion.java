package io.github.offlineglass.hook.adapters.douyin;

import java.util.*;

/** Native-order projection and interruption-safe animation, with no Android dependencies. */
public final class DouyinTopTabMotion {
    public static final Set<String> PINS = Set.of("homepage_follow", "homepage_tablive", "homepage_hot_container");
    public record Point(String id, float center, float alpha) {}
    public record Frame(List<Point> points, float menuCenter, float selectionCenter, float slots, boolean moving) {}
    private List<String> target = List.of();
    private List<String> nativeOrder = List.of();
    private String selected = "";
    private final Map<String, Point> start = new LinkedHashMap<>();
    private long began;
    private boolean initialized;
    private float fromSlots, fromMenu, fromSelection;
    private static final float DURATION = 240f;

    public static List<String> project(List<String> nativeOrder, String current) {
        if (new HashSet<>(nativeOrder).size() != nativeOrder.size()) throw new IllegalArgumentException("Duplicate native tab identity");
        if (!nativeOrder.containsAll(PINS)) throw new IllegalArgumentException("Required native home tabs unavailable");
        if (!nativeOrder.contains(current)) throw new IllegalArgumentException("Current native tab not bound");
        return nativeOrder.stream().filter(id -> PINS.contains(id) || id.equals(current)).toList();
    }
    public boolean target(List<String> order, String current, long now) {
        if (initialized && nativeOrder.equals(order) && selected.equals(current)) return false;
        List<String> next = project(order, current);
        nativeOrder = List.copyOf(order);
        if (initialized && target.equals(next) && selected.equals(current)) return false;
        Frame previous = initialized ? frame(now) : null;
        target = List.copyOf(next); selected = current; began = now;
        start.clear();
        if (previous != null) {
            for (Point point : previous.points()) start.put(point.id(), point);
            fromSlots=previous.slots(); fromMenu=previous.menuCenter(); fromSelection=previous.selectionCenter();
        } else {
            fromSlots=target.size()+1; fromMenu=.5f/fromSlots;
            fromSelection=(target.indexOf(current)+1.5f)/fromSlots;
            for (int i=0;i<target.size();i++) start.put(target.get(i),new Point(target.get(i),(i+1.5f)/fromSlots,1f));
            began=now-(long)DURATION;
        }
        initialized=true;
        return true;
    }
    private static float mix(float a,float b,float t) { return a+(b-a)*t; }
    public Frame frame(long now) {
        if (!initialized) return new Frame(List.of(),.125f,.875f,4f,false);
        float t=Math.max(0f,Math.min(1f,(now-began)/DURATION));
        float eased=t*t*(3f-2f*t), count=target.size()+1;
        List<Point> points=new ArrayList<>();
        Set<String> ids=new LinkedHashSet<>(start.keySet()); ids.addAll(target);
        for (String id:ids) {
            int index=target.indexOf(id);
            Point old=start.get(id);
            float end=index<0 ? old.center() : (index+1.5f)/count;
            if (old==null) old=new Point(id,end,0f);
            float alpha=mix(old.alpha(),index<0?0f:1f,eased);
            if (index>=0 || t<1f) points.add(new Point(id,mix(old.center(),end,eased),alpha));
        }
        float selection=(target.indexOf(selected)+1.5f)/count;
        return new Frame(List.copyOf(points),mix(fromMenu,.5f/count,eased),mix(fromSelection,selection,eased),
                mix(fromSlots,count,eased),t<1f);
    }
    public List<String> visible() { return target; }
}

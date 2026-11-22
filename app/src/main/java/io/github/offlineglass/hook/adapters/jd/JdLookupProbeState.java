package io.github.offlineglass.hook.adapters.jd;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Skips internal lookup-miss stack collection; never changes class lookup results.
 * Reported/escaping failures are filled at observation, not the original throw site.
 */
public final class JdLookupProbeState {
    private static final int MAX_DEFERRED = 256;
    private final ThreadLocal<ArrayDeque<Set<Throwable>>> probes = new ThreadLocal<>();
    private final ThreadLocal<Integer> searches = new ThreadLocal<>();
    private final ThreadLocal<Boolean> restoring = new ThreadLocal<>();
    // The production scope belongs to the main navigation thread. Inactive
    // callbacks must not create ThreadLocal entries on JD's loader threads.
    private volatile Thread probeThread;

    public boolean active() { return probeThread == Thread.currentThread(); }
    public void begin() {
        if (probeThread != null && !active()) throw new IllegalStateException("probe thread already owned");
        var stack = probes.get();
        if (stack == null) { stack = new ArrayDeque<>(); probes.set(stack); }
        stack.addLast(Collections.newSetFromMap(new IdentityHashMap<>()));
        probeThread = Thread.currentThread();
    }
    public void searchEnter() { if (active()) searches.set(searches.get() == null ? 1 : searches.get() + 1); }
    public void searchExit() {
        Integer depth = searches.get();
        if (depth == null || depth <= 1) searches.remove(); else searches.set(depth - 1);
    }
    public boolean defer(Throwable error) {
        if (error == null || error.getClass() != ClassNotFoundException.class || !active()) return false;
        var stack = probes.get();
        if (stack == null || stack.size() > 8 || searches.get() == null || Boolean.TRUE.equals(restoring.get())) return false;
        var deferred = stack.getLast();
        if (deferred.size() >= MAX_DEFERRED && !deferred.contains(error)) return false;
        deferred.add(error);
        return true;
    }
    /** Called before the native error reporter, or before an error escapes the probe. */
    public void observe(Throwable error) {
        if (!active()) return;
        var stack = probes.get();
        if (stack == null || error == null) return;
        boolean wasRestoring = Boolean.TRUE.equals(restoring.get());
        restoring.set(true);
        try {
            // Restore the bounded set itself instead of walking an arbitrary
            // native cause/suppressed graph or calling overridable getCause().
            // This also covers a miss retained outside the final error graph.
            for (var frame : stack) {
                for (var current : frame) current.fillInStackTrace();
                frame.clear();
            }
        } finally {
            if (wasRestoring) restoring.set(true); else restoring.remove();
        }
    }
    public void end(Throwable escaping) {
        if (!active()) return;
        try { observe(escaping); }
        finally {
            var stack = probes.get();
            if (stack != null) {
                stack.removeLast();
                if (stack.isEmpty()) { probes.remove(); searches.remove(); restoring.remove(); probeThread = null; }
            }
        }
    }
}

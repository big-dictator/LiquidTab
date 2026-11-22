package io.github.offlineglass.hook.adapters.xhs;

/** Coalesces foreground recovery and rejects work belonging to a previous window epoch. */
final class XhsForegroundEpoch {
    private boolean pending = true;
    private boolean stopped;
    private int generation;

    boolean update(boolean foreground, boolean bound) {
        if (stopped) return false;
        if (!foreground) { pending = true; return false; }
        if (!bound || !pending) return false;
        pending = false;
        generation++;
        return true;
    }
    int generation() { return generation; }
    boolean accepts(int token, boolean foreground) {
        return !stopped && !pending && foreground && token == generation;
    }
    void stop() { stopped = true; pending = true; }
}

package io.github.offlineglass.hook;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Committed pushes outlive polling caches; a late disk read cannot undo them. */
final class CommittedConfigStore<T> {
    private final ConcurrentHashMap<String, T> values = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> revisions = new ConcurrentHashMap<>();

    T get(String key) { return values.get(key); }
    boolean hasVerifiedRevision(String key) { return revisions.getOrDefault(key, -1L) >= 0L; }

    synchronized void accept(String key, T value) { values.put(key, value); }

    /** A persisted snapshot may advance; delayed replies and disk defaults may not. */
    synchronized boolean acceptVersioned(String key, T value, long revision) {
        long previous = revisions.getOrDefault(key, -1L);
        if (revision < previous) return false;
        // Equal revisions can carry live system-dark state; do not reject those.
        revisions.put(key, revision);
        values.put(key, value);
        return true;
    }

    T resolve(String key, Supplier<T> fallback) {
        T committed = get(key);
        if (committed != null) return committed;
        T polled = fallback.get();
        // A broadcast may arrive while provider/disk IPC is in flight.
        committed = get(key);
        return committed != null ? committed : polled;
    }
}

package io.github.offlineglass.hook.adapters.spotify;

import java.util.WeakHashMap;
import java.util.function.Supplier;

/** Shared visual suppression: only the final consumer may restore the baseline. */
public final class SpotifyVisualLeases<K,S> {
    private static final class Entry<S> {
        final S original;
        final WeakHashMap<Object,Boolean> consumers=new WeakHashMap<>();
        Entry(S original) { this.original=original; }
    }
    private final WeakHashMap<K,Entry<S>> entries=new WeakHashMap<>();
    public S acquire(K key,Object consumer,Supplier<S> capture) {
        Entry<S> entry=entries.get(key);
        if(entry==null) { entry=new Entry<>(capture.get());entries.put(key,entry); }
        entry.consumers.put(consumer,Boolean.TRUE);
        return entry.original;
    }
    public boolean owns(K key) { return entries.containsKey(key); }
    public S release(K key,Object consumer) {
        Entry<S> entry=entries.get(key);
        if(entry==null || entry.consumers.remove(consumer)==null || !entry.consumers.isEmpty())return null;
        entries.remove(key);
        return entry.original;
    }
}

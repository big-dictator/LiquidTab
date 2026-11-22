package io.github.offlineglass.hook.adapters.jd;

import java.util.ArrayList;
import java.util.List;

/** Main-thread, single-navigation FIFO. A generation cannot be replayed twice. */
public final class JdContentBatch<T> {
    private final ArrayList<T> pending = new ArrayList<>();
    private long generation, deadline;
    private boolean open;
    public long begin(long now) { cancel(); open=true; deadline=now+800; return generation; }
    public long generation() {return generation;}
    public boolean open(long now) {return open && now<deadline;}
    public boolean offer(long token,T item) {
        if(!open || token!=generation || pending.size()>=8)return false;
        pending.add(item);return true;
    }
    public boolean isEmpty() {return pending.isEmpty();}
    public List<T> take(long token) {
        if(token!=generation)return List.of();
        open=false;var result=List.copyOf(pending);pending.clear();return result;
    }
    public void cancel() {generation++;open=false;pending.clear();}
}

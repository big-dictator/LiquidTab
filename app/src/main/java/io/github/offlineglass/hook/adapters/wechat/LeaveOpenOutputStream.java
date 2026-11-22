package io.github.offlineglass.hook.adapters.wechat;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** A cipher owns finalization, while the caller retains ownership of the file/header stream. */
public final class LeaveOpenOutputStream extends FilterOutputStream {
    public LeaveOpenOutputStream(OutputStream out) { super(out); }
    @Override public void close() throws IOException { flush(); }
}

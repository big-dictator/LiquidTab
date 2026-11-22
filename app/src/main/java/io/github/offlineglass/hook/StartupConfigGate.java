package io.github.offlineglass.hook;

/** Unknown is not enabled. The first verified decision is applied once per process. */
final class StartupConfigGate {
    private boolean resolved;
    private boolean enabled;

    synchronized boolean resolve(Boolean value) {
        if (value == null || resolved) return false;
        enabled = value;
        resolved = true;
        return true;
    }

    synchronized boolean isResolved() { return resolved; }
    synchronized boolean isEnabled() { return resolved && enabled; }
}

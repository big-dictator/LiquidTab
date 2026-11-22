package io.github.offlineglass.hook.compat;

/** Internal callback model, not a dependency on the removed framework API. */
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        boolean returnEarly;
        public Object getResult() { return result; }
        public void setResult(Object value) { result = value; throwable = null; returnEarly = true; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setThrowable(Throwable value) { throwable = value; result = null; returnEarly = true; }
        void outcome(Object value, Throwable error) { result = value; throwable = error; }
    }

    public static final class Unhook {
        private final Runnable action;
        public Unhook(Runnable action) { this.action = action; }
        public void unhook() { action.run(); }
    }
}

package io.github.offlineglass.hook.compat.callbacks;

/** Adapter input independent of framework lifecycle types. */
public final class XC_LoadPackage {
    private XC_LoadPackage() {}
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}

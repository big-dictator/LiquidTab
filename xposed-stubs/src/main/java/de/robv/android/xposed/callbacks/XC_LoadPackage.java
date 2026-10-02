package de.robv.android.xposed.callbacks;

public final class XC_LoadPackage {
    private XC_LoadPackage() {}
    public static class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
    }
}

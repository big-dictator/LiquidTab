package io.github.offlineglass.hook.compat;

import android.util.Log;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.LinkedHashSet;
import java.util.Set;

/** One modern framework backend shared by every adapter. No legacy framework calls. */
public final class XposedBridge {
    private static volatile XposedInterface framework;
    private XposedBridge() {}
    public static void initialize(XposedInterface value) { framework = value; }
    public static XposedInterface framework() {
        XposedInterface value = framework;
        if (value == null) throw new IllegalStateException("Modern framework not attached");
        return value;
    }
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> type, String name, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> handles = new LinkedHashSet<>();
        for (var method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) handles.add(hook(method, callback));
        }
        return handles;
    }
    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> type, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> handles = new LinkedHashSet<>();
        for (var constructor : type.getDeclaredConstructors()) handles.add(hook(constructor, callback));
        return handles;
    }
    /** Exact-member contract used by adapters, dispatched through modern API. */
    public static XC_MethodHook.Unhook hookMethod(Member member, XC_MethodHook callback) {
        if (!(member instanceof Executable executable)) {
            throw new IllegalArgumentException("Only methods and constructors can be hooked");
        }
        return hook(executable, callback);
    }
    private static XC_MethodHook.Unhook hook(Executable executable, XC_MethodHook callback) {
        var handle = framework().hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> dispatch(callback, chain));
        return new XC_MethodHook.Unhook(handle::unhook);
    }
    static Object dispatch(XC_MethodHook callback, XposedInterface.Chain chain) throws Throwable {
        var param = new XC_MethodHook.MethodHookParam();
        param.thisObject = chain.getThisObject();
        param.args = chain.getArgs().toArray();
        try { callback.beforeHookedMethod(param); }
        catch (Throwable error) {
            log("before callback failed: " + error);
            param.outcome(null, null);
            param.returnEarly = false;
        }
        if (!param.returnEarly) {
            try { param.outcome(chain.proceed(param.args), null); }
            catch (Throwable error) { param.outcome(null, error); }
        }
        Object previous = param.getResult();
        Throwable previousError = param.getThrowable();
        try { callback.afterHookedMethod(param); }
        catch (Throwable error) {
            log("after callback failed: " + error);
            param.outcome(previous, previousError);
        }
        if (param.hasThrowable()) throw param.getThrowable();
        return param.getResult();
    }
    public static void log(String message) {
        XposedInterface value = framework;
        if (value != null) value.log(Log.INFO, "LiquidTab", message);
        else Log.i("LiquidTab", message);
    }
}

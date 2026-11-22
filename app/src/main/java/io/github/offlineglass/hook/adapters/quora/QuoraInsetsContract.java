package io.github.offlineglass.hook.adapters.quora;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

public final class QuoraInsetsContract {
    public static Method callback(Class<?> owner,Class<?> view,Class<?> insets) {
        Method found=null;
        for(Method m:owner.getDeclaredMethods()) {
            if(!Modifier.isStatic(m.getModifiers())||m.getReturnType()!=insets||
                !Arrays.equals(m.getParameterTypes(),new Class<?>[]{boolean.class,view,insets}))continue;
            if(found!=null)return null;
            found=m;
        }
        return found;
    }
    private QuoraInsetsContract(){}
}

package io.github.offlineglass.rendering;

import java.util.HashMap;

/** Exact last submitted values. Commit only after the native setter succeeds. */
public final class ExactUniformValues {
    private static final class Value {
        int size,a,b,c,d; int[] large;
        int at(int i) { return large!=null?large[i]:switch(i){case 0->a;case 1->b;case 2->c;default->d;}; }
    }
    private final HashMap<String,Value> values=new HashMap<>();
    public boolean matches(String name,int size,int a,int b,int c,int d) {
        Value v=values.get(name);
        return v!=null&&v.size==size&&v.large==null&&v.a==a&&v.b==b&&v.c==c&&v.d==d;
    }
    public void commit(String name,int size,int a,int b,int c,int d) {
        Value v=slot(name);v.size=size;v.a=a;v.b=b;v.c=c;v.d=d;v.large=null;
    }
    public boolean matches(String name,float[] input) {
        Value v=values.get(name);
        if(v==null||v.size!=input.length)return false;
        for(int i=0;i<input.length;i++)if(v.at(i)!=Float.floatToRawIntBits(input[i]))return false;
        return true;
    }
    public void commit(String name,float[] input) {
        if(input.length<=4) {
            commit(name,input.length,input.length>0?Float.floatToRawIntBits(input[0]):0,
                input.length>1?Float.floatToRawIntBits(input[1]):0,
                input.length>2?Float.floatToRawIntBits(input[2]):0,
                input.length>3?Float.floatToRawIntBits(input[3]):0);
        } else {
            Value v=slot(name);v.size=input.length;
            if(v.large==null||v.large.length!=input.length)v.large=new int[input.length];
            for(int i=0;i<input.length;i++)v.large[i]=Float.floatToRawIntBits(input[i]);
        }
    }
    private Value slot(String name) {
        Value v=values.get(name);
        if(v!=null)return v;
        if(values.size()>=64)values.clear();
        v=new Value();values.put(name,v);return v;
    }
    public int size(){return values.size();}
    public void invalidate(String name){values.remove(name);}
}

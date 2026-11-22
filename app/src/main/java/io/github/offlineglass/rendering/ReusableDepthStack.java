package io.github.offlineglass.rendering;

import java.util.Arrays;

/** Reusable traversal workspace; never caches a topology or retains popped views. */
public final class ReusableDepthStack<T> {
    private Object[] values=new Object[32];
    private int[] depths=new int[32];
    private int size;
    public int poppedDepth;
    public boolean isNotEmpty(){return size!=0;}
    public void push(T value,int depth){
        if(size==values.length){values=Arrays.copyOf(values,size*2);depths=Arrays.copyOf(depths,size*2);}
        values[size]=value;depths[size++]=depth;
    }
    @SuppressWarnings("unchecked") public T pop(){
        if(size==0)throw new IllegalStateException("empty traversal");
        int index=--size;T value=(T)values[index];values[index]=null;poppedDepth=depths[index];return value;
    }
    public void clear(){Arrays.fill(values,0,size,null);size=0;}
}

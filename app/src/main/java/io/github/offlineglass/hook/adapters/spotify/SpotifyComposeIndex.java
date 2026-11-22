package io.github.offlineglass.hook.adapters.spotify;

import java.lang.reflect.*;
import java.util.*;

/** Verify the native ID-to-LayoutNode table against the nodes' own semantic IDs. */
public final class SpotifyComposeIndex {
    public static final class Snapshot {
        public final Object root;
        private final int[] keys;
        private final Object[] values;
        private final Field id;
        Snapshot(Object root,int[] keys,Object[] values,Field id) {
            this.root=root;this.keys=keys;this.values=values;this.id=id;
        }
        public Object node(int wanted) {
            Object result=null;
            for(int i=0;i<keys.length;i++)if(keys[i]==wanted && root.getClass().isInstance(values[i]))try {
                if(id.getInt(values[i])!=wanted)continue;
                if(result!=null && result!=values[i])return null;
                result=values[i];
            } catch(ReflectiveOperationException ignored) { return null; }
            return result;
        }
    }
    private static final ClassValue<List<Field>> FIELDS=new ClassValue<List<Field>>() {
        protected List<Field> computeValue(Class<?> type) {
            List<Field> result=new ArrayList<>();
            for(Class<?> t=type;t!=null && t!=Object.class;t=t.getSuperclass())
                for(Field f:t.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers()))try {
                    f.setAccessible(true);result.add(f);
                } catch(RuntimeException ignored) { }
            return result;
        }
    };
    public static Snapshot read(Object root,Object map) {
        if(root==null || map==null)return null;
        List<Field> keys=new ArrayList<>(),values=new ArrayList<>();
        for(Field f:FIELDS.get(map.getClass())) {
            if(f.getType()==int[].class)keys.add(f);
            if(f.getType()==Object[].class)values.add(f);
        }
        if(keys.size()!=1 || values.size()!=1)return null;
        try {
            int[] k=(int[])keys.get(0).get(map);Object[] v=(Object[])values.get(0).get(map);
            if(k==null || v==null || k.length!=v.length || k.length>131072)return null;
            List<Integer> live=new ArrayList<>();boolean containsRoot=false;
            for(int i=0;i<v.length;i++)if(root.getClass().isInstance(v[i])) {
                if(v[i]==root)containsRoot=true;
                if(live.size()<16)live.add(i);
            }
            if(!containsRoot || live.size()<2)return null;
            List<Field> idFields=new ArrayList<>();
            for(Field f:FIELDS.get(root.getClass()))if(f.getType()==int.class) {
                boolean match=true;
                for(int i:live)if(f.getInt(v[i])!=k[i]) { match=false;break; }
                if(match)idFields.add(f);
            }
            return idFields.size()==1 ? new Snapshot(root,k,v,idFields.get(0)) : null;
        } catch(ReflectiveOperationException | RuntimeException ignored) { return null; }
    }
    private SpotifyComposeIndex() { }
}

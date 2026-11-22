package io.github.offlineglass.hook.adapters.spotify;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Metadata-only semantic discovery. No Spotify version numbers or class-name table. */
public final class SpotifyNavigationIndex {
    public static final class Symbols {
        public final String model, entry, identity, enumeration;
        Symbols(String m,String e,String i,String n) { model=m;entry=e;identity=i;enumeration=n; }
    }
    private static final Set<String> ROLES=new HashSet<>(Arrays.asList("HOME","SEARCH","YOUR_LIBRARY","PREMIUM","CREATE"));
    private static final class Shape {
        String name; boolean set, roles;
        Map<String,String> fields=new LinkedHashMap<>();
    }
    private static String binary(String value) { return value.substring(1,value.length()-1).replace('/','.'); }
    public static Symbols resolve(String source,String dataDir) throws IOException {
        File apk=new File(source), cache=new File(dataDir,"code_cache/liquidtab_spotify_navigation_v1.properties");
        String fingerprint=apk.getAbsolutePath()+":"+apk.length()+":"+apk.lastModified();
        Properties p=new Properties();
        if(cache.isFile()) try(InputStream in=new FileInputStream(cache)) {
            p.load(in);
            if(fingerprint.equals(p.getProperty("fingerprint")) && p.getProperty("model")!=null &&
                p.getProperty("entry")!=null && p.getProperty("identity")!=null && p.getProperty("enum")!=null)
                return new Symbols(p.getProperty("model"),p.getProperty("entry"),p.getProperty("identity"),p.getProperty("enum"));
        } catch(IOException ignored) { }
        List<Shape> shapes=new ArrayList<>();
        try(ZipFile zip=new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry e=entries.nextElement();
                if(!e.getName().matches("classes[0-9]*\\.dex")) continue;
                if(e.getSize()<112 || e.getSize()>64*1024*1024) throw new IOException("DEX size");
                byte[] bytes=new byte[(int)e.getSize()];
                try(DataInputStream in=new DataInputStream(zip.getInputStream(e))) { in.readFully(bytes); }
                new Dex(bytes).read(shapes);
            }
        }
        List<Symbols> found=new ArrayList<>();
        for(Shape enumeration:shapes) if(enumeration.roles)
            for(Shape entry:shapes) for(Map.Entry<String,String> f:entry.fields.entrySet())
                if(f.getValue().equals(enumeration.name)) for(Shape model:shapes) if(model.set) {
                    int count=0;
                    for(String t:model.fields.values()) if(t.equals(entry.name)) count++;
                    if(count>=3) found.add(new Symbols(binary(model.name),binary(entry.name),f.getKey(),binary(enumeration.name)));
                }
        if(found.size()!=1) throw new IOException("Ambiguous navigation semantics: "+found.size());
        Symbols result=found.get(0);
        p.clear();p.setProperty("fingerprint",fingerprint);p.setProperty("model",result.model);
        p.setProperty("entry",result.entry);p.setProperty("identity",result.identity);p.setProperty("enum",result.enumeration);
        if(cache.getParentFile().isDirectory() || cache.getParentFile().mkdirs())
            try(OutputStream out=new FileOutputStream(cache)) { p.store(out,"Semantic navigation symbols; never a layout snapshot"); }
            catch(IOException ignored) { }
        return result;
    }
    /** Metadata-only candidate narrowing by a runtime-verified field type. */
    public static List<String> holdersOfField(String source,String descriptor) throws IOException {
        List<Shape> shapes=new ArrayList<>();
        try(ZipFile zip=new ZipFile(source)) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry e=entries.nextElement();if(!e.getName().matches("classes[0-9]*\\.dex"))continue;
                if(e.getSize()<112 || e.getSize()>64*1024*1024)throw new IOException("DEX size");
                byte[] bytes=new byte[(int)e.getSize()];
                try(DataInputStream in=new DataInputStream(zip.getInputStream(e))){in.readFully(bytes);}
                new Dex(bytes).read(shapes);
            }
        }
        List<String> result=new ArrayList<>();
        for(Shape shape:shapes)if(Collections.frequency(new ArrayList<>(shape.fields.values()),descriptor)==1)result.add(binary(shape.name));
        if(result.size()>256)throw new IOException("Ambiguous padding holder domain");
        return result;
    }
    private static final class Dex {
        final byte[] b; final String[] strings,types;
        final int fieldOff,methodOff;
        Dex(byte[] bytes) throws IOException {
            b=bytes;
            if(b.length<112 || b[0]!='d' || b[1]!='e' || b[2]!='x' || b[3]!='\n' || u4(32)!=b.length || u4(40)!=0x12345678)
                throw new IOException("DEX header");
            int sc=u4(56),tc=u4(64);
            if(sc<0 || sc>1000000 || tc<0 || tc>65536) throw new IOException("DEX counts");
            strings=new String[sc]; types=new String[tc]; fieldOff=u4(84);methodOff=u4(92);
        }
        void bounds(int o,long n) throws IOException { if(o<0 || n<0 || o+n>b.length) throw new IOException("DEX bounds"); }
        int u2(int o) throws IOException { bounds(o,2);return (b[o]&255)|((b[o+1]&255)<<8); }
        int u4(int o) throws IOException { return u2(o)|(u2(o+2)<<16); }
        int leb(int[] c) throws IOException {
            int v=0; for(int s=0;s<35;s+=7) { bounds(c[0],1);int x=b[c[0]++]&255;v|=(x&127)<<s;if(x<128)return v; }
            throw new IOException("ULEB");
        }
        String string(int id) throws IOException {
            if(id<0 || id>=strings.length) throw new IOException("String id");
            if(strings[id]!=null)return strings[id];
            int[] c={u4(u4(60)+id*4)};leb(c);int end=c[0];while(end<b.length && b[end]!=0)end++;
            bounds(end,1);return strings[id]=new String(b,c[0],end-c[0],StandardCharsets.UTF_8);
        }
        String type(int id) throws IOException {
            if(id<0 || id>=types.length)throw new IOException("Type id");
            return types[id]!=null?types[id]:(types[id]=string(u4(u4(68)+id*4)));
        }
        boolean roles(int code) throws IOException {
            int n=u4(code+12);bounds(code+16,(long)n*2);Set<String> names=new HashSet<>();
            for(int k=0;k+1<n;k++) {
                int op=u2(code+16+k*2)&255;int id=-1;
                if(op==0x1a)id=u2(code+18+k*2);
                else if(op==0x1b && k+2<n)id=u4(code+18+k*2);
                if(id>=0 && id<strings.length) { String s=string(id);if(ROLES.contains(s))names.add(s); }
            }
            return names.containsAll(ROLES);
        }
        void read(List<Shape> output) throws IOException {
            int count=u4(96),off=u4(100);if(count<0 || count>100000)throw new IOException("Class count");bounds(off,(long)count*32);
            for(int i=0;i<count;i++) {
                int at=off+i*32,cd=u4(at+24);if(cd==0)continue;
                Shape s=new Shape();s.name=type(u4(at));int flags=u4(at+4),interfaces=u4(at+12);
                if(interfaces!=0) { int n=u4(interfaces);bounds(interfaces+4,(long)n*2);
                    for(int j=0;j<n;j++)if(type(u2(interfaces+4+j*2)).equals("Ljava/util/Set;"))s.set=true; }
                int[] c={cd};int sf=leb(c),nf=leb(c),dm=leb(c),vm=leb(c);
                for(int group=0;group<2;group++) {
                    int id=0,n=group==0?sf:nf;
                    for(int j=0;j<n;j++) { id+=leb(c);leb(c);int f=fieldOff+id*8;
                        if(group==1)s.fields.put(string(u4(f+4)),type(u2(f+2))); }
                }
                if((flags&0x4000)!=0) for(int group=0;group<2;group++) {
                    int id=0,n=group==0?dm:vm;
                    for(int j=0;j<n;j++) { id+=leb(c);leb(c);int code=leb(c);
                        if(code!=0 && string(u4(methodOff+id*8+4)).equals("<clinit>"))s.roles=roles(code); }
                }
                if(s.roles || s.set || !s.fields.isEmpty())output.add(s);
            }
        }
    }
}

package io.github.offlineglass.hook.adapters.bilibili;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** One bounded, read-only semantic/call-graph discovery; no obfuscated name candidates. */
public final class BilibiliCommentIndex {
    private static final String MOD="Landroidx/compose/ui/Modifier;", COM="Landroidx/compose/runtime/Composer;";
    private static final String KEY="Lkntr/common/comment/common/theme/CommentColorKey;";
    private static final String PREFIX="Lkntr/common/comment/page/ui/";
    public record Ref(String owner,String name,String result,List<String> args) {
        String encoded() { return owner+"|"+name+"|"+result+"|"+String.join(",",args); }
        static Ref decode(String value) {
            String[] p=value.split("\\|",-1);
            if(p.length!=4) throw new IllegalArgumentException("Cached binding");
            return new Ref(p[0],p[1],p[2],p[3].isEmpty()?List.of():List.of(p[3].split(",")));
        }
    }
    public record Binding(Ref input,Ref colors,Ref capsule,Ref emoji,Ref list) {}

    public static Binding resolve(String apk,String cachePath) throws IOException {
        File source=new File(apk),cache=new File(cachePath);
        String fingerprint="3:"+source.getAbsolutePath()+":"+source.length()+":"+source.lastModified();
        if(cache.isFile()) try(InputStream in=new FileInputStream(cache)) {
            Properties p=new Properties();p.load(in);
            if(fingerprint.equals(p.getProperty("fingerprint"))) return new Binding(
                Ref.decode(p.getProperty("input")),Ref.decode(p.getProperty("colors")),
                Ref.decode(p.getProperty("capsule")),Ref.decode(p.getProperty("emoji")),Ref.decode(p.getProperty("list")));
        } catch(Exception ignored) { }
        List<Binding> found=new ArrayList<>();
        try(ZipFile zip=new ZipFile(source)) {
            var entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry e=entries.nextElement();
                if(!e.getName().matches("classes[0-9]*\\.dex") || e.getSize()<112 || e.getSize()>64*1024*1024) continue;
                byte[] data;
                try(InputStream in=zip.getInputStream(e)) { data=in.readAllBytes(); }
                Dex dex=new Dex(data);
                if(dex.hasPageTypes() || dex.hasInputTrace()) found.addAll(dex.find());
            }
        }
        if(found.size()!=1) throw new IOException("Comment semantic binding count="+found.size());
        Binding result=found.get(0);
        Properties p=new Properties();p.setProperty("fingerprint",fingerprint);
        p.setProperty("input",result.input.encoded());p.setProperty("colors",result.colors.encoded());
        p.setProperty("capsule",result.capsule.encoded());p.setProperty("emoji",result.emoji.encoded());
        p.setProperty("list",result.list.encoded());
        File parent=cache.getParentFile();
        if(parent.isDirectory() || parent.mkdirs()) try(OutputStream out=new FileOutputStream(cache)) {
            p.store(out,"Verified comment semantics and call edges; APK identity invalidates this cache");
        } catch(IOException ignored) { }
        return result;
    }
    private static final class Body {
        final Ref ref;
        final Set<Ref> calls=new LinkedHashSet<>();
        final Set<String> keys=new HashSet<>(),anchors=new HashSet<>();
        Set<String> fieldTypes=Set.of();
        Body(Ref ref) { this.ref=ref; }
    }
    static final class Dex {
        final byte[] data;final ByteBuffer b;
        final int strings,types,protos,methods,fields;
        final Map<Integer,String> text=new HashMap<>();
        Dex(byte[] data) {
            this.data=data;b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if(data.length<112 || data[0]!='d' || data[1]!='e' || data[2]!='x') throw new IllegalArgumentException("DEX header");
            strings=u4(60);types=u4(68);protos=u4(76);methods=u4(92);fields=u4(84);
        }
        int u4(int p) { int v=b.getInt(p);if(v<0)throw new IllegalArgumentException("DEX offset");return v; }
        int u2(int p) { return b.getShort(p)&65535; }
        String str(int id) { return text.computeIfAbsent(id,key->{
            int p=u4(strings+key*4);while((data[p++]&128)!=0){}
            int start=p;while(data[p]!=0)p++;
            return new String(data,start,p-start,StandardCharsets.UTF_8);
        }); }
        String type(int id) { return str(u4(types+id*4)); }
        boolean hasPageTypes() {
            int low=0,high=u4(64);
            while(low<high) { int m=(low+high)>>>1;if(type(m).compareTo(PREFIX)<0)low=m+1;else high=m; }
            return low<u4(64) && type(low).startsWith(PREFIX);
        }
        boolean hasInputTrace() {
            String prefix="kntr.common.comment.page.ui.CommentInputBar (";
            int low=0,high=u4(56);
            while(low<high) {
                int mid=(low+high)>>>1;
                if(str(mid).compareTo(prefix)<0) low=mid+1;
                else high=mid;
            }
            return low<u4(56) && str(low).startsWith(prefix);
        }
        Ref ref(int id) {
            int m=methods+id*8,p=protos+u2(m+2)*12,a=u4(p+8);
            List<String> args=new ArrayList<>();
            if(a!=0) for(int j=0;j<u4(a);j++) args.add(type(u2(a+4+j*2)));
            return new Ref(type(u2(m)),str(u4(m+4)),type(u4(p+4)),List.copyOf(args));
        }
        final class Cursor {
            int p;Cursor(int p){this.p=p;}
            int leb(){int v=0,s=0,q;do{q=data[p++]&255;v|=(q&127)<<s;s+=7;if(s>35)throw new IllegalArgumentException("LEB");}while((q&128)!=0);return v;}
        }
        /** Exact public contract -> its actual field accesses, never a guessed field name. */
        Map<Ref,Integer> contractBodies(String owner) {
            Map<Ref,Integer> result=new LinkedHashMap<>();
            for(int i=0;i<u4(96);i++) {
                int def=u4(100)+i*32;
                if(!type(u4(def)).equals(owner))continue;
                int start=u4(def+24);if(start==0)continue;
                Cursor c=new Cursor(start);int s=c.leb(),f=c.leb(),d=c.leb(),v=c.leb();
                for(int n:new int[]{s,f})for(int j=0;j<n;j++){c.leb();c.leb();}
                for(int n:new int[]{d,v}) {int id=0;for(int j=0;j<n;j++) {
                    id+=c.leb();c.leb();int code=c.leb();if(code!=0)result.put(ref(id),code);
                }}
            }
            return result;
        }
        Set<String> booleanFields(int code,boolean write) throws IOException {
            return accessedFields(code,write?0x5c:0x55);
        }
        Set<String> accessedFields(int code,int opcode) throws IOException {
            Set<String> result=new LinkedHashSet<>();int count=u4(code+12),start=code+16;
            for(int pos=0;pos<count;) {
                int word=u2(start+pos*2),op=word&255,w=width(op);
                if(op==0 && word!=0) {
                    if(word==0x100)w=4+u2(start+(pos+1)*2)*2;
                    else if(word==0x200)w=2+u2(start+(pos+1)*2)*4;
                    else if(word==0x300)w=4+(int)(((long)u2(start+(pos+1)*2)*u4(start+(pos+2)*2)+1)/2);
                    else throw new IOException("DEX payload");
                }
                if(w<1 || pos+w>count)throw new IOException("DEX instruction width");
                if(op==opcode) {
                    int f=fields+u2(start+(pos+1)*2)*8;
                    result.add(type(u2(f))+"|"+str(u4(f+4)));
                }
                pos+=w;
            }
            return result;
        }
        List<Binding> find() throws IOException {
            List<Body> bodies=new ArrayList<>();
            int definitions=u4(100);
            for(int i=0;i<u4(96);i++) {
                int def=definitions+i*32;
                String owner=type(u4(def));
                boolean inPagePackage=owner.startsWith(PREFIX);
                // R8 can repackage generated classes into the root namespace.
                if(!inPagePackage && owner.indexOf('/')>=0) continue;
                int start=u4(def+24);if(start==0)continue;
                Cursor c=new Cursor(start);int statics=c.leb(),instances=c.leb(),direct=c.leb(),virtual=c.leb();
                Set<String> fieldTypes=new HashSet<>();
                for(int count:new int[]{statics,instances}) {
                    int id=0;
                    for(int j=0;j<count;j++){id+=c.leb();c.leb();fieldTypes.add(type(u2(fields+id*8+2)));}
                }
                for(int count:new int[]{direct,virtual}) {
                    int id=0;
                    for(int j=0;j<count;j++) {
                        id+=c.leb();c.leb();int code=c.leb();
                        if(code==0) continue;
                        Ref method=ref(id);
                        boolean relocatedList=fieldTypes.containsAll(Set.of("Landroidx/compose/foundation/lazy/LazyListState;",
                            "Landroidx/compose/foundation/layout/PaddingValues;","Ljava/util/List;"));
                        if(!inPagePackage && !relocatedList && !method.args.contains(COM)) continue;
                        Body body=new Body(method);
                        body.fieldTypes=fieldTypes;
                        inspect(body,code);
                        if(inPagePackage || relocatedList || !body.anchors.isEmpty()) bodies.add(body);
                    }
                }
            }
            List<Body> inputs=new ArrayList<>(),lists=new ArrayList<>(),structuralLists=new ArrayList<>();
            for(Body body:bodies) {
                List<String> a=body.ref.args;
                boolean input=a.size()==10 && a.get(0).equals("Ljava/lang/String;") && a.get(2).equals("Z") &&
                    a.get(3).equals("Lkotlin/jvm/functions/Function1;") && a.get(4).equals(MOD) &&
                    a.get(5).equals("Lkotlin/jvm/functions/Function0;") && a.get(6).equals("Z") &&
                    a.get(7).equals("Landroidx/compose/foundation/layout/WindowInsets;") && a.get(8).equals(COM) && a.get(9).equals("I") && body.ref.result.equals("V");
                if(input && body.keys.containsAll(Set.of("SECTION_BACKGROUND","INPUT_BAR_BACKGROUND","CARD_DIVIDER","SORT"))) inputs.add(body);
                boolean lazy=body.calls.stream().anyMatch(r->r.owner.equals("Landroidx/compose/foundation/lazy/LazyDslKt;") && r.name.equals("LazyColumn"));
                if(lazy && body.anchors.stream().anyMatch(s->s.contains("CommentListContent"))) lists.add(body);
                if(lazy && body.fieldTypes.containsAll(Set.of("Landroidx/compose/foundation/lazy/LazyListState;",
                    "Landroidx/compose/foundation/layout/PaddingValues;","Ljava/util/List;"))) structuralLists.add(body);
            }
            if(lists.isEmpty()) lists=structuralLists;
            if(inputs.isEmpty() && lists.isEmpty()) return List.of();
            if(inputs.size()!=1 || lists.size()!=1) throw new IOException("Comment contracts: input="+inputs.size()+", list="+lists.size());
            Body input=inputs.get(0);
            Ref colors=unique(input.calls.stream().filter(r->r.result.equals("J") && r.args.equals(List.of(KEY))).toList());
            Ref capsule=unique(input.calls.stream().filter(r->r.result.equals(MOD) && r.args.size()==3 &&
                (r.args.get(0).equals("F") || r.args.get(0).equals("I")) && r.args.get(1).equals(MOD) && r.args.get(2).equals("J")).toList());
            Ref emoji=unique(input.calls.stream().filter(r->r.result.equals(MOD) && r.args.size()==4 &&
                r.args.subList(0,3).equals(List.of("F","I",COM)) &&
                (r.args.get(3).equals(MOD) || r.args.get(3).equals("Landroidx/compose/ui/Modifier$Companion;"))).toList());
            if(colors==null || capsule==null || emoji==null)throw new IOException("Comment call contracts: colors="+colors+", capsule="+capsule+", emoji="+emoji);
            return List.of(new Binding(input.ref,colors,capsule,emoji,lists.get(0).ref));
        }
        Ref unique(List<Ref> refs) { return refs.size()==1?refs.get(0):null; }
        void inspect(Body body,int code) throws IOException {
            int count=u4(code+12),start=code+16;
            if((long)start+count*2L>data.length)throw new IOException("DEX instructions");
            for(int pos=0;pos<count;) {
                int word=u2(start+pos*2),op=word&255,width=width(op);
                if(op==0 && word!=0) {
                    if(word==0x100)width=4+u2(start+(pos+1)*2)*2;
                    else if(word==0x200)width=2+u2(start+(pos+1)*2)*4;
                    else if(word==0x300)width=4+(int)(((long)u2(start+(pos+1)*2)*u4(start+(pos+2)*2)+1)/2);
                    else throw new IOException("DEX payload");
                }
                if(width<1 || pos+width>count)throw new IOException("DEX instruction width");
                if(op==0x1a || op==0x1b) {
                    int id=op==0x1a?u2(start+(pos+1)*2):u4(start+(pos+1)*2);
                    String s=str(id);if(s.contains("CommentInputBar") || s.contains("CommentListContent"))body.anchors.add(s);
                }
                if(op>=0x60 && op<=0x66) {
                    int f=fields+u2(start+(pos+1)*2)*8;
                    if(type(u2(f)).equals(KEY))body.keys.add(str(u4(f+4)));
                }
                if((op>=0x6e && op<=0x72)||(op>=0x74 && op<=0x78))body.calls.add(ref(u2(start+(pos+1)*2)));
                pos+=width;
            }
        }
        static int width(int op) {
            if(op==0x18)return 5;if(op==0xfa||op==0xfb)return 4;
            if(op==3||op==6||op==9||op==0x14||op==0x17||op==0x1b||op==0x24||op==0x25||op==0x26||op==0x2a||op==0x2b||op==0x2c||
                (op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)||op==0xfc||op==0xfd)return 3;
            if(op==2||op==5||op==8||op==0x13||op==0x15||op==0x16||op==0x19||op==0x1a||op==0x1c||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x29||
                (op>=0x2d&&op<=0x3d)||(op>=0x44&&op<=0x6d)||(op>=0x90&&op<=0xaf)||(op>=0xd0&&op<=0xe2)||op==0xfe||op==0xff)return 2;
            return 1;
        }
    }
}

package io.github.offlineglass.hook.adapters.xhs;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** APK-local semantic bytecode contracts. No obfuscation tables or class loading. */
public final class XhsNativeContract {
    static final String AD="Lcom/xingin/advert/intersitial/bean/SplashAd;";
    public record Ref(String owner,String name,String result,List<String> args) {
        public String key(){return owner+"|"+name+"|"+result+"|"+String.join(",",args);}
    }
    record Field(String owner,String name,String type) {
        String key(){return owner+"|"+name+"|"+type;}
    }
    static final class Body {
        final Ref ref; final int flags;
        final Set<String> strings=new HashSet<>();
        final List<Ref> calls=new ArrayList<>();
        final List<Field> reads=new ArrayList<>(),writes=new ArrayList<>();
        final List<Field> titled=new ArrayList<>();
        final Map<String, List<Field>> labelledReads=new HashMap<>();
        Body(Ref ref,int flags){this.ref=ref;this.flags=flags;}
    }
    public static Properties resolve(String[] paths,String cachePath) throws IOException {
        StringBuilder identity=new StringBuilder("xhs-contract-6");
        for(String path:paths){File f=new File(path);identity.append('|').append(path).append(':').append(f.length()).append(':').append(f.lastModified());}
        Properties p=new Properties(); File cache=new File(cachePath);
        if(cache.isFile())try(InputStream in=new FileInputStream(cache)){
            p.load(in);if(identity.toString().equals(p.getProperty("identity")))return p;
        }catch(IOException ignored){}
        p.clear();List<Body> bodies=new ArrayList<>();
        for(String path:paths)try(ZipFile zip=new ZipFile(path)){
            var entries=zip.entries();
            while(entries.hasMoreElements()){
                ZipEntry e=entries.nextElement();
                if(!e.getName().matches("classes[0-9]*\\.dex"))continue;
                if(e.getSize()<112 || e.getSize()>64*1024*1024)throw new IOException("DEX size");
                try(InputStream in=zip.getInputStream(e)){new Dex(in.readAllBytes()).scan(bodies);}
            }
        }
        bind(bodies,p);
        p.setProperty("identity",identity.toString());
        File pending=new File(cachePath+".pending");
        try(OutputStream out=new FileOutputStream(pending)){p.store(out,"Semantic contracts; APK scoped");}
        if(!pending.renameTo(cache))pending.delete();
        return p;
    }
    static void role(Properties p,String name,List<String> values){
        Set<String> unique=new LinkedHashSet<>(values);
        p.setProperty(name+".count",Integer.toString(unique.size()));
        if(unique.size()==1)p.setProperty(name,unique.iterator().next());
    }
    static void bind(List<Body> all,Properties p){
        var errors=all.stream().filter(b->b.ref.args.isEmpty() && b.ref.result.equals("V") &&
            b.strings.stream().anyMatch(s->s.startsWith("splashLoadError -> use CountdownLatch")) &&
            b.calls.stream().anyMatch(c->c.owner.equals("Ljava/util/concurrent/CountDownLatch;")&&c.name.equals("countDown"))).toList();
        role(p,"coldError",errors.stream().map(b->b.ref.key()).toList());
        if(errors.size()==1){String owner=errors.get(0).ref.owner;
            role(p,"coldGetter",all.stream().filter(b->b.ref.args.isEmpty()&&b.ref.result.equals(owner)&&
                b.calls.stream().anyMatch(c->c.owner.equals("Lkotlin/Lazy;")&&c.name.equals("getValue"))).map(b->b.ref.key()).toList());
            role(p,"coldCompletion",all.stream().filter(b->b.ref.owner.equals(owner)&&b.ref.args.size()==3&&b.ref.result.equals("V")&&
                b.ref.args.subList(1,3).equals(List.of("Lkotlin/jvm/functions/Function1;","Lkotlin/jvm/functions/Function1;"))&&
                b.strings.contains("no splash ad")&&b.strings.contains("load splash ad success")).map(b->b.ref.key()).toList());
        }
        var hotFailures=all.stream().filter(b->b.ref.owner.startsWith("Lcom/xingin/advert/intersitial/ui/")&&b.ref.args.equals(List.of("I"))&&
            b.ref.result.equals("V")&&b.strings.stream().anyMatch(s->s.startsWith("load ad failed ["))&&
            b.calls.stream().anyMatch(c->c.name.equals("notifyStateChanged"))).toList();
        role(p,"hotFailure",hotFailures.stream().map(b->b.ref.key()).toList());
        if(hotFailures.size()==1){String callback=hotFailures.get(0).ref.owner;
            var ctors=all.stream().filter(b->b.ref.owner.equals(callback)&&b.ref.name.equals("<init>")&&
                b.ref.args.size()==2&&b.ref.args.get(1).equals("Ljava/lang/ref/WeakReference;")).toList();
            role(p,"hotConstructor",ctors.stream().map(b->b.ref.key()).toList());
            if(ctors.size()==1){String owner=ctors.get(0).ref.args.get(0);
                role(p,"hotLoad",all.stream().filter(b->b.ref.owner.equals(owner)&&b.ref.result.equals("V")&&
                    b.ref.args.equals(List.of("Ljava/lang/ref/WeakReference;"))&&b.calls.stream().anyMatch(c->c.owner.equals(callback)&&c.name.equals("<init>"))).map(b->b.ref.key()).toList());
                role(p,"hotShow",all.stream().filter(b->b.ref.owner.equals(owner)&&b.ref.result.equals("V")&&
                    b.ref.args.equals(List.of("Ljava/lang/ref/WeakReference;",AD))&&
                    b.strings.contains("activity is null, can't show ads")).map(b->b.ref.key()).toList());
            }
        }
        String controller="Lcom/xingin/xhs/index/v2/splash/SplashV2Controller;";
        role(p,"coldHandoff",all.stream().filter(b->b.ref.owner.equals(controller)&&b.ref.args.equals(List.of(AD))&&b.ref.result.equals("V")&&
            b.strings.contains("homeAdsId = ")).map(b->b.ref.key()).toList());
        var defaults=all.stream().filter(b->b.ref.owner.equals(controller)).flatMap(b->b.calls.stream())
            .filter(c->c.result.equals("V")&&c.args.size()==4&&c.args.get(0).equals(c.owner)&&
                c.args.subList(1,4).equals(List.of(AD,"I","Ljava/lang/Object;"))).distinct().toList();
        role(p,"coldLinker",all.stream().filter(b->defaults.contains(b.ref)).flatMap(b->b.calls.stream())
            .filter(c->c.result.equals("V")&&c.args.equals(List.of(AD))&&defaults.stream().anyMatch(d->d.owner.equals(c.owner))).map(Ref::key).toList());
        role(p,"maxHeight",all.stream().filter(b->b.ref.owner.equals("Lcom/xingin/xhs/homepage/explorefeed/category/panel/widgets/MaxHeightFrameLayout;")&&
            b.ref.name.equals("setMaxHeight")&&b.ref.args.equals(List.of("I"))&&b.calls.stream().anyMatch(c->c.name.equals("requestLayout")))
            .flatMap(b->b.writes.stream().filter(f->f.owner.equals(b.ref.owner)&&f.type.equals("I")&&all.stream().anyMatch(measure->
                measure.ref.owner.equals(f.owner)&&measure.ref.name.equals("onMeasure")&&measure.ref.args.equals(List.of("I","I"))&&
                measure.reads.contains(f)&&measure.calls.stream().anyMatch(c->c.owner.equals("Landroid/view/View$MeasureSpec;")&&c.name.equals("makeMeasureSpec")))))
            .map(Field::key).toList());
        role(p,"sidebar",all.stream().filter(b->b.ref.owner.equals("Lcom/xingin/xhs/homepage/container/home/IndexHomeFragment;")&&
            (b.flags&8)!=0&&b.ref.args.size()==1&&b.ref.result.equals("Lkotlin/Unit;")&&b.strings.contains("main_tab")&&
            b.writes.stream().anyMatch(f->f.name.equals("pageSource")&&f.type.equals("Ljava/lang/String;"))).map(b->b.ref.key()).toList());
        role(p,"lottieFilter",all.stream().filter(b->b.ref.owner.endsWith("/LottieAnimationView;")&&
            b.calls.stream().anyMatch(c->c.owner.equals(b.ref.owner)&&c.args.size()==3&&c.args.get(1).equals("Ljava/lang/Object;")))
            .flatMap(b->b.reads.stream().filter(f->f.type.equals("Landroid/graphics/ColorFilter;"))).map(Field::key).toList());
        role(p,"channelTitle",all.stream().filter(b->b.ref.owner.startsWith("Lcom/xingin/matrix/widgets/NewTabLayout$")&&
            b.ref.name.equals("toString")&&b.strings.contains("ItemBean(title="))
            .flatMap(b->b.titled.stream()).map(Field::key).toList());
        XhsImageSaveContract.bind(all,p);
    }
    static final class Dex {
        final byte[] b; final Map<Integer,String> text=new HashMap<>();
        Dex(byte[] b)throws IOException{this.b=b;if(b.length<112||b[0]!='d'||b[1]!='e'||b[2]!='x'||u4(32)!=b.length)throw new IOException("DEX header");}
        int u2(int p){if(p<0||p+2>b.length)throw new IllegalArgumentException("DEX bounds");return(b[p]&255)|((b[p+1]&255)<<8);}
        int u4(int p){return u2(p)|(u2(p+2)<<16);}
        int leb(int[] p){int v=0;for(int i=0;i<5;i++){int q=b[p[0]++]&255;v|=(q&127)<<(7*i);if((q&128)==0)return v;}throw new IllegalArgumentException("ULEB");}
        String str(int id){return text.computeIfAbsent(id,k->{int[] p={u4(u4(60)+k*4)};leb(p);int end=p[0];while(b[end]!=0)end++;return new String(b,p[0],end-p[0],StandardCharsets.UTF_8);});}
        String type(int id){return str(u4(u4(68)+id*4));}
        Ref ref(int id){int m=u4(92)+id*8,proto=u4(76)+u2(m+2)*12,params=u4(proto+8);List<String>a=new ArrayList<>();if(params!=0)for(int i=0;i<u4(params);i++)a.add(type(u2(params+4+i*2)));return new Ref(type(u2(m)),str(u4(m+4)),type(u4(proto+4)),List.copyOf(a));}
        Field field(int id){int p=u4(84)+id*8;return new Field(type(u2(p)),str(u4(p+4)),type(u2(p+2)));}
        void scan(List<Body> output)throws IOException{
            for(int i=0;i<u4(96);i++){
                int def=u4(100)+i*32,data=u4(def+24);if(data==0)continue;
                String owner=type(u4(def));
                // Most DEX files cannot contain any cold/hot ad anchor; avoid
                // retaining bodies unrelated to the small set of contracts.
                int[] p={data};int s=leb(p),f=leb(p),d=leb(p),v=leb(p);
                for(int n=0;n<s+f;n++){leb(p);leb(p);}
                for(int count:new int[]{d,v}){int id=0;for(int n=0;n<count;n++){
                    id+=leb(p);int flags=leb(p),code=leb(p);if(code==0)continue;
                    Ref r=ref(id);
                    boolean known=owner.startsWith("Lcom/xingin/advert/intersitial/ui/")||owner.equals("Lcom/xingin/xhs/index/v2/splash/SplashV2Controller;")||
                        owner.equals("Lcom/xingin/xhs/homepage/container/home/IndexHomeFragment;")||owner.endsWith("/LottieAnimationView;")||
                        owner.startsWith("Lcom/xingin/matrix/widgets/NewTabLayout$")||owner.equals("Lcom/xingin/xhs/homepage/explorefeed/category/panel/widgets/MaxHeightFrameLayout;");
                    boolean cold=r.args.isEmpty()||r.args.contains(AD)||(r.args.size()==3&&r.args.subList(1,3).equals(List.of("Lkotlin/jvm/functions/Function1;","Lkotlin/jvm/functions/Function1;")));
                    boolean imageSave=XhsImageSaveContract.inspect(r);
                    if(!known&&!cold&&!imageSave)continue;
                    Body body=inspect(r,flags,code);
                    if(known||XhsImageSaveContract.retain(body)||r.args.contains(AD)||body.strings.contains("no splash ad")||body.strings.stream().anyMatch(t->t.startsWith("splashLoadError -> use CountdownLatch"))||
                        (r.args.isEmpty()&&body.calls.stream().anyMatch(c->c.owner.equals("Lkotlin/Lazy;")&&c.name.equals("getValue"))))output.add(body);
                }}
            }
        }
        Body inspect(Ref r,int flags,int code)throws IOException{
            Body body=new Body(r,flags);int count=u4(code+12),start=code+16;
            if(count<0||(long)start+count*2L>b.length)throw new IOException("DEX code bounds");
            boolean titlePending=false;
            String imageLabel=null;
            for(int pos=0;pos<count;){int word=u2(start+pos*2),op=word&255,w=width(op);
                if(op==0&&word!=0){if(word==0x100)w=4+u2(start+(pos+1)*2)*2;else if(word==0x200)w=2+u2(start+(pos+1)*2)*4;else if(word==0x300)w=4+(int)(((long)u2(start+(pos+1)*2)*Integer.toUnsignedLong(u4(start+(pos+2)*2))+1)/2);else throw new IOException("DEX payload");}
                if(w<1||pos+w>count)throw new IOException("DEX instruction");
                if(op==0x1a||op==0x1b){String value=str(op==0x1a?u2(start+(pos+1)*2):u4(start+(pos+1)*2));body.strings.add(value);if(value.equals("ItemBean(title="))titlePending=true;
                    if(XhsImageSaveContract.isActionLabel(value))imageLabel=value;}
                if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))body.calls.add(ref(u2(start+(pos+1)*2)));
                if((op>=0x52&&op<=0x58)||(op>=0x60&&op<=0x66)){
                    Field f=field(u2(start+(pos+1)*2));body.reads.add(f);
                    if(titlePending&&f.owner.equals(r.owner)&&f.type.equals("Ljava/lang/String;")){body.titled.add(f);titlePending=false;}
                    if(imageLabel!=null&&f.owner.equals(r.owner)){body.labelledReads.computeIfAbsent(imageLabel,key->new ArrayList<>()).add(f);imageLabel=null;}
                }
                if((op>=0x59&&op<=0x5f)||(op>=0x67&&op<=0x6d))body.writes.add(field(u2(start+(pos+1)*2)));
                pos+=w;
            }return body;
        }
        static int width(int op){
            if(op==0x18)return 5;if(op==0xfa||op==0xfb)return 4;
            if(op==0x03||op==0x06||op==0x09||op==0x14||op==0x17||op==0x1b||op==0x24||op==0x25||op==0x26||op==0x2a||op==0x2b||op==0x2c||(op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)||op==0xfc||op==0xfd)return 3;
            if(op==0x02||op==0x05||op==0x08||op==0x13||op==0x15||op==0x16||op==0x19||op==0x1a||op==0x1c||op==0x1f||op==0x20||op==0x22||op==0x23||op==0x29||(op>=0x2d&&op<=0x3d)||(op>=0x44&&op<=0x6d)||(op>=0x90&&op<=0xaf)||(op>=0xd0&&op<=0xe2)||op==0xfe||op==0xff)return 2;return 1;
        }
    }
}

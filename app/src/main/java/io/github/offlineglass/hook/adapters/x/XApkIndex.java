package io.github.offlineglass.hook.adapters.x;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Small read-only DEX index owned by the X adapter. No class loading, code execution,
 * native library, or dependency on an obfuscation mapping. Only exact semantic anchors
 * plus checked prototypes qualify; ambiguous matches are deliberately rejected.
 */
public final class XApkIndex {
    public static final String COMPOSER = "Landroidx/compose/runtime/Composer;";
    public static final String FUNCTION0 = "Lkotlin/jvm/functions/Function0;";
    public static final String FUNCTION1 = "Lkotlin/jvm/functions/Function1;";
    public static final String MODIFIER = "Landroidx/compose/ui/Modifier;";
    public static final class MethodRef {
        public final String owner, name, result;
        public final List<String> args;
        MethodRef(String owner, String name, String result, List<String> args) {
            this.owner=owner; this.name=name; this.result=result; this.args=args;
        }
        public String key() { return owner+"->"+name+"("+String.join("",args)+")"+result; }
        public String toString() { return key(); }
        public boolean equals(Object other) {
            if(this==other) return true;
            if(!(other instanceof MethodRef)) return false;
            MethodRef method=(MethodRef)other;
            return owner.equals(method.owner) && name.equals(method.name) && result.equals(method.result) && args.equals(method.args);
        }
        public int hashCode() {
            int hash=31*owner.hashCode()+name.hashCode();
            return 31*(31*hash+result.hashCode())+args.hashCode();
        }
    }
    public final Map<String,List<MethodRef>> matches=new LinkedHashMap<>();
    private final Map<String,Set<String>> tabFields=new HashMap<>();
    private final Map<String,List<MethodRef>> nodeConstructors=new HashMap<>();
    private final Map<String,List<MethodRef>> nodeInvalidations=new HashMap<>();
    private final Set<String> drawInterfaces=new HashSet<>();
    private final Map<MethodRef,List<MethodRef>> composerCalls=new LinkedHashMap<>();
    private final Map<MethodRef,List<MethodRef>> coordinateRoots=new LinkedHashMap<>();
    private final Map<MethodRef,List<MethodRef>> calls=new LinkedHashMap<>();
    private final Map<MethodRef,Set<String>> semantics=new LinkedHashMap<>();
    private final Set<MethodRef> drawerOpenReaders=new HashSet<>();
    private final List<MethodRef> landingCandidates=new ArrayList<>(), fabCandidates=new ArrayList<>();
    public static final Set<String> TAB_NAMES=new HashSet<>(Arrays.asList("HOME","EXPLORE","GROK","NOTIFICATIONS","DM"));
    public MethodRef unique(String role) {
        List<MethodRef> list=matches.getOrDefault(role,Collections.emptyList());
        return list.size()==1 ? list.get(0) : null;
    }
    private void add(String role,MethodRef m) {
        List<MethodRef> list=matches.computeIfAbsent(role,k->new ArrayList<>());
        if(!list.contains(m)) list.add(m);
    }
    public static XApkIndex scan(List<File> apks) throws IOException {
        XApkIndex index=new XApkIndex();
        for(File apk:apks) try(ZipFile zip=new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries=zip.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                if(entry.getSize()<112 || entry.getSize()>64*1024*1024) throw new IOException("Invalid DEX size");
                byte[] data=new byte[(int)entry.getSize()];
                try(InputStream in=zip.getInputStream(entry)) {
                    int cursor=0;
                    while(cursor<data.length) {
                        int count=in.read(data,cursor,data.length-cursor);
                        if(count<0) throw new EOFException("Truncated DEX entry");
                        cursor+=count;
                    }
                    if(in.read()!=-1) throw new IOException("DEX size mismatch");
                }
                new Dex(data,index).scan();
            }
        }
        for (MethodRef candidate : index.matches.getOrDefault("bottomShape", Collections.emptyList())) {
            if (index.semantics.getOrDefault(candidate, Collections.emptySet()).contains("BottomBarOffsetAnimation") ||
                index.calls.getOrDefault(candidate, Collections.emptyList()).stream().anyMatch(call ->
                    index.semantics.getOrDefault(call, Collections.emptySet()).contains("BottomBarOffsetAnimation"))) {
                index.add("bottom", candidate);
                for (MethodRef call : index.calls.getOrDefault(candidate, Collections.emptyList())) {
                    if (index.semantics.getOrDefault(call, Collections.emptySet()).contains("BottomBarOffsetAnimation"))
                        index.add("bottomAnimationDependency", call);
                }
            }
        }
        index.matches.remove("bottomShape");
        MethodRef bottom=index.unique("bottom");
        for(MethodRef m:index.landingCandidates) {
            long enums=m.args.stream().filter(p->index.tabFields.getOrDefault(p,Collections.emptySet()).containsAll(TAB_NAMES)).count();
            Set<String> anchors=index.semantics.getOrDefault(m,Collections.emptySet());
            if(enums==1 && m.args.contains(FUNCTION1) &&
                anchors.contains("onBottomBarHeightChanged") && anchors.contains("onGrokTabTooltipDismissed") &&
                bottom!=null && index.reaches(m,bottom,3,new HashSet<>())) index.add("landing",m);
        }
        for(MethodRef m:index.fabCandidates)
            if(index.reachesNamespace(m,"Lcom/x/composer/fab/ui/",3,new HashSet<>())) index.add("postFab",m);
        Set<MethodRef> drawerBranches=new HashSet<>();
        for (Map.Entry<MethodRef,List<MethodRef>> entry:index.calls.entrySet()) {
            MethodRef parent=entry.getKey();
            if(!parent.owner.startsWith("Lcom/x/main/")) continue;
            for(String type:parent.args) {
                if(!type.startsWith("Lcom/x/main/api/landing/")) continue;
                MethodRef identity=new MethodRef(type,"toString","Ljava/lang/String;",Collections.emptyList());
                if(!index.semantics.getOrDefault(identity,Collections.emptySet()).contains("DrawerState(accounts=")) continue;
                index.add("drawerModelIdentity",identity);
                for(MethodRef call:entry.getValue()) if(call.owner.equals(parent.owner)) drawerBranches.add(call);
            }
        }
        for (Map.Entry<MethodRef,List<MethodRef>> entry:index.calls.entrySet()) {
            MethodRef container=entry.getKey();
            if(!container.owner.startsWith("Lcom/x/main/") || !container.args.contains(COMPOSER)) continue;
            boolean landingDrawerHost=container.args.stream()
                .filter(p->p.startsWith("Lcom/x/main/api/landing/")).count()>=2 ||
                drawerBranches.contains(container);
            if(!landingDrawerHost) continue;
            for(MethodRef renderer:entry.getValue()) {
                List<String> args=renderer.args;
                if(renderer.owner.startsWith("Lcom/x/main/drawer/") && renderer.result.equals("V") &&
                    args.size()==7 && args.get(0).startsWith("Lcom/x/main/drawer/") &&
                    args.get(1).equals("Z") && args.get(2).equals(args.get(4)) &&
                    args.get(2).startsWith("Landroidx/compose/runtime/internal/") && args.get(3).equals(MODIFIER) &&
                    args.subList(5,7).equals(Arrays.asList(COMPOSER,"I"))) {
                    for(MethodRef reader:index.drawerOpenReaders)
                        if(reader.owner.equals(args.get(0)) && reader.name.equals("isOpen")) {
                            index.add("gestureDrawerContainer",container);
                            index.add("gestureDrawerRenderer",renderer);
                            index.add("gestureDrawerOpen",reader);
                        }
                }
                if(!renderer.owner.startsWith("Landroidx/compose/material3/") || !renderer.result.equals("V") ||
                    args.size()!=8 || !args.get(1).equals(MODIFIER) || !args.get(0).equals(args.get(5)) ||
                    !args.get(0).startsWith("Landroidx/compose/runtime/internal/") ||
                    !args.subList(3,5).equals(Arrays.asList("Z","J")) ||
                    !args.subList(6,8).equals(Arrays.asList(COMPOSER,"I"))) continue;
                Set<MethodRef> readers=new HashSet<>();
                for(MethodRef reader:index.calls.getOrDefault(renderer,Collections.emptyList()))
                    if(reader.owner.equals(args.get(2)) && index.drawerOpenReaders.contains(reader)) readers.add(reader);
                if(readers.size()==1) {
                    index.add("drawerContainer",container);
                    index.add("drawerRenderer",renderer);
                    index.add("drawerOpen",readers.iterator().next());
                }
            }
        }
        for(MethodRef constructor:index.matches.getOrDefault("detailSlotOwner",Collections.emptyList())) {
            if(index.calls.getOrDefault(constructor,Collections.emptyList()).stream().anyMatch(call ->
                call.owner.equals(constructor.owner) && index.matches.getOrDefault("detailDestroy",Collections.emptyList()).contains(call)))
                index.add("detailConstructor",constructor);
        }
        index.matches.remove("detailSlotOwner");
        MethodRef detail=index.unique("detailConstructor");
        List<MethodRef> exits=index.matches.get("detailDestroy");
        if(exits!=null) exits.removeIf(m -> detail==null || !m.owner.equals(detail.owner));
        MethodRef element=index.unique("backgroundElementConstructor");
        if(element!=null) {
            for(MethodRef ref:index.nodeConstructors.getOrDefault(element.owner,Collections.emptyList())) index.add("backgroundNodeConstructor",ref);
            for(MethodRef ref:index.nodeInvalidations.getOrDefault(element.owner,Collections.emptyList()))
                if(index.drawInterfaces.contains(ref.args.get(0))) index.add("backgroundInvalidation",ref);
        }
        MethodRef area=index.unique("hazeAreaIdentity");
        if(area!=null) {
            List<MethodRef> layers=index.matches.get("hazeAreaLayer");
            if(layers!=null) layers.removeIf(m->!m.owner.equals(area.owner));
            List<MethodRef> flags=index.matches.get("hazeAreaRecording");
            if(flags!=null) flags.removeIf(m->!m.owner.equals(area.owner));
        }
        MethodRef editor=index.unique("replyComposer"),border=index.unique("replyBorderFactory");
        if(editor!=null) {
            List<MethodRef> layouts=new ArrayList<>();
            Map<MethodRef,Integer> surfaceDepths=new LinkedHashMap<>();
            for(Map.Entry<MethodRef,List<MethodRef>> entry:index.composerCalls.entrySet())
                if(entry.getValue().stream().anyMatch(call->call.owner.equals(editor.owner) && call.name.equals("<init>"))) layouts.add(entry.getKey());
            if(layouts.size()==1) for(Map.Entry<MethodRef,List<MethodRef>> entry:index.composerCalls.entrySet()) {
                MethodRef surface=entry.getKey();
                if(surface.args.stream().filter(p->p.startsWith("Ldev/chrisbanes/haze/")).count()!=1) continue;
                int depth=1;
                while(depth<=4 && !index.reachesEditorLayout(surface,layouts.get(0),depth,new HashSet<>())) depth++;
                boolean appliesHaze=entry.getValue().stream().anyMatch(call->call.result.equals(MODIFIER) &&
                    call.args.stream().anyMatch(p->p.startsWith("Ldev/chrisbanes/haze/") && surface.args.contains(p)));
                if(depth<=4 && (appliesHaze || entry.getValue().contains(border))) surfaceDepths.put(surface,depth);
            }
            int nearest=surfaceDepths.values().stream().min(Integer::compareTo).orElse(Integer.MAX_VALUE);
            for(Map.Entry<MethodRef,Integer> candidate:surfaceDepths.entrySet()) {
                if(candidate.getValue()!=nearest) continue;
                MethodRef surface=candidate.getKey();
                index.add("replySurface",surface);
                if(index.composerCalls.get(surface).contains(border)) index.add("replyBorderScope",surface);
            }
        }
        MethodRef mask=index.unique("replyMaskScope");
        if(mask==null && !index.matches.containsKey("replyMaskScope") && index.unique("replySurface")!=null) {
            MethodRef surface=index.unique("replySurface");
            for(MethodRef wrapper:index.composerCalls.keySet()) {
                if(wrapper.equals(surface) || !index.reachesEditorLayout(wrapper,surface,4,new HashSet<>())) continue;
                for(MethodRef helper:index.calls.getOrDefault(wrapper,Collections.emptyList())) {
                    List<String> p=helper.args;
                    if(!helper.result.equals(MODIFIER) || p.size()!=5 || !p.containsAll(Arrays.asList(COMPOSER,MODIFIER,FUNCTION0,"I")) ||
                        p.stream().noneMatch(t->t.startsWith("Ldev/chrisbanes/haze/") && surface.args.contains(t))) continue;
                    for(MethodRef factory:index.calls.getOrDefault(helper,Collections.emptyList())) {
                        if(isFooterFactory(factory) && p.contains(factory.args.get(1))) {
                            index.add("replyMaskOwner",wrapper);
                            index.add("replyMaskScope",helper);
                            index.add("replyMaskFactory",factory);
                        }
                    }
                }
            }
        }
        if(mask!=null) for(MethodRef call:index.composerCalls.getOrDefault(mask,Collections.emptyList())) {
            if(isFooterFactory(call)) index.add("replyMaskFactory",call);
        }
        MethodRef bounds=index.unique("coordinateBounds");
        if(bounds!=null) for(MethodRef root:index.coordinateRoots.getOrDefault(bounds,Collections.emptyList())) index.add("coordinateRoot",root);
        if(bounds!=null) for(MethodRef call:index.calls.getOrDefault(bounds,Collections.emptyList()))
            if(call.owner.equals(bounds.args.get(0)) && call.args.isEmpty() && call.result.equals("J")) index.add("coordinateSize",call);
        MethodRef haze=index.unique("hazeDraw");
        List<MethodRef> content=index.matches.get("drawContent");
        if(content!=null) content.removeIf(m->haze==null || !m.owner.equals(haze.args.get(0)));
        index.calls.clear();index.semantics.clear();index.composerCalls.clear();index.coordinateRoots.clear();index.drawerOpenReaders.clear();
        index.nodeConstructors.clear();index.nodeInvalidations.clear();index.drawInterfaces.clear();
        index.tabFields.clear();index.landingCandidates.clear();index.fabCandidates.clear();
        return index;
    }
    private static boolean isFooterFactory(MethodRef call) {
        List<String> p=call.args;
        return call.result.equals(MODIFIER) && p.size()==12 && p.get(0).equals(MODIFIER) &&
            p.get(1).startsWith("Ldev/chrisbanes/haze/") && p.get(2).equals("J") &&
            p.subList(4,7).equals(Arrays.asList("F","Z","F")) && p.get(8).equals(FUNCTION1) &&
            p.subList(9,12).equals(Arrays.asList(COMPOSER,"I","I"));
    }
    private boolean reachesEditorLayout(MethodRef from,MethodRef layout,int depth,Set<MethodRef> seen) {
        if(from.equals(layout)) return true;
        if(depth<=0 || !seen.add(from)) return false;
        for(MethodRef call:calls.getOrDefault(from,Collections.emptyList())) {
            if(!call.owner.startsWith("Lcom/x/composer/minimal/")) continue;
            if(reachesEditorLayout(call,layout,depth-1,seen)) return true;
            if(call.name.equals("<init>")) {
                MethodRef invoke=new MethodRef(call.owner,"invoke","Ljava/lang/Object;",
                    Arrays.asList("Ljava/lang/Object;","Ljava/lang/Object;"));
                if(reachesEditorLayout(invoke,layout,depth-1,seen)) return true;
            }
        }
        return false;
    }
    private boolean reaches(MethodRef from,MethodRef target,int depth,Set<MethodRef> seen) {
        if(depth<0 || !seen.add(from)) return false;
        for(MethodRef call:calls.getOrDefault(from,Collections.emptyList()))
            if(call.equals(target) || reaches(call,target,depth-1,seen)) return true;
        return false;
    }
    private boolean reachesNamespace(MethodRef from,String prefix,int depth,Set<MethodRef> seen) {
        if(depth<0 || !seen.add(from)) return false;
        for(MethodRef call:calls.getOrDefault(from,Collections.emptyList()))
            if(call.owner.startsWith(prefix) || reachesNamespace(call,prefix,depth-1,seen)) return true;
        return false;
    }
    private static final class Dex {
        private final byte[] b;
        private final XApkIndex index;
        private final int stringCount,stringOff,typeCount,typeOff,protoOff,methodCount,methodOff;
        private final String[] strings;
        private final String[] types;
        private final MethodRef[] methods;
        private final Map<Integer,String> anchors=new HashMap<>();
        Dex(byte[] bytes,XApkIndex index) throws IOException {
            this.b=bytes; this.index=index;
            if(b.length<112 || b[0]!='d' || b[1]!='e' || b[2]!='x' || b[3]!='\n' || b[7]!=0 ||
                u4(32)!=b.length || u4(36)!=112 || u4(40)!=0x12345678) throw new IOException("Unsupported DEX header");
            String version=new String(b,4,3,java.nio.charset.StandardCharsets.US_ASCII);
            if(!Arrays.asList("035","037","038","039","040").contains(version)) throw new IOException("Unsupported DEX version");
            stringCount=u4(56); stringOff=u4(60); typeCount=u4(64); typeOff=u4(68);
            protoOff=u4(76); methodCount=u4(88); methodOff=u4(92);
            range(stringOff,(long)stringCount*4); range(typeOff,(long)typeCount*4); range(methodOff,(long)methodCount*8);
            strings=new String[stringCount]; types=new String[typeCount];
            methods=new MethodRef[methodCount];
        }
        void range(int off,long length) throws IOException {
            if(off<0 || length<0 || (long)off+length>b.length) throw new IOException("DEX bounds");
        }
        int u2(int off) { return (b[off]&255)|((b[off+1]&255)<<8); }
        int u4(int off) { return u2(off)|(u2(off+2)<<16); }
        int leb(int[] cursor) throws IOException {
            int result=0;
            for(int n=0;n<5;n++) {
                range(cursor[0],1); int v=b[cursor[0]++]&255;
                result|=(v&127)<<(n*7); if((v&128)==0) return result;
            }
            throw new IOException("Invalid ULEB128");
        }
        String string(int n) throws IOException {
            if(n<0 || n>=strings.length) throw new IOException("DEX string index");
            if(strings[n]!=null) return strings[n];
            int[] cursor={u4(stringOff+n*4)}; leb(cursor); int start=cursor[0],end=start;
            while(end<b.length && b[end]!=0) end++;
            range(start,(long)end-start+1);
            // All descriptors and semantic anchors we use are ASCII, unaffected by MUTF-8.
            return strings[n]=new String(b,start,end-start,java.nio.charset.StandardCharsets.UTF_8);
        }
        String type(int n) throws IOException {
            if(n<0 || n>=types.length) throw new IOException("DEX type index");
            if(types[n]==null) types[n]=string(u4(typeOff+n*4)); return types[n];
        }
        MethodRef method(int n) throws IOException {
            if(n<0 || n>=methodCount) throw new IOException("DEX method index");
            if(methods[n]!=null) return methods[n];
            int off=methodOff+n*8,proto=protoOff+u2(off+2)*12;
            range(proto,12); int params=u4(proto+8);
            List<String> args=new ArrayList<>();
            if(params!=0) {
                range(params,4); int count=u4(params); range(params+4,(long)count*2);
                for(int i=0;i<count;i++) args.add(type(u2(params+4+i*2)));
            }
            return methods[n]=new MethodRef(type(u2(off)),string(u4(off+4)),type(u4(proto+4)),args);
        }
        void scan() throws IOException {
            for(int i=0;i<stringCount;i++) {
                String value=string(i);
                if(value.equals("BottomBarOffsetAnimation") || value.equals("inbox_action_button") ||
                    value.equals("inline-composer-slot") || value.equals("android_scribe_conversation_reply_dwell_linger_enabled") ||
                    value.equals("post-detail-reply-text-field") || value.equals("hazeAlpha") || value.equals("onBottomBarHeightChanged") ||
                    value.equals("onGrokTabTooltipDismissed") || value.equals("DrawerState(accounts=") || value.startsWith("Attempting to drawContent for a `null` node") ||
                    value.startsWith("BackgroundElement(") || value.equals("HazeArea(") || value.startsWith("Modifier.hazeEffect nodes cannot draw an ancestor")) anchors.put(i,value);
            }
            int fieldCount=u4(80),fieldOff=u4(84); range(fieldOff,(long)fieldCount*8);
            for(int i=0;i<fieldCount;i++) {
                int off=fieldOff+i*8; String name=string(u4(off+4));
                if(TAB_NAMES.contains(name)) {
                    String owner=type(u2(off));
                    if(owner.startsWith("Lcom/x/navigation/") && owner.equals(type(u2(off+2))))
                        index.tabFields.computeIfAbsent(owner,k->new HashSet<>()).add(name);
                }
            }
            int count=u4(96),off=u4(100); range(off,(long)count*32);
            for(int c=0;c<count;c++) {
                int data=u4(off+c*32+24); if(data==0) continue;
                int[] cursor={data}; int staticFields=leb(cursor),instanceFields=leb(cursor),direct=leb(cursor),virtual=leb(cursor);
                for(int i=0;i<staticFields+instanceFields;i++) { leb(cursor); leb(cursor); }
                for(int part=0;part<2;part++) {
                    int id=0, size=part==0?direct:virtual;
                    for(int m=0;m<size;m++) {
                        id+=leb(cursor); int access=leb(cursor),code=leb(cursor);
                        MethodRef ref=method(id);
                        if((u4(off+c*32+4)&0x200)!=0 && ref.result.equals("V") && ref.args.size()==1 &&
                            (ref.args.get(0).startsWith("Landroidx/compose/ui/graphics/drawscope/") ||
                             ref.args.get(0).startsWith("Landroidx/compose/ui/node/"))) index.drawInterfaces.add(ref.owner);
                        if((access&8)!=0 && ref.result.equals("V")) typed(ref);
                        if((access&8)!=0 && ref.result.equals(MODIFIER) && ref.owner.startsWith("Landroidx/compose/foundation/") &&
                            ref.args.size()==3 && ref.args.get(0).equals(MODIFIER) && ref.args.get(1).equals("J") &&
                            ref.args.get(2).startsWith("Landroidx/compose/ui/graphics/")) index.add("backgroundFactory",ref);
                        if((access&8)!=0 && ref.result.equals(MODIFIER) && ref.owner.startsWith("Landroidx/compose/foundation/") &&
                            ref.args.size()==4 && ref.args.subList(0,3).equals(Arrays.asList(MODIFIER,"F","J")) &&
                            ref.args.get(3).startsWith("Landroidx/compose/ui/graphics/")) index.add("replyBorderFactory",ref);
                        if((access&8)!=0 && ref.owner.startsWith("Landroidx/compose/ui/layout/") && ref.args.size()==2 &&
                            ref.args.get(0).startsWith("Landroidx/compose/ui/layout/") && ref.args.get(1).equals("Z") &&
                            ref.result.startsWith("Landroidx/compose/ui/geometry/")) index.add("coordinateBounds",ref);
                        if(code!=0) inspect(ref,code,(access&8)!=0);
                    }
                }
            }
        }
        void typed(MethodRef m) {
            List<String> p=m.args;
            if(p.contains(COMPOSER) && p.contains(FUNCTION1) && p.stream().anyMatch(t->t.startsWith("Lcom/x/navigation/")))
                index.landingCandidates.add(m);
            if(p.size()==5 && p.get(0).startsWith("Lcom/x/composer/fab/") && p.get(1).equals(FUNCTION0) &&
                p.get(2).equals(MODIFIER) && p.get(3).equals(COMPOSER) && p.get(4).equals("I")) index.fabCandidates.add(m);
        }
        void inspect(MethodRef m,int code,boolean isStatic) throws IOException {
            range(code,16); int size=u4(code+12),start=code+16; range(start,(long)size*2);
            boolean drawerReader=!isStatic && m.args.isEmpty() && m.result.equals("Z") &&
                (m.owner.startsWith("Landroidx/compose/material3/") || m.owner.startsWith("Lcom/x/main/drawer/"));
            boolean readsOpen=false, readsState=false;
            Set<String> found=new HashSet<>(); int visibleCalls=0;
            List<MethodRef> detailExits=new ArrayList<>();
            List<MethodRef> reads=new ArrayList<>();
            Set<MethodRef> overlayFields=new HashSet<>();
            Set<String> visibleOwners=new HashSet<>();
            boolean trackCalls=m.args.contains(COMPOSER) || m.owner.startsWith("Lcom/x/main/") ||
                m.owner.startsWith("Lcom/x/android/main/landing/") || m.owner.startsWith("Lcom/x/composer/fab/") ||
                m.owner.startsWith("Landroidx/compose/ui/layout/") || m.owner.startsWith("Lcom/x/composer/minimal/") ||
                (m.name.equals("<init>") && m.owner.startsWith("Lcom/x/postdetail/"));
            boolean composerCall=m.owner.startsWith("Lcom/x/composer/minimal/") && m.args.contains(COMPOSER);
            boolean coordinateBounds=m.owner.startsWith("Landroidx/compose/ui/layout/") && m.args.size()==2 &&
                m.args.get(0).startsWith("Landroidx/compose/ui/layout/") && m.args.get(1).equals("Z") && m.result.startsWith("Landroidx/compose/ui/geometry/");
            boolean overlay=m.result.equals("V") && m.args.size()==2 && m.args.get(1).equals("Z") && m.owner.startsWith("Lcom/x/ui/common/");
            boolean lifecycle=m.name.equals("onDestroy");
            boolean backgroundFactory=m.result.equals(MODIFIER) && m.owner.startsWith("Landroidx/compose/foundation/") &&
                m.args.size()==3 && m.args.get(0).equals(MODIFIER) && m.args.get(1).equals("J") &&
                m.args.get(2).startsWith("Landroidx/compose/ui/graphics/");
            boolean elementFactory=m.owner.startsWith("Landroidx/compose/foundation/") && m.args.isEmpty() && m.result.startsWith("Landroidx/compose/ui/");
            boolean elementUpdate=m.owner.startsWith("Landroidx/compose/foundation/") && m.args.size()==1 && m.args.get(0).startsWith("Landroidx/compose/ui/") && m.result.equals("V");
            for(int pos=0;pos<size;) {
                int value=u2(start+pos*2),op=value&255;
                int width=width(op);
                if(op==0 && value!=0) {
                    if(value==0x100) { if(pos+4>size) throw new IOException("Switch payload"); width=4+u2(start+(pos+1)*2)*2; }
                    else if(value==0x200) { if(pos+2>size) throw new IOException("Switch payload"); width=2+u2(start+(pos+1)*2)*4; }
                    else if(value==0x300) { if(pos+4>size) throw new IOException("Array payload"); long n=(long)u2(start+(pos+1)*2)*Integer.toUnsignedLong(u4(start+(pos+2)*2)); if(n>Integer.MAX_VALUE) throw new IOException("Array size"); width=4+(int)((n+1)/2); }
                    else throw new IOException("Unknown payload");
                }
                if(width<=0 || pos+width>size) throw new IOException("Invalid instruction");
                if(op==0x1a || op==0x1b) {
                    int id=op==0x1a?u2(start+(pos+1)*2):u4(start+(pos+1)*2);
                    String anchor=anchors.get(id); if(anchor!=null) found.add(anchor);
                }
                if((op==0x54 || op==0x55) && m.owner.startsWith("Ldev/chrisbanes/haze/")) {
                    int fieldId=u2(start+(pos+1)*2);
                    if(fieldId>=u4(80)) throw new IOException("DEX field index");
                    int offset=u4(84)+fieldId*8;range(offset,8);
                    reads.add(new MethodRef(type(u2(offset)),string(u4(offset+4)),type(u2(offset+2)),Collections.emptyList()));
                }
                if(overlay && op==0x54) {
                    int offset=u4(84)+u2(start+(pos+1)*2)*8;range(offset,8);
                    overlayFields.add(new MethodRef(type(u2(offset)),string(u4(offset+4)),type(u2(offset+2)),Collections.emptyList()));
                }
                if(drawerReader && op==0x62) {
                    int offset=u4(84)+u2(start+(pos+1)*2)*8;range(offset,8);
                    String enumOwner=type(u2(offset));
                    if(enumOwner.startsWith("Landroidx/compose/material3/") && enumOwner.equals(type(u2(offset+2))) &&
                        string(u4(offset+4)).equals("Open")) readsOpen=true;
                }
                if(trackCalls && ((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)))
                    index.calls.computeIfAbsent(m,k->new ArrayList<>()).add(method(u2(start+(pos+1)*2)));
                if(drawerReader && ((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))) {
                    MethodRef call=method(u2(start+(pos+1)*2));
                    if(call.owner.startsWith("Landroidx/compose/runtime/") && call.name.equals("getValue") &&
                        call.args.isEmpty() && call.result.equals("Ljava/lang/Object;")) readsState=true;
                }
                if(elementFactory && op==0x22) {
                    String node=type(u2(start+(pos+1)*2));
                    if(node.startsWith("Landroidx/compose/foundation/")) index.nodeConstructors.computeIfAbsent(m.owner,k->new ArrayList<>())
                        .add(new MethodRef(node,"<init>","V",Collections.emptyList()));
                }
                if(composerCall && ((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)))
                    index.composerCalls.computeIfAbsent(m,k->new ArrayList<>()).add(method(u2(start+(pos+1)*2)));
                if(coordinateBounds && (op==0x71 || op==0x77)) {
                    MethodRef call=method(u2(start+(pos+1)*2));
                    if(call.result.equals(m.args.get(0)) && call.args.equals(Collections.singletonList(m.args.get(0))))
                        index.coordinateRoots.computeIfAbsent(m,k->new ArrayList<>()).add(call);
                }
                if((overlay||lifecycle||backgroundFactory||elementFactory||elementUpdate) && ((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))) {
                    MethodRef call=method(u2(start+(pos+1)*2));
                    if(overlay && call.name.equals("setVisible") && call.args.equals(Collections.singletonList("Z")) && call.result.equals("V")) {
                        visibleCalls++;visibleOwners.add(call.owner);
                    }
                    if(lifecycle && call.owner.startsWith("Lcom/x/postdetail/") && call.result.equals("V") && call.args.isEmpty()) {
                        detailExits.add(call);index.add("detailLifecycle",m);
                    }
                    if(backgroundFactory && call.name.equals("<init>") && call.owner.startsWith("Landroidx/compose/foundation/") &&
                        call.args.contains("J") && call.args.contains(m.args.get(2))) index.add("backgroundElementConstructor",call);
                    if(elementFactory && call.name.equals("<init>") && call.owner.startsWith("Landroidx/compose/foundation/") && call.args.isEmpty())
                        index.nodeConstructors.computeIfAbsent(m.owner,k->new ArrayList<>()).add(call);
                    if(elementUpdate && (op==0x71||op==0x77) && call.owner.startsWith("Landroidx/compose/ui/node/") &&
                        call.result.equals("V") && call.args.size()==1 && call.args.get(0).startsWith("Landroidx/compose/ui/node/"))
                        index.nodeInvalidations.computeIfAbsent(m.owner,k->new ArrayList<>()).add(call);
                }
                pos+=width;
            }
            if(readsOpen && readsState) index.drawerOpenReaders.add(m);
            List<String> p=m.args;
            if(!found.isEmpty()) index.semantics.put(m,found);
            if(found.stream().anyMatch(v->v.startsWith("Attempting to drawContent for a `null` node")) &&
                p.isEmpty() && m.result.equals("V")) index.add("drawContent",m);
            if(found.contains("post-detail-reply-text-field")) index.add("replyComposer",m);
            if(found.contains("hazeAlpha") && composerCall && m.result.equals("V")) index.add("replyMaskScope",m);
            if(found.contains("HazeArea(") && m.name.equals("toString") && p.isEmpty()) index.add("hazeAreaIdentity",m);
            if(m.owner.startsWith("Ldev/chrisbanes/haze/") && p.isEmpty() && m.result.startsWith("Landroidx/compose/ui/graphics/layer/")) index.add("hazeAreaLayer",m);
            if(found.stream().anyMatch(v->v.startsWith("Modifier.hazeEffect nodes cannot draw an ancestor")) && m.result.equals("V") && p.size()==1 && p.get(0).startsWith("Landroidx/compose/ui/node/")) {
                index.add("hazeDraw",m);
                for(MethodRef field:reads) {
                    if(field.owner.equals(m.owner) && field.result.equals("Ljava/util/List;")) index.add("hazeAreas",field);
                    if(field.result.equals("Z")) index.add("hazeAreaRecording",field);
                }
            }
            if(m.name.equals("toString") && p.isEmpty() && m.result.equals("Ljava/lang/String;") &&
                m.owner.startsWith("Landroidx/compose/foundation/") && found.stream().anyMatch(v->v.startsWith("BackgroundElement("))) index.add("backgroundElement",m);
            if(isStatic && m.owner.startsWith("Lcom/x/ui/common/") && m.result.equals("V") && p.size()==11 &&
                p.get(0).startsWith("Landroidx/compose/runtime/internal/") && p.get(0).equals(p.get(4)) && p.get(0).equals(p.get(8)) &&
                p.get(2).equals(FUNCTION1) && p.get(3).equals(MODIFIER) && p.subList(5,8).equals(Arrays.asList("Z","Z","Z")) &&
                p.get(9).equals(COMPOSER) && p.get(10).equals("I")) index.add("bottomShape",m);
            if(isStatic && found.contains("inbox_action_button") && m.result.equals("V") &&
                m.owner.startsWith("Lcom/x/dm/convlist/") && Collections.frequency(p,FUNCTION0)==1 &&
                p.stream().anyMatch(t->t.startsWith("Lcom/x/dm/convlist/")) &&
                p.size()>=4 && p.subList(p.size()-2,p.size()).equals(Arrays.asList(COMPOSER,"I"))) index.add("chatFab",m);
            if(m.name.equals("<init>") && m.owner.startsWith("Lcom/x/postdetail/") && found.contains("inline-composer-slot")) {
                index.add("detailSlotOwner",m);
                if(found.contains("android_scribe_conversation_reply_dwell_linger_enabled")) index.add("detailConstructor",m);
            }
            if(visibleCalls==4 && visibleOwners.size()==1 && overlayFields.size()==4 &&
                overlayFields.stream().allMatch(f->f.owner.equals(p.get(0)) && visibleOwners.contains(f.result))) index.add("toggle",m);
            for(MethodRef exit:detailExits) index.add("detailDestroy",exit);
        }
        static int width(int op) {
            if(op==0x18) return 5;
            if(op==0xfa||op==0xfb) return 4;
            if(op==0x03||op==0x06||op==0x09||op==0x14||op==0x17||op==0x1b||op==0x24||op==0x25||op==0x26||
                op==0x2a||op==0x2b||op==0x2c||(op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)||op==0xfc||op==0xfd) return 3;
            if(op==0x02||op==0x05||op==0x08||op==0x13||op==0x15||op==0x16||op==0x19||op==0x1a||op==0x1c||
                op==0x1f||op==0x20||op==0x22||op==0x23||op==0x29||(op>=0x2d&&op<=0x3d)||(op>=0x44&&op<=0x6d)||
                (op>=0x90&&op<=0xaf)||(op>=0xd0&&op<=0xe2)||op==0xfe||op==0xff) return 2;
            return 1;
        }
    }
}

package io.github.offlineglass.hook.adapters.chatgpt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.*;

/** Read-only APK contracts rooted in platform/Compose API and drawing semantics.
 * Internal descriptors are discovered outputs only, never search keys. */
public final class ChatGptNativeContract {
    enum Capability {
        GLASS("scope.content","scope.type","scope.canvasScope","scope.parameters","parameters.canvas",
            "parameters.size","parameters.direction","parameters.density","background.draw","background.shape","background.type",
            "node.coordinate","node.attached","node.next","node.delegate","node.type","layout.outer","layout.children",
            "layout.chain","layout.owner","layout.parent","layout.placed","layout.deactivated","chain.head","chain.inner",
            "owner.nodes","semantics.lookup","coordinate.layout","coordinate.relativeMatrix","coordinate.screen",
            "coordinate.size","coordinate.type","coordinate.invalidate","layout.draw","canvas.native","canvas.type",
            "shape.outline","outline.bounds","outline.round","outline.roundValue","outline.generic","outline.path","path.native",
            "rectangle.left","rectangle.top","rectangle.right","rectangle.bottom","round.left","round.top","round.right","round.bottom",
            "round.topLeft","round.topRight","round.bottomRight","round.bottomLeft"),
        NATIVE_SHADOW("shadow.draw","shadow.paint","shadow.painter","shadow.registryNode","shadow.registryDraw",
            "shadow.registry","shadow.entryPainter","shadow.entryActive"),
        EXTERIOR_SHADOW("coordinate.parent","coordinate.layer","layer.graphics","graphics.renderer","renderer.native"),
        SEARCH_GRADIENT("searchGradient.type","searchGradient.draw"),
        SCAFFOLD_GRADIENT("scaffoldGradient.draw","scaffoldGradient.builder","scaffoldGradient.factory");

        final List<String> roles;
        Capability(String... roles){this.roles=List.of(roles);}
        List<String> missing(Properties properties){
            return roles.stream().filter(role->properties.getProperty(role)==null).toList();
        }
    }

    public record Ref(String owner,String name,String result,List<String> args) {
        public String key(){return owner+"|"+name+"|"+result+"|"+String.join(",",args);}
    }
    public record Field(String owner,String name,String type) { public String key(){return owner+"|"+name+"|"+type;} }
    static final class Body {
        final Ref ref;final int flags;final Set<String> strings=new HashSet<>();
        final Map<String,List<Field>> castReads=new HashMap<>();
        final Map<String,List<Field>> labelled=new HashMap<>();final List<Ref> calls=new ArrayList<>();final List<Field> reads=new ArrayList<>(),writes=new ArrayList<>();
        Body(Ref r,int f){ref=r;flags=f;}
    }
    static final class Type {
        final List<String> interfaces=new ArrayList<>();final String name,parent;final List<Field> fields=new ArrayList<>();final List<Body> methods=new ArrayList<>();
        Type(String n,String p){name=n;parent=p;}
    }
    static final class Index { final Map<String,Type> types=new LinkedHashMap<>();final Map<Ref,Body> methods=new LinkedHashMap<>(); }
    static final String LAYOUT="Landroidx/compose/ui/node/LayoutNode;",VIEW="Landroidx/compose/ui/platform/AndroidComposeView;";
    private final Index index;private final Properties roles=new Properties();
    private ChatGptNativeContract(Index i){index=i;}
    public static Properties inspect(String[] apks)throws IOException {
        Index i=new Index();
        for(String apk:apks)try(ZipFile z=new ZipFile(apk)){
            var entries=z.entries();while(entries.hasMoreElements()){
                var e=entries.nextElement();if(!e.getName().matches("classes[0-9]*\\.dex"))continue;
                if(e.getSize()<112||e.getSize()>64*1024*1024)throw new IOException("DEX size");
                try(InputStream in=z.getInputStream(e)){new Dex(in.readAllBytes()).scan(i);}
                if(i.types.containsKey(LAYOUT)&&i.types.containsKey(VIEW)) {
                    ChatGptNativeContract c=new ChatGptNativeContract(i);c.bind();
                    if(Arrays.stream(Capability.values()).allMatch(capability->capability.missing(c.roles).isEmpty()))return c.roles;
                }
            }
        }
        ChatGptNativeContract c=new ChatGptNativeContract(i);c.bind();return c.roles;
    }
    private Type type(String name){Type t=index.types.get(name);if(t==null)throw new IllegalStateException("Missing dependency type");return t;}
    private List<Body> all(){return new ArrayList<>(index.methods.values());}
    private <T> T unique(String role,Collection<T> values){
        List<T> list=new ArrayList<>(new LinkedHashSet<>(values));roles.setProperty(role+".candidates",Integer.toString(list.size()));
        if(list.size()!=1)return null;T value=list.get(0);
        roles.setProperty(role,value instanceof Ref?((Ref)value).key():value instanceof Field?((Field)value).key():value.toString());return value;
    }
    private Ref method(String role,String owner,Predicate<Body> match){
        return unique(role,type(owner).methods.stream().filter(match).map(b->b.ref).toList());
    }
    private Field field(String role,String owner,Predicate<Field> match){return unique(role,type(owner).fields.stream().filter(match).toList());}
    private void bindChainField(String role,Type chain,Predicate<String> valueType){
        Set<Field> candidates=new LinkedHashSet<>();
        for(Field field:chain.fields)if(valueType.test(field.type))candidates.add(field);
        for(Type owner:List.of(type(LAYOUT),chain)){
            for(Body method:owner.methods)method.castReads.forEach((cast,fields)->{
                if(valueType.test(cast))for(Field field:fields)
                    if(field.owner.equals(chain.name)&&field.type.equals("Ljava/lang/Object;")&&chain.fields.contains(field))candidates.add(field);
            });
        }
        unique(role,candidates);
    }
    private static boolean args(Ref r,String... args){return r.args.equals(List.of(args));}
    private Body body(Ref r){return index.methods.get(r);}
    private void bind(){
        try { bindDrawing(); } catch(RuntimeException e) { roles.setProperty("failure",e.toString()); }
    }
    private void bindDrawing(){
        Ref content=unique("scope.content",all().stream().filter(b->args(b.ref)&&b.ref.result.equals("V")&&
            b.strings.stream().anyMatch(s->s.startsWith("Attempting to drawContent for a `null` node"))).map(b->b.ref).toList());
        if(content==null)return;String scope=content.owner;unique("scope.type",List.of(scope));
        Ref direction=method("scope.direction",scope,b->b.ref.name.equals("getLayoutDirection")&&args(b.ref));
        Ref outer=method("layout.outer",LAYOUT,b->b.ref.name.equals("getOuterCoordinator$ui")&&args(b.ref));
        if(direction==null||outer==null)return;
        String coord=outer.result;unique("coordinate.type",List.of(coord));
        Field chain=unique("layout.chain",body(outer).reads.stream().filter(f->f.owner.equals(LAYOUT)).toList());
        if(chain==null)return;
        Field coordLayout=field("coordinate.layout",coord,f->f.type.equals(LAYOUT));
        Ref detach=unique("node.detach",all().stream().filter(b->b.strings.contains("Cannot detach a node that is not attached")&&args(b.ref)&&b.ref.result.equals("V")).map(b->b.ref).toList());
        if(detach==null)return;String node=detach.owner;unique("node.type",List.of(node));
        Field attached=unique("node.attached",body(detach).writes.stream().filter(f->f.owner.equals(node)&&f.type.equals("Z")).toList());
        field("node.coordinate",node,f->f.type.equals(coord));
        Type chainType=index.types.get(chain.type);
        if(chainType==null)return;
        bindChainField("chain.head",chainType,t->t.equals(node));
        bindChainField("chain.inner",chainType,t->index.types.containsKey(t)&&coord.equals(type(t).parent));
        unique("node.next",body(content).reads.stream().filter(f->f.owner.equals(node)&&f.type.equals(node)&&type(node).methods.stream().filter(b->b.ref.name.equals("<init>")).noneMatch(b->b.writes.contains(f))).distinct().toList());
        // Resolve the draw dispatcher through its single concrete Canvas wrapper.
        List<String> wrappers=index.types.values().stream().filter(t->t.fields.stream().anyMatch(f->f.type.equals("Landroid/graphics/Canvas;"))).map(t->t.name).toList();
        List<Body> layoutDraws=type(LAYOUT).methods.stream().filter(b->b.ref.result.equals("V")&&b.ref.args.size()==2&&
            b.calls.contains(outer)&&b.calls.stream().anyMatch(c->c.owner.equals(coord)&&c.result.equals("V")&&c.args.equals(b.ref.args))).toList();
        Ref layoutDraw=unique("layout.draw",layoutDraws.stream().map(b->b.ref).toList());
        if(layoutDraw==null)return;
        String canvasApi=layoutDraw.args.get(0);
        String wrapper=unique("canvas.type",wrappers.stream().filter(w->type(w).interfaces.contains(canvasApi)&&
            type(w).methods.stream().anyMatch(b->b.calls.stream().anyMatch(c->c.owner.equals("Landroid/graphics/Canvas;")&&c.name.equals("drawPath")))).toList());
        if(wrapper!=null)field("canvas.native",wrapper,f->f.type.equals("Landroid/graphics/Canvas;"));
        // A Background draws content, caches an outline, and has a Shape field
        // with the packed-size/layout-direction/density -> outline contract.
        List<Body> backgrounds=all().stream().filter(b->b.ref.result.equals("V")&&args(b.ref,scope)&&b.calls.contains(content)&&
            type(b.ref.owner).fields.stream().filter(f->f.type.equals("F")).count()==1&&
            type(b.ref.owner).fields.stream().filter(f->f.type.equals("J")).count()>=2&&
            type(b.ref.owner).fields.stream().anyMatch(f->index.types.containsKey(f.type)&&type(f.type).methods.stream().anyMatch(m->
                m.ref.args.size()==3&&m.ref.args.get(0).equals("J")&&m.ref.args.get(1).equals(direction.result)&&
                index.types.containsKey(m.ref.args.get(2))&&type(m.ref.args.get(2)).methods.stream().anyMatch(d->d.ref.name.equals("getDensity"))))).toList();
        Ref draw=unique("background.draw",backgrounds.stream().map(b->b.ref).toList());
        if(draw==null)return;String background=draw.owner;unique("background.type",List.of(background));
        List<Field> shapes=type(background).fields.stream().filter(f->index.types.containsKey(f.type)&&type(f.type).methods.stream().anyMatch(m->m.ref.args.size()==3&&m.ref.args.get(0).equals("J")&&m.ref.args.get(1).equals(direction.result))).toList();
        Field shape=unique("background.shape",shapes.stream().filter(f->!body(draw).writes.contains(f)).toList());
        if(shape!=null)method("shape.outline",shape.type,b->b.ref.args.size()==3&&b.ref.args.get(0).equals("J")&&b.ref.args.get(1).equals(direction.result));
        Field canvasScope=unique("scope.canvasScope",body(direction).reads.stream().filter(f->f.owner.equals(scope)).toList());
        if(canvasScope!=null){
            Field params=unique("scope.parameters",body(direction).reads.stream().filter(f->f.owner.equals(canvasScope.type)).toList());
            if(params!=null){
                field("parameters.size",params.type,f->f.type.equals("J"));
                field("parameters.direction",params.type,f->f.type.equals(direction.result));
                if(shape!=null){Ref outline=type(shape.type).methods.get(0).ref;if(outline.args.size()==3)field("parameters.density",params.type,f->f.type.equals(outline.args.get(2)));}
                field("parameters.canvas",params.type,f->f.type.equals(canvasApi));
            }
        }
        List<Body> invalidations=type(coord).methods.stream().filter(b->args(b.ref)&&b.ref.result.equals("V")&&b.calls.contains(b.ref)&&
            b.reads.stream().anyMatch(f->f.owner.equals(coord)&&f.type.equals(coord))&&
            b.calls.stream().anyMatch(c->!c.owner.equals(coord)&&c.result.equals("V")&&args(c))).toList();
        Ref invalidation=unique("coordinate.invalidate",invalidations.stream().map(b->b.ref).toList());
        if(invalidation!=null){
            unique("coordinate.parent",body(invalidation).reads.stream().filter(f->f.owner.equals(coord)&&f.type.equals(coord)).toList());
            unique("coordinate.layer",body(invalidation).reads.stream().filter(f->f.owner.equals(coord)&&!f.type.equals(coord)).toList());
        }
        List<Body> packed=type(coord).methods.stream().filter(b->b.ref.result.equals("J")&&args(b.ref,"J")).toList();
        roles.setProperty("coordinate.packedTransforms.candidates",Integer.toString(packed.size()));
        Ref root=unique("coordinate.root",packed.stream().filter(b->b.calls.stream().anyMatch(c->c.owner.equals("Landroidx/compose/ui/node/Owner;")&&c.name.equals("getRectManager"))).map(b->b.ref).toList());
        if(root!=null)unique("coordinate.screen",packed.stream().filter(b->b.calls.contains(root)&&b.reads.stream().anyMatch(f->f.owner.equals(VIEW)&&f.type.equals("[F"))).map(b->b.ref).toList());
        Ref matrix=method("coordinate.relativeMatrix",coord,b->b.ref.result.equals("V")&&b.ref.args.size()==2&&b.ref.args.get(1).equals("[F")&&!b.ref.args.get(0).equals(coord));
        if(matrix!=null){String coordsApi=matrix.args.get(0);method("coordinate.size",coordsApi,b->args(b.ref)&&b.ref.result.equals("J"));}
        field("layout.owner",LAYOUT,f->f.type.equals("Landroidx/compose/ui/node/Owner;"));
        Ref placed=method("layout.placed",LAYOUT,b->args(b.ref)&&b.ref.result.equals("Ljava/lang/Boolean;"));
        method("layout.children",LAYOUT,b->b.ref.name.equals("getChildren$ui")&&args(b.ref));
        unique("node.delegate",body(content).reads.stream().filter(f->f.type.equals(node)&&!f.owner.equals(node)).toList());
        Ref text=method("layout.description",LAYOUT,b->b.ref.name.equals("toString")&&args(b.ref)&&b.strings.contains(" deactivated: "));
        if(text!=null)unique("layout.deactivated",body(text).labelled.getOrDefault(" deactivated: ",List.of()).stream().filter(f->f.owner.equals(LAYOUT)&&f.type.equals("Z")).toList());
        if(text!=null){
            var virtualFields=body(text).labelled.getOrDefault(" isVirtual: ",List.of());
            Field virtual=unique("layout.virtual",virtualFields.stream().filter(f->f.owner.equals(LAYOUT)&&f.type.equals("Z")).toList());
            if(virtual!=null)method("layout.parent",LAYOUT,b->args(b.ref)&&b.ref.result.equals(LAYOUT)
                &&b.calls.isEmpty()&&b.writes.isEmpty()&&b.reads.contains(virtual)
                &&b.reads.stream().filter(f->f.owner.equals(LAYOUT)&&f.type.equals(LAYOUT)).count()>=2);
        }
        Ref nodes=method("owner.nodes",VIEW,b->b.ref.name.equals("getLayoutNodes")&&args(b.ref)&&(b.flags&64)==0);
        if(nodes!=null){String map=nodes.result;
            while(map!=null){List<Body> gets=type(map).methods.stream().filter(b->args(b.ref,"I")&&b.ref.result.equals("Ljava/lang/Object;")&&b.writes.isEmpty()&&b.calls.stream().allMatch(c->c.owner.startsWith("Ljava/lang/"))&&b.reads.stream().anyMatch(f->f.type.equals("[Ljava/lang/Object;"))&&b.reads.stream().anyMatch(f->f.type.equals("[I"))).toList();
                if(!gets.isEmpty()){unique("semantics.lookup",gets.stream().map(b->b.ref).toList());break;}map=type(map).parent;}
        }
        Ref outline=shape==null?null:type(shape.type).methods.stream().filter(b->b.ref.args.size()==3&&b.ref.args.get(0).equals("J")&&b.ref.args.get(1).equals(direction.result)).map(b->b.ref).findFirst().orElse(null);
        if(outline!=null){
            Ref bounds=method("outline.bounds",outline.result,b->args(b.ref)&&b.ref.result.startsWith("L"));
            if(bounds!=null){
                Ref ctor=method("rectangle.constructor",bounds.result,b->b.ref.name.equals("<init>")&&args(b.ref,"F","F","F","F"));
                if(ctor!=null){List<Field> fs=body(ctor).writes.stream().filter(f->f.owner.equals(bounds.result)&&f.type.equals("F")).distinct().toList();
                    if(fs.size()==4)for(int j=0;j<4;j++)unique("rectangle."+List.of("left","top","right","bottom").get(j),List.of(fs.get(j)));}
            }
            for(Type t:index.types.values())if(outline.result.equals(t.parent)){
                for(Field value:t.fields){Type g=index.types.get(value.type);if(g==null)continue;
                    var ctors=g.methods.stream().filter(b->b.ref.name.equals("<init>")&&args(b.ref,"F","F","F","F","J","J","J","J")).toList();
                    if(ctors.size()==1){unique("outline.round",List.of(t.name));unique("outline.roundValue",List.of(value));
                        List<Field> fs=ctors.get(0).writes.stream().filter(f->f.owner.equals(g.name)).distinct().toList();
                        if(fs.size()==8)for(int j=0;j<8;j++)unique("round."+List.of("left","top","right","bottom","topLeft","topRight","bottomRight","bottomLeft").get(j),List.of(fs.get(j)));
                    }
                    if(t.fields.size()==1&&g.fields.stream().filter(f->f.type.equals("Landroid/graphics/Path;")).count()==1){
                        unique("outline.generic",List.of(t.name));unique("outline.path",List.of(value));field("path.native",g.name,f->f.type.equals("Landroid/graphics/Path;"));
                    }
                }
            }
        }
        try {
            bindSearchGradient(node, scope, content);
        } catch(RuntimeException e) {
            roles.setProperty("searchGradientFailure", e.toString());
        }
        try {
            bindScaffoldGradient(node, scope);
        } catch(RuntimeException e) {
            roles.setProperty("scaffoldGradientFailure", e.toString());
        }
        try {
            if(shape!=null)bindNativeShadow(scope,content,shape);
        } catch(RuntimeException e) {
            roles.setProperty("nativeShadowFailure",e.toString());
        }
        try {
            if(invalidation!=null)bindExteriorShadow(coord,invalidation);
        } catch(RuntimeException e) {
            roles.setProperty("exteriorShadowFailure",e.toString());
        }
    }
    private void bindSearchGradient(String node, String scope, Ref content) {
        Ref element = unique("searchGradient.element", all().stream()
            .filter(body -> body.strings.contains("elevatedContentGradient"))
            .flatMap(body -> body.calls.stream())
            .filter(call -> call.name.equals("<init>") && call.args.size() == 4
                && call.args.get(0).startsWith("L") && call.args.subList(1, 4).equals(List.of("I", "J", "Z")))
            .toList());
        if (element == null || !index.types.containsKey(element.owner)) return;
        Ref created = unique("searchGradient.nodeConstructor", type(element.owner).methods.stream()
            .filter(body -> args(body.ref) && body.ref.result.equals(node))
            .flatMap(body -> body.calls.stream())
            .filter(call -> call.name.equals("<init>") && call.args.equals(element.args)
                && index.types.containsKey(call.owner) && node.equals(type(call.owner).parent))
            .toList());
        if (created == null) return;
        Ref drawing = method("searchGradient.draw", created.owner, body ->
            body.ref.result.equals("V") && args(body.ref, scope) && body.calls.contains(content)
                && body.calls.stream().filter(call -> call.owner.equals(created.owner) && args(call))
                    .map(index.methods::get).filter(Objects::nonNull)
                    .anyMatch(helper -> helper.reads.stream().anyMatch(
                        field -> field.owner.equals(created.owner) && field.type.equals("J"))));
        if (drawing != null) unique("searchGradient.type", List.of(created.owner));
    }

    private void bindScaffoldGradient(String node, String scope) {
        String function = "Lkotlin/jvm/functions/Function1;";
        String object = "Ljava/lang/Object;";
        Predicate<Ref> buildsFade = call -> call.args.size() == 8 && call.result.equals(call.args.get(0))
            && call.args.subList(1, 5).equals(List.of("J", function, function, function))
            && call.args.subList(6, 8).equals(List.of("I", "I")) && body(call) != null;
        // The scaffold's dock modifier and MainTabBarHost independently
        // reach the same cached bitmap fade factory.
        Set<Ref> factories = new LinkedHashSet<>();
        for (Body entry : index.methods.values()) {
            if (entry.strings.contains("MainTabBarHost") && entry.strings.contains("main-tab-bar")) {
                entry.calls.stream().filter(buildsFade).forEach(factories::add);
            }
            if (entry.strings.contains("ScaffoldLayout")) {
                entry.calls.stream()
                    .filter(call -> call.args.size() == 4 && call.args.get(0).equals("I")
                        && call.result.equals(call.args.get(2)) && call.args.get(3).equals("Z"))
                    .map(this::body).filter(Objects::nonNull)
                    .flatMap(modifier -> modifier.calls.stream().filter(call -> call.result.equals(modifier.ref.result)))
                    .filter(buildsFade).forEach(factories::add);
            }
        }
        Ref factory = unique("scaffoldGradient.composition", factories);
        if (factory == null) return;
        String modifier = factory.result;
        Body composition = body(factory);
        Ref builder = unique("scaffoldGradient.builderConstructor", composition.calls.stream()
            .filter(call -> call.name.equals("<init>") && call.args.size() == 4
                && call.args.get(0).equals(function) && call.args.subList(2, 4).equals(List.of(function, function))
                && index.types.containsKey(call.owner) && type(call.owner).interfaces.contains(function)
                && type(call.owner).methods.stream().anyMatch(method -> args(method.ref, object)
                    && method.ref.result.equals(object)
                    && method.calls.stream().anyMatch(c -> c.owner.equals("Landroid/graphics/Canvas;") && c.name.equals("drawRect"))
                    && method.calls.stream().anyMatch(c -> c.owner.equals("Landroid/graphics/Bitmap;") && c.name.equals("getHeight"))))
            .toList());
        Ref cache = unique("scaffoldGradient.cacheModifier", composition.calls.stream()
            .filter(call -> args(call, modifier, function) && call.result.equals(modifier) && body(call) != null)
            .toList());
        if (builder == null || cache == null) return;
        Ref element = unique("scaffoldGradient.cacheElement", body(cache).calls.stream()
            .filter(call -> call.name.equals("<init>") && args(call, function) && index.types.containsKey(call.owner))
            .toList());
        if (element == null) return;
        Ref created = unique("scaffoldGradient.cacheNode", type(element.owner).methods.stream()
            .filter(method -> args(method.ref) && method.ref.result.equals(node))
            .flatMap(method -> method.calls.stream())
            .filter(call -> call.name.equals("<init>") && call.args.size() == 2 && call.args.get(1).equals(function)
                && index.types.containsKey(call.owner) && node.equals(type(call.owner).parent))
            .toList());
        if (created == null) return;
        Ref draw = method("scaffoldGradient.draw", created.owner, method ->
            args(method.ref, scope) && method.ref.result.equals("V")
                && method.strings.contains("DrawResult not defined, did you forget to call onDraw?"));
        if (draw != null) {
            unique("scaffoldGradient.builder", List.of(builder.owner));
            unique("scaffoldGradient.factory", body(created).writes.stream()
                .filter(field -> field.owner.equals(created.owner) && field.type.equals(function)).toList());
        }
    }

    private void bindNativeShadow(String scope,Ref content,Field shape){
            List<Body> shadowDraws=all().stream().filter(b->args(b.ref,scope)&&b.ref.result.equals("V")&&b.calls.contains(content)&&
                type(b.ref.owner).fields.stream().anyMatch(f->f.type.equals(shape.type))&&
                type(b.ref.owner).fields.stream().anyMatch(f->f.type.equals("Lkotlin/jvm/functions/Function0;"))&&
                b.calls.stream().anyMatch(c->c.result.equals("V")&&args(c,scope,"F")&&type(b.ref.owner).fields.stream().anyMatch(f->f.type.equals(c.owner)))).toList();
            Ref shadow=unique("shadow.draw",shadowDraws.stream().map(b->b.ref).toList());
            if(shadow!=null){Ref painter=unique("shadow.paint",body(shadow).calls.stream().filter(c->c.result.equals("V")&&args(c,scope,"F")).toList());
                if(painter!=null){field("shadow.painter",shadow.owner,f->f.type.equals(painter.owner));
                    Ref registry=unique("shadow.registryDraw",all().stream().filter(b->args(b.ref,scope)&&b.ref.result.equals("V")&&b.calls.contains(painter)&&
                        b.calls.stream().anyMatch(c->c.owner.equals("Ljava/util/Map$Entry;")&&c.name.equals("getValue"))).map(b->b.ref).toList());
                    if(registry!=null){field("shadow.registryNode",shadow.owner,f->f.type.equals(registry.owner));
                        field("shadow.registry",registry.owner,f->index.types.containsKey(f.type)&&type(f.type).interfaces.contains("Ljava/util/Map;"));
                        Field entry=unique("shadow.entryPainter",body(registry).reads.stream().filter(f->f.type.equals(painter.owner)).toList());
                        if(entry!=null)unique("shadow.entryActive",body(registry).reads.stream().filter(f->f.owner.equals(entry.owner)&&f.type.equals("Z")&&!body(registry).writes.contains(f)).toList());
                    }
                }
            }
    }
    private void bindExteriorShadow(String coord,Ref invalidation){
        Ref alpha=unique("renderer.alphaSetter",all().stream().filter(b->b.ref.result.equals("V")&&args(b.ref,"F")&&
            b.calls.stream().anyMatch(c->c.owner.equals("Landroid/graphics/RenderNode;")&&c.name.equals("setAlpha")&&args(c,"F"))).map(b->b.ref).toList());
        if(alpha!=null){
            field("renderer.native",alpha.owner,f->f.type.equals("Landroid/graphics/RenderNode;"));
            List<String> rendererInterfaces=type(alpha.owner).interfaces;
            Field implementation=unique("graphics.renderer",index.types.values().stream().filter(t->t.fields.stream().anyMatch(f->f.type.equals("Landroid/graphics/Outline;")))
                .flatMap(t->t.fields.stream()).filter(f->rendererInterfaces.contains(f.type)).toList());
            Field owned=body(invalidation).reads.stream().filter(f->f.owner.equals(coord)&&!f.type.equals(coord)).findFirst().orElse(null);
            if(implementation!=null&&owned!=null)unique("layer.graphics",index.types.values().stream().filter(t->t.interfaces.contains(owned.type)&&t.fields.stream().anyMatch(f->f.type.equals(VIEW)))
                .flatMap(t->t.fields.stream()).filter(f->f.type.equals(implementation.owner)).toList());
        }
        // Delegated nodes are found via their parent-side modifier chain role;
        // shadow registry and animation alpha need independent unique contracts.
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
        void scan(Index out)throws IOException {
            for(int i=0;i<u4(96);i++){
                int def=u4(100)+i*32,data=u4(def+24); String owner=type(u4(def));
                Type t=new Type(owner,u4(def+8)==-1?null:type(u4(def+8))); out.types.put(owner,t);
                int interfaces=u4(def+12);if(interfaces!=0)for(int j=0;j<u4(interfaces);j++)t.interfaces.add(type(u2(interfaces+4+j*2)));
                if(data==0)continue;
                int[] p={data};int s=leb(p),f=leb(p),d=leb(p),v=leb(p);
                for(int count:new int[]{s,f}){int id=0;for(int n=0;n<count;n++){
                    id+=leb(p);int flags=leb(p);if((flags&8)==0)t.fields.add(field(id));
                }}
                for(int count:new int[]{d,v}){int id=0;for(int n=0;n<count;n++){
                    id+=leb(p);int flags=leb(p),code=leb(p);Ref r=ref(id);
                    Body body=code==0?new Body(r,flags):inspect(r,flags,code);
                    t.methods.add(body);out.methods.put(r,body);
                }}
            }
        }
        Body inspect(Ref r,int flags,int code)throws IOException{
            Body body=new Body(r,flags);int count=u4(code+12),start=code+16;
            if(count<0||(long)start+count*2L>b.length)throw new IOException("DEX code bounds");
            
            String label=null;Map<Integer,Object> registers=new HashMap<>();
            for(int pos=0;pos<count;){int word=u2(start+pos*2),op=word&255,w=width(op);
                if(op==0&&word!=0){if(word==0x100)w=4+u2(start+(pos+1)*2)*2;else if(word==0x200)w=2+u2(start+(pos+1)*2)*4;else if(word==0x300)w=4+(int)(((long)u2(start+(pos+1)*2)*Integer.toUnsignedLong(u4(start+(pos+2)*2))+1)/2);else throw new IOException("DEX payload");}
                if(w<1||pos+w>count)throw new IOException("DEX instruction");
                if(op==0x1a||op==0x1b){String value=str(op==0x1a?u2(start+(pos+1)*2):u4(start+(pos+1)*2));body.strings.add(value);registers.put(word>>>8,value);}
                if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                    Ref call=ref(u2(start+(pos+1)*2));body.calls.add(call);
                    if(call.owner.equals("Ljava/lang/StringBuilder;")&&call.name.equals("append")&&call.args.size()==1){
                        int argument=(op>=0x74)?u2(start+(pos+2)*2)+1:(u2(start+(pos+2)*2)>>>4)&15;
                        Object value=registers.get(argument);
                        if(value instanceof String)label=(String)value;
                        else if(value instanceof Field && label!=null){body.labelled.computeIfAbsent(label,k->new ArrayList<>()).add((Field)value);label=null;}
                    }
                }
                if(op==0x01||op==0x04||op==0x07){int destination=(word>>>8)&15,source=word>>>12;registers.put(destination,registers.get(source));}
                if(op==0x02||op==0x05||op==0x08)registers.put(word>>>8,registers.get(u2(start+(pos+1)*2)));
                if((op>=0x52&&op<=0x58)||(op>=0x60&&op<=0x66)){
                    Field f=field(u2(start+(pos+1)*2));body.reads.add(f);registers.put(op<=0x58?(word>>>8)&15:word>>>8,f);
                }
                if(op==0x54&&pos+3<count){
                    int next=u2(start+(pos+2)*2);
                    if((next&255)==0x1f&&(next>>>8)==((word>>>8)&15)){
                        Field field=field(u2(start+(pos+1)*2));
                        if(field.type.equals("Ljava/lang/Object;"))
                            body.castReads.computeIfAbsent(type(u2(start+(pos+3)*2)),key->new ArrayList<>()).add(field);
                    }
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

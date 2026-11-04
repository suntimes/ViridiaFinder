package org.example.viridiafinder;

import com.google.gson.*;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.item.Items;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import org.lwjgl.glfw.GLFW;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.*;

public final class ViridiaFinder implements ClientModInitializer {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    static final Path DIR=FabricLoader.getInstance().getConfigDir().resolve("viridiafinder-0.3");
    static final List<Survey.Pos> POINTS=loadPoints();
    static Survey.Settings settings=new Survey.Settings();
    static Survey survey;
    static ClientWorld world;
    static String instance="主群岛", scope="", cycle="未开始";
    static boolean enabled=false, openRequested=false;
    static Survey.Pos target;
    static String targetLabel="";
    static Survey.RoutePlan routePlan;
    static FastSurvey fastSurvey;
    static int searchPending;
    static RoundTracker roundTracker=new RoundTracker();

    static final Set<String> newBoxes=new HashSet<>();
    static int ticks;
    static int routeRequest;
    static SeaMap seaMap;
    static boolean seaPlanning;
    static int lastSeaReplan;
    static SeaPlanner.Plan seaPlan;
    static int seaStop,seaWaypoint,seaWait,seaRadiusLimit=240,seaStartPending;
    static String navigationStatus="未开始海面巡查";
    record SeaResult(SeaMap map,SeaPlanner.Plan plan){}
    static KeyBinding key;
    static KeyBinding highlightKey;
    static final class Saved {
        RoundTracker eye;
        String cycle;
        List<Survey.Candidate> candidates;
        Saved(String cycle,List<Survey.Candidate> candidates) { this.cycle=cycle; this.candidates=candidates;this.eye=roundTracker; }
    }
    static List<Survey.Pos> loadPoints() {
        List<Survey.Pos> points=new ArrayList<>();
        try(var in=ViridiaFinder.class.getResourceAsStream("/assets/viridiafinder/candidates.csv")) {
            if(in==null) throw new IOException("候选数据库缺失");
            try(var reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))) {
                for(String line;(line=reader.readLine())!=null;) {
                    if(line.isBlank()||line.startsWith("#")) continue;
                    String[] s=line.split(","); points.add(new Survey.Pos(Integer.parseInt(s[0]),Integer.parseInt(s[1]),Integer.parseInt(s[2])));
                }
            }
        } catch(Exception e) { throw new IllegalStateException("无法读取候选数据库",e); }
        if(points.size()!=169 || new HashSet<>(points).size()!=169) throw new IllegalStateException("需要169个唯一候选坐标");
        return List.copyOf(points);
    }
    @Override public void onInitializeClient() {
        try { if(Files.exists(DIR.resolve("设置.json"))) settings=JSON.fromJson(Files.readString(DIR.resolve("设置.json")),Survey.Settings.class); }
        catch(Exception e) { settings=new Survey.Settings(); }
        if(settings==null) settings=new Survey.Settings(); settings.validate();
        survey=new Survey(POINTS,settings);
        key=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.viridiafinder.open",InputUtil.Type.KEYSYM,GLFW.GLFW_KEY_F8,"category.viridiafinder"));
        highlightKey=KeyBindingHelper.registerKeyBinding(new KeyBinding("key.viridiafinder.highlight",InputUtil.Type.KEYSYM,GLFW.GLFW_KEY_F7,"category.viridiafinder"));
        ClientPreAttackCallback.EVENT.register((client,player,clickCount)->{
            if(clickCount>0&&client.currentScreen==null){
                syncWorld(client);
                var held=player.getMainHandStack();String name=held.getName().getString();
                if(held.isOf(Items.ENDER_EYE)&&(name.contains("维里迪亚之眼")||name.toLowerCase(Locale.ROOT).contains("eye of viridia")))roundTracker.arm(System.currentTimeMillis());
            }
            return false;
        });
        ClientReceiveMessageEvents.GAME.register((text,overlay)->{
            if(overlay||world==null||world!=MinecraftClient.getInstance().world)return;
            var result=roundTracker.accept(text.getString(),System.currentTimeMillis());
            if(result==RoundTracker.Result.NEW_ROUND){
                if(settings.autoRound){newRound(true);}
                else message("之眼提示可能已换轮；自动更新已关闭，请在F8中更新。");
            }
            else if(result==RoundTracker.Result.TIME&&settings.autoRound&&cycle.matches("[0-9]+")
                &&roundTracker.localRecordOlderThanHint(Long.parseLong(cycle),System.currentTimeMillis()))newRound(true);
            if(result!=RoundTracker.Result.IGNORED)save();
        });
        ClientTickEvents.END_CLIENT_TICK.register(ViridiaFinder::tick);
        ClientChunkEvents.CHUNK_LOAD.register((w,chunk)->invalidateChunk(chunk.getPos().x,chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((w,chunk)->invalidateChunk(chunk.getPos().x,chunk.getPos().z));
        HudRenderCallback.EVENT.register((ctx,delta)->FinderScreen.hud(ctx));
        WorldRenderEvents.AFTER_ENTITIES.register(ContainerHighlights::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)-> {
            var root=literal("viridiafinder").executes(c->{openRequested=true;return 1;});
            root.then(literal("帮助").executes(c->{help();return 1;}));
            root.then(literal("高亮").executes(c->{settings.highlight=true;enable();save();return 1;}));
            root.then(literal("检索").executes(c->{settings.highlight=true;enable();searchPending=40;message("快速检索已开启，约2秒后列出当前发现的近处容器；未加载区域仍未知。");return 1;}));
            root.then(literal("取消高亮").executes(c->{settings.highlight=false;save();message("箱子边框已隐藏");return 1;}));
            for(String name:List.of("开启","on")) root.then(literal(name).executes(c->{enable();return 1;}));
            for(String name:List.of("关闭","off")) root.then(literal(name).executes(c->{enabled=false;save();message("扫描已关闭");return 1;}));
            for(String name:List.of("状态","status")) root.then(literal(name).executes(c->{message("扫描"+(enabled?"开启":"关闭")+"；候选169；已完成轮询"+survey.passes+"轮；范围水平±"+settings.radius+"，向下"+settings.below+"，向上"+settings.above);return 1;}));
            for(String name:List.of("路线","route")) root.then(literal(name).executes(c->{route();return 1;}));
            root.then(literal("新一轮").executes(c->{newRound(false);return 1;}));
            root.then(literal("结果").executes(c->{openRequested=true;return 1;}));
            root.then(literal("导出").executes(c->{export();return 1;}));
            root.then(literal("地图").executes(c->{settings.hud=!settings.hud;save();message("地图"+(settings.hud?"开启":"关闭"));return 1;}));
            root.then(literal("木桶").executes(c->{message("本版本仅检索普通箱子和陷阱箱，木桶识别已移除。");return 1;}));
            root.then(literal("范围").then(argument("水平半径",IntegerArgumentType.integer(0,64)).then(argument("向下高度",IntegerArgumentType.integer(0,128)).then(argument("向上高度",IntegerArgumentType.integer(0,128)).executes(c->{
                settings.radius=IntegerArgumentType.getInteger(c,"水平半径");settings.below=IntegerArgumentType.getInteger(c,"向下高度");settings.above=IntegerArgumentType.getInteger(c,"向上高度");survey.resetProgress();save();message("范围已更新，正在重新扫描");return 1;
            })))));
            root.then(literal("预算").then(argument("每帧次数",IntegerArgumentType.integer(128,8192)).executes(c->{settings.budget=IntegerArgumentType.getInteger(c,"每帧次数");save();message("每帧检查预算已更新");return 1;})));
            root.then(literal("实例").then(argument("名称",StringArgumentType.word()).executes(c->{save();instance=StringArgumentType.getString(c,"名称");world=null;scope="";enabled=false;message("实例标签已更改，请重新开启扫描");return 1;})));
            dispatcher.register(root);
        });
    }
    static void help() {
        message("按F7同时开启扫描、箱子高亮和寻宝地图；再次按F7全部关闭。");
        message("/viridiafinder 检索：快速检索已加载候选区域，并列出192格内最近的容器");
        message("按F8或输入 /viridiafinder 打开中文结果界面");
        message("/viridiafinder 开启、关闭、状态、路线、地图、结果、导出、新一轮");
        message("/viridiafinder 范围 <水平半径0–64> <向下0–128> <向上0–128>");
        message("/viridiafinder 预算 <128–8192>；/viridiafinder 实例 <标签>");
        message("容器不等于宝藏。请在结果界面选择容器后标记疑似、确认或已领取。");
    }
    static void enable() {
        syncWorld(MinecraftClient.getInstance());
        if(world==null) {message("请先进入世界");return;}
        enabled=true;message("持续扫描已开启；仅检查客户端已加载区块。请核对分线与实例标签。");
    }
    static void syncWorld(MinecraftClient client) {
        if(world==client.world) return;
        save(); TravelAssist.disconnect();roundTracker=new RoundTracker();world=client.world;enabled=false;target=null;routePlan=null;seaPlan=null;seaPlanning=false;seaStartPending=0;seaRadiusLimit=240;routeRequest++;searchPending=0;newBoxes.clear();
        survey=new Survey(POINTS,settings);
        if(world==null) {scope="";return;}
        String server=client.getCurrentServerEntry()!=null?client.getCurrentServerEntry().address:
            client.getServer()!=null?"单人:"+client.getServer().getSaveProperties().getLevelName():"本地";
        scope=server+"|"+world.getRegistryKey().getValue()+"|"+instance;
        cycle=Long.toString(System.currentTimeMillis());
        try {
            if(Files.exists(pointerPath())) {
                String savedCycle=Files.readString(pointerPath()).trim();
                if(!savedCycle.matches("[0-9]+")) throw new IOException("轮次文件格式不正确");
                cycle=savedCycle;
            }
            if(Files.exists(statePath())) {
                Saved data=JSON.fromJson(Files.readString(statePath()),Saved.class);
                if(data.eye!=null){roundTracker=data.eye;roundTracker.forgetSession();}
                if(data.candidates!=null) for(Survey.Candidate c:data.candidates) {
                    if(c.id<1||c.id>169||!POINTS.get(c.id-1).equals(c.pos)) continue;
                    Survey.Candidate dest=survey.candidates.get(c.id-1);dest.visited=c.visited;
                    if(c.found!=null) for(Survey.Found f:c.found.values()) if(f!=null&&f.pos!=null&&f.kind!=null&&f.mark!=null) {
                        f.present=false;dest.found.put(f.pos.key(),f);
                    }
                }
            }
        } catch(Exception e) { message("记录读取失败，原文件保留；请检查配置目录");cycle=Long.toString(System.currentTimeMillis());survey=new Survey(POINTS,settings); }
    }
    static void invalidateChunk(int x,int z) {
        if(survey==null)return;
        survey.invalidateChunk(x,z);fastSurvey=null;
    }
    static void tick(MinecraftClient client) {
        syncWorld(client);
        while(highlightKey.wasPressed()){
            if(enabled){TravelAssist.stop(client);TravelAssist.riptide=false;TravelAssist.swim=false;enabled=false;settings.highlight=false;settings.hud=false;seaPlanning=false;seaStartPending=0;routeRequest++;searchPending=0;newBoxes.clear();save();message("扫描、箱子高亮和寻宝地图已关闭");}
            else {enable();if(enabled){settings.highlight=true;settings.hud=true;seaStartPending=40;navigationStatus="测量已加载范围";save();message("扫描、箱子高亮和寻宝地图已开启");}}
        }
        while(key.wasPressed()) openRequested=true;
        if(openRequested) {openRequested=false;if(client.world!=null)client.setScreen(new FinderScreen());}
        if(world==null || client.player==null) return;
        TravelAssist.tick(client);
        if(roundTracker.reminderDue(System.currentTimeMillis()))message("预计刷新时间已到；请主手持维里迪亚之眼左键确认。尚未自动清空记录。");
        if(enabled){
            if(fastSurvey==null||fastSurvey.survey!=survey)fastSurvey=new FastSurvey(survey);
            fastSurvey.tick(view(),playerPos(),512,System.nanoTime()+1_000_000L,f->{
                newBoxes.add(f.pos.key());
            });
        }
        if(enabled) survey.tick(view(),settings.budget,System.nanoTime()+2_000_000L,f->{
            newBoxes.add(f.pos.key());
        });
        if(enabled&&ticks%20==0){
            var nearest=ContainerHighlights.collect(survey,view(),playerPos(),192,1);
            if(!nearest.isEmpty()){
                var box=nearest.get(0);
                if(routePlan==null&&seaPlan==null&&!seaPlanning&&seaStartPending==0){target=box.pos();targetLabel="最近"+box.kind().label;}
                if(!newBoxes.isEmpty())message("新发现"+newBoxes.size()+"处箱子；最近未领取："+coords(box.pos())+"，"+direction(box.pos())+"（未确认宝藏）");
            } else if(routePlan==null&&seaPlan==null&&targetLabel.startsWith("最近")){target=null;targetLabel="";}
            newBoxes.clear();
        }
        if(enabled&&seaStartPending>0&&--seaStartPending==0)route();
        if(enabled&&seaPlan!=null)updateSeaRoute();
        if(enabled&&routePlan!=null) updateRoute();
        if(enabled&&searchPending>0&&--searchPending==0)searchResults();
        if(++ticks%200==0) save();
    }
    static Survey.View view() {
        return new Survey.View() {
            public boolean loaded(int x,int z) {return world!=null&&world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false)!=null;}
            public Survey.Kind read(Survey.Pos p) {
                var chunk=world.getChunkManager().getChunk(p.x()>>4,p.z()>>4,ChunkStatus.FULL,false);
                if(chunk==null) throw new IllegalStateException("未加载区块不能读取");
                var state=chunk.getBlockState(new BlockPos(p.x(),p.y(),p.z()));
                return ContainerTypes.identify(state);
            }
            public int bottom(){return world.getBottomY();}
            public int top(){return world.getTopY();}
            public List<Survey.Pos> indexedPositions(int x,int z) {
                var chunk=world.getChunkManager().getChunk(x,z,ChunkStatus.FULL,false);
                if(chunk==null)return List.of();
                // 仅取已有方块实体的位置，不读取物品、NBT或交互容器。
                return chunk.getBlockEntities().keySet().stream().map(p->new Survey.Pos(p.getX(),p.getY(),p.getZ())).toList();
            }
        };
    }
    static void newRound(boolean fromEye){
        if(world==null){message("请先进入世界");return;}
        if(!save()){message("旧轮次未能存档，已取消重置。");return;}
        long previous=cycle.matches("[0-9]+")?Long.parseLong(cycle):0;
        cycle=Long.toString(Math.max(System.currentTimeMillis(),previous+1));
        survey=new Survey(POINTS,settings);fastSurvey=null;target=null;targetLabel="";routePlan=null;seaPlan=null;seaPlanning=false;seaStartPending=0;seaRadiusLimit=240;routeRequest++;searchPending=0;newBoxes.clear();
        if(!fromEye)roundTracker=new RoundTracker();
        if(enabled)seaStartPending=40;save();message(fromEye?"依据之眼轮次提示，已更新本地轮次；旧记录已存档。":"已开始新一轮本地记录；旧记录已存档。");
    }
    static void searchResults(){
        var list=ContainerHighlights.collect(survey,view(),playerPos(),192,8);
        if(list.isEmpty()){message("当前192格内的已加载候选区域尚未发现容器；检索仍持续进行。");return;}
        message("当前检索结果（最近"+list.size()+"处，容器不等于宝藏）：");
        for(var h:list)message(h.kind().label+"："+coords(h.pos())+"；"+direction(h.pos()));
        target=list.get(0).pos();targetLabel=list.get(0).kind().label;routePlan=null;seaPlan=null;seaPlanning=false;seaStartPending=0;routeRequest++;
    }
    static Path statePath() {return DIR.resolve(UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8))+"-"+cycle+".json");}
    static Path pointerPath(){return DIR.resolve(UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8))+"-当前.txt");}
    static boolean save() {
        try {
            Files.createDirectories(DIR);atomic(DIR.resolve("设置.json"),JSON.toJson(settings));
            if(survey!=null&&!scope.isEmpty()) {atomic(statePath(),JSON.toJson(new Saved(cycle,survey.candidates)));atomic(pointerPath(),cycle);}
            return true;
        } catch(Exception e) {message("无法保存记录："+e.getMessage());return false;}
    }
    static void atomic(Path path,String contents)throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");Files.writeString(temp,contents,StandardCharsets.UTF_8);
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);
    }
    static Survey.Pos playerPos() {
        var p=MinecraftClient.getInstance().player;
        return p==null?new Survey.Pos(0,0,0):new Survey.Pos(p.getBlockX(),p.getBlockY(),p.getBlockZ());
    }
    static String coords(Survey.Pos p){return p.x()+" "+p.y()+" "+p.z();}
    static String direction(Survey.Pos p) {
        Survey.Pos q=playerPos();double angle=Math.atan2(p.x()-q.x(),-(p.z()-q.z()));
        String[] names={"北","东北","东","东南","南","西南","西","西北"};
        return names[Math.floorMod((int)Math.round(angle/(Math.PI/4)),8)]+" "+String.format(Locale.ROOT,"%.1f",q.distance(p))+"格";
    }
    static String presence(Survey.Found f) {
        if(world==null||!view().loaded(f.pos.x()>>4,f.pos.z()>>4)) return "未加载／历史观察";
        return f.present?"上次扫描存在":"已消失或等待复查";
    }
    static List<Survey.Found> visible(Survey.Candidate c) {
        return c.found.values().stream().filter(f->survey.inRange(c,f.pos)&&(settings.barrels||f.kind!=Survey.Kind.BARREL)).sorted(Comparator.comparingDouble(f->f.pos.distance(c.pos))).toList();
    }
    static void mark(Survey.Found f,Survey.Mark mark) {
        for(Survey.Candidate c:survey.candidates) {Survey.Found match=c.found.get(f.pos.key());if(match!=null){match.mark=mark;if(mark==Survey.Mark.CLAIMED)c.visited=true;}}
        save();message("已标记："+mark.label+"，"+coords(f.pos));
    }
    static void route(){planSeaRoute(true);}
    static void planSeaRoute(boolean announce){
        if(world==null){if(announce)message("请先进入世界");return;}
        if(!world.getRegistryKey().getValue().toString().equals("monumenta:isles")){
            navigationStatus="仅支持主群岛海图";if(announce)message("此海图只适用于monumenta:isles；不会在其他地图套用海路。");return;
        }
        if(!enabled)enable();settings.hud=true;settings.highlight=true;seaStartPending=0;
        for(var c:survey.candidates)if(survey.fullyScanned(c,view())||c.found.values().stream().anyMatch(f->f.mark==Survey.Mark.CLAIMED))c.visited=true;
        var source=survey;var start=playerPos();int request=++routeRequest;
        var todo=survey.candidates.stream().filter(c->!c.visited&&c.found.values().stream().noneMatch(f->f.mark==Survey.Mark.CLAIMED)).toList();
        int observed=SeaPlanner.observedRadius(view(),start);
        int radius=Math.max(16,Math.min(seaRadiusLimit,(observed-1)*16-settings.radius));
        routePlan=null;seaPlan=null;seaPlanning=true;lastSeaReplan=ticks;target=null;navigationStatus="海面覆盖路线计算中";
        if(todo.isEmpty()){seaPlanning=false;navigationStatus="本轮候选巡查完成";save();return;}
        java.util.concurrent.CompletableFuture.supplyAsync(()->{
            try{var map=SeaMap.load();return new SeaResult(map,SeaPlanner.plan(map,start,todo,radius));}
            catch(IOException e){throw new java.util.concurrent.CompletionException(e);}
        }).whenComplete((result,error)->MinecraftClient.getInstance().execute(()->{
            if(source!=survey||request!=routeRequest||world==null)return;
            seaPlanning=false;
            if(error!=null){navigationStatus="海图读取失败，停止导航";message(navigationStatus);return;}
            seaMap=result.map();seaPlan=result.plan();seaStop=0;seaWaypoint=0;seaWait=0;
            navigationStatus="海面巡查；覆盖半径"+radius;
            if(announce)message("已规划"+seaPlan.stops().size()+"个海面扫描站；已观测完整加载圈"+observed+"区块，规划覆盖半径"+radius+"格。进度见右上角。");
            StringBuilder export=new StringBuilder("海面覆盖巡查；历史Xaero地表数据，不保证实时水路\n起点 "+coords(start)+"\n覆盖半径 "+radius+"；未覆盖候选 "+seaPlan.unresolved()+"\n");
            int n=0;for(var stop:seaPlan.stops()){export.append("扫描站 ").append(++n).append("，预计覆盖候选 ").append(stop.candidates()).append('\n');for(var pos:stop.path())export.append(coords(pos)).append('\n');}
            try{Files.createDirectories(DIR);Files.writeString(DIR.resolve("海面巡查路线.txt"),export);}catch(IOException e){message("海路导出失败："+e.getMessage());}
            updateSeaRoute();save();
        }));
    }
    static void updateSeaRoute(){
        for(var c:survey.candidates)if(!c.visited&&survey.fullyScanned(c,view()))c.visited=true;
        if(seaStop>=seaPlan.stops().size()){
            long left=survey.candidates.stream().filter(c->!c.visited).count();
            navigationStatus=left==0?"本轮候选巡查完成":"海路完成；待核查"+left+"点";
            target=null;targetLabel="";seaPlan=null;save();return;
        }
        var stop=seaPlan.stops().get(seaStop);var path=stop.path();
        for(int id:stop.candidates()){
            var c=survey.candidates.get(id-1);
            if(!c.visited&&survey.coverage(c,view()).equals("区域全部已加载")){survey.prioritize(c);break;}
        }
        if(ticks-lastSeaReplan>100&&stop.candidates().stream().allMatch(id->survey.candidates.get(id-1).visited)
            &&RouteOptimizer.distance(playerPos(),path.get(path.size()-1))>16){planSeaRoute(false);return;}
        if(seaWaypoint<path.size()){
            int currentCell=seaMap.cell(playerPos());
            if(seaMap.water(currentCell)){
                for(int ahead=path.size()-1;ahead>seaWaypoint;ahead--)if(seaMap.clear(currentCell,seaMap.cell(path.get(ahead)))){seaWaypoint=ahead;break;}
                if(!seaMap.clear(currentCell,seaMap.cell(path.get(seaWaypoint)))&&ticks-lastSeaReplan>100){planSeaRoute(false);return;}
            }
            var point=path.get(seaWaypoint);target=point;
            targetLabel=seaStop==0&&seaWaypoint==0&&!seaMap.water(currentCell)?"先到海面入口":"海路"+(seaStop+1)+"/"+seaPlan.stops().size();
            if(RouteOptimizer.distance(playerPos(),point)<=6&&seaMap.water(currentCell))seaWaypoint++;
            return;
        }
        var remaining=stop.candidates().stream().map(id->survey.candidates.get(id-1)).filter(c->!c.visited).toList();
        if(remaining.isEmpty()){seaStop++;seaWaypoint=0;seaWait=0;return;}
        navigationStatus="扫描站补扫 "+remaining.size()+"点";
        for(var c:remaining)if(survey.coverage(c,view()).equals("区域全部已加载")){survey.prioritize(c);break;}
        if(++seaWait>200){
            if(seaRadiusLimit>16){seaRadiusLimit=Math.max(16,Math.min(seaRadiusLimit,seaPlan.radius())/2);navigationStatus="加载不足，缩小覆盖重规划";planSeaRoute(false);}
            else {navigationStatus="有候选未加载；请靠近后重规划";seaPlan=null;target=null;save();}
        }
    }
    static void updateRoute() {
        int captured=routePlan.captureCompleted(survey,view());
        int advanced=routePlan.advance(survey,view());
        var next=routePlan.current();
        if(next==null){routePlan=null;target=null;message("巡查路线已完成；这不表示已经找到全部宝藏。");}
        else {
            target=next.pos;targetLabel="巡查#"+next.id;
            if(survey.coverage(next,view()).equals("区域全部已加载")&&!survey.fullyScanned(next,view()))survey.prioritize(next);

        }
        if(advanced>0||captured>0)save();
    }
    static void export() {
        if(world==null){message("请先进入世界");return;}
        StringBuilder out=new StringBuilder("候选编号,候选坐标,区域加载状态,扫描状态,容器类型,容器坐标,偏移XYZ,候选距离,人工标记,当前观察\n");
        for(var c:survey.candidates) {
            String scan=c.completedAt==0?"未完成一次扫描":c.unknown>0?"上次部分扫描":"上次扫描完成";
            var list=visible(c);
            if(list.isEmpty()) out.append(c.id).append(',').append(coords(c.pos)).append(',').append(survey.coverage(c,view())).append(',').append(scan).append(",尚未发现,,,,,不能推断无箱子\n");
            for(var f:list) out.append(c.id).append(',').append(coords(c.pos)).append(',').append(survey.coverage(c,view())).append(',').append(scan).append(',').append(f.kind.label).append(',').append(coords(f.pos)).append(',').append('"').append(f.pos.offset(c.pos)).append('"').append(',').append(String.format(Locale.ROOT,"%.2f",f.pos.distance(c.pos))).append(',').append(f.mark.label).append(',').append(presence(f)).append('\n');
        }
        try{Files.createDirectories(DIR);Files.writeString(DIR.resolve("扫描结果.csv"),"\ufeff"+out,StandardCharsets.UTF_8);message("已导出："+DIR.resolve("扫描结果.csv"));}catch(IOException e){message("导出失败："+e.getMessage());}
    }
    static void message(String text){var c=MinecraftClient.getInstance();if(c.player!=null)c.player.sendMessage(Text.literal("[维里迪亚寻宝] "+text),false);}
}


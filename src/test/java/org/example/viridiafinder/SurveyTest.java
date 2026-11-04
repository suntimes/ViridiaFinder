package org.example.viridiafinder;

import com.google.gson.Gson;
import net.minecraft.SharedConstants;
import net.minecraft.Bootstrap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class SurveyTest {
    static final List<String> report=new ArrayList<>();
    static void check(boolean condition,String label) {
        if(!condition)throw new AssertionError(label);
        report.add("通过："+label);System.out.println("通过："+label);
    }
    static class Fixture implements Survey.View {
        Map<Survey.Pos,BlockState> blocks=new HashMap<>();
        Set<String> unloaded=new HashSet<>();
        boolean noneLoaded;
        int reads,minY=-64,maxY=320;
        int indexQueries;
        public boolean loaded(int x,int z){return !noneLoaded&&!unloaded.contains(x+","+z);}
        public Survey.Kind read(Survey.Pos p) {
            if(!loaded(p.x()>>4,p.z()>>4))throw new AssertionError("读取了未加载区块");
            if(p.y()<minY||p.y()>=maxY)throw new AssertionError("超出世界高度");
            reads++;return ContainerTypes.identify(blocks.getOrDefault(p,Blocks.AIR.getDefaultState()));
        }
        public int bottom(){return minY;}
        public int top(){return maxY;}
        public List<Survey.Pos> indexedPositions(int x,int z){
            if(!loaded(x,z))throw new AssertionError("读取了未加载区块索引");
            indexQueries++;
            return blocks.keySet().stream().filter(p->(p.x()>>4)==x&&(p.z()>>4)==z).toList();
        }
    }
    static Survey make(Survey.Pos p,int radius,int below,int above) {
        var cfg=new Survey.Settings();cfg.radius=radius;cfg.below=below;cfg.above=above;
        return new Survey(List.of(p),cfg);
    }
    static void pass(Survey survey,Fixture f) {
        long before=survey.passes;int limit=0;
        while(survey.passes==before) {
            survey.tick(f,1,Long.MAX_VALUE,x->{});
            if(++limit>2000000)throw new AssertionError("扫描没有完成");
        }
    }
    public static void main(String[] args)throws Exception {
        SharedConstants.createGameVersion();Bootstrap.initialize();
        check(ContainerTypes.identify(Blocks.CHEST.getDefaultState())==Survey.Kind.CHEST,"Minecraft真实普通箱子方块状态");
        check(ContainerTypes.identify(Blocks.TRAPPED_CHEST.getDefaultState())==Survey.Kind.TRAPPED,"Minecraft真实陷阱箱方块状态");
        check(ContainerTypes.identify(Blocks.BARREL.getDefaultState())==Survey.Kind.NONE,"Minecraft真实木桶方块不再识别");
        check(ContainerTypes.identify(Blocks.SEA_PICKLE.getDefaultState())==Survey.Kind.NONE&&ContainerTypes.identify(Blocks.PRISMARINE.getDefaultState())==Survey.Kind.NONE,"海泡菜及海晶石不是识别条件");
        var origin=new Survey.Pos(15,64,-1);var survey=make(origin,2,3,4);var f=new Fixture();
        var chest=new Survey.Pos(17,67,1);var trap=new Survey.Pos(13,61,-3);var barrel=new Survey.Pos(15,64,-1);
        f.blocks.put(chest,Blocks.CHEST.getDefaultState());f.blocks.put(trap,Blocks.TRAPPED_CHEST.getDefaultState());f.blocks.put(barrel,Blocks.BARREL.getDefaultState());
        f.blocks.put(new Survey.Pos(18,64,-1),Blocks.CHEST.getDefaultState());
        pass(survey,f);var c=survey.candidates.get(0);
        check(c.found.size()==2,"跨正负区块边界扫描；含水平及垂直边缘，排除范围外方块");
        check(c.checked==200&&c.unknown==0,"完整扫描5×5×8范围，而非单方块");
        check(c.found.get(chest.key()).pos.equals(chest)&&chest.offset(origin).equals("2, 3, 2")&&Math.abs(chest.distance(origin)-Math.sqrt(17))<1e-9,"实际坐标、偏移XYZ和三维距离");
        check(c.found.values().stream().allMatch(v->v.mark==Survey.Mark.CONTAINER),"扫描发现不自动升级为疑似或已确认宝藏");
        int reads=f.reads;
        check(survey.tick(f,7,Long.MAX_VALUE,x->{})==7&&f.reads-reads==7,"每帧扫描严格遵守读取预算");
        check(survey.tick(f,100,0,x->{})==0,"时间预算截止后停止扫描");
        f.blocks.remove(chest);pass(survey,f);
        check(!c.found.get(chest.key()).present,"持续复查识别容器消失并保留历史");
        f.blocks.put(chest,Blocks.CHEST.getDefaultState());pass(survey,f);
        check(c.found.get(chest.key()).present,"持续复查识别容器重新出现");
        int[] events={0};survey.tick(f,200,Long.MAX_VALUE,x->events[0]++);
        check(events[0]==0,"重复扫描不重复产生新发现通知");
        var newPos=new Survey.Pos(16,63,0);f.blocks.put(newPos,Blocks.CHEST.getDefaultState());pass(survey,f);
        check(c.found.containsKey(newPos.key()),"无人工编号操作即可发现后续新增箱子");
        f.unloaded.add("1,0");reads=f.reads;pass(survey,f);
        check(c.unknown>0&&c.checked>0&&f.reads>reads&&survey.coverage(c,f).startsWith("部分"),"部分区块未加载：只读已加载区域并明确标记不完整");
        f.noneLoaded=true;reads=f.reads;pass(survey,f);
        check(f.reads==reads&&c.checked==0&&c.unknown==200&&survey.coverage(c,f).equals("未加载"),"全部卸载时零方块读取，不报告空区域");
        f.noneLoaded=false;f.unloaded.clear();pass(survey,f);
        check(c.unknown==0&&c.found.get(chest.key()).present,"区块重新加载后继续检测");
        survey.settings.barrels=false;pass(survey,f);
        check(!c.found.containsKey(barrel.key())&&c.found.get(chest.key()).present,"关闭木桶识别不会影响箱子");
        var heightSurvey=make(new Survey.Pos(0,0,0),1,8,8);var heightWorld=new Fixture();heightWorld.minY=0;heightWorld.maxY=4;
        pass(heightSurvey,heightWorld);
        check(heightSurvey.candidates.get(0).checked==36,"逐列裁剪到世界高度，不读取世界底部以下");
        c.found.get(chest.key()).mark=Survey.Mark.CONFIRMED;pass(survey,f);
        check(c.found.get(chest.key()).mark==Survey.Mark.CONFIRMED,"扫描不会覆盖玩家的人工确认");
        var json=new Gson();var restored=json.fromJson(json.toJson(c),Survey.Candidate.class);
        check(restored.found.get(chest.key()).pos.equals(chest)&&restored.found.get(chest.key()).mark==Survey.Mark.CONFIRMED,"容器坐标与人工确认可正确保存及读取");
        var routeSurvey=new Survey(List.of(new Survey.Pos(0,64,0),new Survey.Pos(10,64,0),new Survey.Pos(20,64,0)),new Survey.Settings());
        routeSurvey.candidates.get(1).visited=true;
        var route=routeSurvey.route(new Survey.Pos(21,64,0));
        check(route.size()==2&&route.get(0).id==3&&route.get(1).id==1,"路线覆盖全部未访问点并按最近邻排序");
        var cfg=new Survey.Settings();cfg.radius=999;cfg.below=-1;cfg.budget=99999;cfg.validate();
        check(cfg.radius==64&&cfg.below==0&&cfg.budget==8192,"配置边界限制有效");
        List<Survey.Pos> wiki=new ArrayList<>();
        try(var input=SurveyTest.class.getResourceAsStream("/assets/viridiafinder/candidates.csv")) {
            if(input==null)throw new AssertionError("发行资源缺失");
            for(String row:new String(input.readAllBytes(),StandardCharsets.UTF_8).split("\\R")) {
                if(row.isBlank()||row.startsWith("#"))continue;
                String[] parts=row.split(",");wiki.add(new Survey.Pos(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2])));
            }
        }
        check(wiki.size()==169&&new HashSet<>(wiki).size()==169,"打包数据库为169个唯一候选点");
        var all=new Survey(wiki,new Survey.Settings());var allWorld=new Fixture();
        var actual=wiki.get(0);var example=new Survey.Pos(actual.x()+2,actual.y()+3,actual.z()+2);
        allWorld.blocks.put(example,Blocks.CHEST.getDefaultState());
        long t=System.nanoTime();pass(all,allWorld);
        check(all.candidates.get(0).found.containsKey(example.key())&&all.candidates.stream().mapToInt(v->v.found.size()).sum()==1,"完整169点轮询：实际数据库坐标附近的测试箱子可自动发现");
        report.add("169点全加载模拟扫描总读取数："+allWorld.reads+"；测试耗时毫秒："+(System.nanoTime()-t)/1_000_000+"（无预算分帧，不代表游戏帧率）");
        ViridiaFinder.survey=survey;
        ViridiaFinder.invalidateChunk(chest.x()>>4,chest.z()>>4);
        check(!c.found.get(chest.key()).present&&c.found.get(trap.key()).present,"客户端区块失效处理只撤销对应区块的存在状态，等待复查");
        var navConfig=new Survey.Settings();navConfig.radius=0;navConfig.below=0;navConfig.above=0;
        var navSurvey=new Survey(List.of(new Survey.Pos(0,64,0),new Survey.Pos(16,64,0),new Survey.Pos(32,64,0)),navConfig);
        var navWorld=new Fixture();var plan=new Survey.RoutePlan(navSurvey.candidates);
        check(plan.advance(navSurvey,navWorld)==0&&plan.current().id==1,"导航：没有完成扫描时停留当前站");
        navSurvey.tick(navWorld,1,Long.MAX_VALUE,x->{});
        check(plan.advance(navSurvey,navWorld)==1&&plan.current().id==2&&navSurvey.candidates.get(0).visited,"导航：一号完整扫描后自动切到二号并记录已巡查");
        navWorld.unloaded.add("1,0");navSurvey.tick(navWorld,1,Long.MAX_VALUE,x->{});
        check(plan.advance(navSurvey,navWorld)==0&&plan.current().id==2,"导航：未加载区块不能作为已完成站点");
        navWorld.unloaded.clear();navSurvey.tick(navWorld,1,Long.MAX_VALUE,x->{});
        check(plan.advance(navSurvey,navWorld)==0&&plan.current().id==2,"导航：后续站点完成不绕过尚未完成的当前站");
        navSurvey.tick(navWorld,2,Long.MAX_VALUE,x->{});
        check(plan.advance(navSurvey,navWorld)==2&&plan.current()==null,"导航：当前站补扫完成后连续跳过已完成站并结束路线");
        navSurvey.invalidateChunk(0,0);
        check(!navSurvey.fullyScanned(navSurvey.candidates.get(0),navWorld),"导航：区块失效后旧扫描结果不可作为完成依据");
        var highSurvey=make(new Survey.Pos(0,64,0),8,8,8);var highWorld=new Fixture();
        var hp1=new Survey.Pos(1,64,1);var hp2=new Survey.Pos(2,64,1);var hp3=new Survey.Pos(3,64,1);
        highWorld.blocks.put(hp1,Blocks.CHEST.getDefaultState());highWorld.blocks.put(hp2,Blocks.TRAPPED_CHEST.getDefaultState());highWorld.blocks.put(hp3,Blocks.BARREL.getDefaultState());
        pass(highSurvey,highWorld);
        var duplicate=new Survey.Candidate(2,new Survey.Pos(0,64,0));duplicate.found.putAll(highSurvey.candidates.get(0).found);highSurvey.candidates.add(duplicate);
        var highlights=ContainerHighlights.collect(highSurvey,highWorld,new Survey.Pos(0,64,0),96,256);
        check(highlights.size()==2&&new HashSet<>(highlights.stream().map(h->h.kind()).toList()).size()==2,"高亮：仅两类箱子独立识别且重叠候选不重复绘制");
        reads=highWorld.reads;highlights=ContainerHighlights.collect(highSurvey,highWorld,new Survey.Pos(0,64,0),96,1);
        check(highlights.size()==1&&highlights.get(0).pos().equals(hp1)&&highWorld.reads-reads==1,"高亮：优先近处容器并限制每帧方块复核次数");
        highWorld.unloaded.add("0,0");reads=highWorld.reads;
        check(ContainerHighlights.collect(highSurvey,highWorld,new Survey.Pos(0,64,0),96,256).isEmpty()&&highWorld.reads==reads,"高亮：未加载区块不读取也不显示历史框");
        highWorld.unloaded.clear();highSurvey.settings.barrels=false;
        check(ContainerHighlights.collect(highSurvey,highWorld,new Survey.Pos(0,64,0),96,256).size()==2,"高亮：旧木桶配置不影响两类箱子");
        highWorld.blocks.remove(hp1);
        highlights=ContainerHighlights.collect(highSurvey,highWorld,new Survey.Pos(0,64,0),96,256);
        check(highlights.size()==1&&highlights.get(0).kind()==Survey.Kind.TRAPPED,"高亮：实际方块已消失时立即排除旧框");
        check(ContainerHighlights.collect(highSurvey,highWorld,new Survey.Pos(200,64,0),96,256).isEmpty(),"高亮：超过绘制距离的容器不提交");
        var fastSource=new Survey(wiki,new Survey.Settings());var fastWorld=new Fixture();
        var screenshot=new Survey.Pos(-546,41,-234);
        fastWorld.blocks.put(screenshot,Blocks.CHEST.getDefaultState());
        var fast=new FastSurvey(fastSource);int[] hits={0};int fastSteps=0;
        while(hits[0]==0&&fastSteps<100){fast.tick(fastWorld,screenshot,1,Long.MAX_VALUE,x->hits[0]++);fastSteps++;}
        check(hits[0]==1&&fastSource.candidates.get(123).found.containsKey(screenshot.key())&&fastWorld.reads==1,"快速检索：优先近处124号候选，索引中单箱仅需一次方块复核即可发现");
        check(fastSource.candidates.get(123).completedAt==0,"快速检索：发现容器不伪造完整区域扫描完成状态");
        fast.tick(fastWorld,screenshot,512,Long.MAX_VALUE,x->hits[0]++);
        check(hits[0]==1,"快速检索：重复索引扫描不重复触发发现事件");
        var emptySurvey=make(screenshot,8,8,8);var emptyWorld=new Fixture();var emptyFast=new FastSurvey(emptySurvey);
        emptyWorld.noneLoaded=true;
        check(emptyFast.tick(emptyWorld,screenshot,16,Long.MAX_VALUE,x->{})<=16&&emptyWorld.indexQueries==0&&emptyWorld.reads==0,"快速检索：未加载时不读取索引或方块并遵守步骤预算");
        emptyWorld.noneLoaded=false;emptyFast.tick(emptyWorld,screenshot,16,Long.MAX_VALUE,x->{});
        check(emptySurvey.candidates.get(0).completedAt==0&&emptySurvey.candidates.get(0).found.isEmpty(),"快速检索：空索引不作为没有箱子的证明");
        check(emptyFast.tick(emptyWorld,screenshot,16,0,x->{})==0,"快速检索：时间预算截止即停止");
        report.add("快速检索固定场景：169候选中靠近124号，发现一个索引内箱子所需方块读取="+fastWorld.reads+"（包含后续重复检索；首次发现为1次，不能据此推断游戏帧率）。");
        var overlapCfg=new Survey.Settings();overlapCfg.radius=1;overlapCfg.below=1;overlapCfg.above=1;
        var overlap=new Survey(List.of(new Survey.Pos(4,64,4),new Survey.Pos(5,64,4)),overlapCfg);
        var overlapWorld=new Fixture();var overlapBox=new Survey.Pos(4,64,4);
        overlapWorld.blocks.put(overlapBox,Blocks.CHEST.getDefaultState());
        var optimized=new FastSurvey(overlap);
        optimized.tick(overlapWorld,overlapBox,100,Long.MAX_VALUE,x->{});
        check(optimized.rounds==1&&overlapWorld.indexQueries==1&&overlapWorld.reads==1&&overlap.candidates.stream().allMatch(v->v.found.containsKey(overlapBox.key())),"优化：重叠候选一轮仅查询一次区块索引、一次方块状态，两个候选都记录");
        overlapWorld.blocks.remove(overlapBox);
        overlapWorld.blocks.put(new Survey.Pos(5,64,4),Blocks.TRAPPED_CHEST.getDefaultState());
        optimized.tick(overlapWorld,overlapBox,100,Long.MAX_VALUE,x->{});
        check(overlapWorld.indexQueries==2&&overlap.candidates.get(0).found.containsKey("5,64,4"),"优化：下一轮刷新索引，可发现新放置的陷阱箱");
        for(var candidate:overlap.candidates)for(var box:candidate.found.values())box.mark=Survey.Mark.CLAIMED;
        check(ContainerHighlights.collect(overlap,overlapWorld,overlapBox,192,256).isEmpty(),"已领取箱子退出自动高亮和最近箱子检索");
        var legacy=new Survey.Settings();legacy.barrels=true;legacy.validate();
        check(!legacy.barrels,"旧配置中的木桶开关强制关闭");
        report.add("重叠候选优化固定场景：2个候选共用1个区块，索引读取从2次降至1次；实际方块复核从2次降至1次，不代表实际寻宝用时减半。");
        var eye=new RoundTracker();long now=100000;
        String four="[维戈拉 (Vigora)] Of the 4 caches, 4 have been claimed. The trackable cache has already been claimed.";
        String zero="[维戈拉 (Vigora)] Of the 4 caches, 0 have been claimed.";
        String estimate="[维戈拉 (Vigora)] Taking into account the ocean currents right now, I'd estimate that it'll probably be 81分钟before the next caches appear.";
        check(eye.accept(four,now)==RoundTracker.Result.IGNORED,"之眼：无手动查询窗口时不解析");
        eye.arm(now);
        check(eye.accept(four,now)==RoundTracker.Result.COUNT&&eye.claimed==4,"之眼：截图4/4仅记录，不重置");
        check(eye.accept(estimate,now)==RoundTracker.Result.TIME&&eye.expectedAt==now+81*60000L,"之眼：识别截图中英混合81分钟提示");
        check(!eye.reminderDue(now)&&eye.reminderDue(now+81*60000L)&&!eye.reminderDue(now+82*60000L)&&eye.claimed==4,"之眼：到点只提醒一次，未清空状态");
        check(eye.accept(zero,now)==RoundTracker.Result.IGNORED,"之眼：同一次查询不重复接受领取数");
        eye.arm(now+10000);
        check(eye.accept(zero,now+10000)==RoundTracker.Result.NEW_ROUND&&eye.claimed==0,"之眼：新查询4降至0识别换轮");
        eye.arm(now+20000);
        check(eye.accept(zero,now+20000)==RoundTracker.Result.COUNT,"之眼：重复0/4不再次换轮");
        eye.arm(now+30000);
        check(eye.accept("玩家: "+four,now+30000)==RoundTracker.Result.IGNORED&&eye.accept("[Other] Of the 4 caches, 4 have been claimed.",now+30000)==RoundTracker.Result.IGNORED,"之眼：普通聊天与错误NPC不匹配");
        check(eye.accept(four,now+40000)==RoundTracker.Result.IGNORED,"之眼：查询窗口超时不接受旧回复");
        eye.arm(now+50000);eye.accept(four,now+50000);eye.forgetSession();eye.arm(now+60000);
        check(eye.accept(zero,now+60000)==RoundTracker.Result.COUNT,"之眼：重新连接清除比较基线，避免跨实例误重置");
        var restoredEye=json.fromJson(json.toJson(eye),RoundTracker.class);restoredEye.forgetSession();
        check(restoredEye.claimed==-1,"之眼：持久化不带入上一连接的领取比较基线");
        var spacedEye=new RoundTracker();spacedEye.arm(now);
        check(spacedEye.accept("[维戈拉 （Vigora） ] Of the 4 caches, 4 have been claimed.",now)==RoundTracker.Result.COUNT,"之眼：兼容翻译后全角括号和NPC名称尾部空格");
        var timing=new RiptideClock();timing.started(0);
        check(!timing.ready(249_999_999L)&&timing.ready(250_000_000L),"激流：失败尝试保留250毫秒重试限速");
        timing.released(3_000_000_000L);
        check(!timing.ready(3_049_999_999L)&&timing.ready(3_050_000_000L),"激流：正常释放后只保留50毫秒调度间隔，不追赶连发");
        // Force client mixin application in the Fabric test classloader, without opening a window.
        var mcType=Class.forName("net.minecraft.client.MinecraftClient",false,SurveyTest.class.getClassLoader());
        check(Arrays.stream(mcType.getDeclaredMethods()).anyMatch(m->m.getName().contains("maintainOwnedCharge")),"客户端输入Mixin在Fabric类加载器中成功应用（非窗口实测）");
        var trident=new net.minecraft.item.ItemStack(net.minecraft.item.Items.TRIDENT);
        check(!TravelAssist.canCharge(trident,true,false,false),"激流：无激流附魔不自动使用，避免投掷普通三叉戟");
        trident.addEnchantment(net.minecraft.enchantment.Enchantments.RIPTIDE,3);
        check(TravelAssist.canCharge(trident,true,false,false)&&!TravelAssist.canCharge(trident,false,false,false)&&!TravelAssist.canCharge(trident,true,true,false)&&!TravelAssist.canCharge(trident,true,false,true),"激流：水雨、骑乘和冷却条件控制启动");
        trident.setDamage(trident.getMaxDamage()-1);
        check(!TravelAssist.canCharge(trident,true,false,false),"激流：耐久即将耗尽时不启动");
        var routeStart=new Survey.Pos(-763,84,1321);var routeData=new Survey(wiki,new Survey.Settings());
        var greedyPath=RouteOptimizer.nearest(routeStart,routeData.candidates);
        long routeStarted=System.nanoTime();var improvedPath=routeData.route(routeStart);long routeMs=(System.nanoTime()-routeStarted)/1_000_000;
        double greedyDistance=RouteOptimizer.length(routeStart,greedyPath),improvedDistance=RouteOptimizer.length(routeStart,improvedPath);
        check(improvedPath.size()==169&&new HashSet<>(improvedPath).size()==169&&new HashSet<>(improvedPath).equals(new HashSet<>(routeData.candidates)),"路线：真实169坐标全部覆盖，无重复、无边界裁剪");
        check(improvedDistance<=greedyDistance+1e-6,"路线：用户起点优化结果不长于原最近邻路线");
        check(improvedPath.equals(routeData.route(routeStart)),"路线：相同起点和数据得到稳定顺序");
        var metrics=new StringBuilder("起点X,起点Z,旧最近邻格数,优化格数,减少百分比\n");
        for(int ox:new int[]{-50,0,50})for(int oz:new int[]{-50,0,50}){
            var at=new Survey.Pos(-763+ox,84,1321+oz);var original=RouteOptimizer.nearest(at,routeData.candidates);var optimizedRoute=routeData.route(at);
            double before=RouteOptimizer.length(at,original),after=RouteOptimizer.length(at,optimizedRoute);
            check(after<=before+1e-6&&optimizedRoute.size()==169,"路线：起点偏移("+ox+","+oz+")保持完整覆盖且不劣于最近邻");
            metrics.append(String.format(Locale.ROOT,"%d,%d,%.2f,%.2f,%.2f%n",at.x(),at.z(),before,after,100*(before-after)/before));
        }
        check(RouteOptimizer.optimize(routeStart,List.of()).isEmpty()&&RouteOptimizer.optimize(routeStart,List.of(routeData.candidates.get(0))).size()==1,"路线：空集合和单点正常处理");
        var capture=make(new Survey.Pos(8,64,8),0,0,0);var captureWorld=new Fixture();var capturedPlan=new Survey.RoutePlan(capture.candidates);
        pass(capture,captureWorld);check(capturedPlan.captureCompleted(capture,captureWorld)==1,"路线：沿途完整扫描即时记录访问");
        captureWorld.noneLoaded=true;capture.invalidateChunk(0,0);
        check(capturedPlan.advance(capture,captureWorld)==1&&capturedPlan.current()==null,"路线：已经完整巡查的区块卸载后不再回头走");
        var unknownCapture=make(new Survey.Pos(8,64,8),0,0,0);var unknownPlan=new Survey.RoutePlan(unknownCapture.candidates);
        pass(unknownCapture,captureWorld);
        check(unknownPlan.captureCompleted(unknownCapture,captureWorld)==0&&!unknownCapture.candidates.get(0).visited,"路线：未知区块不会记录已巡查");
        var priority=new Survey(List.of(new Survey.Pos(0,64,0),new Survey.Pos(32,64,0)),navConfig);var priorityWorld=new Fixture();
        priority.prioritize(priority.candidates.get(1));priority.tick(priorityWorld,1,Long.MAX_VALUE,x->{});
        check(priority.fullyScanned(priority.candidates.get(1),priorityWorld)&&priority.candidates.get(0).completedAt==0,"路线：优先扫描导航目标，无需等待169点轮询");
        var partial=make(new Survey.Pos(0,64,0),1,0,0);partial.candidates.add(new Survey.Candidate(2,new Survey.Pos(32,64,0)));
        partial.tick(priorityWorld,2,Long.MAX_VALUE,x->{});partial.prioritize(partial.candidates.get(1));partial.prioritize(partial.candidates.get(0));
        partial.tick(priorityWorld,9,Long.MAX_VALUE,x->{});
        check(partial.candidates.get(0).checked==9,"路线：中断扫描后重计范围，不拼接重复方块伪造完成");
        Files.writeString(Path.of("路线距离对比.csv"),metrics,StandardCharsets.UTF_8);
        StringBuilder routeCsv=new StringBuilder("类型,顺序,候选编号,X,Z\n");
        for(String type:List.of("旧最近邻","优化路线")){
            routeCsv.append(type).append(",0,0,-763,1321\n");int orderNo=0;
            for(var waypoint:type.equals("旧最近邻")?greedyPath:improvedPath)routeCsv.append(type).append(',').append(++orderNo).append(',').append(waypoint.id).append(',').append(waypoint.pos.x()).append(',').append(waypoint.pos.z()).append('\n');
        }
        Files.writeString(Path.of("路线坐标对比.csv"),routeCsv,StandardCharsets.UTF_8);
        report.add(String.format(Locale.ROOT,"用户起点(-763,1321)：旧最近邻%.2f格，优化%.2f格，减少%.2f%%；计算耗时%d毫秒（仅水平距离，非实测水路或寻宝耗时）。",greedyDistance,improvedDistance,100*(greedyDistance-improvedDistance)/greedyDistance,routeMs));
        var zhEye=new RoundTracker();zhEye.arm(now);
        String zhZero="[维戈拉 (Vigora)] 共4处宝藏，其中0处已被领取。可追踪的宝藏尚未被领取。";
        String zhTime="[维戈拉 (Vigora)] 根据目前的洋流情况，我估计大约76分钟后，下一批宝藏才会出现。";
        check(zhEye.accept(zhZero,now)==RoundTracker.Result.COUNT&&zhEye.accept(zhTime,now)==RoundTracker.Result.TIME&&zhEye.expectedAt==now+76*60000L,"中文之眼：截图0/4和76分钟准确识别");
        zhEye.arm(now+76*60000L);
        zhEye.accept(zhZero,now+76*60000L);
        check(zhEye.accept(zhTime.replace("76分钟","118分钟"),now+76*60000L)==RoundTracker.Result.NEW_ROUND,"中文之眼：领取数相同，但过期后下轮时间大幅推进时更新轮次");
        var splitEye=new RoundTracker();splitEye.arm(now);splitEye.accept("[维戈拉 (Vigora)]",now);
        check(splitEye.accept("共4处宝藏，其中4处已被领取。",now+100)==RoundTracker.Result.COUNT,"中文之眼：支持NPC名与正文分为两条系统消息");
        var keyboard=Class.forName("net.minecraft.client.input.KeyboardInput",false,SurveyTest.class.getClassLoader());
        var clientPlayer=Class.forName("net.minecraft.client.network.ClientPlayerEntity",false,SurveyTest.class.getClassLoader());
        check(Arrays.stream(keyboard.getDeclaredMethods()).anyMatch(m->m.getName().contains("swimForward"))&&Arrays.stream(clientPlayer.getDeclaredMethods()).anyMatch(m->m.getName().contains("swimSprint")),"游泳：前进输入和原版疾跑请求Mixin成功应用");
        byte[] small=new byte[9*9];Arrays.fill(small,(byte)2);for(int z=0;z<7;z++)small[z*9+4]=1;
        var miniSea=new SeaMap(0,0,4,9,9,small);var detour=miniSea.path(2*9+2,2*9+6);
        check(!miniSea.clear(2*9+2,2*9+6)&&!detour.isEmpty()&&detour.stream().anyMatch(p->p.z()>=28),"海路：绕过陆地墙，不用直线穿陆");
        boolean safe=true;for(int i=1;i<detour.size();i++)safe&=miniSea.clear(miniSea.cell(detour.get(i-1)),miniSea.cell(detour.get(i)));
        check(safe,"海路：简化后的每一条线段仍只通过水域");
        byte[] diagonal={2,0,0,2};var corner=new SeaMap(0,0,4,2,2,diagonal);
        check(corner.path(0,3).isEmpty()&&!corner.clear(0,3),"海路：未知格阻挡，不允许斜穿陆地或未知角落");
        var radiusWorld=new Fixture();radiusWorld.unloaded.add("3,0");
        check(SeaPlanner.observedRadius(radiusWorld,new Survey.Pos(0,80,0))==2,"覆盖：实际加载缺口限制观测半径，不盲信16区块");
        var realSea=SeaMap.load();var seaStart=new Survey.Pos(-763,84,1321);
        long seaBegan=System.nanoTime();var seaRoute=SeaPlanner.plan(realSea,seaStart,routeData.candidates,232);
        var seaIds=new HashSet<Integer>(seaRoute.unresolved());for(var stop:seaRoute.stops())seaIds.addAll(stop.candidates());
        check(seaIds.size()==169,"覆盖海路：169候选全部计入扫描站或明确的未覆盖列表");
        safe=true;for(var stop:seaRoute.stops()){for(var point:stop.path())safe&=realSea.water(realSea.cell(point));for(int i=1;i<stop.path().size();i++)safe&=realSea.clear(realSea.cell(stop.path().get(i-1)),realSea.cell(stop.path().get(i)));}
        check(safe,"真实缓存海路：所有航点和航段均通过已记录水域");
        check(!seaRoute.stops().isEmpty()&&seaRoute.stops().size()<169&&seaRoute.unresolved().isEmpty(),"覆盖海路：一个扫描站覆盖多候选，减少停靠数量");
        var seaExport=new StringBuilder("扫描站,航点,X,Z,覆盖候选\n");int station=0;
        for(var stop:seaRoute.stops()){station++;int waypoint=0;for(var point:stop.path())seaExport.append(station).append(',').append(++waypoint).append(',').append(point.x()).append(',').append(point.z()).append(',').append('"').append(stop.candidates()).append('"').append('\n');}
        Files.writeString(Path.of("海面覆盖路线.csv"),seaExport,StandardCharsets.UTF_8);
        report.add(String.format(Locale.ROOT,"16区块完整加载圈假设下（实际游戏需检测），232格覆盖半径：%d扫描站，海路%.1f格，未覆盖%s，后台计算%d毫秒；非服务器实测。",seaRoute.stops().size(),seaRoute.length(),seaRoute.unresolved(),(System.nanoTime()-seaBegan)/1_000_000));
        var firstHint=new RoundTracker();long clockNow=20_000_000L;firstHint.arm(clockNow);
        firstHint.accept(zhZero,clockNow);firstHint.accept(zhTime,clockNow);
        check(firstHint.localRecordOlderThanHint(clockNow-3*3600000L,clockNow),"单次之眼：仅凭本次中文提示和旧存档时间即可识别明显旧轮记录");
        check(!firstHint.localRecordOlderThanHint(clockNow-10*60000L,clockNow),"单次之眼：本轮创建的记录不重置");
        check(!firstHint.localRecordOlderThanHint(clockNow-46*60000L,clockNow),"单次之眼：刷新估算边界保留五分钟余量");
        var smallerSea=SeaPlanner.plan(realSea,seaStart,routeData.candidates,104);
        var smallIds=new HashSet<Integer>(smallerSea.unresolved());for(var stop:smallerSea.stops())smallIds.addAll(stop.candidates());
        check(!smallerSea.stops().isEmpty()&&smallIds.size()==169,"覆盖海路：较小加载范围仍保留169候选，无静默漏点");
        report.add(String.format(Locale.ROOT,"8区块完整加载圈假设下，104格覆盖半径：%d扫描站，海路%.1f格，未覆盖%s；均须以游戏实际扫描确认。",smallerSea.stops().size(),smallerSea.length(),smallerSea.unresolved()));
        Files.write(Path.of("扫描验证结果.txt"),report,StandardCharsets.UTF_8);
        System.out.println("全部通过："+report.stream().filter(s->s.startsWith("通过：")).count()+"项；使用真实Minecraft方块状态和模拟区块视图，未连接服务器。");
    }
}


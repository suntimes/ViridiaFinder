package org.example.viridiafinder;

import java.util.function.BiConsumer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public final class AutoSailTest {
    static final class Water implements AutoSail.WaterView {
        boolean missing,dry;
        Box wall;
        int reads;
        public boolean loaded(int cx,int cz){return !missing||cx<1;}
        public boolean water(BlockPos pos){reads++;return !dry||pos.getX()<12;}
        public boolean blocked(Box body){reads++;return wall!=null&&body.intersects(wall);}
    }
    public static void run(BiConsumer<Boolean,String> check){
        check.accept(AutoSail.bearing(1,0)==-90f&&AutoSail.bearing(-1,0)==90f&&AutoSail.bearing(0,1)==0f,
            "自动航行：航点东、西、南方位匹配游戏视角");
        float acrossNorth=AutoSail.turn(179,-179);
        check.accept(acrossNorth==181f,"自动航行：跨越正负180度时沿短边转向");
        float yaw=0;
        for(int t=0;t<16;t++){
            float next=AutoSail.turn(yaw,173);
            if(Math.abs(MathHelper.wrapDegrees(next-yaw))>12)throw new AssertionError("航向单刻突变");
            yaw=next;
        }
        check.accept(yaw==173,"自动航行：大角度转向每刻最多十二度并最终对齐");
        check.accept(AutoSail.tridentSlot(7,i->i==7||i==0)==7,"自动航行：优先使用手中的可用激流三叉戟");
        check.accept(AutoSail.tridentSlot(7,i->i==8)==8&&AutoSail.tridentSlot(8,i->{
            if(i<0||i>8)throw new AssertionError("越过快捷栏");
            return false;})==-1,"自动航行：检索仅限九个快捷栏，无可用三叉戟时保留游泳");
        var lease=new AutoSail.SlotLease();
        lease.start(1);lease.select(7);
        check.accept(!lease.changed(7)&&lease.restore(7)==1,"自动航行：停靠后可恢复模组临时切换前的槽位");
        check.accept(lease.changed(5)&&lease.restore(5)<0,"自动航行：玩家手动切槽优先，保留其选择");
        var attempts=new AutoSail.Attempts();
        check.accept(attempts.ready(-100),"自动航行：新会话允许首次尝试");
        attempts.record(0);
        check.accept(!attempts.ready(3_999_999_999L)&&attempts.ready(4_000_000_000L),
            "自动航行：从尝试开始计时，四秒内禁止再次使用激流");
        attempts.record(7_000_000_000L);
        check.accept(!attempts.ready(10_000_000_000L),"自动航行：失败或延迟也没有补发尝试");
        check.accept(AutoSail.canBoost(32,2,-2)&&!AutoSail.canBoost(31,0,0)&&!AutoSail.canBoost(64,11,0)&&!AutoSail.canBoost(64,0,3),
            "自动航行：转弯、靠站及俯仰未就位时仅游泳");
        var progress=new AutoSail.Progress();
        var goal=new Survey.Pos(82,80,4);
        for(int t=0;t<80;t++){
            if(progress.stalled(goal,80))throw new AssertionError("过早停航");
        }
        check.accept(progress.stalled(goal,80),"自动航行：持续八十刻未靠近航点时停航");
        progress.reset();
        boolean stopped=false;
        for(int t=0;t<400;t++)stopped|=progress.stalled(goal,90-t*0.15);
        check.accept(!stopped,"自动航行：缓慢但持续游近航点时保持航行");
        check.accept(!progress.stalled(new Survey.Pos(-120,80,4),150),"自动航行：新航段重新计算进展");
        var water=new Water();
        var origin=new Vec3d(8,80,8);
        var body=new Box(7.7,80,7.7,8.3,80.6,8.3);
        var launch=new Vec3d(30,4,0);
        check.accept(AutoSail.clearSegment(water,body,origin,launch),"自动航行：连贯已加载水路可通过激流前方检查");
        water.missing=true;water.reads=0;
        check.accept(!AutoSail.clearSegment(water,body,origin,launch)&&water.reads==0,
            "自动航行：跨越缺失区块时直接退出，不读取区块方块或碰撞");
        water.missing=false;water.dry=true;
        check.accept(!AutoSail.clearSegment(water,body,origin,launch),"自动航行：当前地表缺水的航线禁止自动激流");
        water.dry=false;water.wall=new Box(28,82,7,29,85,9);
        check.accept(!AutoSail.clearSegment(water,body,origin,launch),"自动航行：斜上方激流路线被挡时不启动蓄力");
    }
}

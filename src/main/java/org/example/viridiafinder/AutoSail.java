package org.example.viridiafinder;

import java.util.function.IntPredicate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;

/** Geometry and state of one optional, user-started voyage. */
final class AutoSail {
    static float bearing(double dx,double dz) {
        return (float)Math.toDegrees(Math.atan2(-dx,dz));
    }
    static float turn(float yaw,float wanted) {
        return yaw+MathHelper.clamp(MathHelper.wrapDegrees(wanted-yaw),-12f,12f);
    }
    static int tridentSlot(int held,IntPredicate eligible) {
        if(held<0||held>=9)return -1;
        if(eligible.test(held))return held;
        for(int i=0;i<9;i++)if(i!=held&&eligible.test(i))return i;
        return -1;
    }

    static final class Attempts {
        private boolean attempted;
        private long last;
        boolean ready(long now){return !attempted||now-last>=4_000_000_000L;}
        void record(long now){attempted=true;last=now;}
        void reset(){attempted=false;}
    }
    static boolean canBoost(double distance,float yawError,float pitchError) {
        return distance>=32&&Math.abs(yawError)<=2&&Math.abs(pitchError)<=2;
    }

    static final class SlotLease {
        private int original=-1,expected=-1;
        void start(int slot){original=slot;expected=slot;}
        void select(int slot){expected=slot;}
        boolean changed(int slot){return expected>=0&&slot!=expected;}
        int restore(int selected){
            return selected==expected&&original!=expected?original:-1;
        }
    }

    static final class Progress {
        private Survey.Pos goal;
        private double bestDistance;
        private int stagnant;
        void reset(){goal=null;stagnant=0;}
        boolean stalled(Survey.Pos next,double distance) {
            if(!next.equals(goal)||bestDistance-distance>=0.75){
                goal=next;bestDistance=distance;stagnant=0;
            }else stagnant++;
            return stagnant>=80;
        }
    }
    interface WaterView{
        boolean loaded(int cx,int cz);
        boolean water(BlockPos pos);
        boolean blocked(Box body);
    }

    /** Only inspect loaded client chunks, including the margin used by vanilla block collision lookup. */
    static boolean clearAhead(MinecraftClient client,double dx,double dz,double range) {
        return clearAhead(client,dx,dz,range,0);
    }
    static boolean clearLaunch(MinecraftClient client){
        var look=client.player.getRotationVec(1.0f);
        double horizontal=Math.hypot(look.x,look.z);
        if(horizontal<0.01)return false;
        return clearAhead(client,look.x*32,look.z*32,32,look.y/horizontal);
    }
    private static boolean clearAhead(MinecraftClient client,double dx,double dz,double range,double slope) {
        var p=client.player;
        var world=client.world;
        double distance=Math.hypot(dx,dz);
        if(p==null||world==null||distance<0.01)return false;
        double length=Math.min(range,distance);
        double endX=dx*length/distance,endZ=dz*length/distance,endY=slope*length;
        return clearSegment(new WaterView(){
            public boolean loaded(int cx,int cz){return world.getChunkManager().getChunk(cx,cz,ChunkStatus.FULL,false)!=null;}
            public boolean water(BlockPos pos){return world.getFluidState(pos).isIn(FluidTags.WATER);}
            public boolean blocked(Box body){return world.getBlockCollisions(p,body).iterator().hasNext();}
        },p.getBoundingBox().contract(0.01),p.getPos(),new Vec3d(endX,endY,endZ));
    }
    static boolean clearSegment(WaterView view,Box body,Vec3d origin,Vec3d travel){
        Box corridor=body.stretch(travel).expand(2);
        for(int cx=MathHelper.floor(corridor.minX)>>4;cx<=(MathHelper.floor(corridor.maxX)>>4);cx++){
            for(int cz=MathHelper.floor(corridor.minZ)>>4;cz<=(MathHelper.floor(corridor.maxZ)>>4);cz++){
                if(!view.loaded(cx,cz))return false;
            }
        }
        int samples=Math.max(1,(int)Math.ceil(travel.horizontalLength()/0.5));
        Vec3d step=travel.multiply(1.0/samples);
        Box sweptStep=body.stretch(step);
        for(int i=0;i<samples;i++){
            Vec3d end=step.multiply(i+1);
            BlockPos feet=BlockPos.ofFloored(origin.x+end.x,origin.y,origin.z+end.z);
            if(!view.water(feet)&&!view.water(feet.down()))return false;
            if(view.blocked(sweptStep.offset(step.multiply(i))))return false;
        }
        return true;
    }
}

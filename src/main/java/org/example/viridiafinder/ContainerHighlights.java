package org.example.viridiafinder;

import java.util.*;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.ChunkStatus;

/** 已加载容器的普通深度测试边框，不改变服务器数据或游戏遮挡规则。 */
public final class ContainerHighlights {
    private ContainerHighlights() {}
    public record Highlight(Survey.Pos pos,Survey.Kind kind) {}

    public static List<Highlight> collect(Survey survey,Survey.View view,Survey.Pos player,double radius,int limit) {
        if(limit<=0)return List.of();
        Map<String,Survey.Found> unique=new HashMap<>();
        for(var c:survey.candidates) for(var f:c.found.values()) {
            if(f.mark==Survey.Mark.CLAIMED||!f.present||f.kind==Survey.Kind.NONE||!survey.inRange(c,f.pos)||
                (!survey.settings.barrels&&f.kind==Survey.Kind.BARREL)||f.pos.distance(player)>radius)continue;
            if(!view.loaded(f.pos.x()>>4,f.pos.z()>>4))continue;
            unique.putIfAbsent(f.pos.key(),f);
        }
        var closest=unique.values().stream().sorted(Comparator.comparingDouble(f->f.pos.distance(player))).limit(limit).toList();
        List<Highlight> result=new ArrayList<>();
        for(var f:closest) if(view.read(f.pos)==f.kind) result.add(new Highlight(f.pos,f.kind));
        return result;
    }

    public static void render(WorldRenderContext context) {
        var client=MinecraftClient.getInstance();
        if(!ViridiaFinder.enabled||!ViridiaFinder.settings.highlight||client.player==null||
            context.world()!=ViridiaFinder.world||client.options.hudHidden||context.consumers()==null)return;
        var highlights=collect(ViridiaFinder.survey,ViridiaFinder.view(),ViridiaFinder.playerPos(),96,256);
        if(highlights.isEmpty())return;
        var matrices=context.matrixStack();var camera=context.camera().getPos();
        matrices.push();
        matrices.translate(-camera.x,-camera.y,-camera.z);
        try {
            var lines=context.consumers().getBuffer(RenderLayer.getLines());
            for(var item:highlights) {
                var p=item.pos();var pos=new BlockPos(p.x(),p.y(),p.z());
                var chunk=context.world().getChunkManager().getChunk(p.x()>>4,p.z()>>4,ChunkStatus.FULL,false);
                if(chunk==null)continue;
                var shape=chunk.getBlockState(pos).getOutlineShape(context.world(),pos);
                Box box=(shape.isEmpty()?new Box(0,0,0,1,1,1):shape.getBoundingBox()).offset(pos).expand(0.006);
                if(context.frustum()!=null&&!context.frustum().isVisible(box))continue;
                float red=1f,green=0.85f,blue=0.08f;
                if(item.kind()==Survey.Kind.TRAPPED){green=0.18f;blue=0.12f;}
                if(item.kind()==Survey.Kind.BARREL){red=0.15f;green=0.75f;blue=1f;}
                WorldRenderer.drawBox(matrices,lines,box,red,green,blue,1f);
            }
        } finally {matrices.pop();}
    }
}

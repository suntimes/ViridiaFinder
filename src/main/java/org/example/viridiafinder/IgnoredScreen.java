package org.example.viridiafinder;

import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import static org.example.viridiafinder.ViridiaFinder.*;

/** Independent of round records so exclusions remain reversible after a reset. */
final class IgnoredScreen extends Screen {
    private final Screen parent;
    private final String openedScope;
    private List<Survey.Pos> positions=List.of();
    private int page,left,panelWidth,perPage;

    IgnoredScreen(Screen parent){
        super(Text.literal("已忽略的非宝藏箱子"));
        this.parent=parent;
        openedScope=scope;
    }
    private void button(String label,int x,int y,int w,Runnable action){
        addDrawableChild(ButtonWidget.builder(Text.literal(label),b->action.run()).dimensions(x,y,w,20).build());
    }
    @Override protected void init(){
        positions=survey.ignoredPositions();
        panelWidth=Math.min(320,width-16);left=(width-panelWidth)/2;
        perPage=Math.max(1,(height-100)/25);
        page=Math.min(page,Math.max(0,(positions.size()-1)/perPage));
        for(int row=0;row<perPage && page*perPage+row<positions.size();row++){
            var pos=positions.get(page*perPage+row);
            button("恢复",left+panelWidth-48,57+row*25,48,()->{
                if(openedScope.equals(scope)){setContainerIgnored(pos,false);clearAndInit();}
            });
        }
        int w=(panelWidth-8)/3;
        button("上一页",left,height-28,w,()->{page=Math.max(0,page-1);clearAndInit();});
        button("下一页",left+w+4,height-28,w,()->{page=Math.min(Math.max(0,(positions.size()-1)/perPage),page+1);clearAndInit();});
        button("返回",left+2*w+8,height-28,panelWidth-2*w-8,this::close);
    }
    @Override public void tick(){if(!openedScope.equals(scope))close();}
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        ctx.fill(0,0,width,height,0xED101920);
        ctx.drawTextWithShadow(textRenderer,getTitle(),left,10,0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,"仅本服务器、维度和实例；跨轮次保留",left,25,0xB8C0CD);
        ctx.drawTextWithShadow(textRenderer,"共 "+positions.size()+" 处 · 第 "+(page+1)+" 页",left,40,0xFFE08A);
        if(positions.isEmpty())ctx.drawTextWithShadow(textRenderer,"没有已忽略箱子",left,62,0xB8C0CD);
        for(int row=0;row<perPage && page*perPage+row<positions.size();row++){
            String label=coords(positions.get(page*perPage+row));
            ctx.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(label,panelWidth-54),left,63+row*25,0xB8C0CD);
        }
        super.render(ctx,mx,my,delta);
    }
    @Override public void close(){client.setScreen(parent);}
    @Override public boolean shouldPause(){return false;}
}

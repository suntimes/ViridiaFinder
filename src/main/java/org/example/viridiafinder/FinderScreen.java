package org.example.viridiafinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.*;
import static org.example.viridiafinder.ViridiaFinder.*;

public final class FinderScreen extends Screen {
    int candidate=0, page=0;
    String selected="";
    FinderScreen(){super(Text.literal("维里迪亚寻宝 0.4.0"));}
    Survey.Candidate current(){return survey.candidates.get(candidate);}
    List<Survey.Found> rows(){return visible(current());}
    int perPage(){return Math.max(1,(height-202)/40);}
    Survey.Found chosen(){return current().found.get(selected);}
    void button(String label,int x,int y,int w,Runnable action){
        addDrawableChild(ButtonWidget.builder(Text.literal(label),b->action.run()).dimensions(x,y,w,20).build());
    }
    @Override protected void init(){
        int w=Math.max(45,(width-24)/5);
        button("上一候选",8,28,w,()->{candidate=Math.floorMod(candidate-1,169);page=0;selected="";});
        button("下一候选",10+w,28,w,()->{candidate=(candidate+1)%169;page=0;selected="";});
        button("找箱子",12+w*2,28,w,()->{
            for(int i=1;i<=169;i++){int next=(candidate+i)%169;if(!visible(survey.candidates.get(next)).isEmpty()){candidate=next;page=0;selected="";break;}}
        });
        button("扫描开关",14+w*3,28,w,()->{if(enabled){enabled=false;save();}else enable();});
        button("海面巡查",16+w*4,28,w,ViridiaFinder::route);
        button("上一页",8,height-64,60,()->page=Math.max(0,page-1));
        button("下一页",72,height-64,60,()->page=Math.min(Math.max(0,(rows().size()-1)/perPage()),page+1));
        button("巡查切换",136,height-64,65,()->{current().visited=!current().visited;save();});
        button("导出",205,height-64,50,ViridiaFinder::export);
        button("轮次／激流／游泳",259,height-64,110,()->client.setScreen(new AssistScreen(this)));
        String[] labels={"普通容器","疑似宝藏","确认宝藏","已领取"};
        Survey.Mark[] marks=Survey.Mark.values();
        for(int i=0;i<4;i++){final int n=i;button(labels[i],8+i*((width-20)/4),height-40,(width-24)/4,()->{var f=chosen();if(f!=null)mark(f,marks[n]);else message("请先点击一条容器记录");});}
    }
    void line(DrawContext ctx,String s,int x,int y,int color){
        ctx.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(s,width-x-8),x,y,color);
    }
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        ctx.fill(0,0,width,height,0xED101920);
        line(ctx,"维里迪亚寻宝 0.4.0｜"+(enabled?"正在扫描":"已暂停")+"｜实例："+instance,8,10,0xFFFFFF);
        var c=current();var list=rows();
        line(ctx,"候选 #"+c.id+" /169："+coords(c.pos)+(c.visited?"（已巡查）":"（未巡查）"),8,56,0xD4E8ED);
        line(ctx,"中心区块："+(view().loaded(c.pos.x()>>4,c.pos.z()>>4)?"已加载":"未加载")+"；"+survey.coverage(c,view()),8,69,0xA7CFD6);
        line(ctx,c.completedAt==0?"扫描尚未完成，不能判断没有箱子":"上次扫描："+c.checked+"个方块；未加载跳过："+c.unknown+"个方块",8,82,0xA7CFD6);
        long boxes=list.stream().filter(f->f.present&&f.kind!=Survey.Kind.BARREL).count();
        long barrels=list.stream().filter(f->f.present&&f.kind==Survey.Kind.BARREL).count();
        line(ctx,"上次观察：箱子"+boxes+"；含历史记录"+list.size()+"条",8,95,0xFFE08A);
        line(ctx,"水平±"+settings.radius+"；向下"+settings.below+"；向上"+settings.above+"｜点击容器以定位并标记",8,108,0xB8C0CD);
        page=Math.min(page,Math.max(0,(list.size()-1)/perPage()));
        for(int n=0;n<perPage();n++){
            int i=page*perPage()+n;if(i>=list.size())break;
            var f=list.get(i);int y=122+n*40;
            if(selected.equals(f.pos.key()))ctx.fill(6,y-2,width-6,y+37,0xFF294553);
            line(ctx,f.kind.label+"："+coords(f.pos)+"｜"+presence(f),10,y,markerColor(f));
            line(ctx,"偏移("+f.pos.offset(c.pos)+")；距候选"+String.format(Locale.ROOT,"%.2f",f.pos.distance(c.pos))+"格；"+direction(f.pos),10,y+12,0xE0E0E0);
            line(ctx,f.mark.label+"；人工确认："+(f.mark==Survey.Mark.CONFIRMED||f.mark==Survey.Mark.CLAIMED?"是":"否"),10,y+24,0xC4B8D8);
        }
        if(list.isEmpty())line(ctx,"尚未发现容器；未加载区域及扫描进度不能作为无箱子的证据。",8,130,0xEEEEEE);
        line(ctx,"容器不自动判定为宝藏。第"+(page+1)+"页",8,height-78,0xA9BCC7);
        line(ctx,"按 Esc 关闭；灰色标记表示历史记录",8,height-14,0xA9BCC7);
        super.render(ctx,mx,my,delta);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==0&&y>=120&&y<height-78){
            int n=(int)((y-122)/40),i=page*perPage()+n;
            var list=rows();if(n>=0&&n<perPage()&&i<list.size()){
                var f=list.get(i);selected=f.pos.key();routePlan=null;seaPlan=null;seaPlanning=false;seaStartPending=0;routeRequest++;target=f.pos;targetLabel=f.kind.label;return true;
            }
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean shouldPause(){return false;}
    static int markerColor(Survey.Found f){
        if(!f.present)return 0x888888;
        return switch(f.mark){
            case CLAIMED->0x64DA91;case CONFIRMED->0x7BEAFF;case SUSPECT->0xEAA3FF;
            default->switch(f.kind){case CHEST->0xFFE08A;case TRAPPED->0xFF826B;case BARREL->0x89ADFF;default->0xAAAAAA;};
        };
    }
    static void hud(DrawContext ctx){
        var client=MinecraftClient.getInstance();
        if(world==null||client.player==null||!settings.hud||client.options.hudHidden||client.currentScreen!=null)return;
        var tr=client.textRenderer;int size=104,x=client.getWindow().getScaledWidth()-size-8,y=18;
        ctx.fill(x-3,y-13,x+size+3,y+size+65,0xBB101920);
        ctx.drawTextWithShadow(tr,"寻宝地图 · 北↑",x,y-11,0xFFFFFF);
        ctx.fill(x+size/2,y,x+size/2+1,y+size,0xFF354854);
        ctx.fill(x,y+size/2,x+size,y+size/2+1,0xFF354854);
        var player=playerPos();
        for(var c:survey.candidates){
            if(Math.abs(c.pos.x()-player.x())>settings.radius+192||Math.abs(c.pos.z()-player.z())>settings.radius+192)continue;
            int px=x+size/2+(int)((c.pos.x()-player.x())*size/384.0),pz=y+size/2+(int)((c.pos.z()-player.z())*size/384.0);
            if(px>=x&&px<x+size&&pz>=y&&pz<y+size)ctx.fill(px,pz,px+1,pz+1,0xFF737D84);
            for(var f:c.found.values()){
                if(!f.present||f.mark==Survey.Mark.CLAIMED||!survey.inRange(c,f.pos)||(!settings.barrels&&f.kind==Survey.Kind.BARREL))continue;
                px=x+size/2+(int)((f.pos.x()-player.x())*size/384.0);pz=y+size/2+(int)((f.pos.z()-player.z())*size/384.0);
                if(px>=x&&px<x+size&&pz>=y&&pz<y+size){
                    int color=view().loaded(f.pos.x()>>4,f.pos.z()>>4)?markerColor(f):0x777777;
                    ctx.fill(px-1,pz-1,px+2,pz+2,0xFF000000|color);
                }
            }
        }
        ctx.drawTextWithShadow(tr,"你",x+size/2-3,y+size/2-3,0xFFFFFF);
        if(target!=null){
            int px=x+size/2+(int)((target.x()-player.x())*size/384.0),pz=y+size/2+(int)((target.z()-player.z())*size/384.0);
            px=Math.max(x+2,Math.min(x+size-3,px));pz=Math.max(y+2,Math.min(y+size-3,pz));
            ctx.drawBorder(px-3,pz-3,7,7,0xFFFFFFFF);
            ctx.drawTextWithShadow(tr,tr.trimToWidth(targetLabel+" "+direction(target),size),x,y+size+3,0xFFE08A);
            ctx.drawTextWithShadow(tr,tr.trimToWidth(coords(target),size),x,y+size+14,0xFFFFFF);
        }
        ctx.drawTextWithShadow(tr,tr.trimToWidth((enabled?"巡查 "+survey.candidates.stream().filter(c->c.visited).count()+"/"+survey.candidates.size():"已暂停")+" · F8结果",size),x,y+size+27,0xC3D1DC);
        ctx.drawTextWithShadow(tr,tr.trimToWidth(navigationStatus,size),x,y+size+39,0xA7CFD6);
        ctx.drawTextWithShadow(tr,tr.trimToWidth(roundTracker.status(System.currentTimeMillis()),size),x,y+size+51,0xA7CFD6);
    }
}


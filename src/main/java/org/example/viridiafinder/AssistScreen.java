package org.example.viridiafinder;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import static org.example.viridiafinder.ViridiaFinder.*;

public final class AssistScreen extends Screen {
    private final Screen parent;
    AssistScreen(Screen parent){super(Text.literal("轮次与移动辅助"));this.parent=parent;}
    private void button(String name,int y,Runnable action){
        addDrawableChild(ButtonWidget.builder(Text.literal(name),b->{action.run();clearAndInit();})
            .dimensions(Math.max(8,width/2-160),y,Math.min(320,width-16),20).build());
    }
    @Override protected void init(){
        button("依据之眼更新轮次："+(settings.autoRound?"开启":"关闭"),55,()->{settings.autoRound=!settings.autoRound;save();});
        button("手动开始新一轮（旧记录留档）",80,()->newRound(false));
        button("连续激流（正常蓄力）："+(TravelAssist.riptide?"开启":"关闭"),110,()->{
            TravelAssist.riptide=!TravelAssist.riptide;if(!TravelAssist.riptide)TravelAssist.stop(client);
        });
        button("自动向前游泳："+(TravelAssist.swim?"开启":"关闭"),135,()->TravelAssist.swim=!TravelAssist.swim);
        button("返回",height-28,()->client.setScreen(parent));
    }
    private void line(DrawContext ctx,String text,int y,int color){
        ctx.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(text,width-16),8,y,color);
    }
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        ctx.fill(0,0,width,height,0xF0101920);
        line(ctx,"维里迪亚寻宝 · 轮次与移动辅助",10,0xFFFFFF);
        line(ctx,roundTracker.status(System.currentTimeMillis()),27,0xFFE08A);
        line(ctx,"主手持之眼左键查询；预计到点不会直接清空。",40,0xB8C0CD);
        line(ctx,"关闭此界面后生效；自动游泳跟随你的视角。",162,0xFFFFFF);
        line(ctx,"激流需要主手激流三叉戟及原版水／雨条件。",175,0xB8C0CD);
        line(ctx,"菜单、失焦、后退、潜行或攻击时暂停；换世界关闭。",188,0xB8C0CD);
        line(ctx,"停止会结束当前蓄力，可能释放最后一次激流。",201,0xFFE08A);
        if(height>=250)line(ctx,"只维持正常水中疾跑，不强制姿态；服务器许可未核实。",214,0xFFE08A);
        super.render(ctx,mx,my,delta);
    }
    @Override public void close(){client.setScreen(parent);}
    @Override public boolean shouldPause(){return false;}
}

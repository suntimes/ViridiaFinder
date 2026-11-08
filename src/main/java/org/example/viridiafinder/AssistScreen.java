package org.example.viridiafinder;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import static org.example.viridiafinder.ViridiaFinder.*;

public final class AssistScreen extends Screen {
    private final Screen parent;
    private int left, top;

    AssistScreen(Screen parent) {
        super(Text.literal("寻宝设置"));
        this.parent = parent;
    }

    private void button(String name, int x, int y, int w, Runnable action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> action.run())
                .dimensions(x,y,w,20).build());
    }

    @Override protected void init() {
        left = width/2-134;
        top = Math.max(6,(height-194)/2);
        button("之眼自动更新轮次"+(settings.autoRound?" · 开":" · 关"),left,top+28,132,()->{
            settings.autoRound=!settings.autoRound;save();clearAndInit();
        });
        button("手动开始新一轮",left+136,top+28,132,()->newRound(false));
        button("连续激流"+(TravelAssist.riptide?" · 开":" · 关"),left,top+53,132,()->toggleAssist(true));
        button("向前游泳"+(TravelAssist.swim?" · 开":" · 关"),left+136,top+53,132,()->toggleAssist(false));
        if (!client.isInSingleplayer() && movementAutomationAllowed(client)) {
            button("撤销本服移动辅助许可",left,top+78,268,()->{revokeMovementPermission();clearAndInit();});
        }
        button("已忽略箱子（"+survey.ignoredPositions().size()+"）",left,top+137,268,()->client.setScreen(new IgnoredScreen(this)));
        button("返回",left,top+162,268,this::close);
    }

    private void toggleAssist(boolean trident) {
        if (trident && TravelAssist.riptide) {
            TravelAssist.riptide=false;TravelAssist.stop(client);
        } else if (!trident && TravelAssist.swim) {
            TravelAssist.swim=false;
        } else {
            requestMovementPermission(this,()->{
                TravelAssist.setAutoSail(client,false);
                if (trident) TravelAssist.riptide=true; else TravelAssist.swim=true;
                clearAndInit();
            });
            return;
        }
        clearAndInit();
    }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0,0,width,height,0xE5101920);
        ctx.drawTextWithShadow(textRenderer,getTitle(),left,top,0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,roundTracker.status(System.currentTimeMillis()),left,top+14,0xFFE08A);
        ctx.drawTextWithShadow(textRenderer,"自动航行中，激流尝试至少间隔 4 秒。",left,top+105,0xCCDBDF);
        String status = TravelAssist.sailingStatus();
        ctx.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(status,268),left,top+118,0xA5B5BC);
        super.render(ctx,mx,my,delta);
    }

    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}

package org.example.viridiafinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.List;
import static org.example.viridiafinder.ViridiaFinder.*;

public final class FinderScreen extends Screen {
    private int left, top, panelWidth;
    private ButtonWidget recalculate, scan, sail;

    FinderScreen() { super(Text.literal("维里迪亚寻宝")); }

    private ButtonWidget button(String name, int x, int y, int w, Runnable action) {
        return addDrawableChild(ButtonWidget.builder(Text.literal(name), b -> action.run())
                .dimensions(x, y, w, 20).build());
    }

    @Override protected void init() {
        panelWidth = Math.min(284, width - 20);
        left = (width - panelWidth) / 2;
        top = Math.max(8, (height - 160) / 2);
        int half = (panelWidth - 4) / 2;
        recalculate = button("从当前位置重算路线", left, top + 66, panelWidth, ViridiaFinder::route);
        scan = button("", left, top + 91, half, ViridiaFinder::toggleSearch);
        sail = button("", left + half + 4, top + 91, panelWidth - half - 4, () -> {
            if (TravelAssist.autoSail) TravelAssist.setAutoSail(client, false);
            else requestMovementPermission(this, this::startSailing);
        });
        button("箱子与候选记录", left, top + 119, half, () -> client.setScreen(new RecordScreen(this)));
        button("设置", left + half + 4, top + 119, panelWidth - half - 4, () -> client.setScreen(new AssistScreen(this)));
        tick();
    }

    private void startSailing() {
        if (!isIsles()) { navigationStatus = "海面路线仅支持主群岛"; return; }
        if (!enabled || seaPlan == null && !seaPlanning) route();
        if ((seaPlan != null || seaPlanning) && TravelAssist.setAutoSail(client, true)) close();
    }

    @Override public void tick() {
        recalculate.active = isIsles() && !seaPlanning;
        sail.active = isIsles();
        recalculate.setMessage(Text.literal(seaPlanning ? "正在计算海路…" : "从当前位置重算路线"));
        scan.setMessage(Text.literal(enabled ? "暂停搜寻" : "开始搜寻"));
        sail.setMessage(Text.literal(TravelAssist.autoSail ? "停止自动航行" : "自动航行"));
    }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(0, 0, width, height, 0x80101920);
        ctx.fill(left-8, top-7, left+panelWidth+8, top+147, 0xEC182128);
        ctx.drawTextWithShadow(textRenderer, getTitle(), left, top+1, 0xFFFFFF);
        long remaining = survey.candidates.stream().filter(c -> !c.visited).count();
        String status = !isIsles() ? "海图仅支持主群岛" : enabled ? navigationStatus : "已暂停";
        ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth("剩余 "+remaining+"/169  ·  "+status,panelWidth),
                left, top+17, 0xBFDAE0);
        ctx.drawTextWithShadow(textRenderer, "当前位置  "+coords(playerPos()), left, top+33, 0xFFFFFF);
        if (routeOrigin != null) {
            String length = seaPlan == null ? "" : "  ·  约 "+Math.round(seaPlan.length())+" 格";
            ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth("计算起点  "+coords(routeOrigin)+length,panelWidth),
                    left, top+47, 0xA6B7BF);
        }
        super.render(ctx, mx, my, delta);
    }

    @Override public boolean shouldPause() { return false; }
    private static int minimapTick = -1;
    private static Survey minimapSurvey;
    private static List<SurveyMinimap.Dot> minimapDots = List.of();

    private static void drawMinimap(DrawContext ctx, int left, int top) {
        if (minimapTick != ticks || minimapSurvey != survey) {
            minimapDots = SurveyMinimap.dots(playerPos(), survey);
            minimapTick = ticks;
            minimapSurvey = survey;
        }

        int size = SurveyMinimap.SIZE;
        int cx = left + SurveyMinimap.CENTER;
        int cy = top + SurveyMinimap.CENTER;
        ctx.fill(left, top, left + size, top + size, 0xB0101920);
        ctx.fill(cx, top, cx + 1, top + size, 0x66445663);
        ctx.fill(left, cy, left + size, cy + 1, 0x66445663);

        ctx.enableScissor(left, top, left + size, top + size);
        try {
            for (SurveyMinimap.Dot dot : minimapDots) {
                int x = left + dot.x();
                int y = top + dot.y();
                var style = dot.style();
                if (!style.container) {
                    ctx.fill(x, y, x + 1, y + 1, style.color);
                } else if (style.historical) {
                    ctx.drawBorder(x - 2, y - 2, 5, 5, style.color);
                } else {
                    ctx.fill(x - 1, y - 1, x + 2, y + 2, style.color);
                }
            }
        } finally {
            ctx.disableScissor();
        }

        // Keep the centre clear so a chest under the player is still visible.
        ctx.fill(cx - 4, cy, cx - 2, cy + 1, 0xFFFFFFFF);
        ctx.fill(cx + 3, cy, cx + 5, cy + 1, 0xFFFFFFFF);
        ctx.fill(cx, cy - 4, cx + 1, cy - 2, 0xFFFFFFFF);
        ctx.fill(cx, cy + 3, cx + 1, cy + 5, 0xFFFFFFFF);
    }

    static void hud(DrawContext ctx) {
        var client = MinecraftClient.getInstance();
        if (!enabled || !settings.hud || world == null || client.player == null
                || client.currentScreen != null || client.options.hudHidden) return;

        var lines = CompactHud.lines(playerPos(), target, survey);
        var renderer = client.textRenderer;
        int right = client.getWindow().getScaledWidth() - 5;
        int left = right - SurveyMinimap.SIZE;
        int mapTop = 5;
        drawMinimap(ctx, left, mapTop);

        int textTop = mapTop + SurveyMinimap.SIZE + 2;
        int spacing = renderer.fontHeight + 2;
        ctx.fill(left, mapTop + SurveyMinimap.SIZE, right, textTop + spacing * lines.size(), 0xAA101920);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            ctx.drawTextWithShadow(renderer, line, right - 2 - renderer.getWidth(line), textTop + i * spacing,
                    i == lines.size() - 1 ? 0xC3D1DC : 0xFFE08A);
        }
    }
}

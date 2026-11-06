package org.example.viridiafinder;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

final class MovementPermissionScreen extends Screen {
    private final Screen parent;
    private final String server;
    private final Runnable onAllowed;

    MovementPermissionScreen(Screen parent, String server, Runnable onAllowed) {
        super(Text.literal("本服移动辅助许可"));
        this.parent = parent;
        this.server = server;
        this.onAllowed = onAllowed;
    }

    @Override protected void init() {
        int y = height / 2 + 15;
        addDrawableChild(ButtonWidget.builder(Text.literal("返回手动操作"), b -> close())
                .dimensions(width / 2 - 132, y, 128, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("已获本服明确许可"), b -> {
            boolean granted = ViridiaFinder.grantMovementPermission(server);
            close();
            if (granted) onAllowed.run();
        }).dimensions(width / 2 + 4, y, 128, 20).build());
    }

    @Override public void render(DrawContext ctx, int mx, int my, float delta) {
        int x = width / 2 - 132;
        int y = height / 2 - 57;
        ctx.fill(0, 0, width, height, 0xE5101920);
        ctx.drawTextWithShadow(textRenderer, getTitle(), x, y, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, textRenderer.trimToWidth(server,264), x, y+15, 0xC4D8DD);
        ctx.drawTextWithShadow(textRenderer, "自动航行会转向、游泳并使用快捷栏激流三叉戟。", x, y+32, 0xE0E0E0);
        ctx.drawTextWithShadow(textRenderer, "请在管理方允许这些操作时开启。", x, y+45, 0xE0E0E0);
        ctx.drawTextWithShadow(textRenderer, "许可仅保存在本机，可在设置中撤销。", x, y+57, 0xADC2C9);
        super.render(ctx, mx, my, delta);
    }

    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}

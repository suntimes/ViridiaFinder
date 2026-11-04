package org.example.viridiafinder.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import org.example.viridiafinder.TravelAssist;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientPlayerEntity.class)
public abstract class SwimmingSprintMixin {
    @Redirect(method="tickMovement",at=@At(value="INVOKE",target="Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
    private boolean viridiafinder$swimSprint(KeyBinding key){
        var client=MinecraftClient.getInstance();
        return key.isPressed()||(key==client.options.sprintKey&&TravelAssist.wantsSwim(client));
    }
}

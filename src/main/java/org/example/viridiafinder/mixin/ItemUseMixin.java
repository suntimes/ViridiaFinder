package org.example.viridiafinder.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.example.viridiafinder.TravelAssist;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftClient.class)
public abstract class ItemUseMixin {
    @Redirect(method="handleInputEvents",at=@At(value="INVOKE",target="Lnet/minecraft/client/option/KeyBinding;isPressed()Z"))
    private boolean viridiafinder$maintainOwnedCharge(KeyBinding key){
        var client=(MinecraftClient)(Object)this;
        // Only sustain an already-started item use. Never start a block/container interaction.
        return key.isPressed()||(key==client.options.useKey&&TravelAssist.holdsUse(client));
    }
}

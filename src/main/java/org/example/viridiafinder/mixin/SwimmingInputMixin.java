package org.example.viridiafinder.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import org.example.viridiafinder.TravelAssist;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class SwimmingInputMixin extends Input {
    @Inject(method="tick",at=@At("TAIL"))
    private void viridiafinder$swimForward(boolean slow,float factor,CallbackInfo ci){
        if(TravelAssist.wantsSwim(MinecraftClient.getInstance())){pressingForward=true;movementForward=slow?factor:1f;}
    }
}

package org.example.viridiafinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

/** Uses vanilla item interaction and sprint only; never edits pose, velocity or position. */
public final class TravelAssist {
    public static boolean riptide,swim;
    private static final RiptideClock clock=new RiptideClock();
    private static ClientPlayerEntity owner;
    private static int slot;
    private static long started;
    public static boolean wantsSwim(MinecraftClient client){
        var p=client.player;
        return swim&&p!=null&&p.isAlive()&&!p.isSpectator()&&client.currentScreen==null&&client.isWindowFocused()
            &&p.isTouchingWater()&&!p.hasVehicle()&&!client.options.sneakKey.isPressed()
            &&!client.options.backKey.isPressed()&&!client.options.attackKey.isPressed()
            &&p.getHungerManager().getFoodLevel()>6&&!p.hasStatusEffect(StatusEffects.BLINDNESS);
    }
    public static boolean canCharge(ItemStack stack,boolean wet,boolean mounted,boolean cooldown){
        return stack.isOf(Items.TRIDENT)&&EnchantmentHelper.getRiptide(stack)>0&&wet&&!mounted
            &&stack.getDamage()<stack.getMaxDamage()-1&&!cooldown;
    }
    public static boolean holdsUse(MinecraftClient client){
        return owner!=null&&owner==client.player&&owner.isUsingItem()&&owner.getActiveHand()==Hand.MAIN_HAND
            &&owner.getInventory().selectedSlot==slot&&owner.getMainHandStack().isOf(Items.TRIDENT);
    }
    public static void stop(MinecraftClient client){
        boolean ours=holdsUse(client);owner=null;
        if(ours&&client.interactionManager!=null)client.interactionManager.stopUsingItem(client.player);
        clock.released(System.nanoTime());
    }
    public static void disconnect(){riptide=false;swim=false;owner=null;clock.reset();}
    public static void tick(MinecraftClient client){
        var p=client.player;
        if(p==null||client.interactionManager==null)return;
        if(!p.isAlive()||p.isSpectator()){stop(client);riptide=false;swim=false;return;}
        if(client.currentScreen!=null||!client.isWindowFocused()||p.isSneaking()||client.options.attackKey.isPressed()||client.options.backKey.isPressed()){
            stop(client);return;
        }
        if(wantsSwim(client)&&p.input.movementForward>0&&!p.horizontalCollision)p.setSprinting(true);
        var stack=p.getMainHandStack();
        boolean eligible=riptide&&canCharge(stack,p.isTouchingWaterOrRain(),p.hasVehicle(),p.getItemCooldownManager().isCoolingDown(Items.TRIDENT));
        if(!eligible){stop(client);return;}
        long now=System.nanoTime();
        if(owner!=null){
            if(!holdsUse(client)){owner=null;clock.released(now);return;}
            if(p.getItemUseTime()>=10||now-started>2_000_000_000L)stop(client);
            return;
        }
        if(!clock.ready(now)||p.isUsingItem()||client.options.useKey.isPressed())return;
        clock.started(now);
        client.interactionManager.interactItem(p,Hand.MAIN_HAND);
        if(p.isUsingItem()&&p.getActiveHand()==Hand.MAIN_HAND){owner=p;slot=p.getInventory().selectedSlot;started=now;}
    }
}

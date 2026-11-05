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
    private static ItemStack ownedStack=ItemStack.EMPTY;
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
    static boolean sameChargeStack(ItemStack expected,ItemStack actual){
        // Equivalent inventory refreshes may replace the Java object without changing the item.
        return !expected.isEmpty()&&ItemStack.areEqual(expected,actual);
    }
    private static boolean ownsActiveUse(MinecraftClient client){
        return owner!=null&&owner==client.player&&owner.isUsingItem()&&owner.getActiveHand()==Hand.MAIN_HAND
            &&sameChargeStack(ownedStack,owner.getActiveItem());
    }
    private static boolean paused(MinecraftClient client){
        var p=client.player;
        return p==null||!p.isAlive()||p.isSpectator()||client.currentScreen!=null||!client.isWindowFocused()
            ||p.isSneaking()||client.options.sneakKey.isPressed()||client.options.attackKey.isPressed()
            ||client.options.backKey.isPressed();
    }
    public static boolean holdsUse(MinecraftClient client){
        // Called from input handling, before END_CLIENT_TICK: never keep a stale session held.
        if(!riptide||paused(client)||client.options.useKey.isPressed()||!ownsActiveUse(client))return false;
        return owner.getInventory().selectedSlot==slot&&sameChargeStack(ownedStack,owner.getMainHandStack())
            &&canCharge(ownedStack,owner.isTouchingWaterOrRain(),owner.hasVehicle(),
                owner.getItemCooldownManager().isCoolingDown(Items.TRIDENT))&&!clock.timedOut(System.nanoTime());
    }
    private static void forgetOwner(){owner=null;ownedStack=ItemStack.EMPTY;}
    private static void finish(MinecraftClient client,boolean interrupted){
        boolean ours=ownsActiveUse(client);
        forgetOwner();
        // A real right click takes ownership: never release the player's own held input.
        if(ours&&!client.options.useKey.isPressed()&&client.interactionManager!=null)
            client.interactionManager.stopUsingItem(client.player);
        if(interrupted)clock.interrupted(System.nanoTime());else clock.released(System.nanoTime());
    }
    public static void stop(MinecraftClient client){
        if(owner!=null)finish(client,true);
    }
    public static void disconnect(){riptide=false;swim=false;forgetOwner();clock.reset();}
    public static void tick(MinecraftClient client){
        var p=client.player;
        if(p==null||client.interactionManager==null){forgetOwner();clock.reset();return;}
        if(!p.isAlive()||p.isSpectator()){stop(client);riptide=false;swim=false;return;}
        if(paused(client)){
            stop(client);return;
        }
        if(wantsSwim(client)&&p.input.movementForward>0&&!p.horizontalCollision)p.setSprinting(true);
        if(client.options.useKey.isPressed()){
            if(owner!=null){forgetOwner();clock.interrupted(System.nanoTime());}
            return;
        }
        var stack=p.getMainHandStack();
        boolean eligible=riptide&&canCharge(stack,p.isTouchingWaterOrRain(),p.hasVehicle(),p.getItemCooldownManager().isCoolingDown(Items.TRIDENT));
        if(!eligible){stop(client);return;}
        long now=System.nanoTime();
        if(owner!=null){
            if(!ownsActiveUse(client)||p.getInventory().selectedSlot!=slot||!sameChargeStack(ownedStack,stack)){
                finish(client,true);return;
            }
            if(clock.timedOut(now)){finish(client,true);return;}
            if(clock.charged(p.getItemUseTime()))finish(client,false);
            return;
        }
        if(!clock.ready(now)||p.isUsingItem()||p.isUsingRiptide()||client.interactionManager.isBreakingBlock())return;
        clock.started(now);
        var result=client.interactionManager.interactItem(p,Hand.MAIN_HAND);
        if(result.isAccepted()&&p.isUsingItem()&&p.getActiveHand()==Hand.MAIN_HAND&&p.getActiveItem()==stack){
            owner=p;slot=p.getInventory().selectedSlot;ownedStack=stack.copy();
        }else clock.interrupted(now);
    }
}

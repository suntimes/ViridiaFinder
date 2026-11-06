package org.example.viridiafinder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;

/** Uses vanilla item interaction and sprint only; never edits pose, velocity or position. */
public final class TravelAssist {
    public static boolean riptide,swim,autoSail;
    private static ClientPlayerEntity sailor;
    private static final AutoSail.SlotLease sailSlot=new AutoSail.SlotLease();
    private static final AutoSail.Progress progress=new AutoSail.Progress();
    private static final AutoSail.Attempts sailAttempts=new AutoSail.Attempts();
    private static Survey.Pos movingTo;
    private static boolean boost,monitorLook;
    private static float lastYaw,lastPitch;
    private static String sailStatus="自动航行已关闭";
    private static final RiptideClock clock=new RiptideClock();
    private static ClientPlayerEntity owner;
    private static int slot;
    private static ItemStack ownedStack=ItemStack.EMPTY;
    public static boolean wantsSwim(MinecraftClient client){
        var p=client.player;
        return (autoSail?sailLegActive(client):swim)&&ViridiaFinder.movementAutomationAllowed(client)
            &&p!=null&&p.isAlive()&&!p.isSpectator()&&client.currentScreen==null&&client.isWindowFocused()
            &&p.isTouchingWater()&&!p.hasVehicle()&&!client.options.sneakKey.isPressed()
            &&!client.options.backKey.isPressed()&&!client.options.attackKey.isPressed()&&!client.options.useKey.isPressed()
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
        if(owner==null||!ViridiaFinder.movementAutomationAllowed(client)||!(autoSail?(boost&&sailLegActive(client)):riptide)
            ||paused(client)||client.options.useKey.isPressed()||!ownsActiveUse(client))return false;
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
    public static String sailingStatus(){return sailStatus;}
    public static boolean setAutoSail(MinecraftClient client,boolean enabled){
        if(!enabled){endVoyage(client,"自动航行已关闭",true);return false;}
        if(client.player==null||!ViridiaFinder.movementAutomationAllowed(client))return false;
        if(autoSail)return true;
        stop(client);swim=false;riptide=false;
        sailor=client.player;sailSlot.start(sailor.getInventory().selectedSlot);
        movingTo=null;boost=false;monitorLook=false;progress.reset();
        autoSail=true;sailStatus="等待海面航点";return true;
    }
    private static void restoreSlot(MinecraftClient client){
        if(client.player!=sailor||sailor==null)return;
        var inventory=sailor.getInventory();
        int restore=sailSlot.restore(inventory.selectedSlot);
        if(restore>=0&&!client.options.useKey.isPressed()&&!sailor.isUsingItem())inventory.selectedSlot=restore;
        sailSlot.start(inventory.selectedSlot);
    }
    private static void endVoyage(MinecraftClient client,String reason,boolean restore){
        if(!autoSail)return;
        stop(client);
        if(restore)restoreSlot(client);
        autoSail=false;sailor=null;boost=false;movingTo=null;monitorLook=false;progress.reset();
        sailStatus=reason;
    }
    private static void suspendVoyage(MinecraftClient client,String reason){
        stop(client);restoreSlot(client);progress.reset();boost=false;movingTo=null;monitorLook=false;
        sailStatus=reason;
    }
    private static boolean manualInput(MinecraftClient client){
        var o=client.options;
        return o.backKey.isPressed()||o.sneakKey.isPressed()||o.useKey.isPressed()||o.attackKey.isPressed()
            ||o.leftKey.isPressed()||o.rightKey.isPressed()||o.forwardKey.isPressed()||o.jumpKey.isPressed();
    }
    private static boolean lookChanged(ClientPlayerEntity p){
        return monitorLook&&(Math.abs(MathHelper.wrapDegrees(p.getYaw()-lastYaw))>1.0f
            ||Math.abs(p.getPitch()-lastPitch)>1.0f);
    }
    private static boolean sailLegActive(MinecraftClient client){
        return autoSail&&sailor!=null&&sailor==client.player&&movingTo!=null&&movingTo.equals(ViridiaFinder.sailingWaypoint())
            &&!sailor.horizontalCollision&&!sailSlot.changed(sailor.getInventory().selectedSlot)&&!manualInput(client)&&!lookChanged(sailor);
    }
    private static void sail(MinecraftClient client){
        var p=client.player;
        if(sailor!=p){endVoyage(client,"角色已切换",false);return;}
        if(sailSlot.changed(p.getInventory().selectedSlot)){
            endVoyage(client,"手动换槽，航行已关闭",false);return;
        }
        if(client.currentScreen!=null||!client.isWindowFocused()){
            suspendVoyage(client,"界面打开或窗口失焦，航行暂停");return;
        }
        if(manualInput(client)||lookChanged(p)){
            endVoyage(client,"已交还手动控制",!client.options.useKey.isPressed());return;
        }
        var goal=ViridiaFinder.sailingWaypoint();
        if(goal==null){suspendVoyage(client,"等待海面航点");return;}
        if (!p.isTouchingWater() && p.isUsingRiptide()) {
            movingTo=null;boost=false;monitorLook=false;
            sailStatus="等待激流结束";return;
        }
        if(!p.isTouchingWater()||p.hasVehicle()||p.getHungerManager().getFoodLevel()<=6||p.hasStatusEffect(StatusEffects.BLINDNESS)){
            suspendVoyage(client,"等待可游泳状态");return;
        }
        if(p.isUsingItem()&&!ownsActiveUse(client)){endVoyage(client,"正在使用物品，航行已关闭",false);return;}
        double dx=goal.x()+0.5-p.getX(),dz=goal.z()+0.5-p.getZ(),distance=Math.hypot(dx,dz);
        // At a tight bend, two blocks can still be in the previous 4x4 sea cell.
        if(distance<0.5){suspendVoyage(client,"等待切换航点");return;}
        if(p.horizontalCollision||!AutoSail.clearAhead(client,dx,dz,3.0)){
            endVoyage(client,"前方水路受阻或尚未加载，航行已关闭",true);return;
        }
        if(progress.stalled(goal,distance)){endVoyage(client,"连续四秒没有接近航点，航行已关闭",true);return;}
        float bearing=AutoSail.bearing(dx,dz);
        lastYaw=AutoSail.turn(p.getYaw(),bearing);
        float targetPitch=p.isSubmergedInWater()?-10f:0f;
        lastPitch=p.getPitch()+MathHelper.clamp(targetPitch-p.getPitch(),-5f,5f);
        if (Math.abs(MathHelper.wrapDegrees(lastYaw-p.getYaw()))>0.75f) p.setYaw(lastYaw);
        if (Math.abs(lastPitch-p.getPitch())>0.75f) p.setPitch(lastPitch);
        lastYaw=p.getYaw();lastPitch=p.getPitch();monitorLook=true;
        movingTo=Math.abs(MathHelper.wrapDegrees(bearing-lastYaw))<=8?goal:null;
        boost=false;
        if(movingTo==null){sailStatus="正在转向";return;}
        sailStatus="游向海面航点";
        // Keep the last part of each leg slow, particularly on approaches to a scan stop.
        if(!AutoSail.canBoost(distance,MathHelper.wrapDegrees(bearing-lastYaw),targetPitch-lastPitch)||
            p.isUsingRiptide()||p.getItemCooldownManager().isCoolingDown(Items.TRIDENT))return;
        if(owner==null&&!sailAttempts.ready(System.nanoTime()))return;
        var inventory=p.getInventory();
        int trident=AutoSail.tridentSlot(inventory.selectedSlot,i->canCharge(inventory.getStack(i),true,false,false));
        if(trident<0||!AutoSail.clearLaunch(client))return;
        if(inventory.selectedSlot!=trident){
            inventory.selectedSlot=trident;sailSlot.select(trident);
        }
        boost=true;sailStatus="沿航线使用激流";
    }
    public static void stopAll(MinecraftClient client){
        endVoyage(client,"自动航行已关闭",true);stop(client);riptide=false;swim=false;
    }
    public static void disconnect(){
        if(autoSail)restoreSlot(MinecraftClient.getInstance());
        riptide=false;swim=false;autoSail=false;sailor=null;movingTo=null;boost=false;monitorLook=false;progress.reset();
        sailStatus="自动航行已关闭";forgetOwner();clock.reset();sailAttempts.reset();
    }
    public static void tick(MinecraftClient client){
        var p=client.player;
        if(!autoSail&&!swim&&!riptide&&owner==null)return;
        if(p==null||client.interactionManager==null){disconnect();return;}
        if(!p.isAlive()||p.isSpectator()||!ViridiaFinder.movementAutomationAllowed(client)){stopAll(client);return;}
        if(autoSail)sail(client);
        if(paused(client)){
            stop(client);return;
        }
        if(wantsSwim(client)&&p.input.movementForward>0&&!p.horizontalCollision)p.setSprinting(true);
        if(client.options.useKey.isPressed()){
            if(owner!=null){forgetOwner();clock.interrupted(System.nanoTime());}
            return;
        }
        var stack=p.getMainHandStack();
        boolean eligible=(autoSail?boost:riptide)&&canCharge(stack,p.isTouchingWaterOrRain(),p.hasVehicle(),p.getItemCooldownManager().isCoolingDown(Items.TRIDENT));
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
        if(autoSail){
            if(!sailAttempts.ready(now))return;
            sailAttempts.record(now);
        }
        clock.started(now);
        var result=client.interactionManager.interactItem(p,Hand.MAIN_HAND);
        if(result.isAccepted()&&p.isUsingItem()&&p.getActiveHand()==Hand.MAIN_HAND&&p.getActiveItem()==stack){
            owner=p;slot=p.getInventory().selectedSlot;ownedStack=stack.copy();
        }else clock.interrupted(now);
    }
}

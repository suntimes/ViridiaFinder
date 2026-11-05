package org.example.viridiafinder;

import java.util.function.BiConsumer;

/** Deterministic scheduler checks; these do not claim a live-server movement test. */
public final class TravelAssistTest {
    public static void run(BiConsumer<Boolean,String> check){
        runClock(check);
        var actual=new net.minecraft.item.ItemStack(net.minecraft.item.Items.TRIDENT);
        actual.addEnchantment(net.minecraft.enchantment.Enchantments.RIPTIDE,3);
        var owned=actual.copy();
        check.accept(TravelAssist.sameChargeStack(owned,actual.copy()),"激流：等价库存同步更换对象时继续同一次蓄力");
        actual.setDamage(1);
        check.accept(!TravelAssist.sameChargeStack(owned,actual),"激流：物品数据已变化时不延续旧蓄力归属");
        check.accept(!TravelAssist.sameChargeStack(owned,net.minecraft.item.ItemStack.EMPTY)
            &&!TravelAssist.sameChargeStack(owned,new net.minecraft.item.ItemStack(net.minecraft.item.Items.TRIDENT)),
            "激流：空手或未附魔替换品不会被旧状态持续按住");
    }
    private static void runClock(BiConsumer<Boolean,String> check){
        var c=new RiptideClock();
        check.accept(c.ready(-9_000_000_000L),"激流：单调时钟为负值时初始状态仍可启动");
        c.started(0);
        check.accept(!c.charged(10)&&!c.charged(11)&&c.charged(12),"激流：十二刻蓄力为原版十刻门槛保留余量");
        check.accept(!c.timedOut(1_249_999_999L)&&c.timedOut(1_250_000_000L),"激流：使用计时卡住时1.25秒看门狗停止自动保持");
        c.interrupted(1_250_000_000L);
        check.accept(!c.ready(1_749_999_999L)&&c.ready(1_750_000_000L),"激流：超时后退让500毫秒，不立即重新卡住");
        c.started(1_750_000_000L);
        check.accept(!c.timedOut(2_000_000_000L),"激流：新尝试重新开始超时计时");
        c.released(2_350_000_000L);
        check.accept(!c.ready(2_499_999_999L)&&c.ready(2_500_000_000L),"激流：正常释放与下一次使用之间保留150毫秒恢复时间");
        c.started(10_000_000_000L);c.interrupted(10_000_000_000L);
        check.accept(!c.ready(10_250_000_000L)&&c.ready(10_500_000_000L),"激流：拒绝的尝试采用失败退避，不补发落后的次数");
        c.reset();c.started(Long.MAX_VALUE-100_000_000L);
        check.accept(!c.ready(Long.MAX_VALUE-1)&&c.ready(Long.MIN_VALUE+149_999_999L),"激流：单调时钟回绕不造成连发或永久等待");
        c.reset();
        check.accept(c.ready(-1),"激流：断开重置后不继承旧冷却期限");
    }
    public static void main(String[] args){
        runClock((ok,label)->{if(!ok)throw new AssertionError(label);System.out.println(label);});
    }
}

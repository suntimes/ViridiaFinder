package org.example.viridiafinder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

/** 唯一识别条件：客户端方块状态的类型；不检查周围结构。 */
public final class ContainerTypes {
    private ContainerTypes() {}
    public static Survey.Kind identify(BlockState state) {
        if(state.isOf(Blocks.CHEST)) return Survey.Kind.CHEST;
        if(state.isOf(Blocks.TRAPPED_CHEST)) return Survey.Kind.TRAPPED;
        return Survey.Kind.NONE;
    }
}

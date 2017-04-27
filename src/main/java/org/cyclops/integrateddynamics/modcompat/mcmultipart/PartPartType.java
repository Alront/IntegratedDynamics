package org.cyclops.integrateddynamics.modcompat.mcmultipart;

import mcmultipart.api.multipart.IMultipart;
import mcmultipart.api.slot.EnumFaceSlot;
import mcmultipart.api.slot.IPartSlot;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import org.cyclops.integrateddynamics.api.part.IPartType;

/**
 * @author rubensworks
 */
public class PartPartType implements IMultipart {

    private final Block block;
    private final IPartType partType;

    public PartPartType(Block block, IPartType partType) {
        this.block = block;
        this.partType = partType;
    }

    @Override
    public Block getBlock() {
        return this.block;
    }

    @Override
    public IPartSlot getSlotForPlacement(World world, BlockPos pos, IBlockState state, EnumFacing facing, float hitX, float hitY, float hitZ, EntityLivingBase placer) {
        return EnumFaceSlot.NORTH; // TODO
    }

    @Override
    public IPartSlot getSlotFromWorld(IBlockAccess world, BlockPos pos, IBlockState state) {
        return EnumFaceSlot.NORTH; // TODO
    }
}

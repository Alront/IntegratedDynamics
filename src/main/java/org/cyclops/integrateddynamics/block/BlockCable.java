package org.cyclops.integrateddynamics.block;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import lombok.Data;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.property.IExtendedBlockState;
import net.minecraftforge.common.property.IUnlistedProperty;
import net.minecraftforge.common.property.Properties;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.cyclops.cyclopscore.block.property.BlockProperty;
import org.cyclops.cyclopscore.client.icon.Icon;
import org.cyclops.cyclopscore.config.configurable.ConfigurableBlockContainer;
import org.cyclops.cyclopscore.config.extendedconfig.ExtendedConfig;
import org.cyclops.cyclopscore.datastructure.DimPos;
import org.cyclops.cyclopscore.helper.TileHelpers;
import org.cyclops.integrateddynamics.client.model.CableModel;
import org.cyclops.integrateddynamics.core.helper.WrenchHelpers;
import org.cyclops.integrateddynamics.core.network.INetworkElement;
import org.cyclops.integrateddynamics.core.network.INetworkElementProvider;
import org.cyclops.integrateddynamics.core.network.Network;
import org.cyclops.integrateddynamics.core.part.IPartContainer;
import org.cyclops.integrateddynamics.core.part.IPartContainerFacade;
import org.cyclops.integrateddynamics.core.part.IPartType;
import org.cyclops.integrateddynamics.core.path.CablePathElement;
import org.cyclops.integrateddynamics.core.tileentity.TileMultipartTicking;

import java.util.*;

/**
 * A block that is build up from different parts.
 * This block refers to a ticking tile entity.
 * Ray tracing code is partially based on BuildCraft's pipe code.
 * @author rubensworks
 */
public class BlockCable extends ConfigurableBlockContainer implements ICableConnectable<CablePathElement>,
        INetworkElementProvider, IPartContainerFacade {

    @BlockProperty
    public static final IUnlistedProperty<Boolean>[] CONNECTED = new IUnlistedProperty[6];
    static {
        for(EnumFacing side : EnumFacing.values()) {
            CONNECTED[side.ordinal()] = Properties.toUnlisted(PropertyBool.create(side.getName()));
        }
    }

    private static BlockCable _instance = null;

    float[][] COLLISION_BOXES = {
            {CableModel.MIN, 0, CableModel.MIN, CableModel.MAX, CableModel.MAX, CableModel.MAX}, // DOWN
            {CableModel.MIN, CableModel.MIN, CableModel.MIN, CableModel.MAX, 1, CableModel.MAX}, // UP
            {CableModel.MIN, CableModel.MIN, 0, CableModel.MAX, CableModel.MAX, CableModel.MAX}, // NORTH
            {CableModel.MIN, CableModel.MAX, CableModel.MIN, CableModel.MAX, CableModel.MIN, 1}, // SOUTH
            {0, CableModel.MIN, CableModel.MIN, CableModel.MAX, CableModel.MAX, CableModel.MAX}, // WEST
            {CableModel.MIN, CableModel.MIN, CableModel.MIN, 1, CableModel.MAX, CableModel.MAX}, // EAST
    };

    @Icon(location = "blocks/cable")
    public TextureAtlasSprite texture;

    /**
     * Get the unique instance.
     * @return The instance.
     */
    public static BlockCable getInstance() {
        return _instance;
    }

    /**
     * Make a new block instance.
     * @param eConfig Config for this block.
     */
    public BlockCable(ExtendedConfig eConfig) {
        super(eConfig, Material.glass, TileMultipartTicking.class);

        setHardness(3.0F);
        setStepSound(soundTypeMetal);
        eConfig.getMod().getIconProvider().registerIconHolderObject(this);
    }

    @Override
    public int getRenderType() {
        return 3;
    }

    @Override
    public IBlockState getExtendedState(IBlockState state, IBlockAccess world, BlockPos pos) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos);
        if(tile != null) {
            return tile.getConnectionState();
        }
        return getDefaultState();
    }

    @Override
    public IExtendedBlockState updateConnections(World world, BlockPos pos) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos);
        if(tile != null) {
            tile.updateCableConnections();
            return tile.getConnectionState();
        }
        return null;
    }

    @Override
    public boolean isConnected(World world, BlockPos pos, EnumFacing side) {
        IExtendedBlockState extendedState = (IExtendedBlockState) getExtendedState(world.getBlockState(pos), world, pos);
        return extendedState != null && extendedState.getValue(CONNECTED[side.ordinal()]) != null
                && extendedState.getValue(BlockCable.CONNECTED[side.ordinal()]);
    }

    @Override
    public void disconnect(World world, BlockPos pos, EnumFacing side) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos);
        if(tile != null) {
            tile.forceDisconnect(side);
        }
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumFacing side, float hitX, float hitY, float hitZ) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos);
        if(!world.isRemote && WrenchHelpers.isWrench(player, pos) && tile != null) {
            RayTraceResult rayTraceResult = doRayTrace(world, pos, player);
            if(rayTraceResult != null) {
                EnumFacing disconnectedSide = rayTraceResult.getSideHit();

                // Store the disconnection in the tile entity
                disconnect(world, pos, disconnectedSide);

                // Signal changes
                tile.updateCableConnections();
                triggerNeighbourConnections(world, pos);

                // Reinit the networks for this block and the disconnected neighbour.
                Network.initiateNetworkSetup(this, world, pos).initialize();
                BlockPos neighbourPos = pos.offset(disconnectedSide);
                Block neighbourBlock = world.getBlockState(neighbourPos).getBlock();
                if(neighbourBlock instanceof ICableConnectable) {
                    Network.initiateNetworkSetup((ICableConnectable<CablePathElement>) neighbourBlock,
                            world, neighbourPos).initialize();
                }
                return true;
            }
        }
        return super.onBlockActivated(world, pos, state, player , side, hitX, hitY, hitZ);
    }

    protected void requestConnectionsUpdate(World world, BlockPos pos) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, pos);
        if(tile != null) {
            tile.updateCableConnections();
        }
        world.markBlockRangeForRenderUpdate(pos, pos);
    }


    protected void triggerNeighbourConnections(World world, BlockPos blockPos) {
        for(EnumFacing side : EnumFacing.VALUES) {
            requestConnectionsUpdate(world, blockPos.offset(side));
        }
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack itemStack) {
        super.onBlockPlacedBy(world, pos, state, placer, itemStack);
        triggerNeighbourConnections(world, pos);

        if(!world.isRemote) {
            Network.initiateNetworkSetup(this, world, pos).initialize();
        }
    }

    @Override
    public void onNeighborBlockChange(World world, BlockPos pos, IBlockState state, Block neighborBlock) {
        super.onNeighborBlockChange(world, pos, state, neighborBlock);
    }

    @Override
    public boolean saveNBTToDroppedItem() {
        return false;
    }

    protected void onPreBlockDestroyed(World world, BlockPos pos) {
        // Drop all parts types as item.
        List<ItemStack> itemStacks = Lists.newLinkedList();
        for (INetworkElement networkElement : createNetworkElements(world, pos)) {
            networkElement.addDrops(itemStacks);
        }
        for(ItemStack itemStack : itemStacks) {
            spawnAsEntity(world, pos, itemStack);
        }

        // Remove the cable from this network if it exists
        Network network = getPartContainer(world, pos).getNetwork();
        if(network != null) {
            network.removeCable(this, createPathElement(world, pos));
        }

        super.onPreBlockDestroyed(world, pos);
    }

    @Override
    protected void onPostBlockDestroyed(World world, BlockPos pos) {
        super.onPostBlockDestroyed(world, pos);
        triggerNeighbourConnections(world, pos);

        // Reinit neighbouring networks.
        for(EnumFacing side : EnumFacing.VALUES) {
            if(!world.isRemote) {
                BlockPos sidePos = pos.offset(side);
                Block block = world.getBlockState(sidePos).getBlock();
                if(block instanceof ICableConnectable) {
                    Network.initiateNetworkSetup((ICableConnectable<CablePathElement>) block, world, sidePos).initialize();
                }
            }
        }
    }

    @Override
    public boolean hasDynamicModel() {
        return true;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IBakedModel createDynamicModel() {
        return new CableModel();
    }

    @Override
    public boolean canConnect(World world, BlockPos selfPosition, ICableConnectable connector, EnumFacing side) {
        TileMultipartTicking tile = TileHelpers.getSafeTile(world, selfPosition);
        if(tile != null) {
            return !tile.isForceDisconnected(side);
        }
        return true;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean isFullCube() {
        return false;
    }

    @Override
    public boolean isNormalCube() {
        return false;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public EnumWorldBlockLayer getBlockLayer() {
        return EnumWorldBlockLayer.TRANSLUCENT;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void addCollisionBoxesToList(World world, BlockPos pos, IBlockState state, AxisAlignedBB axisalignedbb, List list, Entity collidingEntity) {
        // The center collision box of the cable which is always present.
        setBlockBounds(CableModel.MIN, CableModel.MIN, CableModel.MIN,
                       CableModel.MAX, CableModel.MAX, CableModel.MAX);
        super.addCollisionBoxesToList(world, pos, state, axisalignedbb, list, collidingEntity);

        // The boxes for the 6 sides if they are connected
        for(EnumFacing side : EnumFacing.values()) {
            if(isConnected(world, pos, side)) {
                setBlockBounds(getCableBoundingBox(side));
                super.addCollisionBoxesToList(world, pos, state, axisalignedbb, list, collidingEntity);
            }
        }

        // Reset the bounding box to prevent any entity glitches.
        setBlockBounds(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public AxisAlignedBB getSelectedBoundingBox(World world, BlockPos pos) {
        RayTraceResult rayTraceResult = doRayTrace(world, pos, Minecraft.getMinecraft().thePlayer);
        if (rayTraceResult != null && rayTraceResult.boundingBox != null) {
            AxisAlignedBB box = rayTraceResult.boundingBox;
            return box.offset(pos.getX(), pos.getY(), pos.getZ());
        }
        // Happens when client hovers away from a block.
        return super.getSelectedBoundingBox(world, pos).expand(-0.625F, -0.625F, -0.625F);
    }

    /**
     * Do a ray trace for the current look direction of the player.
     * @param world The world.
     * @param pos The block position to perform a ray trace for.
     * @param player The player.
     * @return A holder object with information on the ray tracing.
     */
    public RayTraceResult doRayTrace(World world, BlockPos pos, EntityPlayer player) {
        double reachDistance;
        if (player instanceof EntityPlayerMP) {
            reachDistance = ((EntityPlayerMP) player).theItemInWorldManager.getBlockReachDistance();
        } else {
            reachDistance = 5;
        }

        double eyeHeight = world.isRemote ? player.getEyeHeight(): player.getEyeHeight(); // Client removed :  - player.getDefaultEyeHeight()
        Vec3 lookVec = player.getLookVec();
        Vec3 origin = new Vec3(player.posX, player.posY + eyeHeight, player.posZ);
        Vec3 direction = origin.addVector(lookVec.xCoord * reachDistance, lookVec.yCoord * reachDistance, lookVec.zCoord * reachDistance);

        return doRayTrace(world, pos, origin, direction);
    }

    @Override
    public MovingObjectPosition collisionRayTrace(World world, BlockPos pos, Vec3 origin, Vec3 direction) {
        RayTraceResult raytraceResult = doRayTrace(world, pos, origin, direction);
        if (raytraceResult == null) {
            return null;
        } else {
            return raytraceResult.getMovingObjectPosition();
        }
    }

    private AxisAlignedBB getCableBoundingBox(EnumFacing side) {
        float min = CableModel.MIN;
        float max = CableModel.MAX;
        if (side == null) {
            return AxisAlignedBB.fromBounds(min, min, min, max, max, max).expand(0.002F, 0.002F, 0.002F);
        } else {
            float[] b = COLLISION_BOXES[side.ordinal()];
            return AxisAlignedBB.fromBounds(b[0], b[1], b[2], b[3], b[4], b[5]).expand(0.001F, 0.001F, 0.001F);
        }
    }

    private void doRayTraceForSide(World world, BlockPos pos, Vec3 origin, Vec3 direction, EnumFacing side,
                                   MovingObjectPosition[] hits, AxisAlignedBB[] boxes, EnumFacing[] sideHit) {
        int i = side == null ? 6 : side.ordinal();
        AxisAlignedBB bb = getCableBoundingBox(side);
        setBlockBounds(bb);
        boxes[i]   = bb;
        hits[i]    = super.collisionRayTrace(world, pos, origin, direction);
        sideHit[i] = side;
    }

    private RayTraceResult doRayTrace(World world, BlockPos pos, Vec3 origin, Vec3 direction) {
        // Perform a ray trace for all six sides.
        MovingObjectPosition[] hits = new MovingObjectPosition[7];
        AxisAlignedBB[] boxes = new AxisAlignedBB[7];
        EnumFacing[] sideHit = new EnumFacing[7];
        Arrays.fill(sideHit, null);
        for (EnumFacing side : EnumFacing.VALUES) {
            if(isConnected(world, pos, side)) {
                doRayTraceForSide(world, pos, origin, direction, side, hits, boxes, sideHit);
            }
        }
        // Perform a ray trace for the center
        doRayTraceForSide(world, pos, origin, direction, null, hits, boxes, sideHit);

        // Find the closest hit
        double minDistance = Double.POSITIVE_INFINITY;
        int minIndex = -1;
        for (int i = 0; i < hits.length; i++) {
            if (hits[i] != null) {
                double d = hits[i].hitVec.squareDistanceTo(origin);
                if (d < minDistance) {
                    minDistance = d;
                    minIndex = i;
                }
            }
        }

        // Reset bounds
        setBlockBounds(0, 0, 0, 1, 1, 1);

        if (minIndex != -1) {
            return new RayTraceResult(hits[minIndex], boxes[minIndex], sideHit[minIndex]);
        }
        return null;
    }

    private void setBlockBounds(AxisAlignedBB bounds) {
        setBlockBounds((float) bounds.minX, (float) bounds.minY, (float) bounds.minZ,
                       (float) bounds.maxX, (float) bounds.maxY, (float) bounds.maxZ);
    }

    @Override
    public Collection<INetworkElement> createNetworkElements(World world, BlockPos blockPos) {
        Set<INetworkElement> sidedElements = Sets.newHashSet();
        for(Map.Entry<EnumFacing, IPartType<?, ?>> entry : getPartContainer(world, blockPos).getParts().entrySet()) {
            sidedElements.add(entry.getValue().createNetworkElement(this, DimPos.of(world, blockPos), entry.getKey()));
        }
        return sidedElements;
    }

    @Override
    public IPartContainer getPartContainer(World world, BlockPos pos) {
        return (IPartContainer) world.getTileEntity(pos);
    }

    @Override
    public CablePathElement createPathElement(World world, BlockPos blockPos) {
        return new CablePathElement(this, DimPos.of(world, blockPos));
    }

    @Data
    static class RayTraceResult {
        private final MovingObjectPosition movingObjectPosition;
        private final AxisAlignedBB boundingBox;
        private final EnumFacing sideHit;

        @Override
        public String toString() {
            return String.format("RayTraceResult: %s", boundingBox == null ? "null" : boundingBox.toString());
        }
    }

}

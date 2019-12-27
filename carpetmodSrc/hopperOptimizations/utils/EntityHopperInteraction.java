package hopperOptimizations.utils;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityHopper;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class EntityHopperInteraction {

    public static final List<BlockPos> hopperLocationsToNotify = new ArrayList<>();
    //used to track when the rule was changed, incrementing makes all cached optimization states invalid
    public static int ruleUpdates = 0;
    public static boolean rememberHoppers = false;
    public static boolean checked = false;

    public static void notifyHoppers(Entity targetEntity) {
        if (!checked) {
            if (targetEntity.prevPosX != targetEntity.posX || targetEntity.prevPosY != targetEntity.posY || targetEntity.prevPosZ != targetEntity.posZ)
                findAndNotifyHoppers(targetEntity);
            rememberHoppers = false;
        } else {
            for (BlockPos pos : hopperLocationsToNotify) {
                TileEntity hopper = targetEntity.world.getTileEntity(pos);
                if (hopper instanceof TileEntityHopper) {
                    ((IHopper) hopper).notifyOfNearbyEntity(targetEntity);
                }
            }
            hopperLocationsToNotify.clear();
            rememberHoppers = false;
            checked = false;
        }
    }

    public static void findAndNotifyHoppers(Entity targetEntity) {
        checked = true;
        rememberHoppers = true;

        AxisAlignedBB box = targetEntity.getEntityBoundingBox();
        int minX, maxX, minY, maxY, minZ, maxZ;
        minX = (int) Math.floor(box.minX) - 1;
        minY = (int) Math.floor(box.minY) - 1;
        minZ = (int) Math.floor(box.minZ) - 1;
        maxX = (int) Math.ceil(box.maxX);
        maxY = (int) Math.ceil(box.maxY);
        maxZ = (int) Math.ceil(box.maxZ);

        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; ++x)
            for (int y = minY; y <= maxY; ++y)
                for (int z = minZ; z <= maxZ; ++z) {
                    blockPos.setPos(x, y, z);
                    IBlockState blockState = targetEntity.world.getBlockState(blockPos);
                    if (blockState.getBlock() == Blocks.HOPPER) {
                        hopperLocationsToNotify.add(blockPos.toImmutable());
                    }
                }

        notifyHoppers(targetEntity);
    }

    public static Boolean validate(Boolean newValue) {
        if (ruleUpdates != -1)
            ++ruleUpdates;
        return newValue;
    }
}

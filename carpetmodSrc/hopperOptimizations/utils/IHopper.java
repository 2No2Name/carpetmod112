package hopperOptimizations.utils;

import net.minecraft.entity.Entity;
import net.minecraft.inventory.IInventory;

public interface IHopper {

    static void markDirtyLikeHopperWould(IInventory inv, InventoryOptimizer opt) {
        boolean fakeSignalStrengthChange = opt.isOneItemAboveSignalStrength();
        if (fakeSignalStrengthChange) {
            //crazy workaround to send stupid comparator updates to comparators and make the comparators send updates to even more redstone components
            //also required for comparator to schedule useless but detectable updates on themselves
            opt.setFakeReducedSignalStrength();
            inv.setInventorySlotContents(0, inv.getStackInSlot(0));
            opt.clearFakeChangedSignalStrength();
        }

        inv.setInventorySlotContents(0, inv.getStackInSlot(0));
    }

    boolean tryShortcutFailedTransfer(InventoryOptimizer thisOpt, IInventory other, InventoryOptimizer otherOpt, boolean extracting);

    void setMarkOtherDirty();

    default void notifyOfNearbyEntity(Entity entity) {
    }
}

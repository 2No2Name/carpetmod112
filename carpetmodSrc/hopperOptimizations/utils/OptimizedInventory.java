package hopperOptimizations.utils;

import net.minecraft.inventory.IInventory;

import javax.annotation.Nullable;

public interface OptimizedInventory extends IInventory {
    @Nullable
    InventoryOptimizer getOptimizer();

    void invalidateOptimizer(); //For player actions (probably many uncontrolled actions, can be fixed if neccessary)

    boolean mayHaveOptimizer(); //True when no player is looking into the inventory


    //Only used for chests, cached double inventories can check whether they are still up to date.
    default boolean isStillValid() {
        return true;
    }

    default int getInvalidCount() {
        return -1;
    }
}

package hopperOptimizations.utils;

import carpet.CarpetServer;
import carpet.CarpetSettings;
import javafx.geometry.Pos;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityShulkerBox;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

//Don't store instances of InventoryOptimizer, unless you sync with the corresponding inventory!
//Instances may suddenly become invalid due to unloading or turning the setting off
public class InventoryOptimizer {

    private static final Logger LOGGER = LogManager.getLogger();


    //256 Bit bloom filter //false positive rates estimated with https://hur.st/bloomfilter/?n=27&p=&m=256&k=
    //Decided on going for a high estimate, as a larger bloom filter probably won't be a lag causer, but can still help
    //Estimate useful for 27 slot inventory
    private static final int hashBits = 6;
    private static final int maskLength = 8;
    private static final long mask = (1L << maskLength) - 1;
    private static final int filterLongCount = Math.max(1, (1 << maskLength) / 64); //256 bit total, 8 bits can address it

    //todo(unneccesary) recalculate Filters when 180+ Bits are set -> ~12% chance that negative filters as false positive
    //maybe use usage stats for this as well
    //todo(unneccesary) cache previous firstFreeSlot location in case of the firstFreeSlot jumping around, for preEmptyBloomFilter

    //private static boolean debugOptimizedInventories = false; //nonfinal to be able to change with debugger

    private final InventoryListOptimized<ItemStack> stackList;
    private final ISidedInventory sidedInventory; //only use when required, inventory handling should be mostly independent from the container
    private final boolean itemRestrictions;
    private int inventoryChanges;
    private long[] bloomFilter = new long[filterLongCount];
    private long[] nonFullStackBloomFilter = new long[filterLongCount];
    private long[] preEmptyNonFullStackBloomFilter = new long[filterLongCount];

    private int filterHits;
    private int filterMisses;
    private int filterTrueHits;
    private int filterEdits;

    private int occupiedSlots;
    private int fullSlots;
    private int totalSlots;
    private int firstFreeSlot;
    private int firstOccupiedSlot;
    private int weightedItemCount;

    private int fakeSignalStrength;
    private Map<Integer, Integer> stackSizeToSlotCount = new HashMap<>();

    private boolean initialized;
    private boolean invalid;

    private IInventory entity; //only for debug purposes

    public InventoryOptimizer(InventoryListOptimized<ItemStack> stackList, IInventory inventory) {
        this.stackList = stackList;
        this.entity = inventory;
        this.sidedInventory = inventory instanceof ISidedInventory ? (ISidedInventory) inventory : null;
        this.itemRestrictions = this.sidedInventory != null;

        if (this.itemRestrictions && !(this.sidedInventory instanceof TileEntityShulkerBox))
            //Shulkerbox restrictions are slot independent.
            //Slot dependent restrictions aren't checked atm, since there is no large inventory that has those.
            //OptimizedInventory doesn't seem viable for small inventories.
            throw new UnsupportedOperationException("Implement OptimizedInventory with more complex item insert conditions before using those.");

        this.initialized = false;
        this.invalid = false;
        if (stackList == null) return;
        inventoryChanges = 0;
        filterHits = 0;
        filterMisses = 0;
        filterTrueHits = 0;
        filterEdits = 0;

        fakeSignalStrength = -1;
    }

    private static long hash(ItemStack stack) {
        //Empty and Unstackables don't go into the bloom filter
        if (stack.isEmpty() || stack.getMaxStackSize() <= 1) return 0;
        long hash = mix((long) stack.getItem().hashCode());
        hash ^= mix((long) stack.getItemDamage());
        hash ^= mix((long) Objects.hashCode(stack.getTagCompound()));
        return hash == 0 ? 1 : hash;
    }

    private static long mix(long a){
        //todo check whether this has bad behaviors
        return a;
    }

    private static boolean areItemsAndTagsEqual(ItemStack a, ItemStack b) {
        if (!ItemStack.areItemsEqual(a, b)) return false;
        return ItemStack.areItemStackTagsEqual(a, b);
    }



    public void setInvalid() {
        this.invalid = true;
    }

    public boolean isInvalid() {
        return this.invalid;
    }

    private void consistencyCheck() {
        //this is code from recalculate, but instead of changing anything, we just check if the results are conflicting
        if (!initialized) return;
        try {
        //printStackTrace = false;
        int occupiedSlots = 0;
        int fullSlots = 0;
        int firstFreeSlot = -1;
        int firstOccupiedSlot = -1;
        int totalSlots = size();
            int weightedItemCount = 0;
            Map<Integer, Integer> stackSizeToSlotCount = new HashMap<>();


        for (int i = 0; i < totalSlots; i++) {
            ItemStack stack = getSlot(i);
            long hash = hash(stack);
            if (hash != 0 && !filterContains(bloomFilter, hash))
                throw new IllegalStateException("Itemstack not in bloom filter: " + stack.toString());

            if (!stack.isEmpty()) {
                weightedItemCount += stack.getCount() * (int) (64F / stack.getMaxStackSize());
                stackSizeToSlotCount.put(stack.getMaxStackSize(), stackSizeToSlotCount.getOrDefault(stack.getMaxStackSize(), 0) + 1);

                if (firstOccupiedSlot < 0)
                    firstOccupiedSlot = i;
                occupiedSlots++;
                if (stack.getCount() >= stack.getMaxStackSize()) fullSlots++;
                else if (hash != 0 && !filterContains(nonFullStackBloomFilter, hash))
                    throw new IllegalStateException("Itemstack not in nonFull bloom filter: " + stack.toString());

            } else if (firstFreeSlot < 0) {
                firstFreeSlot = i;
                for (int j = 0; j < firstFreeSlot; j++) {
                    ItemStack stack1 = getSlot(j);
                    if (stack1.getCount() < stack1.getMaxStackSize()) {
                        long hash1 = hash(stack1);
                        if (hash1 != 0 && !filterContains(preEmptyNonFullStackBloomFilter, hash1))
                            throw new IllegalStateException("Itemstack not in preEmptyNonFull bloom filter: " + stack1.toString());
                    }
                }
            }
        }
        if (this.occupiedSlots != occupiedSlots)
            throw new IllegalStateException("occupied slots wrong");
        if (this.fullSlots != fullSlots)
            throw new IllegalStateException("full slots wrong");
        if (this.firstFreeSlot != firstFreeSlot)
            throw new IllegalStateException("first free slot wrong");
        if (this.firstOccupiedSlot != firstOccupiedSlot)
            throw new IllegalStateException("first occupied slot wrong");

            int signal1 = MathHelper.floor(this.weightedItemCount / ((float) this.totalSlots * 64) * 14 + (occupiedSlots == 0 ? 0 : 1));
            int signal2 = calculateComparatorOutput();
            if (this.weightedItemCount != weightedItemCount || signal1 != signal2)
                throw new IllegalStateException("comparator output wrong");
            if (!this.stackSizeToSlotCount.equals(stackSizeToSlotCount))
                throw new IllegalStateException("stacksize slot counts wrong");


        //System.out.println(this.stackList.toString());
        //printStackTrace = true;
        } catch (IllegalStateException e) {
            initialized = false;
            LOGGER.warn("Detected broken optimizer ( " + e.getMessage() + ") at " + Arrays.toString(e.getStackTrace()));
            if (this.entity != null) {
                BlockPos pos = this.entity instanceof TileEntity ? ((TileEntity) this.entity).getPos() : null ;
                String data = this.entity instanceof TileEntity ? ((TileEntity) this.entity).writeToNBT(new NBTTagCompound()).toString() : "not TE";
                LOGGER.warn("Affected Inventory: " + this.entity.getClass().getName() +  " at " +
                        (pos != null ? pos.toString() : "not TE") +
                        " with data " + data + " with optimizer " + this.toString());
            }
        }
    }

    private int calculateComparatorOutput() {
        int int_1 = 0;
        float float_1 = 0.0F;

        for (int int_2 = 0; int_2 < stackList.size(); ++int_2) {
            ItemStack itemStack_1 = stackList.get(int_2);
            if (!itemStack_1.isEmpty()) {
                float_1 += (float) itemStack_1.getCount() / (float) Math.min(64, itemStack_1.getMaxStackSize());
                ++int_1;
            }
        }

        float_1 /= (float) stackList.size();
        return MathHelper.floor(float_1 * 14.0F) + (int_1 > 0 ? 1 : 0);
    }

    public void onItemStackCountChanged(int index, int countChange) {
        if (!initialized) return;

        //assume never increasing count of empty stack //todo logic here

        ItemStack itemStack = getSlot(index);
        int count = itemStack.getCount();

        itemStack.setCount(1);
        ItemStack prevStack = itemStack.copy();
        itemStack.setCount(count);

        prevStack.setCount(count - countChange);

        boolean wasEmpty = prevStack.isEmpty();
        int max = itemStack.getMaxStackSize();
        boolean isEmpty = itemStack.isEmpty();

        if (!wasEmpty || !isEmpty) update(index, prevStack);
        else {
            weightedItemCount += countChange * (int) (64F / max);
            inventoryChanges++;
            if (CarpetSettings.debugOptimizedInventories) consistencyCheck();
        }
    }

    protected ItemStack getSlot(int index) {
        return this.stackList.get(index);
    }

    protected int size() {
        return this.stackList.size();
    }

    public int getInventoryChangeCount() {
        return inventoryChanges;
    }

    public int getOccupiedSlots() {
        if (!initialized) recalculate();
        return occupiedSlots;
    }

    /**
     * Find the first slot that a hopper can take items from.
     *
     * @return index of the first occupied slot a hopper can take from, -1 if none
     */
    public int getFirstOccupiedSlot_extractable() {
        if (!initialized) recalculate();
        return firstOccupiedSlot;
    }

    //old approach: something stupid about conservatively invalidating the bloomfilter everytime the inventory was accessed (bad idea)
    //new approach: assume that nothing besides players and hoppers/droppers change inventory contents
    //control their inventory accesses, notify of the inventory of hidden stacksize changes (see HopperBlockEntityMixin and InventoriesMixin)
    /*/**
     * Remembers that an item escaped to an unknown context. Going to assume its hash and count may change immediately, but not later again.
     * This assumption may lead to incorrect results
     * @param slot Location of the escaped Item
     */
    /*void markEscaped(int slot){
        //possiblyOutdatedSlots |= (1 << slot);
    }//*/

    /**
     * Update the bloom filter after a slot has been modified.
     *
     * @param slot Index of the modified slot
     */
    void update(int slot, ItemStack prevStack) {
        if (!initialized) return;
        inventoryChanges++;


        int oldFirstFreeSlot = firstFreeSlot;

        ItemStack newStack = stackList.get(slot);
        long hash = hash(newStack);
        filterAdd(bloomFilter, hash);
        boolean flagRecalcFree = false;
        boolean flagRecalcOccupied = false;

        int prevC, prevMaxC, newC, newMaxC;

        if ((prevC = prevStack.getCount()) >= (prevMaxC = prevStack.getMaxStackSize())) {
            --fullSlots;
        }
        if ((newC = newStack.getCount()) >= (newMaxC = newStack.getMaxStackSize())) {
            ++fullSlots;
        } else {
            //In case of empty stack, filters are unchanged, otherwise add to according filters
            filterAdd(nonFullStackBloomFilter, hash);
            if (slot < firstFreeSlot)
                filterAdd(preEmptyNonFullStackBloomFilter, hash);
        }
        this.weightedItemCount -= prevC * (int) (64F / prevMaxC) - newC * (int) (64F / newMaxC);


        if (!prevStack.isEmpty()) {
            --occupiedSlots;
            int c = stackSizeToSlotCount.getOrDefault(prevMaxC, 0);
            if (c != 1)
                stackSizeToSlotCount.replace(prevMaxC, c - 1);
            else
                stackSizeToSlotCount.remove(prevMaxC);
        }
        if (!newStack.isEmpty()) {
            ++occupiedSlots;
            stackSizeToSlotCount.put(newMaxC, stackSizeToSlotCount.getOrDefault(newMaxC, 0) + 1);

            firstOccupiedSlot = firstOccupiedSlot > slot || firstOccupiedSlot == -1 ? slot : firstOccupiedSlot;
            if (firstFreeSlot == slot)
                flagRecalcFree = true;
        } else {
            firstFreeSlot = firstFreeSlot > slot || firstFreeSlot == -1 ? slot : firstFreeSlot;
            if (slot == firstOccupiedSlot)
                flagRecalcOccupied = true;
        }
        if (oldFirstFreeSlot > firstFreeSlot || flagRecalcFree || flagRecalcOccupied)
            recalcFirstFreeAndOccupiedSlots(oldFirstFreeSlot, flagRecalcFree, flagRecalcOccupied);


        if (CarpetSettings.debugOptimizedInventories) consistencyCheck();
    }

    private void recalcFirstFreeAndOccupiedSlots(int oldFirstFreeSlot, boolean flagRecalcFree, boolean flagRecalcOccupied) {
        this.totalSlots = size();
        if (flagRecalcOccupied)
            firstOccupiedSlot = -1;
        if (flagRecalcFree)
            firstFreeSlot = -1;

        for (int i = 0; i < totalSlots && ((firstFreeSlot == -1 && flagRecalcFree) || (firstOccupiedSlot == -1 && flagRecalcOccupied)); i++) {
            ItemStack stack = getSlot(i);
            if (!stack.isEmpty()) {
                if (firstOccupiedSlot < 0)
                    firstOccupiedSlot = i;
            } else if (firstFreeSlot < 0)
                firstFreeSlot = i;
        }

        if (oldFirstFreeSlot < firstFreeSlot) {// || firstFreeSlot == -1){
            if (oldFirstFreeSlot == -1) oldFirstFreeSlot = 0;
            for (int i = oldFirstFreeSlot; i < totalSlots && (i < firstFreeSlot /*|| firstFreeSlot == -1*/); i++) {
                ItemStack itemStack = getSlot(i);
                if (!itemStack.isEmpty() && !(itemStack.getMaxStackSize() <= itemStack.getCount()))
                    filterAdd(preEmptyNonFullStackBloomFilter, hash(itemStack));
            }
        }
    }

    public int getSignalStrength() {
        if (fakeSignalStrength != -1) {
            return fakeSignalStrength;
        }
        if (!initialized) recalculate();
        return (int) ((this.weightedItemCount / ((float) this.totalSlots * 64)) * 14) + (occupiedSlots == 0 ? 0 : 1);
    }

    //Used to trick comparators into sending block updates like in vanilla.
    public void setFakeReducedSignalStrength() {
        this.fakeSignalStrength = this.getSignalStrength() - 1;
        if (fakeSignalStrength == -1) fakeSignalStrength = 0;
    }

    public void clearFakeChangedSignalStrength() {
        this.fakeSignalStrength = -1;
    }

    public boolean isOneItemAboveSignalStrength() {
        int maxExtractableItemWeight = 0;
        for (Map.Entry<Integer, Integer> entry : stackSizeToSlotCount.entrySet())
            if (entry.getValue() > 0 && entry.getKey() > maxExtractableItemWeight)
                maxExtractableItemWeight = entry.getKey();

        boolean wouldBeEmpty = occupiedSlots == 0 || this.weightedItemCount <= maxExtractableItemWeight;
        int minOneLessItemSignalStrength = (int) ((this.weightedItemCount - maxExtractableItemWeight / ((float) this.totalSlots * 64)) * 14) + (wouldBeEmpty ? 0 : 1);

        return minOneLessItemSignalStrength != this.getSignalStrength();
    }

    private void clearFilters() {
        for (int i = 0; i < filterLongCount; i++) {
            bloomFilter[i] = 0;
            nonFullStackBloomFilter[i] = 0;
            preEmptyNonFullStackBloomFilter[i] = 0;
        }

        if (filterEdits > 0)
            System.out.println("Filterstats: Edits: " + filterEdits + " Misses: " + filterMisses + " Hits: " + filterHits + " TrueHits " + filterTrueHits);
    }

    public void recalculate() {
        //printStackTrace = false;

        clearFilters();

        int occupiedSlots = 0;
        int fullSlots = 0;
        int firstFreeSlot = -1;
        int firstOccupiedSlot = -1;
        this.weightedItemCount = 0;
        this.totalSlots = size();
        stackSizeToSlotCount.clear();

        for (int i = 0; i < totalSlots; i++) {
            ItemStack stack = getSlot(i);
            long hash = hash(stack);
            filterAdd(bloomFilter, hash);
            if (!stack.isEmpty()) {
                this.weightedItemCount += stack.getCount() * (int) (64F / stack.getMaxStackSize());
                stackSizeToSlotCount.put(stack.getMaxStackSize(), stackSizeToSlotCount.getOrDefault(stack.getMaxStackSize(), 0) + 1);

                if (firstOccupiedSlot < 0)
                    firstOccupiedSlot = i;
                occupiedSlots++;
                if (stack.getCount() >= stack.getMaxStackSize()) fullSlots++;
                else filterAdd(nonFullStackBloomFilter, hash);
            } else if (firstFreeSlot < 0) {
                firstFreeSlot = i;
                for (int j = 0; j < firstFreeSlot; j++) {
                    ItemStack stack1 = getSlot(j);
                    if (stack1.getCount() < stack1.getMaxStackSize()) {
                        long hash1 = hash(stack1);
                        filterAdd(preEmptyNonFullStackBloomFilter, hash1);
                    }
                }
            }
        }
        this.occupiedSlots = occupiedSlots;
        this.fullSlots = fullSlots;
        this.firstFreeSlot = firstFreeSlot;
        this.firstOccupiedSlot = firstOccupiedSlot;

        this.initialized = true;
        //printStackTrace = true;
    }

    public int getFirstFreeSlot() {
        if (!initialized) recalculate();
        return firstFreeSlot;
    }

    public boolean isFull_insertable(EnumFacing fromDirection) {
        if (!initialized) recalculate();
        return fullSlots >= totalSlots;
    }

    //Does not support unstackable items!
    private boolean maybeContains(ItemStack stack) {
        if (!initialized) recalculate();

        if (stack.isEmpty()) return getFirstFreeSlot() >= 0;
        if (occupiedSlots == 0) return false;
        long hash = hash(stack);

        boolean ret = filterContains(bloomFilter, hash);
        if (ret)
            filterHits++;
        else
            filterMisses++;
        return ret;
    }

    private boolean maybeContainsNonFullStack_insertable(ItemStack stack, EnumFacing fromDirection) {
        if (!initialized) recalculate();

        if (stack.isEmpty()) return getFirstFreeSlot() >= 0;
        if (occupiedSlots == 0) return false;
        if (isFull_insertable(fromDirection)) return false;
        boolean ret = filterContains(nonFullStackBloomFilter, hash(stack));
        if (ret)
            filterHits++;
        else
            filterMisses++;
        return ret;
    }

    private boolean canMaybeInsert(ItemStack stack, EnumFacing fromDirection) {
        if (!initialized) recalculate();

        if (hasFreeSlots_insertable()) return true;
        if (isFull_insertable(fromDirection)) return false;
        return maybeContainsNonFullStack_insertable(stack, fromDirection);
    }

    private boolean filterContains(long[] bloomFilter, long hash) {
        if (hash == 0) return false;

        boolean ret = true; //becomes false if any of the corresponding bits is not set
        //Use the lowest maskLength bits of the hash, hashBits times checking a bit in the filter
        for (int i = 0; i < hashBits && ret; ++i) {
            long hIndex = (hash & (mask << (i * maskLength))) >> (i * maskLength);
            ret = (bloomFilter[((int) hIndex) / 64] & (1L << (hIndex % 64))) != 0;
        }
        return ret;
    }

    private void filterAdd(long[] bloomFilter, long hash) {
        if (hash == 0) return;
        filterEdits++;
        //Use the lowest maskLength bits of the hash hashBits times to set a bit in the filter
        for (int i = 0; i < hashBits; ++i) {
            long hIndex = (hash & (mask << (i * maskLength))) >> (i * maskLength);
            bloomFilter[((int) hIndex) / 64] |= (1L << (hIndex % 64));
        }
    }

    /**
     * Finds the first slot that matches stack and can be sucked by a hopper.
     *
     * @param stack to find a matching item for
     * @return index of the matching item, -1 if none found.
     */
    private int indexOf_extractable(ItemStack stack) {
        if (!initialized) recalculate();
        return indexOf_extractable_endIndex(stack, totalSlots);
    }

    //Does not support unstackable items!
    public int indexOf_extractable_endIndex(ItemStack stack, int stop) {
        if (!initialized) recalculate();
        if (stop == -1) stop = this.totalSlots;
        if (stack.isEmpty()) return -1;
        if (!maybeContains(stack)) return -1;
        for (int i = firstOccupiedSlot; i < stop; i++) {
            ItemStack slot = getSlot(i);
            if (areItemsAndTagsEqual(stack, slot)) {
                filterTrueHits++;
                return i;
            }
        }
        return -1;
    }

    public boolean hasFreeSlots_insertable() {
        if (!initialized) recalculate();
        return getFirstFreeSlot() >= 0;
    }

    public int findInsertSlot(ItemStack stack, EnumFacing fromDirection) {
        if (!initialized) recalculate();

        //Empty slot available? Check for non full stacks before the empty slot.
        int firstFreeSlot = getFirstFreeSlot();
        if ((firstFreeSlot == 0 || stack.getMaxStackSize() == 1))
            return this.itemRestrictions && !this.sidedInventory.canInsertItem(firstFreeSlot, stack, fromDirection) ? -1 : firstFreeSlot;
        else if (firstFreeSlot > 0) {
            long hash = hash(stack);
            if (filterContains(preEmptyNonFullStackBloomFilter, hash)) {
                filterHits++;

                for (int i = 0; i < firstFreeSlot; i++) {
                    ItemStack slot = getSlot(i);
                    if (slot.getCount() >= slot.getMaxStackSize()) continue;
                    if (areItemsAndTagsEqual(stack, slot)) {
                        filterTrueHits++;
                        return i;
                    }
                }
            } else filterMisses++;
            return firstFreeSlot;
        }

        //No empty Slot, search everything if there may be a fitting non full stack
        if (!maybeContainsNonFullStack_insertable(stack, fromDirection)) return -1;

        int start = 0;
        for (int i = start; i < totalSlots; i++) {
            ItemStack slot = getSlot(i);
            if (slot.getCount() >= slot.getMaxStackSize()) continue;
            if (areItemsAndTagsEqual(stack, slot)) {
                filterTrueHits++;
                return i;
            }
        }
        return -1;
    }
}

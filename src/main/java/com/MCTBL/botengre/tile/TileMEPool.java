package com.MCTBL.botengre.tile;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.DimensionalCoord;
import appeng.me.GridAccessException;
import appeng.tile.TileEvent;
import appeng.tile.events.TileEventType;
import appeng.tile.grid.AENetworkTile;
import vazkii.botania.api.BotaniaAPI;
import vazkii.botania.api.recipe.RecipeManaInfusion;

/*
 * Author : MCTBL
 * Time : 2024-10-06 10:55:48
 */

public class TileMEPool extends AENetworkTile implements ICraftingProvider {

    private final MachineSource source;
    private static ArrayList<ItemStack> fakePatterns = new ArrayList<>();
    private boolean isCrafting = false;
    private int craftingTickCounter = 0;
    private Queue<ICraftingPatternDetails> craftingPatterns = new LinkedList<>();

    public TileMEPool() {
        this.getProxy()
            .setFlags(GridFlags.REQUIRE_CHANNEL);
        this.getProxy()
            .setIdlePowerUsage(80d);
        this.source = new MachineSource(this);
    }

    private void initPatterns() {
        for (RecipeManaInfusion recipe : BotaniaAPI.manaInfusionRecipes) {
            ItemStack pattern = AEApi.instance()
                .definitions()
                .items()
                .encodedPattern()
                .maybeStack(1)
                .orNull();
            if (recipe.getInput() instanceof ItemStack inputstack
                && !ItemStack.areItemStacksEqual(inputstack, recipe.getOutput())) {
                ItemStack output = recipe.getOutput();
                PatternHelper.writeBotaniaPattern(recipe.getManaToConsume(), inputstack, output, pattern);
                fakePatterns.add(pattern);
            }
        }
    }

    @Override
    public boolean pushPattern(final ICraftingPatternDetails patternDetails, final InventoryCrafting table) {
        if (this.craftingPatterns.size() <= 16) {
            this.craftingPatterns.add(patternDetails);
            return true;
        }
        return false;
    }

    private void doCrafting() {
        try {
            IStorageGrid storageGrid = this.getProxy()
                .getStorage();

            ICraftingPatternDetails pd;
            while ((pd = this.craftingPatterns.peek()) != null) {
                int manaConsume = pd.getPattern()
                    .getTagCompound()
                    .getInteger("mana");
                for (final IGridNode machine : this.getProxy()
                    .getGrid()
                    .getMachines(TileSparkBindingPoint.class)) {
                    DimensionalCoord location = machine.getGridBlock()
                        .getLocation();
                    if (machine.getWorld()
                        .getTileEntity(location.x, location.y, location.z) instanceof TileSparkBindingPoint tsbp) {

                        if (tsbp.getCurrentMana() >= manaConsume) {
                            boolean rejected = false;
                            for (IAEItemStack output : pd.getOutputs()) {
                                IAEItemStack rejectedResult = storageGrid.getItemInventory()
                                    .injectItems(output, Actionable.SIMULATE, this.source);
                                if ((rejectedResult != null) && (rejectedResult.getStackSize() > 0)) {
                                    rejected = true;
                                    break;
                                }
                            }
                            if (!rejected) {
                                // Inject into the network
                                for (IAEItemStack output : pd.getOutputs()) {
                                    storageGrid.getItemInventory()
                                        .injectItems(output, Actionable.MODULATE, this.source);
                                }
                                tsbp.recieveMana(-manaConsume);
                                this.craftingPatterns.poll();
                            }
                        } else {
                            return;
                        }
                    }
                }

            }
        } catch (GridAccessException e) {
            // XD
        }
    }

    @TileEvent(TileEventType.TICK)
    public void onTick() {
        if (!this.craftingPatterns.isEmpty() && ++this.craftingTickCounter >= 5) {
            this.craftingTickCounter = 0;
            this.doCrafting();
            this.markForUpdate();
        }
    }

    @Override
    public boolean isBusy() {
        return this.isCrafting;
    }

    @Override
    public void provideCrafting(final ICraftingProviderHelper craftingTracker) {
        if (fakePatterns.isEmpty()) this.initPatterns();
        for (ItemStack pattern : fakePatterns) {
            if (pattern.getItem() instanceof ICraftingPatternItem cpi) {
                craftingTracker.addCraftingOption(this, cpi.getPatternForItem(pattern, this.getWorldObj()));
            }
        }
    }

    static class PatternHelper {

        private static final String NBTKEY_AE_IN = "in", NBTKEY_AE_OUT = "out", NBTKEY_AE_ISCRAFTING = "crafting",
            NBTKEY_AE_CAN_SUB = "substitute", NBTKEY_MANA_REQUIRE = "mana";

        static void writeBotaniaPattern(final int mana, final ItemStack input, final ItemStack output,
            final ItemStack pattern) {
            writePattern(input, output, pattern);
            NBTTagCompound tagCompound = pattern.getTagCompound();
            tagCompound.setInteger(NBTKEY_MANA_REQUIRE, mana);
        }

        static void writePattern(final ItemStack input, final ItemStack output, final ItemStack pattern) {
            // Create a new tag
            NBTTagCompound data = new NBTTagCompound();

            // Write the input
            NBTTagList inTags = new NBTTagList();
            inTags.appendTag(input.writeToNBT(new NBTTagCompound()));

            // Write the outputs
            NBTTagList outTags = new NBTTagList();
            outTags.appendTag(output.writeToNBT(new NBTTagCompound()));

            // Write the basics
            data.setBoolean(NBTKEY_AE_CAN_SUB, false);
            data.setBoolean(NBTKEY_AE_ISCRAFTING, false);

            // Write the lists
            data.setTag(NBTKEY_AE_IN, inTags);
            data.setTag(NBTKEY_AE_OUT, outTags);

            // Save into the item
            pattern.setTagCompound(data);
        }

        static void clearPattern(final ItemStack pattern) {
            pattern.setTagCompound(null);
        }
    }
}

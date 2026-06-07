package com.bedrockbet;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Item value table for the betting system.
 * Players bet by holding items — the plugin converts them to a point value.
 * Winners receive items back based on their payout value.
 */
public class ItemValues {

    // Ordered from most valuable to least (used for payout change-making)
    private static final LinkedHashMap<Material, Integer> VALUES = new LinkedHashMap<>();

    static {
        // Rare items
        VALUES.put(Material.NETHERITE_INGOT, 500);
        VALUES.put(Material.DIAMOND, 100);
        VALUES.put(Material.EMERALD, 50);

        // Mid-tier
        VALUES.put(Material.GOLD_INGOT, 10);
        VALUES.put(Material.IRON_INGOT, 5);
        VALUES.put(Material.LAPIS_LAZULI, 3);
        VALUES.put(Material.COPPER_INGOT, 2);

        // Common
        VALUES.put(Material.COAL, 1);
    }

    /**
     * Returns the per-unit value for a material, or 0 if not accepted.
     */
    public static int getValue(Material material) {
        return VALUES.getOrDefault(material, 0);
    }

    /**
     * Returns the total value of items held in the player's main hand.
     */
    public static int getHeldValue(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) return 0;
        int perUnit = getValue(held.getType());
        return perUnit * held.getAmount();
    }

    /**
     * Takes items worth at least `value` from the player's main hand.
     * Returns the actual value taken, or 0 if not enough.
     * Must be called from the main thread.
     */
    public static int takeFromHand(Player player, int value) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR) return 0;

        int perUnit = getValue(held.getType());
        if (perUnit <= 0) return 0;

        int needed = (int) Math.ceil((double) value / perUnit);
        if (held.getAmount() < needed) return 0;

        int actualValue = needed * perUnit;

        // Remove items from hand
        if (held.getAmount() == needed) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(held.getAmount() - needed);
        }

        return actualValue;
    }

    /**
     * Gives items worth `value` to the player using the most valuable denominations.
     * Change-making algorithm: greedy from highest to lowest value.
     * Must be called from the main thread.
     */
    public static void giveItems(Player player, int value) {
        int remaining = value;

        for (Map.Entry<Material, Integer> entry : VALUES.entrySet()) {
            if (remaining <= 0) break;

            Material mat = entry.getKey();
            int perUnit = entry.getValue();

            int count = remaining / perUnit;
            if (count <= 0) continue;

            remaining -= count * perUnit;

            // Give in stacks of 64
            while (count > 0) {
                int stackSize = Math.min(count, 64);
                ItemStack stack = new ItemStack(mat, stackSize);

                // Try to add to inventory, drop on ground if full
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                for (ItemStack dropped : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                }

                count -= stackSize;
            }
        }
    }

    /**
     * Returns the full value table for display purposes.
     */
    public static LinkedHashMap<Material, Integer> getValueTable() {
        return new LinkedHashMap<>(VALUES);
    }

    /**
     * Formats item name for display (e.g. DIAMOND -> Diamond).
     */
    public static String formatName(Material mat) {
        String name = mat.name().toLowerCase().replace('_', ' ');
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}

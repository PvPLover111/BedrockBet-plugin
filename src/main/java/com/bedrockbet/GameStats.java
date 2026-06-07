package com.bedrockbet;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cumulative statistics for all events
 */
public class GameStats {

    // Player deaths
    private final Map<String, AtomicInteger> playerDeaths = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> playerKills = new ConcurrentHashMap<>();

    // Detailed PvP kills (killer -> victim -> count)
    private final Map<String, Map<String, AtomicInteger>> pvpKills = new ConcurrentHashMap<>();

    // Mob kills (player -> mob -> count)
    private final Map<String, Map<String, AtomicInteger>> mobKills = new ConcurrentHashMap<>();

    // Mob deaths (total by type)
    private final Map<String, AtomicInteger> mobDeaths = new ConcurrentHashMap<>();

    // Blocks (player -> block -> count)
    private final Map<String, Map<String, AtomicInteger>> blocksPlaced = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AtomicInteger>> blocksBroken = new ConcurrentHashMap<>();

    // Items (player -> item -> count)
    private final Map<String, Map<String, AtomicInteger>> itemsPickedUp = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AtomicInteger>> itemsDropped = new ConcurrentHashMap<>();

    // Movement (player -> blocks traveled)
    private final Map<String, AtomicInteger> blocksTraveled = new ConcurrentHashMap<>();

    // Global counters
    private final AtomicInteger totalDeaths = new AtomicInteger(0);
    private final AtomicInteger totalMobKills = new AtomicInteger(0);
    private final AtomicInteger totalBlocksPlaced = new AtomicInteger(0);
    private final AtomicInteger totalBlocksBroken = new AtomicInteger(0);

    // ==================== RECORDING STATISTICS ====================

    public void recordPlayerDeath(String player, String killer, String killerType) {
        playerDeaths.computeIfAbsent(player, k -> new AtomicInteger(0)).incrementAndGet();
        totalDeaths.incrementAndGet();

        if ("player".equals(killerType) && killer != null && !killer.equals(player)) {
            playerKills.computeIfAbsent(killer, k -> new AtomicInteger(0)).incrementAndGet();

            // Detailed PvP statistics
            pvpKills.computeIfAbsent(killer, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(player, k -> new AtomicInteger(0)).incrementAndGet();
        }
    }

    public void recordMobKill(String player, String mobType) {
        mobKills.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(mobType, k -> new AtomicInteger(0)).incrementAndGet();
        mobDeaths.computeIfAbsent(mobType, k -> new AtomicInteger(0)).incrementAndGet();
        totalMobKills.incrementAndGet();
    }

    public void recordBlockPlace(String player, String blockType) {
        blocksPlaced.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(blockType, k -> new AtomicInteger(0)).incrementAndGet();
        totalBlocksPlaced.incrementAndGet();
    }

    public void recordBlockBreak(String player, String blockType) {
        blocksBroken.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(blockType, k -> new AtomicInteger(0)).incrementAndGet();
        totalBlocksBroken.incrementAndGet();
    }

    public void recordItemPickup(String player, String itemType, int amount) {
        itemsPickedUp.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                     .computeIfAbsent(itemType, k -> new AtomicInteger(0)).addAndGet(amount);
    }

    public void recordItemDrop(String player, String itemType, int amount) {
        itemsDropped.computeIfAbsent(player, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(itemType, k -> new AtomicInteger(0)).addAndGet(amount);
    }

    public void recordMove(String player) {
        blocksTraveled.computeIfAbsent(player, k -> new AtomicInteger(0)).incrementAndGet();
    }

    // ==================== READING STATISTICS ====================

    // Player deaths
    public int getPlayerDeaths(String player) {
        AtomicInteger val = playerDeaths.get(player);
        return val != null ? val.get() : 0;
    }

    // Kills by player (PvP) - total
    public int getPlayerKills(String player) {
        AtomicInteger val = playerKills.get(player);
        return val != null ? val.get() : 0;
    }

    // How many times killer killed victim
    public int getPvpKills(String killer, String victim) {
        Map<String, AtomicInteger> kills = pvpKills.get(killer);
        if (kills == null) return 0;
        AtomicInteger val = kills.get(victim);
        return val != null ? val.get() : 0;
    }

    // All victims of a player (who they killed)
    public Map<String, Integer> getPlayerVictims(String killer) {
        Map<String, Integer> result = new HashMap<>();
        Map<String, AtomicInteger> kills = pvpKills.get(killer);
        if (kills != null) {
            kills.forEach((victim, count) -> result.put(victim, count.get()));
        }
        return result;
    }

    // Mob kills by player (total)
    public int getTotalMobKills(String player) {
        Map<String, AtomicInteger> kills = mobKills.get(player);
        if (kills == null) return 0;
        return kills.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    // Kills of a specific mob by player
    public int getMobKills(String player, String mobType) {
        Map<String, AtomicInteger> kills = mobKills.get(player);
        if (kills == null) return 0;
        AtomicInteger val = kills.get(mobType);
        return val != null ? val.get() : 0;
    }

    // Mob deaths (total)
    public int getMobDeaths(String mobType) {
        AtomicInteger val = mobDeaths.get(mobType);
        return val != null ? val.get() : 0;
    }

    // Blocks placed by player
    public int getBlocksPlaced(String player) {
        Map<String, AtomicInteger> blocks = blocksPlaced.get(player);
        if (blocks == null) return 0;
        return blocks.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    // Specific blocks placed
    public int getBlocksPlaced(String player, String blockType) {
        Map<String, AtomicInteger> blocks = blocksPlaced.get(player);
        if (blocks == null) return 0;
        AtomicInteger val = blocks.get(blockType);
        return val != null ? val.get() : 0;
    }

    // Blocks broken by player
    public int getBlocksBroken(String player) {
        Map<String, AtomicInteger> blocks = blocksBroken.get(player);
        if (blocks == null) return 0;
        return blocks.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    // Specific blocks broken
    public int getBlocksBroken(String player, String blockType) {
        Map<String, AtomicInteger> blocks = blocksBroken.get(player);
        if (blocks == null) return 0;
        AtomicInteger val = blocks.get(blockType);
        return val != null ? val.get() : 0;
    }

    // Items picked up
    public int getItemsPickedUp(String player, String itemType) {
        Map<String, AtomicInteger> items = itemsPickedUp.get(player);
        if (items == null) return 0;
        AtomicInteger val = items.get(itemType);
        return val != null ? val.get() : 0;
    }

    // Blocks traveled
    public int getBlocksTraveled(String player) {
        AtomicInteger val = blocksTraveled.get(player);
        return val != null ? val.get() : 0;
    }

    // ==================== OVERALL STATISTICS ====================

    public int getTotalDeaths() { return totalDeaths.get(); }
    public int getTotalMobKills() { return totalMobKills.get(); }
    public int getTotalBlocksPlaced() { return totalBlocksPlaced.get(); }
    public int getTotalBlocksBroken() { return totalBlocksBroken.get(); }

    // ==================== DETAILED DATA ====================

    public Map<String, Integer> getAllPlayerDeaths() {
        Map<String, Integer> result = new HashMap<>();
        playerDeaths.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public Map<String, Integer> getAllMobDeaths() {
        Map<String, Integer> result = new HashMap<>();
        mobDeaths.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public Map<String, Integer> getPlayerMobKills(String player) {
        Map<String, Integer> result = new HashMap<>();
        Map<String, AtomicInteger> kills = mobKills.get(player);
        if (kills != null) {
            kills.forEach((k, v) -> result.put(k, v.get()));
        }
        return result;
    }

    // ==================== RESET ====================

    public void reset() {
        playerDeaths.clear();
        playerKills.clear();
        mobKills.clear();
        mobDeaths.clear();
        blocksPlaced.clear();
        blocksBroken.clear();
        itemsPickedUp.clear();
        itemsDropped.clear();
        blocksTraveled.clear();
        totalDeaths.set(0);
        totalMobKills.set(0);
        totalBlocksPlaced.set(0);
        totalBlocksBroken.set(0);
    }

    public void resetPlayer(String player) {
        playerDeaths.remove(player);
        playerKills.remove(player);
        mobKills.remove(player);
        blocksPlaced.remove(player);
        blocksBroken.remove(player);
        itemsPickedUp.remove(player);
        itemsDropped.remove(player);
        blocksTraveled.remove(player);
    }

}

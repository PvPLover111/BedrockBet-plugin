package com.bedrockbet;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public class GameEventListener implements Listener {

    private final BedrockBet plugin;

    // For tracking position (every block)
    private final Map<String, Location> lastBlockLocation = new HashMap<>();

    // Callback for market system
    private Consumer<GameEvent> eventCallback;

    // Cumulative statistics
    private final GameStats stats = new GameStats();

    public GameEventListener(BedrockBet plugin) {
        this.plugin = plugin;
    }

    public GameStats getStats() {
        return stats;
    }

    public void setEventCallback(Consumer<GameEvent> callback) {
        this.eventCallback = callback;
    }

    // ==================== EVENTS ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Entity killer = victim.getKiller();
        String killerName = "environment";
        String killerType = "environment";

        if (killer != null) {
            if (killer instanceof Player p) {
                killerName = p.getName();
                killerType = "player";
            } else {
                killerName = killer.getType().name();
                killerType = "mob";
            }
        } else if (victim.getLastDamageCause() != null) {
            killerName = victim.getLastDamageCause().getCause().name();
        }

        stats.recordPlayerDeath(victim.getName(), killerName, killerType);

        fireEvent(new GameEvent("DEATH")
            .set("victim", victim.getName())
            .set("victimType", "player")
            .set("killer", killerName)
            .set("killerType", killerType)
            .set("cause", victim.getLastDamageCause() != null ?
                victim.getLastDamageCause().getCause().name() : "UNKNOWN"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) return;

        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer != null) {
            stats.recordMobKill(killer.getName(), victim.getType().name());

            fireEvent(new GameEvent("DEATH")
                .set("victim", victim.getType().name())
                .set("victimType", "mob")
                .set("killer", killer.getName())
                .set("killerType", "player")
                .set("cause", victim.getLastDamageCause() != null ?
                    victim.getLastDamageCause().getCause().name() : "UNKNOWN"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getNewSlot());
        if (item == null || item.getType() == Material.AIR) return;

        fireEvent(new GameEvent("HELD")
            .set("player", player.getName())
            .set("item", item.getType().name())
            .set("amount", item.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = event.getItem().getItemStack();
        stats.recordItemPickup(player.getName(), item.getType().name(), item.getAmount());

        fireEvent(new GameEvent("PICKUP")
            .set("player", player.getName())
            .set("item", item.getType().name())
            .set("amount", item.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        if (to == null) return;

        String playerName = player.getName();
        int newX = to.getBlockX();
        int newY = to.getBlockY();
        int newZ = to.getBlockZ();

        Location lastBlock = lastBlockLocation.get(playerName);
        if (lastBlock != null &&
            lastBlock.getBlockX() == newX &&
            lastBlock.getBlockY() == newY &&
            lastBlock.getBlockZ() == newZ) {
            return;
        }

        lastBlockLocation.put(playerName, to.clone());
        stats.recordMove(playerName);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        stats.recordBlockPlace(player.getName(), block.getType().name());

        fireEvent(new GameEvent("PLACE")
            .set("player", player.getName())
            .set("block", block.getType().name())
            .set("x", block.getX())
            .set("y", block.getY())
            .set("z", block.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        stats.recordBlockBreak(player.getName(), block.getType().name());

        fireEvent(new GameEvent("BREAK")
            .set("player", player.getName())
            .set("block", block.getType().name())
            .set("x", block.getX())
            .set("y", block.getY())
            .set("z", block.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemDrop().getItemStack();
        stats.recordItemDrop(player.getName(), item.getType().name(), item.getAmount());

        fireEvent(new GameEvent("DROP")
            .set("player", player.getName())
            .set("item", item.getType().name())
            .set("amount", item.getAmount()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWeatherChange(WeatherChangeEvent event) {
        fireEvent(new GameEvent("WEATHER")
            .set("world", event.getWorld().getName())
            .set("raining", event.toWeatherState())
            .set("state", event.toWeatherState() ? "START" : "END"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        Player player = event.getPlayer();
        int oldLevel = event.getOldLevel();
        int newLevel = event.getNewLevel();
        if (newLevel <= oldLevel) return;

        fireEvent(new GameEvent("LEVEL")
            .set("player", player.getName())
            .set("oldLevel", oldLevel)
            .set("newLevel", newLevel));
    }

    // ==================== HELPERS ====================

    private void fireEvent(GameEvent event) {
        if (eventCallback != null) {
            eventCallback.accept(event);
        }
    }

    // ==================== EVENT CLASS ====================

    public static class GameEvent {
        private final String type;
        private final Map<String, Object> data = new HashMap<>();
        private final long timestamp;

        public GameEvent(String type) {
            this.type = type;
            this.timestamp = System.currentTimeMillis();
        }

        public GameEvent set(String key, Object value) {
            data.put(key, value);
            return this;
        }

        public String getType() {
            return type;
        }

        public Object get(String key) {
            return data.get(key);
        }

        public String getString(String key) {
            Object v = data.get(key);
            return v != null ? v.toString() : null;
        }

        public int getInt(String key) {
            Object v = data.get(key);
            return v instanceof Number ? ((Number) v).intValue() : 0;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public Map<String, Object> getData() {
            return new HashMap<>(data);
        }

        @Override
        public String toString() {
            return type + " " + data.toString();
        }
    }
}

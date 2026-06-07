package com.bedrockbet;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the balance scoreboard for all players
 */
public class BalanceScoreboard implements Listener {

    private final JavaPlugin plugin;
    private final Database database;

    // Held item value cache (updated in the background)
    private final Map<UUID, Integer> balanceCache = new ConcurrentHashMap<>();

    // Player scoreboards
    private final Map<UUID, Scoreboard> playerScoreboards = new ConcurrentHashMap<>();

    private BukkitRunnable updateTask;
    private boolean running = false;

    public BalanceScoreboard(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /**
     * Starts the scoreboard system
     */
    public void start() {
        if (running) return;
        running = true;

        // Initialize scoreboards for already online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            setupScoreboard(player);
        }

        // Start background update every 2 seconds (40 ticks)
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateAllBalances();
            }
        };
        updateTask.runTaskTimerAsynchronously(plugin, 0L, 40L);

        plugin.getLogger().info("BalanceScoreboard started");
    }

    /**
     * Stops the scoreboard system
     */
    public void stop() {
        running = false;
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }

        // Remove scoreboards from all players
        for (Player player : Bukkit.getOnlinePlayers()) {
            removeScoreboard(player);
        }

        balanceCache.clear();
        playerScoreboards.clear();

        plugin.getLogger().info("BalanceScoreboard stopped");
    }

    /**
     * Creates a scoreboard for a player
     */
    private void setupScoreboard(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        Scoreboard scoreboard = manager.getNewScoreboard();

        // Use Adventure API for the title
        Component title = Component.text("BedrockBet")
                .color(NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD);

        Objective objective = scoreboard.registerNewObjective(
                "bedrockbet_balance",
                Criteria.DUMMY,
                title
        );
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Hide ALL numbers on the right side (Paper 1.20.3+)
        objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());

        playerScoreboards.put(player.getUniqueId(), scoreboard);

        // Set the scoreboard for the player (on the main thread)
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    player.setScoreboard(scoreboard);
                    updatePlayerScoreboard(player);
                }
            }
        }.runTask(plugin);
    }

    /**
     * Removes the scoreboard from a player
     */
    private void removeScoreboard(Player player) {
        playerScoreboards.remove(player.getUniqueId());
        balanceCache.remove(player.getUniqueId());

        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null && player.isOnline()) {
            player.setScoreboard(manager.getMainScoreboard());
        }
    }

    /**
     * Updates held item values for all players (runs on main thread via timer)
     */
    private void updateAllBalances() {
        // Schedule on main thread since we need to read player inventory
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    int heldValue = ItemValues.getHeldValue(player);
                    Integer oldValue = balanceCache.put(player.getUniqueId(), heldValue);

                    if (oldValue == null || oldValue != heldValue) {
                        updatePlayerScoreboard(player);
                    }
                }
            }
        }.runTask(plugin);
    }

    /**
     * Updates the scoreboard for a specific player
     */
    private void updatePlayerScoreboard(Player player) {
        Scoreboard scoreboard = playerScoreboards.get(player.getUniqueId());
        if (scoreboard == null) return;

        Objective objective = scoreboard.getObjective("bedrockbet_balance");
        if (objective == null) return;

        // Clear old entries
        for (String entry : scoreboard.getEntries()) {
            scoreboard.resetScores(entry);
        }

        int heldValue = balanceCache.getOrDefault(player.getUniqueId(), 0);

        int line = 0;

        objective.getScore(ChatColor.YELLOW + "/markets").setScore(line++);
        objective.getScore(" ").setScore(line++);
        objective.getScore(ChatColor.GREEN + "" + ChatColor.BOLD + MarketManager.formatNum(heldValue)).setScore(line++);
        objective.getScore(ChatColor.WHITE + "Held value:").setScore(line++);
    }

    /**
     * Force-refreshes a player's held value display (call after inventory changes)
     */
    public void refreshBalance(Player player) {
        if (!running || player == null) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    int heldValue = ItemValues.getHeldValue(player);
                    balanceCache.put(player.getUniqueId(), heldValue);
                    updatePlayerScoreboard(player);
                }
            }
        }.runTask(plugin);
    }

    // ==================== EVENTS ====================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Small delay to let the player fully load
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && running) {
                    setupScoreboard(player);
                }
            }
        }.runTaskLater(plugin, 20L); // 1 second
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeScoreboard(event.getPlayer());
    }
}

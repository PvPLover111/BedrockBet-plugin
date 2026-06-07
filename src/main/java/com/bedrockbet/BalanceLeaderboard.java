package com.bedrockbet;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages floating hologram leaderboards for balance.
 * Uses Text Display entities (Paper 1.20.4+).
 */
public class BalanceLeaderboard {

    private final JavaPlugin plugin;
    private final Database database;

    // All active leaderboards: entity UUID -> Location
    private final Map<UUID, TextDisplay> leaderboards = new ConcurrentHashMap<>();

    private BukkitTask updateTask;
    private static final int TOP_COUNT = 10;
    private static final long UPDATE_INTERVAL = 100L; // 5 seconds (100 ticks)

    // Promo messages rotation
    private int updateCounter = 0;
    private int promoIndex = 0;
    private static final int PROMO_ROTATE_EVERY = 12; // every 12 updates = 1 minute

    public BalanceLeaderboard(JavaPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /**
     * Starts background updating of all leaderboards
     */
    public void start() {
        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateAll();
            }
        }.runTaskTimer(plugin, 40L, UPDATE_INTERVAL);
    }

    /**
     * Stops updating and removes all holograms
     */
    public void stop() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        removeAll();
    }

    /**
     * Creates a new leaderboard at the specified position
     */
    public TextDisplay create(Location location) {
        TextDisplay display = (TextDisplay) location.getWorld().spawnEntity(
                location, EntityType.TEXT_DISPLAY
        );

        // Appearance configuration
        display.setBillboard(Display.Billboard.CENTER); // always faces the player
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setShadowed(true);
        display.setBackgroundColor(org.bukkit.Color.fromARGB(160, 0, 0, 0)); // semi-transparent black background
        display.setSeeThrough(false);
        display.setLineWidth(200);
        display.setPersistent(true);
        display.setCustomNameVisible(false);

        // Scale: ~3 blocks wide, ~2 blocks tall
        // 1 block = ~4 lines of text at standard scale
        // Top-10 + header + separators = ~13 lines -> scale 0.38 on Y gives ~2 blocks
        // Text width ~6 blocks at 1.0 -> scale 0.5 on X gives ~3 blocks
        org.bukkit.util.Transformation t = display.getTransformation();
        org.joml.Vector3f scale = new org.joml.Vector3f(0.5f, 0.5f, 0.5f);
        display.setTransformation(new org.bukkit.util.Transformation(
                t.getTranslation(), t.getLeftRotation(), scale, t.getRightRotation()
        ));

        leaderboards.put(display.getUniqueId(), display);

        // First update immediately
        updateDisplay(display);

        return display;
    }

    /**
     * Removes a specific leaderboard
     */
    public boolean remove(UUID entityId) {
        TextDisplay display = leaderboards.remove(entityId);
        if (display != null && !display.isDead()) {
            display.remove();
            return true;
        }
        return false;
    }

    /**
     * Removes the nearest leaderboard to the specified position (within a 10-block radius)
     */
    public boolean removeNearest(Location location) {
        TextDisplay nearest = null;
        double minDist = 10.0;

        for (TextDisplay display : leaderboards.values()) {
            if (display.isDead()) continue;
            if (!display.getWorld().equals(location.getWorld())) continue;

            double dist = display.getLocation().distance(location);
            if (dist < minDist) {
                minDist = dist;
                nearest = display;
            }
        }

        if (nearest != null) {
            return remove(nearest.getUniqueId());
        }
        return false;
    }

    /**
     * Removes all leaderboards
     */
    public void removeAll() {
        for (TextDisplay display : leaderboards.values()) {
            if (!display.isDead()) {
                display.remove();
            }
        }
        leaderboards.clear();
    }

    /**
     * Number of active leaderboards
     */
    public int count() {
        return leaderboards.size();
    }

    /**
     * Updates all leaderboards
     */
    private void updateAll() {
        // Remove dead ones
        leaderboards.entrySet().removeIf(e -> e.getValue().isDead());

        if (leaderboards.isEmpty()) return;

        // Rotate promo message every PROMO_ROTATE_EVERY updates
        updateCounter++;
        if (updateCounter >= PROMO_ROTATE_EVERY) {
            updateCounter = 0;
            promoIndex = (promoIndex + 1) % PROMO_COUNT;
        }
        final int currentPromo = promoIndex;

        // Get top from DB asynchronously, then update in the main thread
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    List<Database.User> topUsers = database.getAllUsers();
                    List<Database.User> top = topUsers.stream()
                            .limit(TOP_COUNT)
                            .toList();

                    // Update UI in the main thread
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            Component text = buildLeaderboardText(top, currentPromo);
                            for (TextDisplay display : leaderboards.values()) {
                                if (!display.isDead()) {
                                    display.text(text);
                                }
                            }
                        }
                    }.runTask(plugin);

                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to update leaderboard: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Updates a single specific display
     */
    private void updateDisplay(TextDisplay display) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    List<Database.User> topUsers = database.getAllUsers();
                    List<Database.User> top = topUsers.stream()
                            .limit(TOP_COUNT)
                            .toList();

                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (!display.isDead()) {
                                display.text(buildLeaderboardText(top, promoIndex));
                            }
                        }
                    }.runTask(plugin);

                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to update leaderboard: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    /**
     * Builds the leaderboard text
     */
    private Component buildLeaderboardText(List<Database.User> top, int promo) {
        Component text = Component.text("★ TOP WINNERS ★")
                .color(NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD);

        text = text.append(Component.newline());
        text = text.append(Component.text("─────────────────")
                .color(NamedTextColor.DARK_GRAY));

        if (top.isEmpty()) {
            text = text.append(Component.newline());
            text = text.append(Component.text("No players yet")
                    .color(NamedTextColor.GRAY));
        } else {
            for (int i = 0; i < top.size(); i++) {
                Database.User user = top.get(i);
                text = text.append(Component.newline());

                // Position color
                NamedTextColor posColor = switch (i) {
                    case 0 -> NamedTextColor.GOLD;
                    case 1 -> NamedTextColor.GRAY;
                    case 2 -> NamedTextColor.RED;
                    default -> NamedTextColor.DARK_GRAY;
                };

                // Medal for top 3
                String medal = switch (i) {
                    case 0 -> "1.";
                    case 1 -> "2.";
                    case 2 -> "3.";
                    default -> (i + 1) + ".";
                };

                text = text.append(Component.text(medal + " ")
                        .color(posColor)
                        .decorate(TextDecoration.BOLD));

                text = text.append(Component.text(user.getNickname())
                        .color(NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, false));

                text = text.append(Component.text(" — ")
                        .color(NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.BOLD, false));

                text = text.append(Component.text(MarketManager.formatNum(user.getBalance()))
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, false));
            }
        }

        text = text.append(Component.newline());
        text = text.append(Component.text("─────────────────")
                .color(NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.BOLD, false));

        // Promo message
        text = text.append(Component.newline());
        text = text.append(buildPromoMessage(promo));

        return text;
    }

    private static final int PROMO_COUNT = 5;

    private Component buildPromoMessage(int index) {
        return switch (index % PROMO_COUNT) {
            case 0 -> Component.text("» Place your bets!")
                    .color(NamedTextColor.WHITE)
                    .append(Component.newline())
                    .append(Component.text("/markets")
                            .color(NamedTextColor.YELLOW)
                            .decorate(TextDecoration.BOLD));
            case 1 -> Component.text("» Check item values!")
                    .color(NamedTextColor.WHITE)
                    .append(Component.newline())
                    .append(Component.text("/market values")
                            .color(NamedTextColor.GREEN)
                            .decorate(TextDecoration.BOLD));
            case 2 -> Component.text("» Join our Discord!")
                    .color(NamedTextColor.WHITE)
                    .append(Component.newline())
                    .append(Component.text("discord.gg/bedrockbet")
                            .color(NamedTextColor.LIGHT_PURPLE)
                            .decorate(TextDecoration.BOLD));
            case 3 -> Component.text("» Check your stats!")
                    .color(NamedTextColor.WHITE)
                    .append(Component.newline())
                    .append(Component.text("/stats")
                            .color(NamedTextColor.GOLD)
                            .decorate(TextDecoration.BOLD));
            case 4 -> Component.text("» Hold items to bet!")
                    .color(NamedTextColor.WHITE)
                    .append(Component.newline())
                    .append(Component.text("/market bet <id> <yes/no>")
                            .color(NamedTextColor.AQUA)
                            .decorate(TextDecoration.BOLD));
            default -> Component.empty();
        };
    }
}

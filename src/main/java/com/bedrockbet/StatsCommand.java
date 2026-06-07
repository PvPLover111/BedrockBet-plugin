package com.bedrockbet;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class StatsCommand implements CommandExecutor, TabCompleter {

    private final GameStats stats;

    public StatsCommand(GameStats stats) {
        this.stats = stats;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            showGlobalStats(sender);
            return true;
        }

        String action = args[0].toLowerCase();

        switch (action) {
            case "player" -> {
                if (args.length < 2) {
                    if (sender instanceof Player p) {
                        showPlayerStats(sender, p.getName());
                    } else {
                        sender.sendMessage("§cSpecify a player: /stats player <name>");
                    }
                } else {
                    showPlayerStats(sender, args[1]);
                }
            }

            case "mobs" -> showMobStats(sender);

            case "top" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /stats top <deaths|kills|blocks>");
                } else {
                    showTop(sender, args[1]);
                }
            }

            case "reset" -> {
                if (sender.hasPermission("bedrockbet.admin")) {
                    stats.reset();
                    sender.sendMessage("§aStatistics have been reset!");
                } else {
                    sender.sendMessage("§cNo permission!");
                }
            }

            default -> showHelp(sender);
        }

        return true;
    }

    private void showGlobalStats(CommandSender sender) {
        sender.sendMessage("§6=== General Statistics ===");
        sender.sendMessage("§c☠ Player deaths: §f" + stats.getTotalDeaths());
        sender.sendMessage("§c⚔ Mobs killed: §f" + stats.getTotalMobKills());
        sender.sendMessage("§2🔨 Blocks placed: §f" + stats.getTotalBlocksPlaced());
        sender.sendMessage("§4⛏ Blocks broken: §f" + stats.getTotalBlocksBroken());
        sender.sendMessage("");
        sender.sendMessage("§7/stats player <name> §8- player statistics");
        sender.sendMessage("§7/stats mobs §8- mob statistics");
        sender.sendMessage("§7/stats top <category> §8- top players");
    }

    private void showPlayerStats(CommandSender sender, String playerName) {
        sender.sendMessage("§6=== Statistics for " + playerName + " ===");
        sender.sendMessage("§c☠ Deaths: §f" + stats.getPlayerDeaths(playerName));
        sender.sendMessage("§c⚔ Kills (PvP): §f" + stats.getPlayerKills(playerName));
        sender.sendMessage("§c🗡 Mobs killed: §f" + stats.getTotalMobKills(playerName));
        sender.sendMessage("§2🔨 Blocks placed: §f" + stats.getBlocksPlaced(playerName));
        sender.sendMessage("§4⛏ Blocks broken: §f" + stats.getBlocksBroken(playerName));
        sender.sendMessage("§d🚶 Blocks traveled: §f" + stats.getBlocksTraveled(playerName));

        // Mob kill details
        Map<String, Integer> mobKills = stats.getPlayerMobKills(playerName);
        if (!mobKills.isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage("§6Mobs killed:");
            mobKills.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(5)
                .forEach(e -> sender.sendMessage("  §7" + e.getKey() + ": §f" + e.getValue()));
        }

        // PvP details
        Map<String, Integer> pvpKills = stats.getPlayerVictims(playerName);
        if (!pvpKills.isEmpty()) {
            sender.sendMessage("");
            sender.sendMessage("§cPvP kills:");
            pvpKills.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sender.sendMessage("  §7" + e.getKey() + ": §f" + e.getValue() + " times"));
        }
    }

    private void showMobStats(CommandSender sender) {
        sender.sendMessage("§6=== Mob Statistics ===");

        Map<String, Integer> mobDeaths = stats.getAllMobDeaths();
        if (mobDeaths.isEmpty()) {
            sender.sendMessage("§7No mobs have been killed yet");
            return;
        }

        mobDeaths.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .forEach(e -> sender.sendMessage("§c" + e.getKey() + "§7: §f" + e.getValue() + " killed"));
    }

    private void showTop(CommandSender sender, String category) {
        sender.sendMessage("§6=== Top by " + category + " ===");

        Map<String, Integer> data;
        switch (category.toLowerCase()) {
            case "deaths" -> data = stats.getAllPlayerDeaths();
            case "kills" -> {
                // Need to collect top mob kills
                sender.sendMessage("§7Top mob kills leaderboard is not yet implemented");
                return;
            }
            default -> {
                sender.sendMessage("§cAvailable categories: deaths, kills, blocks");
                return;
            }
        }

        if (data.isEmpty()) {
            sender.sendMessage("§7No data available");
            return;
        }

        final int[] place = {1};
        data.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .forEach(e -> {
                String color = place[0] == 1 ? "§6" : place[0] == 2 ? "§7" : place[0] == 3 ? "§c" : "§f";
                sender.sendMessage(color + place[0] + ". " + e.getKey() + " §7- §f" + e.getValue());
                place[0]++;
            });
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage("§6=== BedrockBet Stats ===");
        sender.sendMessage("§e/stats §7- general statistics");
        sender.sendMessage("§e/stats player [name] §7- player statistics");
        sender.sendMessage("§e/stats mobs §7- killed mobs");
        sender.sendMessage("§e/stats top <deaths> §7- top players");
        sender.sendMessage("§e/stats reset §7- reset (admin)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterStartsWith(Arrays.asList("player", "mobs", "top", "reset"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("player")) {
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
            return filterStartsWith(Arrays.asList("deaths", "kills"), args[1]);
        }
        return List.of();
    }

    private List<String> filterStartsWith(List<String> options, String prefix) {
        return options.stream()
            .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
            .toList();
    }
}

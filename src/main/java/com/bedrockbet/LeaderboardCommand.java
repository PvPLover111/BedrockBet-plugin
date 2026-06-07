package com.bedrockbet;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private final BalanceLeaderboard leaderboard;

    public LeaderboardCommand(BalanceLeaderboard leaderboard) {
        this.leaderboard = leaderboard;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command");
            return true;
        }

        if (!player.isOp()) {
            player.sendMessage("§cYou must be an operator to use this command");
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "create", "spawn", "place" -> {
                leaderboard.create(player.getLocation().add(0, 2, 0));
                player.sendMessage("§aLeaderboard created! §7(Total: " + leaderboard.count() + ")");
            }
            case "remove", "delete" -> {
                if (leaderboard.removeNearest(player.getLocation())) {
                    player.sendMessage("§aNearest leaderboard removed! §7(Total: " + leaderboard.count() + ")");
                } else {
                    player.sendMessage("§cNo leaderboard found within 10 blocks");
                }
            }
            case "removeall", "clear" -> {
                int count = leaderboard.count();
                leaderboard.removeAll();
                player.sendMessage("§aRemoved " + count + " leaderboard(s)");
            }
            default -> sendHelp(player);
        }

        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6=== Leaderboard Commands ===");
        player.sendMessage("§e/leaderboard create §7- Create a floating leaderboard at your position");
        player.sendMessage("§e/leaderboard remove §7- Remove the nearest leaderboard (10 block radius)");
        player.sendMessage("§e/leaderboard removeall §7- Remove all leaderboards");
        player.sendMessage("§7Active leaderboards: §f" + leaderboard.count());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Arrays.asList("create", "remove", "removeall").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}

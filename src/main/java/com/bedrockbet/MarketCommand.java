package com.bedrockbet;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import org.bukkit.Material;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MarketCommand implements CommandExecutor, TabCompleter {

    private final MarketManager marketManager;

    public MarketCommand(MarketManager marketManager) {
        this.marketManager = marketManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase();

        switch (subcommand) {
            case "create" -> handleCreate(sender, args);
            case "bet" -> handleBet(sender, args);
            case "list" -> handleList(sender);
            case "info" -> handleInfo(sender, args);
            case "get" -> handleGet(sender, args);
            case "balance", "bal" -> handleBalance(sender);
            case "values", "items" -> handleValues(sender);
            default -> {
                // Try as create without subcommand
                // /market kill Steve Alex 5m
                handleCreateDirect(sender, args);
            }
        }

        return true;
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can create markets");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /market create <type> <args...> <time>");
            sender.sendMessage("§7Example: /market create kill Steve zombie >10 5m");
            return;
        }

        // Remove "create" from arguments
        String[] marketArgs = Arrays.copyOfRange(args, 1, args.length);

        try {
            Market market = marketManager.createMarket(marketArgs, player.getName());
            player.sendMessage("§aMarket #" + market.getId() + " created!");
        } catch (Exception e) {
            player.sendMessage("§cError: " + e.getMessage());
        }
    }

    private void handleCreateDirect(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can create markets");
            return;
        }

        try {
            Market market = marketManager.createMarket(args, player.getName());
            player.sendMessage("§aMarket #" + market.getId() + " created!");
        } catch (Exception e) {
            player.sendMessage("§cError: " + e.getMessage());
        }
    }

    private void handleBet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can place bets");
            return;
        }

        if (args.length < 3) {
            sender.sendMessage("§cUsage: /market bet <id> <yes/no>");
            sender.sendMessage("§7Hold items in your hand to bet them!");
            return;
        }

        try {
            int marketId = Integer.parseInt(args[1]);
            String outcome = args[2].toLowerCase();

            int heldValue = ItemValues.getHeldValue(player);
            marketManager.placeBet(marketId, player, outcome,
                    () -> player.sendMessage("§aBet placed: items worth §e" + heldValue + " §aon " + outcome.toUpperCase()),
                    error -> player.sendMessage("§cError: " + error));

        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid market ID");
        }
    }

    private void handleList(CommandSender sender) {
        List<Market> markets = marketManager.getActiveMarketsList();

        if (markets.isEmpty()) {
            sender.sendMessage("§7No active markets");
            return;
        }

        sender.sendMessage("§6=== Active Markets ===");
        for (Market market : markets) {
            int progress = marketManager.getProgress(market.getId());
            String progressStr = "";

            // Show progress if applicable
            if (!market.getType().equals("weather") && !market.getType().equals("pos") && !market.getType().equals("height")) {
                progressStr = " §8[" + progress + "/" + market.getValue() + "]";
            }

            sender.sendMessage(String.format("§a#%d §f%s §7(%s)%s",
                market.getId(),
                market.getDescription(),
                market.getRemainingTimeFormatted(),
                progressStr));
        }
        sender.sendMessage("§7Use: /market bet <id> <yes/no> <amount>");
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /market info <id>");
            return;
        }

        try {
            int marketId = Integer.parseInt(args[1]);
            Market market = marketManager.getMarket(marketId);

            if (market == null) {
                sender.sendMessage("§cMarket #" + marketId + " not found");
                return;
            }

            sender.sendMessage("§6=== Market #" + marketId + " ===");
            sender.sendMessage("§7Description: §f" + market.getDescription());
            sender.sendMessage("§7Type: §f" + market.getType());
            sender.sendMessage("§7Status: §f" + market.getStatus());
            sender.sendMessage("§7Time left: §f" + market.getRemainingTimeFormatted());
            sender.sendMessage("§7Created by: §f" + market.getCreatedBy());

            int progress = marketManager.getProgress(marketId);
            if (!market.getType().equals("weather")) {
                sender.sendMessage("§7Progress: §f" + progress + "/" + market.getValue());
            }

            // Show bets
            try {
                List<Database.Bet> bets = marketManager.getDatabase().getBetsForMarket(marketId);
                int totalYes = 0, totalNo = 0;
                for (Database.Bet bet : bets) {
                    if ("yes".equals(bet.getOutcome())) totalYes += bet.getAmount();
                    else totalNo += bet.getAmount();
                }
                sender.sendMessage("§7Bets: §aYES: " + totalYes + " §7| §cNO: " + totalNo);
            } catch (SQLException e) {
                sender.sendMessage("§cError loading bets");
            }

        } catch (NumberFormatException e) {
            sender.sendMessage("§cInvalid market ID");
        }
    }

    /**
     * /market get {id} - private bet information (only for the caller)
     */
    private void handleGet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command");
            return;
        }

        if (args.length < 2) {
            player.sendMessage("§cUsage: /market get <id>");
            return;
        }

        try {
            int marketId = Integer.parseInt(args[1]);
            Market market = marketManager.getMarket(marketId);

            if (market == null) {
                player.sendMessage("§cMarket #" + marketId + " not found");
                return;
            }

            // Get all bets
            List<Database.Bet> bets = marketManager.getDatabase().getBetsForMarket(marketId);

            int totalYes = 0, totalNo = 0;
            Database.Bet myBet = null;

            for (Database.Bet bet : bets) {
                if ("yes".equals(bet.getOutcome())) {
                    totalYes += bet.getAmount();
                } else {
                    totalNo += bet.getAmount();
                }
                // Find this player's bet
                if (bet.getPlayer().equalsIgnoreCase(player.getName())) {
                    myBet = bet;
                }
            }

            int total = totalYes + totalNo;

            // Private message only to this player
            player.sendMessage("§6=== Market #" + marketId + " (Private) ===");
            player.sendMessage("§f" + market.getDescription());
            player.sendMessage("§7Time left: §f" + market.getRemainingTimeFormatted());
            player.sendMessage("");

            // Ratio
            player.sendMessage("§7Total pool: §e" + total);
            if (total > 0) {
                int yesPercent = (totalYes * 100) / total;
                int noPercent = 100 - yesPercent;
                player.sendMessage("§aYES: " + totalYes + " (" + yesPercent + "%) §7| §cNO: " + totalNo + " (" + noPercent + "%)");
            } else {
                player.sendMessage("§aYES: 0 §7| §cNO: 0");
            }

            player.sendMessage("");

            // All bets
            player.sendMessage("§7All bets:");
            if (bets.isEmpty()) {
                player.sendMessage("§8  No bets yet");
            } else {
                for (Database.Bet bet : bets) {
                    String color = "yes".equals(bet.getOutcome()) ? "§a" : "§c";
                    String outcome = bet.getOutcome().toUpperCase();
                    player.sendMessage(String.format("§7  - §f%s§7: %s%s §e%d",
                        bet.getPlayer(), color, outcome, bet.getAmount()));
                }
            }

            // My bet
            player.sendMessage("");
            if (myBet != null) {
                String color = "yes".equals(myBet.getOutcome()) ? "§a" : "§c";
                player.sendMessage("§6Your bet: " + color + myBet.getOutcome().toUpperCase() + " §e" + myBet.getAmount());
            } else {
                player.sendMessage("§7You haven't bet on this market yet");
                player.sendMessage("§7Use: /market bet " + marketId + " <yes/no> <amount>");
            }

        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid market ID");
        } catch (SQLException e) {
            player.sendMessage("§cError loading bets: " + e.getMessage());
        }
    }

    private void handleBalance(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can check held value");
            return;
        }

        int heldValue = ItemValues.getHeldValue(player);
        if (heldValue > 0) {
            player.sendMessage("§6Held item value: §e" + heldValue);
        } else {
            player.sendMessage("§7You're not holding any valuable items");
            player.sendMessage("§7Use §e/market values §7to see accepted items");
        }
    }

    private void handleValues(CommandSender sender) {
        sender.sendMessage("§6=== Item Values ===");
        LinkedHashMap<Material, Integer> table = ItemValues.getValueTable();
        for (Map.Entry<Material, Integer> entry : table.entrySet()) {
            sender.sendMessage("§e  " + ItemValues.formatName(entry.getKey()) + " §7= §f" + entry.getValue());
        }
        sender.sendMessage("§7Hold items in hand and use §e/market bet <id> <yes/no>");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== Market Commands ===");
        sender.sendMessage("§e/market <type> <args> <time> §7- Create market directly");
        sender.sendMessage("§e/market create <type> <args> <time> §7- Create market");
        sender.sendMessage("§e/market bet <id> <yes/no> §7- Bet held items");
        sender.sendMessage("§e/market list §7- Show active markets");
        sender.sendMessage("§e/market info <id> §7- Show market details");
        sender.sendMessage("§e/market get <id> §7- Private: all bets & your bet");
        sender.sendMessage("§e/market balance §7- Show held item value");
        sender.sendMessage("§e/market values §7- Show item value table");
        sender.sendMessage("");
        sender.sendMessage("§6=== Market Types ===");
        sender.sendMessage("§7kill <player> <target> [>N] <time>");
        sender.sendMessage("§7deaths <player> [=0|>N] <time>");
        sender.sendMessage("§7pos <player> y>256 [x>N] [z>N] <time>");
        sender.sendMessage("§7break <player> <block> [>N] <time>");
        sender.sendMessage("§7place <player> <block> [>N] <time>");
        sender.sendMessage("§7pickup <player> <item> [>N] <time>");
        sender.sendMessage("§7drop <player> <item> [>N] <time>");
        sender.sendMessage("§7weather <rain|clear> <time>");
        sender.sendMessage("§7level <player|*|any> <condition> <time>");
        sender.sendMessage("");
        sender.sendMessage("§6Use * or any for 'anyone':");
        sender.sendMessage("§7/market kill * chicken 5m §8- anyone kills chicken");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("create", "bet", "list", "info", "get", "balance", "values",
                "kill", "deaths", "pos", "height", "distance", "break", "place", "pickup", "drop", "weather", "level"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("bet") || sub.equals("info") || sub.equals("get")) {
                // Market IDs
                for (Market m : marketManager.getActiveMarketsList()) {
                    completions.add(String.valueOf(m.getId()));
                }
            } else if (sub.equals("create")) {
                completions.addAll(Arrays.asList("kill", "deaths", "pos", "height", "distance",
                    "break", "place", "pickup", "drop", "weather", "level"));
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("bet")) {
            completions.addAll(Arrays.asList("yes", "no"));
        }

        String lastArg = args[args.length - 1].toLowerCase();
        return completions.stream()
            .filter(s -> s.toLowerCase().startsWith(lastArg))
            .toList();
    }
}

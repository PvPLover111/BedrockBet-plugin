package com.bedrockbet;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MarketsCommand implements CommandExecutor {

    private final MarketGUI marketGUI;

    public MarketsCommand(MarketGUI marketGUI) {
        this.marketGUI = marketGUI;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use this command");
            return true;
        }

        int page = 0;

        // /markets <page>
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]) - 1;
                if (page < 0) page = 0;
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        marketGUI.openMainMenu(player, page);
        return true;
    }
}

package com.bedrockbet;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.scheduler.BukkitRunnable;

public class MarketGUI implements Listener {

    private final MarketManager marketManager;
    private final Set<String> openGUIs = new HashSet<>();

    // Market cache (thread-safe) - store ALL, filter on the fly
    private final List<Market> cachedMarkets = new CopyOnWriteArrayList<>();
    private volatile long lastCacheUpdate = 0;
    private volatile int consecutiveErrors = 0;
    private static final int MAX_ERRORS_BEFORE_BACKOFF = 3;

    // Filter for each player: "all", "active", "my"
    private final Map<String, String> playerFilters = new ConcurrentHashMap<>();

    // Page sizes
    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9; // 54 slots
    private static final int ITEMS_PER_PAGE = 45; // 5 rows for markets

    public MarketGUI(MarketManager marketManager) {
        this.marketManager = marketManager;
        startCacheWorker();
    }

    /**
     * Starts background worker for cache updates
     */
    private void startCacheWorker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    updateCache();
                } catch (Exception e) {
                    // Catch ALL exceptions so the worker doesn't die
                    handleWorkerError(e);
                }
            }
        }.runTaskTimerAsynchronously(marketManager.getPlugin(), 0L, 40L); // every 2 sec (40 ticks)
    }

    /**
     * Handles worker error with backoff
     */
    private void handleWorkerError(Exception e) {
        consecutiveErrors++;

        if (consecutiveErrors >= MAX_ERRORS_BEFORE_BACKOFF) {
            // Log only after several consecutive errors (avoid spam)
            marketManager.getPlugin().getLogger().warning(
                "Market cache worker error (attempt " + consecutiveErrors + "): " + e.getMessage()
            );
        }

        // If too many errors - try to reconnect to DB
        if (consecutiveErrors >= 10) {
            marketManager.getPlugin().getLogger().severe(
                "Market cache worker: too many errors, attempting DB reconnect..."
            );
            tryReconnectDatabase();
            consecutiveErrors = 0; // Reset after attempt
        }
    }

    /**
     * Attempts to reconnect to DB
     */
    private void tryReconnectDatabase() {
        try {
            marketManager.getDatabase().disconnect();
            marketManager.getDatabase().connect();
            marketManager.getPlugin().getLogger().info("Database reconnected successfully");
        } catch (Exception e) {
            marketManager.getPlugin().getLogger().severe("Failed to reconnect: " + e.getMessage());
        }
    }

    /**
     * Updates market cache from DB
     */
    private void updateCache() {
        try {
            // Active from DB
            List<Market> active = marketManager.getDatabase().getActiveMarkets();

            // Closed from DB
            List<Market> closed = marketManager.getDatabase().getClosedMarkets();

            // All markets
            List<Market> all = new ArrayList<>();
            all.addAll(active);
            all.addAll(closed);

            // Sort: active first, then by ID descending
            all.sort((a, b) -> {
                if (a.isActive() && !b.isActive()) return -1;
                if (!a.isActive() && b.isActive()) return 1;
                return Integer.compare(b.getId(), a.getId());
            });

            // Atomically update cache
            cachedMarkets.clear();
            cachedMarkets.addAll(all);

            lastCacheUpdate = System.currentTimeMillis();

            // Success - reset error counter
            consecutiveErrors = 0;

        } catch (SQLException e) {
            // Rethrow for handling in handleWorkerError
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns markets from cache depending on filter
     * In-memory filtering - microseconds
     */
    private List<Market> getCachedMarkets(String playerName) {
        String filter = playerFilters.getOrDefault(playerName, "all");

        return switch (filter) {
            case "active" -> cachedMarkets.stream()
                .filter(Market::isActive)
                .toList();
            case "my" -> getPlayerMarkets(playerName);
            default -> new ArrayList<>(cachedMarkets);
        };
    }

    /**
     * Returns markets where the player has bets
     */
    private List<Market> getPlayerMarkets(String playerName) {
        try {
            // Get IDs of markets where the player placed bets
            List<Integer> marketIds = marketManager.getDatabase().getPlayerMarketIds(playerName);

            // Filter from cache - instant
            return cachedMarkets.stream()
                .filter(m -> marketIds.contains(m.getId()))
                .toList();
        } catch (SQLException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Helpers for quick access to statistics
     */
    private int getActiveCount() {
        return (int) cachedMarkets.stream().filter(Market::isActive).count();
    }

    private int getTotalCount() {
        return cachedMarkets.size();
    }

    /**
     * Opens the main markets menu
     */
    public void openMainMenu(Player player, int page) {
        String playerName = player.getName();
        String filter = playerFilters.getOrDefault(playerName, "all");

        String filterTag = switch (filter) {
            case "active" -> "§a[Active]";
            case "my" -> "§e[My Bets]";
            default -> "§7[All]";
        };
        Inventory inv = Bukkit.createInventory(null, SIZE, "§6§lMarkets " + filterTag + " §8P" + (page + 1));

        // Get markets depending on filter
        List<Market> allMarkets = getAllMarkets(playerName);

        // Pagination
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, allMarkets.size());

        // Fill with markets
        for (int i = startIndex; i < endIndex; i++) {
            Market market = allMarkets.get(i);
            ItemStack item = createMarketItem(market);
            inv.setItem(i - startIndex, item);
        }

        // Bottom navigation bar (row 6)
        fillNavigationBar(inv, page, allMarkets.size(), filter);

        openGUIs.add(player.getName());
        player.openInventory(inv);
    }

    /**
     * Opens a specific market menu
     */
    public void openMarketDetail(Player player, int marketId) {
        Market market = marketManager.getMarket(marketId);
        if (market == null) {
            // Try from DB (closed market)
            try {
                market = marketManager.getDatabase().getMarket(marketId);
            } catch (SQLException e) {
                player.sendMessage("§cMarket not found");
                return;
            }
        }

        if (market == null) {
            player.sendMessage("§cMarket not found");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, 27, "§6§lMarket #" + marketId);

        // Market info (center)
        inv.setItem(4, createMarketInfoItem(market));

        // YES button (left)
        inv.setItem(10, createBetButton(market, "yes"));

        // NO button (right)
        inv.setItem(16, createBetButton(market, "no"));

        // Bet statistics
        inv.setItem(13, createBetStatsItem(marketId));

        // Back button
        inv.setItem(22, createBackButton());

        // Filler
        ItemStack filler = createFiller();
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) {
                inv.setItem(i, filler);
            }
        }

        openGUIs.add(player.getName());
        player.openInventory(inv);
    }

    /**
     * Opens the player positions menu (My Positions)
     */
    public void openPositionsMenu(Player player, int page) {
        String playerName = player.getName();

        List<Database.PlayerPosition> positions;
        try {
            positions = marketManager.getDatabase().getPlayerPositions(playerName);
        } catch (SQLException e) {
            player.sendMessage("§cError loading positions");
            return;
        }

        if (positions.isEmpty()) {
            player.sendMessage("§eYou have no positions yet. Place some bets!");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, SIZE, "§e§lMy Positions §8P" + (page + 1));

        // Pagination
        int startIndex = page * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, positions.size());

        // Fill with positions
        for (int i = startIndex; i < endIndex; i++) {
            Database.PlayerPosition pos = positions.get(i);
            ItemStack item = createPositionItem(pos);
            inv.setItem(i - startIndex, item);
        }

        // Navigation
        fillPositionsNavigationBar(inv, page, positions.size(), playerName);

        openGUIs.add(playerName);
        player.openInventory(inv);
    }

    private ItemStack createPositionItem(Database.PlayerPosition pos) {
        // Material depends on status
        Material material;
        if (pos.isActive()) {
            material = pos.getOutcome().equals("yes") ? Material.LIME_WOOL : Material.RED_WOOL;
        } else {
            material = pos.isWin() ? Material.EMERALD_BLOCK : Material.COAL_BLOCK;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        // Name
        String statusIcon;
        if (pos.isActive()) {
            statusIcon = "§a§l[ACTIVE]";
        } else if (pos.isWin()) {
            statusIcon = "§2§l[WON]";
        } else {
            statusIcon = "§4§l[LOST]";
        }

        String outcomeColor = pos.getOutcome().equals("yes") ? "§a" : "§c";
        meta.setDisplayName(statusIcon + " §f#" + pos.getMarketId() + " " + outcomeColor + pos.getOutcome().toUpperCase());

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("§7Market: §f" + pos.getMarketType().toUpperCase());
        lore.add("§f" + pos.getMarketDescription());
        lore.add("");
        lore.add("§7Your position:");
        lore.add("§e  Bet on: " + outcomeColor + pos.getOutcome().toUpperCase());
        lore.add("§e  Size: §f" + pos.getTotalAmount());
        lore.add("§e  Bets: §f" + pos.getBetCount());
        lore.add("");

        if (pos.isActive()) {
            lore.add("§a>>> PENDING <<<");
            lore.add("§7Waiting for result...");
        } else {
            if (pos.isWin()) {
                lore.add("§2§l>>> YOU WON! <<<");
            } else {
                lore.add("§4§l>>> LOST <<<");
            }
            lore.add("§7Result: §f" + (pos.getWinnerOutcome() != null ? pos.getWinnerOutcome().toUpperCase() : "N/A"));
        }

        lore.add("");
        lore.add("§e▶ Click to view market");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void fillPositionsNavigationBar(Inventory inv, int page, int totalPositions, String playerName) {
        ItemStack filler = createFiller();

        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler);
        }

        // Previous page
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.setDisplayName("§e← Previous Page");
            prev.setItemMeta(prevMeta);
            inv.setItem(45, prev);
        }

        // Statistics
        int totalPages = (int) Math.ceil((double) totalPositions / ITEMS_PER_PAGE);
        ItemStack info = new ItemStack(Material.GOLD_INGOT);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName("§e§lYour Positions");
        List<String> infoLore = new ArrayList<>();
        infoLore.add("§7Total positions: §f" + totalPositions);
        infoLore.add("§7Page: §f" + (page + 1) + "/" + Math.max(1, totalPages));
        infoMeta.setLore(infoLore);
        info.setItemMeta(infoMeta);
        inv.setItem(49, info);

        // Next page
        if ((page + 1) * ITEMS_PER_PAGE < totalPositions) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.setDisplayName("§eNext Page →");
            next.setItemMeta(nextMeta);
            inv.setItem(53, next);
        }

        // Back to markets button
        ItemStack back = new ItemStack(Material.BARRIER);
        ItemMeta backMeta = back.getItemMeta();
        backMeta.setDisplayName("§c§l← Back to Markets");
        back.setItemMeta(backMeta);
        inv.setItem(52, back);
    }

    // ==================== ITEM CREATION ====================

    private ItemStack createMarketItem(Market market) {
        Material material = getMarketMaterial(market);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        // Name with color and status
        if (market.isActive()) {
            meta.setDisplayName("§a§l[ACTIVE] §f#" + market.getId() + " §e" + market.getType().toUpperCase());
        } else {
            String outcome = market.getWinnerOutcome();
            // YES = condition met, NO = condition not met
            boolean completed = outcome != null && outcome.equals("yes");
            String statusTag = completed ? "§2§l[YES]" : "§4§l[NO]";
            meta.setDisplayName(statusTag + " §7#" + market.getId() + " §8" + market.getType().toUpperCase());
        }

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("§f" + market.getDescription());
        lore.add("");

        if (market.isActive()) {
            lore.add("§a§l>>> ACTIVE <<<");
            lore.add("§7Time left: §e" + market.getRemainingTimeFormatted());

            int progress = marketManager.getProgress(market.getId());
            if (!market.getType().equals("weather")) {
                lore.add("§7Progress: §f" + progress + "/" + market.getValue());
            }
            lore.add("");
            lore.add("§e▶ Click to bet!");
        } else {
            String outcome = market.getWinnerOutcome();
            boolean won = outcome != null && outcome.equals("yes");
            String result = won ? "§a§lCOMPLETED" : "§c§lFAILED";
            lore.add("§8Status: " + result);
            lore.add("");
            lore.add("§8(Market closed)");
        }

        lore.add("");
        lore.add("§7Created by: §f" + market.getCreatedBy());
        lore.add("");
        lore.add("§e▶ Click to view details");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createMarketInfoItem(Market market) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName("§6§l" + market.getDescription());

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("§7Type: §f" + market.getType());
        lore.add("§7Player: §f" + (market.isAnyPlayer() ? "Anyone" : market.getPlayer()));

        if (market.getTarget() != null) {
            lore.add("§7Target: §f" + market.getTarget());
        }

        lore.add("§7Condition: §f" + market.getOperator() + market.getValue());
        lore.add("");

        if (market.isActive()) {
            lore.add("§aSTATUS: ACTIVE");
            lore.add("§7Time left: §f" + market.getRemainingTimeFormatted());
        } else {
            String result = market.getWinnerOutcome().equals("yes") ? "§aCOMPLETED" : "§cFAILED";
            lore.add("§7STATUS: " + result);
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBetButton(Market market, String outcome) {
        boolean isYes = outcome.equals("yes");
        Material material = isYes ? Material.LIME_CONCRETE : Material.RED_CONCRETE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName((isYes ? "§a§l" : "§c§l") + "BET " + outcome.toUpperCase());

        List<String> lore = new ArrayList<>();

        if (market.isActive()) {
            lore.add("");
            lore.add("§7Click to bet your held items");
            lore.add("§7on " + outcome.toUpperCase());
            lore.add("");
            lore.add("§e  Hold items in hand & click!");
        } else {
            lore.add("");
            lore.add("§8Market is closed");
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBetStatsItem(int marketId) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName("§e§lBet Statistics");

        List<String> lore = new ArrayList<>();
        lore.add("");

        try {
            List<Database.Bet> bets = marketManager.getDatabase().getBetsForMarket(marketId);

            int totalYes = 0, totalNo = 0;
            int countYes = 0, countNo = 0;

            for (Database.Bet bet : bets) {
                if ("yes".equals(bet.getOutcome())) {
                    totalYes += bet.getAmount();
                    countYes++;
                } else {
                    totalNo += bet.getAmount();
                    countNo++;
                }
            }

            int total = totalYes + totalNo;
            int yesPercent = total > 0 ? (totalYes * 100) / total : 0;
            int noPercent = total > 0 ? 100 - yesPercent : 0;

            lore.add("§7Total pool: §e" + total);
            lore.add("");
            lore.add("§aYES: §f" + totalYes + " (" + yesPercent + "%)");
            lore.add("§7  Bets: " + countYes);
            lore.add("");
            lore.add("§cNO: §f" + totalNo + " (" + noPercent + "%)");
            lore.add("§7  Bets: " + countNo);

        } catch (SQLException e) {
            lore.add("§cError loading stats");
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBackButton() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§c§l← Back to list");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(" ");
        item.setItemMeta(meta);
        return item;
    }

    private void fillNavigationBar(Inventory inv, int page, int totalMarkets, String filter) {
        ItemStack filler = createFiller();

        // Fill bottom row
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, filler);
        }

        // "Previous page" button
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta prevMeta = prev.getItemMeta();
            prevMeta.setDisplayName("§e← Previous Page");
            prev.setItemMeta(prevMeta);
            inv.setItem(45, prev);
        }

        // Page info (center)
        int totalPages = (int) Math.ceil((double) totalMarkets / ITEMS_PER_PAGE);
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName("§6Page " + (page + 1) + " of " + Math.max(1, totalPages));
        List<String> infoLore = new ArrayList<>();
        infoLore.add("§7Showing: " + totalMarkets);
        infoLore.add("§7Active: " + getActiveCount());
        infoLore.add("§7Total: " + getTotalCount());
        infoMeta.setLore(infoLore);
        info.setItemMeta(infoMeta);
        inv.setItem(49, info);

        // "Next page" button
        if ((page + 1) * ITEMS_PER_PAGE < totalMarkets) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = next.getItemMeta();
            nextMeta.setDisplayName("§eNext Page →");
            next.setItemMeta(nextMeta);
            inv.setItem(53, next);
        }

        // "All" filter (slot 46)
        boolean isAll = filter.equals("all");
        ItemStack allFilter = new ItemStack(isAll ? Material.WHITE_CONCRETE : Material.GRAY_DYE);
        ItemMeta allMeta = allFilter.getItemMeta();
        allMeta.setDisplayName(isAll ? "§f§l► All" : "§7All");
        allFilter.setItemMeta(allMeta);
        inv.setItem(46, allFilter);

        // "Active" filter (slot 48)
        boolean isActive = filter.equals("active");
        ItemStack activeFilter = new ItemStack(isActive ? Material.LIME_CONCRETE : Material.LIME_DYE);
        ItemMeta activeMeta = activeFilter.getItemMeta();
        activeMeta.setDisplayName(isActive ? "§a§l► Active" : "§aActive");
        activeFilter.setItemMeta(activeMeta);
        inv.setItem(48, activeFilter);

        // "My Bets" filter (slot 50)
        boolean isMy = filter.equals("my");
        ItemStack myFilter = new ItemStack(isMy ? Material.GOLD_BLOCK : Material.GOLD_NUGGET);
        ItemMeta myMeta = myFilter.getItemMeta();
        myMeta.setDisplayName(isMy ? "§e§l► My Bets" : "§eMy Bets");
        myFilter.setItemMeta(myMeta);
        inv.setItem(50, myFilter);

        // "My Positions" button (slot 52) - detailed view of bets
        ItemStack positionsBtn = new ItemStack(Material.BOOK);
        ItemMeta posMeta = positionsBtn.getItemMeta();
        posMeta.setDisplayName("§d§lMy Positions");
        List<String> posLore = new ArrayList<>();
        posLore.add("§7View detailed info about");
        posLore.add("§7your bets and winnings");
        posMeta.setLore(posLore);
        positionsBtn.setItemMeta(posMeta);
        inv.setItem(52, positionsBtn);
    }

    private Material getMarketMaterial(Market market) {
        // Different materials for different market types
        String baseType = market.getType();

        // First check status
        if (!market.isActive()) {
            String outcome = market.getWinnerOutcome();
            return (outcome != null && outcome.equals("yes")) ?
                Material.GREEN_TERRACOTTA : Material.RED_TERRACOTTA;
        }

        // For active - by type
        return switch (baseType) {
            case "kill" -> Material.DIAMOND_SWORD;
            case "deaths" -> Material.SKELETON_SKULL;
            case "pos", "height" -> Material.COMPASS;
            case "distance" -> Material.LEATHER_BOOTS;
            case "break" -> Material.IRON_PICKAXE;
            case "place" -> Material.BRICKS;
            case "pickup" -> Material.CHEST;
            case "drop" -> Material.DROPPER;
            case "held" -> Material.STICK;
            case "weather" -> Material.WATER_BUCKET;
            case "level" -> Material.EXPERIENCE_BOTTLE;
            default -> Material.PAPER;
        };
    }

    private List<Market> getAllMarkets(String playerName) {
        // Return from cache (updated by worker every 2 sec)
        return getCachedMarkets(playerName);
    }

    // ==================== CLICK HANDLING ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();

        // Check if this is our menu
        if (!title.startsWith("§6§lMarket") && !title.startsWith("§e§lMy Positions")) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        // Positions menu
        if (title.startsWith("§e§lMy Positions")) {
            handlePositionsMenuClick(player, event, title);
            return;
        }

        // Main markets menu
        if (title.contains("[") || title.contains("P")) {
            handleMainMenuClick(player, event, title);
        }
        // Market details
        else if (title.contains("#")) {
            handleDetailMenuClick(player, event, title);
        }
    }

    private void handleMainMenuClick(Player player, InventoryClickEvent event, String title) {
        ItemStack clicked = event.getCurrentItem();
        String displayName = clicked.getItemMeta().getDisplayName();

        // Parse current page (format: "§6§lMarkets §a[Active] §8P1")
        int page = 0;
        try {
            String pageStr = title.replaceAll(".*P(\\d+).*", "$1");
            page = Integer.parseInt(pageStr) - 1;
        } catch (Exception e) {
            // ignore
        }

        // Filters (check by display name content)
        if (displayName.contains("All") && !displayName.contains("My")) {
            playerFilters.put(player.getName(), "all");
            openMainMenu(player, 0);
            return;
        }

        if (displayName.contains("Active")) {
            playerFilters.put(player.getName(), "active");
            openMainMenu(player, 0);
            return;
        }

        if (displayName.contains("My Bets")) {
            playerFilters.put(player.getName(), "my");
            openMainMenu(player, 0);
            return;
        }

        // "My Positions" button - open detailed view
        if (displayName.contains("My Positions")) {
            openPositionsMenu(player, 0);
            return;
        }

        // Previous page
        if (displayName.contains("Previous")) {
            openMainMenu(player, page - 1);
            return;
        }

        // Next page
        if (displayName.contains("Next")) {
            openMainMenu(player, page + 1);
            return;
        }

        // Click on market (contains #ID)
        if (displayName.contains("#")) {
            try {
                String idStr = displayName.replaceAll(".*#(\\d+).*", "$1");
                int marketId = Integer.parseInt(idStr);
                openMarketDetail(player, marketId);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void handleDetailMenuClick(Player player, InventoryClickEvent event, String title) {
        ItemStack clicked = event.getCurrentItem();
        String displayName = clicked.getItemMeta().getDisplayName();

        // Back button
        if (displayName.contains("Back")) {
            openMainMenu(player, 0);
            return;
        }

        // Parse market ID from title
        int marketId;
        try {
            String idStr = title.replaceAll(".*#(\\d+).*", "$1");
            marketId = Integer.parseInt(idStr);
        } catch (Exception e) {
            return;
        }

        Market market = marketManager.getMarket(marketId);
        if (market == null || !market.isActive()) {
            player.sendMessage("§cMarket is not active");
            return;
        }

        // Bet YES or NO — bets whatever items the player is holding
        if (displayName.contains("BET YES") || displayName.contains("BET NO")) {
            String outcome = displayName.contains("YES") ? "yes" : "no";

            int finalMarketId = marketId;
            int heldValue = ItemValues.getHeldValue(player);
            marketManager.placeBet(marketId, player, outcome,
                    // onSuccess
                    () -> {
                        player.sendMessage("§aBet placed: items worth " + heldValue + " on " + outcome.toUpperCase());
                        openMarketDetail(player, finalMarketId);
                    },
                    // onError
                    error -> player.sendMessage("§c" + error)
            );
        }
    }

    private void handlePositionsMenuClick(Player player, InventoryClickEvent event, String title) {
        ItemStack clicked = event.getCurrentItem();
        String displayName = clicked.getItemMeta().getDisplayName();

        // Parse page
        int page = 0;
        try {
            String pageStr = title.replaceAll(".*P(\\d+).*", "$1");
            page = Integer.parseInt(pageStr) - 1;
        } catch (Exception e) {
            // ignore
        }

        // Back to markets button
        if (displayName.contains("Back to Markets")) {
            openMainMenu(player, 0);
            return;
        }

        // Previous page
        if (displayName.contains("Previous")) {
            openPositionsMenu(player, page - 1);
            return;
        }

        // Next page
        if (displayName.contains("Next")) {
            openPositionsMenu(player, page + 1);
            return;
        }

        // Click on position - open market details
        if (displayName.contains("#")) {
            try {
                String idStr = displayName.replaceAll(".*#(\\d+).*", "$1");
                int marketId = Integer.parseInt(idStr);
                openMarketDetail(player, marketId);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openGUIs.remove(player.getName());
        }
    }
}

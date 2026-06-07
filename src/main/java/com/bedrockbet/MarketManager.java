package com.bedrockbet;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class MarketManager {

    private final BedrockBet plugin;
    private final Database database;
    private final Logger logger;

    // Active markets in memory for fast access
    private final Map<Integer, Market> activeMarkets = new ConcurrentHashMap<>();

    // Market progress (marketId -> (playerName -> progress))
    // For markets with "*" we track each player individually
    private final Map<Integer, Map<String, Integer>> marketProgress = new ConcurrentHashMap<>();

    // Locks for preventing race conditions
    private final Set<Integer> resolvingMarkets = ConcurrentHashMap.newKeySet(); // markets being resolved
    private final Map<String, Object> playerLocks = new ConcurrentHashMap<>();   // per-player locks

    private BukkitTask checkTask;

    public MarketManager(BedrockBet plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        this.logger = plugin.getLogger();
    }

    public void start() {
        // Load active markets from DB (async)
        new BukkitRunnable() {
            @Override
            public void run() {
                loadActiveMarkets();
            }
        }.runTaskAsynchronously(plugin);

        // Start task for checking expired markets (async - does not block the game)
        checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkExpiredMarkets, 20L, 20L);
    }

    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
        }
    }

    private void loadActiveMarkets() {
        try {
            List<Market> markets = database.getActiveMarkets();
            for (Market market : markets) {
                activeMarkets.put(market.getId(), market);
            }
            logger.info("Loaded " + markets.size() + " active markets");
        } catch (SQLException e) {
            logger.severe("Failed to load active markets: " + e.getMessage());
        }
    }

    // ==================== MARKET CREATION ====================

    public Market createMarket(String[] args, String createdBy) throws Exception {
        Market market = Market.parse(args, createdBy);

        // Create in DB (sync - need ID immediately)
        int id = database.createMarket(market);
        market.setId(id);

        activeMarkets.put(id, market);
        marketProgress.put(id, new ConcurrentHashMap<>());

        // Log async
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    database.logEvent(id, "MARKET_CREATED", market.getDescription());
                } catch (SQLException e) {
                    logger.warning("Failed to log market creation: " + e.getMessage());
                }
            }
        }.runTaskAsynchronously(plugin);

        broadcast("§6[Market] §a#" + id + " §f" + market.getDescription());
        broadcast("§7Use: §e/market bet " + id + " yes/no <amount>");

        return market;
    }

    // ==================== BETS ====================

    /**
     * Places a bet using items held in the player's main hand.
     * Must be called from the main thread (accesses inventory).
     */
    public void placeBet(int marketId, Player playerObj, String outcome,
                         Runnable onSuccess, java.util.function.Consumer<String> onError) {
        String player = playerObj.getName();
        Market market = activeMarkets.get(marketId);

        if (market == null) {
            onError.accept("Market #" + marketId + " not found or not active");
            return;
        }

        if (!market.isActive()) {
            onError.accept("Market #" + marketId + " is not active");
            return;
        }

        if (!outcome.equals("yes") && !outcome.equals("no")) {
            onError.accept("Outcome must be 'yes' or 'no'");
            return;
        }

        // Check held item value (main thread)
        int heldValue = ItemValues.getHeldValue(playerObj);
        if (heldValue <= 0) {
            onError.accept("Hold a valuable item to bet! Use /market values to see accepted items");
            return;
        }

        // Take items from hand (main thread)
        int taken = ItemValues.takeFromHand(playerObj, heldValue);
        if (taken <= 0) {
            onError.accept("Failed to take items from hand");
            return;
        }

        final int betAmount = taken;

        // Record bet in DB (async)
        new BukkitRunnable() {
            @Override
            public void run() {
                Object lock = playerLocks.computeIfAbsent(player, k -> new Object());
                synchronized (lock) {
                    try {
                        database.placeBet(marketId, player, betAmount, outcome);
                        database.logEvent(marketId, "BET_PLACED", player + " bet " + betAmount + " (items) on " + outcome);
                    } catch (SQLException e) {
                        logger.warning("Failed to save bet: " + e.getMessage());
                        // Give items back on the main thread
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                ItemValues.giveItems(playerObj, betAmount);
                                onError.accept("Database error — items returned");
                            }
                        }.runTask(plugin);
                        return;
                    }
                }

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        onSuccess.run();
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    // ==================== EVENT HANDLING ====================

    /**
     * Called from GameEventListener on every event
     */
    public void onGameEvent(GameEventListener.GameEvent event) {
        for (Market market : activeMarkets.values()) {
            if (!market.isActive()) continue;

            try {
                checkMarketEvent(market, event);
            } catch (Exception e) {
                logger.warning("Error checking market #" + market.getId() + ": " + e.getMessage());
            }
        }
    }

    private void checkMarketEvent(Market market, GameEventListener.GameEvent event) {
        String eventType = event.getType();
        String marketType = market.getType();

        boolean matches = switch (marketType) {
            case "kill" -> checkKillEvent(market, event);
            case "deaths" -> checkDeathEvent(market, event);
            case "pos", "height" -> checkPosEvent(market, event);
            case "distance" -> checkDistanceEvent(market, event);
            case "break" -> checkBreakEvent(market, event);
            case "place" -> checkPlaceEvent(market, event);
            case "pickup" -> checkPickupEvent(market, event);
            case "drop" -> checkDropEvent(market, event);
            case "held" -> checkHeldEvent(market, event);
            case "weather" -> checkWeatherEvent(market, event);
            case "level" -> checkLevelEvent(market, event);
            default -> false;
        };

        if (matches) {
            // Get the player from the event
            String playerName = event.getString("player");
            if (playerName == null) playerName = event.getString("killer"); // for kill events

            // Increment progress for this player
            int progress = addProgress(market.getId(), playerName, 1);

            // Check condition - resolve async
            if (market.checkCondition(progress)) {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        resolveMarket(market, "yes");
                    }
                }.runTaskAsynchronously(plugin);
            }
        }
    }

    /**
     * Adds progress for a player in a market
     * @return the player's new progress
     */
    private int addProgress(int marketId, String playerName, int amount) {
        Map<String, Integer> playerProgress = marketProgress.computeIfAbsent(marketId, k -> new ConcurrentHashMap<>());
        int newProgress = playerProgress.getOrDefault(playerName, 0) + amount;
        playerProgress.put(playerName, newProgress);
        return newProgress;
    }

    /**
     * Gets the maximum progress among all players in a market
     */
    private int getMaxProgress(int marketId) {
        Map<String, Integer> playerProgress = marketProgress.get(marketId);
        if (playerProgress == null || playerProgress.isEmpty()) return 0;
        return playerProgress.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private boolean checkKillEvent(Market market, GameEventListener.GameEvent event) {
        if (!"DEATH".equals(event.getType())) return false;

        String killer = event.getString("killer");
        String victim = event.getString("victim");

        // Check that the killer is our player (supports * and any)
        if (!market.matchesPlayer(killer)) return false;

        // Check the target
        String target = market.getTarget();
        return target.equalsIgnoreCase(victim) ||
               target.equalsIgnoreCase(event.getString("victimType"));
    }

    private boolean checkDeathEvent(Market market, GameEventListener.GameEvent event) {
        if (!"DEATH".equals(event.getType())) return false;

        String victim = event.getString("victim");
        String victimType = event.getString("victimType");

        // Supports * and any
        return "player".equals(victimType) && market.matchesPlayer(victim);
    }

    private boolean checkPosEvent(Market market, GameEventListener.GameEvent event) {
        if (!"MOVE".equals(event.getType())) return false;

        String player = event.getString("player");
        // Supports * and any
        if (!market.matchesPlayer(player)) return false;

        int x = event.getInt("x");
        int y = event.getInt("y");
        int z = event.getInt("z");

        return market.checkPosCondition(x, y, z);
    }

    private boolean checkDistanceEvent(Market market, GameEventListener.GameEvent event) {
        // Distance is checked by accumulated statistics
        // This is a special case - checked in checkExpiredMarkets
        return false;
    }

    private boolean checkBreakEvent(Market market, GameEventListener.GameEvent event) {
        if (!"BREAK".equals(event.getType())) return false;

        String player = event.getString("player");
        String block = event.getString("block");

        return market.matchesPlayer(player) &&
               market.getTarget().equalsIgnoreCase(block);
    }

    private boolean checkPlaceEvent(Market market, GameEventListener.GameEvent event) {
        if (!"PLACE".equals(event.getType())) return false;

        String player = event.getString("player");
        String block = event.getString("block");

        return market.matchesPlayer(player) &&
               market.getTarget().equalsIgnoreCase(block);
    }

    private boolean checkPickupEvent(Market market, GameEventListener.GameEvent event) {
        if (!"PICKUP".equals(event.getType())) return false;

        String player = event.getString("player");
        String item = event.getString("item");

        if (!market.matchesPlayer(player)) return false;
        if (!market.getTarget().equalsIgnoreCase(item)) return false;

        // For pickup add amount-1 (another +1 will be added in checkMarketEvent)
        int amount = event.getInt("amount");
        if (amount > 1) {
            addProgress(market.getId(), player, amount - 1);
        }

        return true;
    }

    private boolean checkDropEvent(Market market, GameEventListener.GameEvent event) {
        if (!"DROP".equals(event.getType())) return false;

        String player = event.getString("player");
        String item = event.getString("item");

        if (!market.matchesPlayer(player)) return false;
        if (!market.getTarget().equalsIgnoreCase(item)) return false;

        int amount = event.getInt("amount");
        if (amount > 1) {
            addProgress(market.getId(), player, amount - 1);
        }

        return true;
    }

    private boolean checkHeldEvent(Market market, GameEventListener.GameEvent event) {
        if (!"HELD".equals(event.getType())) return false;

        String player = event.getString("player");
        String item = event.getString("item");

        return market.matchesPlayer(player) &&
               market.getTarget().equalsIgnoreCase(item);
    }

    private boolean checkWeatherEvent(Market market, GameEventListener.GameEvent event) {
        if (!"WEATHER".equals(event.getType())) return false;

        boolean isRaining = (boolean) event.get("raining");
        String target = market.getTarget();

        return ("rain".equals(target) && isRaining) ||
               ("clear".equals(target) && !isRaining);
    }

    private boolean checkLevelEvent(Market market, GameEventListener.GameEvent event) {
        if (!"LEVEL".equals(event.getType())) return false;

        String player = event.getString("player");
        int newLevel = event.getInt("newLevel");

        // Check the player (supports * and any)
        if (!market.matchesPlayer(player)) return false;

        // For level we set the current player level (not cumulative)
        Map<String, Integer> playerProgress = marketProgress.computeIfAbsent(market.getId(), k -> new ConcurrentHashMap<>());
        playerProgress.put(player, newLevel);

        return market.checkCondition(newLevel);
    }

    // ==================== EXPIRED MARKET CHECK ====================

    private void checkExpiredMarkets() {
        try {
            for (Market market : activeMarkets.values()) {
                if (!market.isActive()) continue;

                if (market.isExpired()) {
                    // Time expired - check if condition is met
                    // Get the maximum progress among all players
                    int progress = getMaxProgress(market.getId());

                    // For deaths with =0 special logic is needed
                    if ("deaths".equals(market.getType()) && "=".equals(market.getOperator()) && market.getValue() == 0) {
                        // If progress == 0, means never died - YES wins
                        resolveMarket(market, progress == 0 ? "yes" : "no");
                    } else {
                        // Standard logic - if condition is not met, NO wins
                        resolveMarket(market, "no");
                    }
                }
            }
        } catch (Exception e) {
            // Catch ALL exceptions so the worker does not die
            logger.warning("Error in checkExpiredMarkets worker: " + e.getMessage());
            // Worker will continue on the next iteration
        }
    }

    // ==================== MARKET RESOLUTION ====================

    /**
     * Resolves a market (called from an async thread)
     */
    private void resolveMarket(Market market, String winnerOutcome) {
        // Atomic check - if already being resolved, exit
        if (!resolvingMarkets.add(market.getId())) {
            return; // Another thread is already resolving this market
        }

        if (!market.isActive()) {
            resolvingMarkets.remove(market.getId());
            return;
        }

        // Mark immediately
        market.setStatus("resolved");
        market.setWinnerOutcome(winnerOutcome);

        try {
            database.beginTransaction();

            database.updateMarketStatus(market.getId(), "resolved", winnerOutcome);

            List<Database.Bet> bets = database.getBetsForMarket(market.getId());

            int totalYes = 0, totalNo = 0;
            for (Database.Bet bet : bets) {
                if ("yes".equals(bet.getOutcome())) totalYes += bet.getAmount();
                else totalNo += bet.getAmount();
            }

            int totalPool = totalYes + totalNo;
            int winnerPool = winnerOutcome.equals("yes") ? totalYes : totalNo;

            // Calculate payouts for winners
            String biggestWinnerName = null;
            int biggestPayout = 0;
            int winnersCount = 0;
            int losersCount = 0;
            List<int[]> winnerPayouts = new ArrayList<>(); // [betIndex, payout]

            for (int i = 0; i < bets.size(); i++) {
                Database.Bet bet = bets.get(i);
                if (bet.getOutcome().equals(winnerOutcome) && winnerPool > 0) {
                    double ratio = (double) bet.getAmount() / winnerPool;
                    int payout = (int) (totalPool * ratio);
                    winnersCount++;

                    if (payout > biggestPayout) {
                        biggestPayout = payout;
                        biggestWinnerName = bet.getPlayer();
                    }

                    winnerPayouts.add(new int[]{i, payout});
                } else {
                    losersCount++;
                }
            }

            database.logEvent(market.getId(), "MARKET_RESOLVED", "Winner: " + winnerOutcome);

            database.commitTransaction();

            // === Give items to winners on the main thread ===

            for (int[] wp : winnerPayouts) {
                Database.Bet bet = bets.get(wp[0]);
                String playerName = bet.getPlayer();
                int finalPayout = wp[1];
                int profit = finalPayout - bet.getAmount();
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Player p = Bukkit.getPlayer(playerName);
                        if (p != null && p.isOnline()) {
                            ItemValues.giveItems(p, finalPayout);
                            p.sendMessage("§a[Market] §fYou won items worth §e" + formatNum(finalPayout) +
                                " §f(+" + formatNum(profit) + " profit) §7on market §a#" + market.getId());
                        }
                    }
                }.runTask(plugin);
            }

            String result = winnerOutcome.equals("yes") ? "§aCOMPLETED" : "§cFAILED";
            final String topWinner = biggestWinnerName;
            final int topPayout = biggestPayout;
            final int fWinners = winnersCount;
            final int fLosers = losersCount;
            final int fTotalPool = totalPool;
            final int fTotalYes = totalYes;
            final int fTotalNo = totalNo;

            new BukkitRunnable() {
                @Override
                public void run() {
                    broadcast("§6§l══════ MARKET REVEAL ══════");
                    broadcast("§6[Market] §a#" + market.getId() + " " + result);
                    broadcast("§7" + market.getDescription());
                    broadcast("§7Pool: §eYES " + formatNum(fTotalYes) + " §7/ §eNO " + formatNum(fTotalNo) + " §7(total: §e" + formatNum(fTotalPool) + "§7)");
                    broadcast("§7Winners: §a" + fWinners + " §7| Losers: §c" + fLosers);
                    if (topWinner != null) {
                        broadcast("§7Biggest win: §e" + formatNum(topPayout) + " §7by §f" + topWinner);
                    } else if (bets.isEmpty()) {
                        broadcast("§7No bets were placed");
                    }
                    broadcast("§6§l═══════════════════════");
                }
            }.runTask(plugin);

            activeMarkets.remove(market.getId());
            marketProgress.remove(market.getId());
            resolvingMarkets.remove(market.getId());

        } catch (SQLException e) {
            database.rollbackTransaction();
            market.setStatus("active");
            market.setWinnerOutcome(null);
            logger.severe("Error resolving market #" + market.getId() + ": " + e.getMessage());
            resolvingMarkets.remove(market.getId());
        }
    }

    // ==================== UTILITIES ====================

    public List<Market> getActiveMarketsList() {
        return List.copyOf(activeMarkets.values());
    }

    public Market getMarket(int id) {
        return activeMarkets.get(id);
    }

    public int getProgress(int marketId) {
        return getMaxProgress(marketId);
    }

    public Database getDatabase() {
        return database;
    }

    public BedrockBet getPlugin() {
        return plugin;
    }

    // ==================== PERSISTENCE ====================

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public void saveProgress(File file) {
        // Snapshot to plain map
        Map<String, Map<String, Integer>> snapshot = new HashMap<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : marketProgress.entrySet()) {
            snapshot.put(String.valueOf(entry.getKey()), new HashMap<>(entry.getValue()));
        }
        try (Writer writer = new FileWriter(file)) {
            GSON.toJson(snapshot, writer);
        } catch (IOException e) {
            logger.warning("Failed to save market progress: " + e.getMessage());
        }
    }

    public void loadProgress(File file) {
        if (!file.exists()) return;
        try (Reader reader = new FileReader(file)) {
            Map<String, Map<String, Integer>> data = GSON.fromJson(reader,
                    new TypeToken<Map<String, Map<String, Integer>>>(){}.getType());
            if (data == null) return;
            for (Map.Entry<String, Map<String, Integer>> entry : data.entrySet()) {
                int marketId = Integer.parseInt(entry.getKey());
                if (activeMarkets.containsKey(marketId)) {
                    marketProgress.put(marketId, new ConcurrentHashMap<>(entry.getValue()));
                }
            }
            logger.info("Loaded market progress for " + data.size() + " markets");
        } catch (Exception e) {
            logger.warning("Failed to load market progress: " + e.getMessage());
        }
    }

    static String formatNum(int n) {
        if (n >= 1_000_000) {
            double v = n / 1_000_000.0;
            return (v == (int) v ? String.valueOf((int) v) : String.format("%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "")) + "KK";
        } else if (n >= 1_000) {
            double v = n / 1_000.0;
            return (v == (int) v ? String.valueOf((int) v) : String.format("%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "")) + "K";
        }
        return String.valueOf(n);
    }

    private void broadcast(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(message);
        }
        logger.info(message.replaceAll("§.", ""));
    }
}

package com.bedrockbet;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;

public class BedrockBet extends JavaPlugin {

    private GameEventListener gameListener;
    private Database database;
    private MarketManager marketManager;
    private BalanceScoreboard balanceScoreboard;
    private BalanceLeaderboard balanceLeaderboard;
    private BukkitTask persistenceTask;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        database = new Database(getDataFolder(), getLogger());
        try {
            database.connect();
        } catch (SQLException e) {
            getLogger().severe("Failed to connect to database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        gameListener = new GameEventListener(this);
        getServer().getPluginManager().registerEvents(gameListener, this);

        marketManager = new MarketManager(this, database);
        marketManager.start();
        marketManager.loadProgress(new File(getDataFolder(), "market_progress.json"));

        persistenceTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                marketManager.saveProgress(new File(getDataFolder(), "market_progress.json"));
            } catch (Exception e) {
                getLogger().warning("Periodic save failed: " + e.getMessage());
            }
        }, 600L, 600L);

        gameListener.setEventCallback(event -> marketManager.onGameEvent(event));

        getCommand("stats").setExecutor(new StatsCommand(gameListener.getStats()));

        MarketCommand marketCommand = new MarketCommand(marketManager);
        getCommand("market").setExecutor(marketCommand);
        getCommand("market").setTabCompleter(marketCommand);

        MarketGUI marketGUI = new MarketGUI(marketManager);
        getServer().getPluginManager().registerEvents(marketGUI, this);
        getCommand("markets").setExecutor(new MarketsCommand(marketGUI));

        balanceScoreboard = new BalanceScoreboard(this, database);
        getServer().getPluginManager().registerEvents(balanceScoreboard, this);
        balanceScoreboard.start();

        balanceLeaderboard = new BalanceLeaderboard(this, database);
        balanceLeaderboard.start();
        LeaderboardCommand lbCommand = new LeaderboardCommand(balanceLeaderboard);
        getCommand("leaderboard").setExecutor(lbCommand);
        getCommand("leaderboard").setTabCompleter(lbCommand);

        getLogger().info("BedrockBet plugin started!");
    }

    @Override
    public void onDisable() {
        if (persistenceTask != null) persistenceTask.cancel();

        try {
            if (marketManager != null) {
                marketManager.saveProgress(new File(getDataFolder(), "market_progress.json"));
            }
        } catch (Exception e) {
            getLogger().severe("Failed to save on shutdown: " + e.getMessage());
        }

        if (balanceLeaderboard != null) balanceLeaderboard.stop();
        if (balanceScoreboard != null) balanceScoreboard.stop();
        if (marketManager != null) marketManager.stop();
        if (database != null) database.disconnect();

        getLogger().info("BedrockBet plugin stopped");
    }

    public GameEventListener getGameListener() { return gameListener; }
    public Database getDatabase() { return database; }
    public MarketManager getMarketManager() { return marketManager; }
    public BalanceScoreboard getBalanceScoreboard() { return balanceScoreboard; }
}

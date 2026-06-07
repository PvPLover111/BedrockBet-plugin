package com.bedrockbet;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class Database {

    private final String dbPath;
    private final Logger logger;
    private Connection connection;

    public Database(File dataFolder, Logger logger) {
        this.dbPath = "jdbc:sqlite:" + new File(dataFolder, "markets.db").getAbsolutePath();
        this.logger = logger;
    }

    public void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        connection = DriverManager.getConnection(dbPath);
        logger.info("SQLite database connected");
        createTables();
    }

    public void disconnect() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                logger.info("SQLite database disconnected");
            }
        } catch (SQLException e) {
            logger.warning("Error closing database: " + e.getMessage());
        }
    }

    // ==================== TRANSACTIONS ====================

    private final ReentrantLock txLock = new ReentrantLock();

    public void beginTransaction() throws SQLException {
        txLock.lock();
        try {
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            txLock.unlock();
            throw e;
        }
    }

    public void commitTransaction() throws SQLException {
        try {
            connection.commit();
            connection.setAutoCommit(true);
        } finally {
            txLock.unlock();
        }
    }

    public void rollbackTransaction() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            logger.warning("Failed to rollback transaction: " + e.getMessage());
        } finally {
            txLock.unlock();
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Markets table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS markets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    player TEXT,
                    target TEXT,
                    operator TEXT DEFAULT '>=',
                    value INTEGER DEFAULT 1,
                    pos_conditions TEXT,
                    start_time BIGINT NOT NULL,
                    end_time BIGINT NOT NULL,
                    description TEXT NOT NULL,
                    status TEXT DEFAULT 'active',
                    created_by TEXT NOT NULL,
                    winner_outcome TEXT
                )
            """);

            // Bets table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bets (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    market_id INTEGER NOT NULL,
                    player TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    outcome TEXT NOT NULL,
                    placed_at BIGINT NOT NULL,
                    FOREIGN KEY (market_id) REFERENCES markets(id)
                )
            """);

            // Users table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    nickname TEXT PRIMARY KEY,
                    balance INTEGER DEFAULT 0,
                    created_at BIGINT DEFAULT 0
                )
            """);

            // Migrate old data (if the balances table exists)
            migrateBalancesToUsers(stmt);

            // Event log (for history)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS event_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    market_id INTEGER,
                    event_type TEXT NOT NULL,
                    event_data TEXT,
                    timestamp BIGINT NOT NULL,
                    FOREIGN KEY (market_id) REFERENCES markets(id)
                )
            """);

            // Fees table - tracks all collected commissions
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS fees (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player TEXT NOT NULL,
                    market_id INTEGER NOT NULL,
                    bet_amount INTEGER NOT NULL,
                    fee_amount INTEGER NOT NULL,
                    collected_at BIGINT NOT NULL,
                    FOREIGN KEY (market_id) REFERENCES markets(id)
                )
            """);

            logger.info("Database tables created/verified");
        }
    }

    /**
     * Migrates data from the old balances table to the new users table
     */
    private void migrateBalancesToUsers(Statement stmt) {
        try {
            // Check if the old table exists
            ResultSet rs = stmt.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='balances'"
            );
            if (rs.next()) {
                // Transfer data
                stmt.execute("""
                    INSERT OR IGNORE INTO users (nickname, balance, created_at)
                    SELECT player, balance, 0 FROM balances
                """);
                // Drop the old table
                stmt.execute("DROP TABLE balances");
                logger.info("Migrated balances -> users table");
            }
            rs.close();
        } catch (SQLException e) {
            // Ignore - table already migrated or does not exist
        }
    }

    // ==================== MARKETS ====================

    public int createMarket(Market market) throws SQLException {
        String sql = """
            INSERT INTO markets (type, player, target, operator, value, pos_conditions,
                                 start_time, end_time, description, status, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', ?)
        """;

        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, market.getType());
            ps.setString(2, market.getPlayer());
            ps.setString(3, market.getTarget());
            ps.setString(4, market.getOperator());
            ps.setInt(5, market.getValue());
            ps.setString(6, market.getPosConditions());
            ps.setLong(7, market.getStartTime());
            ps.setLong(8, market.getEndTime());
            ps.setString(9, market.getDescription());
            ps.setString(10, market.getCreatedBy());

            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return -1;
    }

    public Market getMarket(int id) throws SQLException {
        String sql = "SELECT * FROM markets WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return marketFromResultSet(rs);
                }
            }
        }
        return null;
    }

    public List<Market> getActiveMarkets() throws SQLException {
        List<Market> markets = new ArrayList<>();
        String sql = "SELECT * FROM markets WHERE status = 'active' ORDER BY end_time ASC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                markets.add(marketFromResultSet(rs));
            }
        }
        return markets;
    }

    public List<Market> getClosedMarkets() throws SQLException {
        List<Market> markets = new ArrayList<>();
        String sql = "SELECT * FROM markets WHERE status != 'active' ORDER BY id DESC LIMIT 100";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                markets.add(marketFromResultSet(rs));
            }
        }
        return markets;
    }

    public void updateMarketStatus(int marketId, String status, String winnerOutcome) throws SQLException {
        String sql = "UPDATE markets SET status = ?, winner_outcome = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, winnerOutcome);
            ps.setInt(3, marketId);
            ps.executeUpdate();
        }
    }

    private Market marketFromResultSet(ResultSet rs) throws SQLException {
        Market m = new Market();
        m.setId(rs.getInt("id"));
        m.setType(rs.getString("type"));
        m.setPlayer(rs.getString("player"));
        m.setTarget(rs.getString("target"));
        m.setOperator(rs.getString("operator"));
        m.setValue(rs.getInt("value"));
        m.setPosConditions(rs.getString("pos_conditions"));
        m.setStartTime(rs.getLong("start_time"));
        m.setEndTime(rs.getLong("end_time"));
        m.setDescription(rs.getString("description"));
        m.setStatus(rs.getString("status"));
        m.setCreatedBy(rs.getString("created_by"));
        m.setWinnerOutcome(rs.getString("winner_outcome"));
        return m;
    }

    // ==================== BETS ====================

    public void placeBet(int marketId, String player, int amount, String outcome) throws SQLException {
        // If a bet on the same market and same outcome already exists - add to it
        String updateSql = "UPDATE bets SET amount = amount + ?, placed_at = ? WHERE market_id = ? AND player = ? AND outcome = ?";
        try (PreparedStatement ps = connection.prepareStatement(updateSql)) {
            ps.setInt(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, marketId);
            ps.setString(4, player);
            ps.setString(5, outcome);
            int updated = ps.executeUpdate();
            if (updated > 0) return;
        }
        // Otherwise create a new one
        String insertSql = "INSERT INTO bets (market_id, player, amount, outcome, placed_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insertSql)) {
            ps.setInt(1, marketId);
            ps.setString(2, player);
            ps.setInt(3, amount);
            ps.setString(4, outcome);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public List<Bet> getBetsForMarket(int marketId) throws SQLException {
        List<Bet> bets = new ArrayList<>();
        String sql = "SELECT * FROM bets WHERE market_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, marketId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Bet bet = new Bet();
                    bet.setId(rs.getInt("id"));
                    bet.setMarketId(rs.getInt("market_id"));
                    bet.setPlayer(rs.getString("player"));
                    bet.setAmount(rs.getInt("amount"));
                    bet.setOutcome(rs.getString("outcome"));
                    bet.setPlacedAt(rs.getLong("placed_at"));
                    bets.add(bet);
                }
            }
        }
        return bets;
    }

    public List<Integer> getPlayerMarketIds(String player) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT DISTINCT market_id FROM bets WHERE player = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getInt("market_id"));
                }
            }
        }
        return ids;
    }

    /**
     * Gets all bets for a player aggregated by market
     */
    public List<PlayerPosition> getPlayerPositions(String player) throws SQLException {
        List<PlayerPosition> positions = new ArrayList<>();
        // Group bets by market and outcome, sum up amount
        String sql = """
            SELECT
                b.market_id,
                b.outcome,
                SUM(b.amount) as total_amount,
                COUNT(*) as bet_count,
                m.type,
                m.description,
                m.status,
                m.winner_outcome
            FROM bets b
            JOIN markets m ON b.market_id = m.id
            WHERE b.player = ?
            GROUP BY b.market_id, b.outcome
            ORDER BY m.status ASC, b.market_id DESC
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, player);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PlayerPosition pos = new PlayerPosition();
                    pos.setMarketId(rs.getInt("market_id"));
                    pos.setOutcome(rs.getString("outcome"));
                    pos.setTotalAmount(rs.getInt("total_amount"));
                    pos.setBetCount(rs.getInt("bet_count"));
                    pos.setMarketType(rs.getString("type"));
                    pos.setMarketDescription(rs.getString("description"));
                    pos.setMarketStatus(rs.getString("status"));
                    pos.setWinnerOutcome(rs.getString("winner_outcome"));
                    positions.add(pos);
                }
            }
        }
        return positions;
    }

    // DTO for player position
    public static class PlayerPosition {
        private int marketId;
        private String outcome;
        private int totalAmount;
        private int betCount;
        private String marketType;
        private String marketDescription;
        private String marketStatus;
        private String winnerOutcome;

        public int getMarketId() { return marketId; }
        public void setMarketId(int marketId) { this.marketId = marketId; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
        public int getTotalAmount() { return totalAmount; }
        public void setTotalAmount(int totalAmount) { this.totalAmount = totalAmount; }
        public int getBetCount() { return betCount; }
        public void setBetCount(int betCount) { this.betCount = betCount; }
        public String getMarketType() { return marketType; }
        public void setMarketType(String marketType) { this.marketType = marketType; }
        public String getMarketDescription() { return marketDescription; }
        public void setMarketDescription(String marketDescription) { this.marketDescription = marketDescription; }
        public String getMarketStatus() { return marketStatus; }
        public void setMarketStatus(String marketStatus) { this.marketStatus = marketStatus; }
        public String getWinnerOutcome() { return winnerOutcome; }
        public void setWinnerOutcome(String winnerOutcome) { this.winnerOutcome = winnerOutcome; }

        public boolean isActive() { return "active".equals(marketStatus); }
        public boolean isWin() { return winnerOutcome != null && winnerOutcome.equals(outcome); }
    }

    // ==================== USERS ====================

    public int getBalance(String nickname) throws SQLException {
        String sql = "SELECT balance FROM users WHERE nickname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nickname);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("balance");
                }
            }
        }
        // New player - create with zero balance
        createUser(nickname);
        return 0;
    }

    public void setBalance(String nickname, int balance) throws SQLException {
        String sql = "UPDATE users SET balance = ? WHERE nickname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, balance);
            ps.setString(2, nickname);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                // User does not exist - create
                createUser(nickname);
                setBalance(nickname, balance);
            }
        }
    }

    /**
     * Atomically adds amount to the balance and returns the new balance.
     * If the user does not exist, creates them and retries.
     */
    public int addBalance(String nickname, int amount) throws SQLException {
        String sql = "UPDATE users SET balance = balance + ? WHERE nickname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setString(2, nickname);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                createUser(nickname);
                return addBalance(nickname, amount);
            }
        }
        return getBalance(nickname);
    }

    /**
     * Creates a new user
     */
    public void createUser(String nickname) throws SQLException {
        String sql = "INSERT OR IGNORE INTO users (nickname, balance, created_at) VALUES (?, 0, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nickname);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /**
     * Gets user information
     */
    public User getUser(String nickname) throws SQLException {
        String sql = "SELECT * FROM users WHERE nickname = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, nickname);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    User user = new User();
                    user.setNickname(rs.getString("nickname"));
                    user.setBalance(rs.getInt("balance"));
                    user.setCreatedAt(rs.getLong("created_at"));
                    return user;
                }
            }
        }
        return null;
    }

    /**
     * Gets all users
     */
    public List<User> getAllUsers() throws SQLException {
        List<User> users = new ArrayList<>();
        String sql = "SELECT * FROM users ORDER BY balance DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                User user = new User();
                user.setNickname(rs.getString("nickname"));
                user.setBalance(rs.getInt("balance"));
                user.setCreatedAt(rs.getLong("created_at"));
                users.add(user);
            }
        }
        return users;
    }

    // DTO for user
    public static class User {
        private String nickname;
        private int balance;
        private long createdAt;

        public String getNickname() { return nickname; }
        public void setNickname(String nickname) { this.nickname = nickname; }
        public int getBalance() { return balance; }
        public void setBalance(int balance) { this.balance = balance; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    }

    // ==================== EVENT LOG ====================

    public void logEvent(Integer marketId, String eventType, String eventData) throws SQLException {
        String sql = "INSERT INTO event_log (market_id, event_type, event_data, timestamp) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (marketId != null) {
                ps.setInt(1, marketId);
            } else {
                ps.setNull(1, Types.INTEGER);
            }
            ps.setString(2, eventType);
            ps.setString(3, eventData);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // ==================== FEES ====================

    public void recordFee(String player, int marketId, int betAmount, int feeAmount) throws SQLException {
        String sql = "INSERT INTO fees (player, market_id, bet_amount, fee_amount, collected_at) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, player);
            ps.setInt(2, marketId);
            ps.setInt(3, betAmount);
            ps.setInt(4, feeAmount);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public long getTotalFees() throws SQLException {
        String sql = "SELECT COALESCE(SUM(fee_amount), 0) as total FROM fees";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong("total");
            }
        }
        return 0;
    }

    public int getFeeTransactionCount() throws SQLException {
        String sql = "SELECT COUNT(*) as cnt FROM fees";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getInt("cnt");
            }
        }
        return 0;
    }

    // ==================== INNER CLASSES ====================

    public static class Bet {
        private int id;
        private int marketId;
        private String player;
        private int amount;
        private String outcome;
        private long placedAt;

        // Getters & Setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public int getMarketId() { return marketId; }
        public void setMarketId(int marketId) { this.marketId = marketId; }
        public String getPlayer() { return player; }
        public void setPlayer(String player) { this.player = player; }
        public int getAmount() { return amount; }
        public void setAmount(int amount) { this.amount = amount; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
        public long getPlacedAt() { return placedAt; }
        public void setPlacedAt(long placedAt) { this.placedAt = placedAt; }
    }
}

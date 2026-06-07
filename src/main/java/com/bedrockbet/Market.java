package com.bedrockbet;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Market {

    private int id;
    private String type;           // kill, deaths, pos, break, place, pickup, drop, weather, held, distance
    private String player;         // target player (Steve)
    private String target;         // action target (Alex, zombie, diamond_ore, rain)
    private String operator;       // >, <, =, >=, <=
    private int value;             // numeric value (5, 10, 256)
    private String posConditions;  // for pos: "x>100,y>256,z<0"
    private long startTime;        // when created
    private long endTime;          // when it expires
    private String description;    // human-readable description
    private String status;         // active, won, lost, cancelled
    private String createdBy;      // who created it
    private String winnerOutcome;  // yes/no - which outcome won

    // Current progress (not saved in DB, tracked in memory)
    private transient int currentProgress = 0;

    public Market() {
        this.operator = ">=";
        this.value = 1;
        this.status = "active";
    }

    // ==================== COMMAND PARSING ====================

    /**
     * Parses a command and creates a Market
     * Examples:
     *   kill Steve Alex 5m
     *   kill Steve zombie >10 5m
     *   deaths Steve 5m
     *   pos Steve y>256 5m
     *   break Steve diamond_ore >5 5m
     *   weather rain 5m
     */
    public static Market parse(String[] args, String createdBy) throws IllegalArgumentException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: /market <type> <args...> <time>");
        }

        Market market = new Market();
        market.setCreatedBy(createdBy);
        market.setStartTime(System.currentTimeMillis());

        String type = args[0].toLowerCase();
        market.setType(type);

        // Parse time (last argument)
        String timeArg = args[args.length - 1];
        long durationMs = parseTime(timeArg);
        market.setEndTime(market.getStartTime() + durationMs);

        // Remove type and time from arguments
        String[] middleArgs = new String[args.length - 2];
        System.arraycopy(args, 1, middleArgs, 0, args.length - 2);

        switch (type) {
            case "kill" -> parseKill(market, middleArgs);
            case "deaths" -> parseDeaths(market, middleArgs);
            case "pos", "height" -> parsePos(market, middleArgs);
            case "distance" -> parseDistance(market, middleArgs);
            case "break" -> parseBreak(market, middleArgs);
            case "place" -> parsePlace(market, middleArgs);
            case "pickup" -> parsePickup(market, middleArgs);
            case "drop" -> parseDrop(market, middleArgs);
            case "held" -> parseHeld(market, middleArgs);
            case "weather" -> parseWeather(market, middleArgs);
            case "level" -> parseLevel(market, middleArgs);
            default -> throw new IllegalArgumentException("Unknown market type: " + type);
        }

        market.setDescription(market.generateDescription(timeArg));
        return market;
    }

    private static void parseKill(Market m, String[] args) {
        // kill Steve Alex [>N]
        // kill Steve zombie [>N]
        if (args.length < 2) throw new IllegalArgumentException("Usage: kill <player> <target> [condition] <time>");

        m.setPlayer(args[0]);
        m.setTarget(args[1]);

        if (args.length >= 3) {
            parseCondition(m, args[2]);
        }
    }

    private static void parseDeaths(Market m, String[] args) {
        // deaths Steve [=0] [>N]
        if (args.length < 1) throw new IllegalArgumentException("Usage: deaths <player> [condition] <time>");

        m.setPlayer(args[0]);

        if (args.length >= 2) {
            parseCondition(m, args[1]);
        }
    }

    private static void parsePos(Market m, String[] args) {
        // pos Steve y>256
        // pos Steve x>100 y>64 z<0
        // height Steve >256 (alias for y)
        if (args.length < 2) throw new IllegalArgumentException("Usage: pos <player> <conditions...> <time>");

        m.setPlayer(args[0]);

        StringBuilder posConditions = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            // Check coordinate format: x>100, y>256, z<0 or just >256 (for height)
            if (arg.matches("[xyz]?[><!=]+\\d+")) {
                if (posConditions.length() > 0) posConditions.append(",");

                // If no coordinate letter and type is height - add y
                if (m.getType().equals("height") && !arg.matches("[xyz].*")) {
                    posConditions.append("y").append(arg);
                } else {
                    posConditions.append(arg);
                }
            }
        }
        m.setPosConditions(posConditions.toString());
    }

    private static void parseDistance(Market m, String[] args) {
        // distance Steve >500
        if (args.length < 2) throw new IllegalArgumentException("Usage: distance <player> <condition> <time>");

        m.setPlayer(args[0]);
        parseCondition(m, args[1]);
    }

    private static void parseBreak(Market m, String[] args) {
        // break Steve diamond_ore [>5]
        if (args.length < 2) throw new IllegalArgumentException("Usage: break <player> <block> [condition] <time>");

        m.setPlayer(args[0]);
        m.setTarget(args[1].toUpperCase());

        if (args.length >= 3) {
            parseCondition(m, args[2]);
        }
    }

    private static void parsePlace(Market m, String[] args) {
        // place Steve dirt [>20]
        if (args.length < 2) throw new IllegalArgumentException("Usage: place <player> <block> [condition] <time>");

        m.setPlayer(args[0]);
        m.setTarget(args[1].toUpperCase());

        if (args.length >= 3) {
            parseCondition(m, args[2]);
        }
    }

    private static void parsePickup(Market m, String[] args) {
        // pickup Steve diamond [>10]
        if (args.length < 2) throw new IllegalArgumentException("Usage: pickup <player> <item> [condition] <time>");

        m.setPlayer(args[0]);
        m.setTarget(args[1].toUpperCase());

        if (args.length >= 3) {
            parseCondition(m, args[2]);
        }
    }

    private static void parseDrop(Market m, String[] args) {
        // drop Steve diamond [>5]
        if (args.length < 2) throw new IllegalArgumentException("Usage: drop <player> <item> [condition] <time>");

        m.setPlayer(args[0]);
        m.setTarget(args[1].toUpperCase());

        if (args.length >= 3) {
            parseCondition(m, args[2]);
        }
    }

    private static void parseHeld(Market m, String[] args) {
        // held Steve diamond_sword [>3]
        if (args.length < 2) throw new IllegalArgumentException("Usage: held <player> <item> [condition] <time>");

        m.setPlayer(args[0]);
        m.setTarget(args[1].toUpperCase());

        if (args.length >= 3) {
            parseCondition(m, args[2]);
        }
    }

    private static void parseWeather(Market m, String[] args) {
        // weather rain
        // weather clear
        if (args.length < 1) throw new IllegalArgumentException("Usage: weather <rain|clear> <time>");

        m.setTarget(args[0].toLowerCase());
    }

    private static void parseLevel(Market m, String[] args) {
        // level Steve >10      - Steve will reach level >10
        // level * >10          - any player will reach level >10
        // level any >10        - any player will reach level >10
        if (args.length < 2) throw new IllegalArgumentException("Usage: level <player|*|any> <condition> <time>");

        m.setPlayer(args[0]);
        parseCondition(m, args[1]);
    }

    private static void parseCondition(Market m, String condition) {
        // >5, <10, =0, >=5, <=10
        Pattern pattern = Pattern.compile("([><!=]+)(\\d+)");
        Matcher matcher = pattern.matcher(condition);

        if (matcher.matches()) {
            m.setOperator(matcher.group(1));
            m.setValue(Integer.parseInt(matcher.group(2)));
        }
    }

    private static long parseTime(String time) {
        Pattern pattern = Pattern.compile("(\\d+)([smh])");
        Matcher matcher = pattern.matcher(time.toLowerCase());

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid time format. Use: 30s, 5m, 1h");
        }

        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);

        return switch (unit) {
            case "s" -> value * 1000;
            case "m" -> value * 60 * 1000;
            case "h" -> value * 60 * 60 * 1000;
            default -> value * 60 * 1000;
        };
    }

    // ==================== DESCRIPTION GENERATION ====================

    public String generateDescription(String timeStr) {
        String time = "within " + timeStr;

        return switch (type) {
            case "kill" -> {
                if (value == 1 && ">=".equals(operator)) {
                    yield String.format("%s will kill %s %s", player, target, time);
                }
                yield String.format("%s will kill %s %s %d times %s", player, target, operatorToText(), value, time);
            }
            case "deaths" -> {
                if (value == 0 && "=".equals(operator)) {
                    yield String.format("%s will not die %s", player, time);
                }
                if (value == 1 && ">=".equals(operator)) {
                    yield String.format("%s will die %s", player, time);
                }
                yield String.format("%s will die %s %d times %s", player, operatorToText(), value, time);
            }
            case "pos", "height" -> String.format("%s will reach %s %s", player, posConditions, time);
            case "distance" -> String.format("%s will travel %s %d blocks %s", player, operatorToText(), value, time);
            case "break" -> {
                if (value == 1 && ">=".equals(operator)) {
                    yield String.format("%s will break %s %s", player, target, time);
                }
                yield String.format("%s will break %s %d %s %s", player, operatorToText(), value, target, time);
            }
            case "place" -> {
                if (value == 1 && ">=".equals(operator)) {
                    yield String.format("%s will place %s %s", player, target, time);
                }
                yield String.format("%s will place %s %d %s %s", player, operatorToText(), value, target, time);
            }
            case "pickup" -> {
                if (value == 1 && ">=".equals(operator)) {
                    yield String.format("%s will pick up %s %s", player, target, time);
                }
                yield String.format("%s will pick up %s %d %s %s", player, operatorToText(), value, target, time);
            }
            case "drop" -> {
                if (value == 1 && ">=".equals(operator)) {
                    yield String.format("%s will drop %s %s", player, target, time);
                }
                yield String.format("%s will drop %s %d %s %s", player, operatorToText(), value, target, time);
            }
            case "held" -> {
                if (value == 1 && ">=".equals(operator)) {
                    yield String.format("%s will hold %s %s", player, target, time);
                }
                yield String.format("%s will hold %s %s %d times %s", player, target, operatorToText(), value, time);
            }
            case "weather" -> {
                if ("rain".equals(target)) {
                    yield String.format("Rain will start %s", time);
                }
                yield String.format("Weather will clear %s", time);
            }
            case "level" -> {
                String who = isAnyPlayer() ? "Someone" : player;
                yield String.format("%s will reach level %s %d %s", who, operatorToText(), value, time);
            }
            default -> "Unknown market";
        };
    }

    private String operatorToText() {
        return switch (operator) {
            case ">" -> "more than";
            case "<" -> "less than";
            case "=" -> "exactly";
            case ">=" -> "at least";
            case "<=" -> "at most";
            default -> operator;
        };
    }

    // ==================== CONDITION CHECKING ====================

    public boolean checkCondition(int currentValue) {
        return switch (operator) {
            case ">" -> currentValue > value;
            case "<" -> currentValue < value;
            case "=" -> currentValue == value;
            case ">=" -> currentValue >= value;
            case "<=" -> currentValue <= value;
            default -> false;
        };
    }

    public Map<String, Integer> parsePosConditions() {
        Map<String, Integer> conditions = new HashMap<>();
        if (posConditions == null || posConditions.isEmpty()) {
            return conditions;
        }

        // Format: "x>100,y>256,z<0"
        String[] parts = posConditions.split(",");
        for (String part : parts) {
            Pattern p = Pattern.compile("([xyz])([><!=]+)(\\d+)");
            Matcher m = p.matcher(part);
            if (m.matches()) {
                String coord = m.group(1);
                String op = m.group(2);
                int val = Integer.parseInt(m.group(3));
                // Store as "x>", "y>=", etc.
                conditions.put(coord + op, val);
            }
        }
        return conditions;
    }

    public boolean checkPosCondition(int x, int y, int z) {
        if (posConditions == null || posConditions.isEmpty()) {
            return false;
        }

        String[] parts = posConditions.split(",");
        for (String part : parts) {
            Pattern p = Pattern.compile("([xyz])([><!=]+)(\\d+)");
            Matcher m = p.matcher(part);
            if (m.matches()) {
                String coord = m.group(1);
                String op = m.group(2);
                int val = Integer.parseInt(m.group(3));

                int actual = switch (coord) {
                    case "x" -> x;
                    case "y" -> y;
                    case "z" -> z;
                    default -> 0;
                };

                boolean matches = switch (op) {
                    case ">" -> actual > val;
                    case "<" -> actual < val;
                    case "=" -> actual == val;
                    case ">=" -> actual >= val;
                    case "<=" -> actual <= val;
                    default -> false;
                };

                if (!matches) return false;
            }
        }
        return true;
    }

    // ==================== GETTERS & SETTERS ====================

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPlayer() { return player; }
    public void setPlayer(String player) { this.player = player; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public int getValue() { return value; }
    public void setValue(int value) { this.value = value; }

    public String getPosConditions() { return posConditions; }
    public void setPosConditions(String posConditions) { this.posConditions = posConditions; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getWinnerOutcome() { return winnerOutcome; }
    public void setWinnerOutcome(String winnerOutcome) { this.winnerOutcome = winnerOutcome; }

    public int getCurrentProgress() { return currentProgress; }
    public void setCurrentProgress(int currentProgress) { this.currentProgress = currentProgress; }
    public void addProgress(int amount) { this.currentProgress += amount; }

    public long getRemainingTime() {
        return Math.max(0, endTime - System.currentTimeMillis());
    }

    public String getRemainingTimeFormatted() {
        long remaining = getRemainingTime();
        long seconds = remaining / 1000;
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m " + (seconds % 60) + "s";
        long hours = minutes / 60;
        return hours + "h " + (minutes % 60) + "m";
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > endTime;
    }

    public boolean isActive() {
        return "active".equals(status);
    }

    /**
     * Checks if the market is for "any player" (* or any)
     */
    public boolean isAnyPlayer() {
        return player != null && (player.equals("*") || player.equalsIgnoreCase("any"));
    }

    /**
     * Checks if a player matches the market condition
     */
    public boolean matchesPlayer(String playerName) {
        if (isAnyPlayer()) return true;
        return player != null && player.equalsIgnoreCase(playerName);
    }
}

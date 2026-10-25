package com.reazip.economycraft;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.config.FactionsSection;
import com.reazip.economycraft.config.MotdBlock;
import com.reazip.economycraft.config.ProfessionsSection;
import com.reazip.economycraft.config.QuestsSection;
import com.reazip.economycraft.gossip.GossipConfig;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.function.Supplier;

/** Runtime configuration shared by the economy, upstream 1.11 features, and fork extensions. */
public class EconomyConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEFAULT_RESOURCE_PATH = "/assets/economycraft/config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /* Upstream 1.11 sectioned configuration. */
    public Economy economy = new Economy();
    public Display display = new Display();
    public Commands commands = new Commands();
    public Shop shop = new Shop();
    public Selling selling = new Selling();
    @SerializedName("dynamic_prices")
    public DynamicPrices dynamicPrices = new DynamicPrices();
    public Auction auction = new Auction();
    public Orders orders = new Orders();
    @SerializedName("transaction_log")
    public TransactionLog transactionLog = new TransactionLog();

    public static class Economy {
        @SerializedName("starting_balance")
        public long startingBalance = 1000;
        @SerializedName("max_balance")
        public long maxBalance = EconomyManager.ABSOLUTE_MAX_BALANCE;
        @SerializedName("daily_reward")
        public long dailyReward = 100;
        @SerializedName("tax_rate")
        public double taxRate = 0.1;
    }

    public static class Display {
        @SerializedName("balance_separator")
        public String balanceSeparator = ".";
        @SerializedName("currency_symbol")
        public String currencySymbol = DEFAULT_CURRENCY_SYMBOL;
        @SerializedName("currency_symbol_position")
        public CurrencyPosition currencySymbolPosition = CurrencyPosition.PREFIX;
        @SerializedName("currency_name")
        public String currencyName = "";
        @SerializedName("scoreboard_enabled")
        public boolean scoreboardEnabled = true;
    }

    public enum CurrencyPosition {
        @SerializedName("prefix") PREFIX,
        @SerializedName("suffix") SUFFIX
    }

    public static class Commands {
        public boolean standalone = true;
        @SerializedName("standalone_admin")
        public boolean standaloneAdmin = false;
    }

    public static class Shop {
        public boolean enabled = true;
    }

    public static class Selling {
        public boolean enabled = true;
        @SerializedName("daily_limit")
        public long dailyLimit = 10000;
        @SerializedName("worth_enabled")
        public boolean worthEnabled = true;
    }

    public static class DynamicPrices {
        public boolean enabled = false;
        @SerializedName("min_multiplier")
        public double minMultiplier = 0.5;
        @SerializedName("max_multiplier")
        public double maxMultiplier = 5.0;
        @SerializedName("min_active_days")
        public int minActiveDays = 30;
        @SerializedName("update_minutes")
        public int updateMinutes = 60;
    }

    public static class Auction {
        public boolean enabled = true;
        @SerializedName("expiration_hours")
        public int expirationHours = 168;
        @SerializedName("max_active_per_player")
        public int maxActivePerPlayer = 0;
    }

    public static class Orders {
        public boolean enabled = true;
        @SerializedName("expiration_hours")
        public int expirationHours = 168;
        @SerializedName("max_active_per_player")
        public int maxActivePerPlayer = 0;
    }

    public static class TransactionLog {
        public boolean enabled = true;
        @SerializedName("retention_days")
        public int retentionDays = 7;
    }

    /*
     * Fork settings that upstream does not know about. They stay at the root so existing server
     * configs and the faction/profession/quest systems keep their current schema.
     */
    @SerializedName("auction_listing_broadcast")
    public boolean auctionListingBroadcast = true;
    @SerializedName("auction_listing_notify_active_days")
    public int auctionListingNotifyActiveDays = 30;
    @SerializedName("max_active_tolls_per_player")
    public int maxActiveTollsPerPlayer = 10;
    @SerializedName("contracts_enabled")
    public boolean contractsEnabled = true;
    @SerializedName("max_active_contracts_per_player")
    public int maxActiveContractsPerPlayer = 0;
    @SerializedName("max_contract_reward")
    public long maxContractReward = 1_000_000;
    @SerializedName("contract_default_duration_hours")
    public int contractDefaultDurationHours = 168;
    @SerializedName("contract_max_duration_hours")
    public int contractMaxDurationHours = 720;
    @SerializedName("contract_review_hours")
    public int contractReviewHours = 72;
    @SerializedName("contract_max_revisions")
    public int contractMaxRevisions = 2;
    @SerializedName("contract_revision_extension_hours")
    public int contractRevisionExtensionHours = 72;
    @SerializedName("wealth_tax_enabled")
    public boolean wealthTaxEnabled = false;
    @SerializedName("wealth_tax_rate")
    public double wealthTaxRate = 0.015;
    @SerializedName("wealth_tax_floor")
    public long wealthTaxFloor = 1000;
    @SerializedName("wealth_tax_median_floor_factor")
    public double wealthTaxMedianFloorFactor = 0.5;
    @SerializedName("wealth_tax_inactive_days")
    public int wealthTaxInactiveDays = 7;
    @SerializedName("wealth_tax_inactive_multiplier")
    public double wealthTaxInactiveMultiplier = 3.33;
    @SerializedName("wealth_tax_max_catchup_days")
    public int wealthTaxMaxCatchupDays = 7;
    @SerializedName("wealth_tax_rebate_enabled")
    public boolean wealthTaxRebateEnabled = false;
    @SerializedName("wealth_tax_rebate_max_rate")
    public double wealthTaxRebateMaxRate = 0.01;
    @SerializedName("wealth_tax_rebate_trigger_factor")
    public double wealthTaxRebateTriggerFactor = 1.0;

    @SerializedName("factions")
    public FactionsSection factions = new FactionsSection();
    @SerializedName("professions")
    public ProfessionsSection professions = new ProfessionsSection();
    @SerializedName("quests")
    public QuestsSection quests = new QuestsSection();
    @SerializedName("gemini_gossip")
    public GossipConfig geminiGossip = GossipConfig.createDefault();
    @SerializedName("motd")
    public com.reazip.economycraft.config.MotdSection motd = new com.reazip.economycraft.config.MotdSection();
    @SerializedName("http_market_enabled")
    public boolean httpMarketEnabled = false;
    @SerializedName("http_market_port")
    public int httpMarketPort = 0;
    @SerializedName("http_market_token")
    public String httpMarketToken = "";

    /* Temporary aliases for fork code that is migrated to the sectioned accessors incrementally. */
    public transient long startingBalance;
    public transient long dailyAmount;
    public transient long dailySellLimit;
    public transient double taxRate;
    public transient boolean standaloneCommands;
    public transient boolean standaloneAdminCommands;
    public transient boolean scoreboardEnabled;
    public transient boolean shopEnabled;
    public transient boolean auctionEnabled;
    public transient boolean sellEnabled;
    public transient boolean worthEnabled;
    public transient boolean ordersEnabled;
    public transient String balanceSeparator;
    public transient boolean transactionLogEnabled;
    public transient int transactionLogRetentionDays;
    public transient int orderExpirationHours;
    public transient int auctionExpirationHours;
    public transient int maxActiveOrdersPerPlayer;
    public transient int maxActiveAuctionsPerPlayer;
    public transient boolean dynamicPricesEnabled;
    public transient double dynamicPriceMinMultiplier;
    public transient double dynamicPriceMaxMultiplier;
    public transient int dynamicPriceMinActiveDays;

    public static final int MIN_TRANSACTION_LOG_RETENTION_DAYS = 1;
    public static final int WARN_TRANSACTION_LOG_RETENTION_DAYS = 90;
    public static final double MIN_DYNAMIC_PRICE_MULTIPLIER = 0.5;
    public static final double MAX_DYNAMIC_PRICE_MULTIPLIER = 100.0;
    public static final int MIN_DYNAMIC_PRICE_UPDATE_MINUTES = 1;
    public static final String DEFAULT_CURRENCY_SYMBOL = "$";
    public static final int MAX_CURRENCY_SYMBOL_LENGTH = 16;
    public static final int MAX_CURRENCY_NAME_LENGTH = 32;
    public static final double MAX_MEDIAN_FLOOR_FACTOR = 10.0;
    public static final double MAX_INACTIVE_MULTIPLIER = 100.0;
    public static final double MAX_REBATE_TRIGGER_FACTOR = 100.0;

    private static EconomyConfig INSTANCE = new EconomyConfig();
    private static Path file;
    private static JsonObject rawRoot = new JsonObject();

    public static EconomyConfig get() { return INSTANCE; }

    public static void load(MinecraftServer server) {
        file = EconomyPaths.configDir(server).resolve("config.json");
        if (Files.notExists(file)) copyDefaultFromJarOrThrow();
        else mergeNewDefaultsFromBundledDefault();

        try {
            rawRoot = readJsonObject(file);
            EconomyConfig parsed = GSON.fromJson(rawRoot, EconomyConfig.class);
            if (parsed == null) throw new IllegalStateException("config.json parsed to null");
            fillMissingSections(parsed);

            parsed.economy.taxRate = clampPercentage("economy.tax_rate", parsed.economy.taxRate);
            if (parsed.economy.dailyReward < 0) {
                LOGGER.warn("[EconomyCraft] economy.daily_reward ({}) is negative; clamping to 0.", parsed.economy.dailyReward);
                parsed.economy.dailyReward = 0;
            }
            parsed.economy.maxBalance = clampMaxBalance(parsed.economy.maxBalance);
            parsed.display.balanceSeparator = validSeparator(parsed.display.balanceSeparator);
            parsed.display.currencySymbol = validCurrencySymbol(parsed.display.currencySymbol);
            parsed.display.currencyName = validCurrencyName(parsed.display.currencyName);
            if (parsed.display.currencySymbolPosition == null) parsed.display.currencySymbolPosition = CurrencyPosition.PREFIX;
            parsed.transactionLog.retentionDays = clampRetentionDays(parsed.transactionLog.retentionDays);
            parsed.orders.expirationHours = clampNonNegative("orders.expiration_hours", parsed.orders.expirationHours);
            parsed.auction.expirationHours = clampNonNegative("auction.expiration_hours", parsed.auction.expirationHours);
            parsed.orders.maxActivePerPlayer = clampNonNegative("orders.max_active_per_player", parsed.orders.maxActivePerPlayer);
            parsed.auction.maxActivePerPlayer = clampNonNegative("auction.max_active_per_player", parsed.auction.maxActivePerPlayer);
            parsed.dynamicPrices.minActiveDays = clampNonNegative("dynamic_prices.min_active_days", parsed.dynamicPrices.minActiveDays);
            parsed.dynamicPrices.updateMinutes = clampDynamicPriceUpdateMinutes(parsed.dynamicPrices.updateMinutes);

            parsed.auctionListingNotifyActiveDays = clampNonNegative("auction_listing_notify_active_days", parsed.auctionListingNotifyActiveDays);
            parsed.maxActiveTollsPerPlayer = clampNonNegative("max_active_tolls_per_player", parsed.maxActiveTollsPerPlayer);
            parsed.maxActiveContractsPerPlayer = clampNonNegative("max_active_contracts_per_player", parsed.maxActiveContractsPerPlayer);
            parsed.maxContractReward = clampContractReward(parsed.maxContractReward);
            parsed.contractDefaultDurationHours = clampNonNegative("contract_default_duration_hours", parsed.contractDefaultDurationHours);
            parsed.contractMaxDurationHours = clampNonNegative("contract_max_duration_hours", parsed.contractMaxDurationHours);
            if (parsed.contractMaxDurationHours < parsed.contractDefaultDurationHours) parsed.contractMaxDurationHours = parsed.contractDefaultDurationHours;
            parsed.contractReviewHours = clampNonNegative("contract_review_hours", parsed.contractReviewHours);
            parsed.contractMaxRevisions = clampNonNegative("contract_max_revisions", parsed.contractMaxRevisions);
            parsed.contractRevisionExtensionHours = clampNonNegative("contract_revision_extension_hours", parsed.contractRevisionExtensionHours);
            parsed.wealthTaxRate = clampPercentage("wealth_tax_rate", parsed.wealthTaxRate);
            parsed.wealthTaxFloor = clampNonNegative("wealth_tax_floor", parsed.wealthTaxFloor);
            parsed.wealthTaxMedianFloorFactor = clampRange("wealth_tax_median_floor_factor", parsed.wealthTaxMedianFloorFactor, 0.0, MAX_MEDIAN_FLOOR_FACTOR, "");
            parsed.wealthTaxInactiveDays = clampNonNegative("wealth_tax_inactive_days", parsed.wealthTaxInactiveDays);
            parsed.wealthTaxInactiveMultiplier = clampRange("wealth_tax_inactive_multiplier", parsed.wealthTaxInactiveMultiplier, 0.0, MAX_INACTIVE_MULTIPLIER, "");
            parsed.wealthTaxMaxCatchupDays = clampNonNegative("wealth_tax_max_catchup_days", parsed.wealthTaxMaxCatchupDays);
            parsed.wealthTaxRebateMaxRate = clampPercentage("wealth_tax_rebate_max_rate", parsed.wealthTaxRebateMaxRate);
            parsed.wealthTaxRebateTriggerFactor = clampRange("wealth_tax_rebate_trigger_factor", parsed.wealthTaxRebateTriggerFactor, 0.0, MAX_REBATE_TRIGGER_FACTOR, "");
            parsed.httpMarketPort = clampPort(parsed.httpMarketPort);
            if (parsed.httpMarketToken == null) parsed.httpMarketToken = "";
            parsed.factions = requireSection("factions", parsed.factions, FactionsSection::new);
            parsed.professions = requireSection("professions", parsed.professions, ProfessionsSection::new);
            parsed.quests = requireSection("quests", parsed.quests, QuestsSection::new);
            parsed.geminiGossip = requireSection("gemini_gossip", parsed.geminiGossip, GossipConfig::createDefault);
            parsed.motd = requireSection("motd", parsed.motd, com.reazip.economycraft.config.MotdSection::new);
            parsed.factions.clamp();
            parsed.professions.clamp();
            parsed.quests.clamp();
            parsed.geminiGossip = parsed.geminiGossip.clamped();
            parsed.motd.clamp();
            INSTANCE = parsed;
            normalizeDynamicPriceBounds();
            syncLegacyAliasesFromSections(parsed);
        } catch (Exception e) {
            throw new IllegalStateException("[EconomyCraft] Failed to read/parse config.json at " + file, e);
        }
    }

    private static void fillMissingSections(EconomyConfig config) {
        if (config.economy == null) config.economy = new Economy();
        if (config.display == null) config.display = new Display();
        if (config.commands == null) config.commands = new Commands();
        if (config.shop == null) config.shop = new Shop();
        if (config.selling == null) config.selling = new Selling();
        if (config.dynamicPrices == null) config.dynamicPrices = new DynamicPrices();
        if (config.auction == null) config.auction = new Auction();
        if (config.orders == null) config.orders = new Orders();
        if (config.transactionLog == null) config.transactionLog = new TransactionLog();
    }

    private static void syncLegacyAliasesFromSections(EconomyConfig c) {
        c.startingBalance = c.economy.startingBalance;
        c.dailyAmount = c.economy.dailyReward;
        c.dailySellLimit = c.selling.dailyLimit;
        c.taxRate = c.economy.taxRate;
        c.standaloneCommands = c.commands.standalone;
        c.standaloneAdminCommands = c.commands.standaloneAdmin;
        c.scoreboardEnabled = c.display.scoreboardEnabled;
        c.shopEnabled = c.shop.enabled;
        c.auctionEnabled = c.auction.enabled;
        c.sellEnabled = c.selling.enabled;
        c.worthEnabled = c.selling.worthEnabled;
        c.ordersEnabled = c.orders.enabled;
        c.balanceSeparator = c.display.balanceSeparator;
        c.transactionLogEnabled = c.transactionLog.enabled;
        c.transactionLogRetentionDays = c.transactionLog.retentionDays;
        c.orderExpirationHours = c.orders.expirationHours;
        c.auctionExpirationHours = c.auction.expirationHours;
        c.maxActiveOrdersPerPlayer = c.orders.maxActivePerPlayer;
        c.maxActiveAuctionsPerPlayer = c.auction.maxActivePerPlayer;
        c.dynamicPricesEnabled = c.dynamicPrices.enabled;
        c.dynamicPriceMinMultiplier = c.dynamicPrices.minMultiplier;
        c.dynamicPriceMaxMultiplier = c.dynamicPrices.maxMultiplier;
        c.dynamicPriceMinActiveDays = c.dynamicPrices.minActiveDays;
    }

    private static <T> T requireSection(String name, T section, Supplier<T> fallback) {
        if (section != null) return section;
        LOGGER.warn("[EconomyCraft] {} section is null; rebuilding it from defaults.", name);
        return fallback.get();
    }

    private static String validSeparator(@Nullable String separator) {
        if (separator == null || separator.isEmpty() || textProblem(separator.substring(0, 1)) != null) {
            LOGGER.warn("[EconomyCraft] display.balance_separator is invalid; defaulting to '.'.");
            return ".";
        }
        return separator;
    }

    private static String validCurrencySymbol(@Nullable String symbol) {
        String value = symbol == null ? DEFAULT_CURRENCY_SYMBOL : symbol.trim();
        if (currencySymbolProblem(value) != null) return DEFAULT_CURRENCY_SYMBOL;
        return value;
    }

    private static String validCurrencyName(@Nullable String name) {
        String value = name == null ? "" : name.trim();
        if (currencyNameProblem(value) != null) return "";
        return value;
    }

    public static @Nullable String currencySymbolProblem(String symbol) {
        if (symbol.length() > MAX_CURRENCY_SYMBOL_LENGTH) return "is longer than " + MAX_CURRENCY_SYMBOL_LENGTH + " characters";
        String problem = textProblem(symbol);
        if (problem != null) return problem;
        if (symbol.chars().anyMatch(Character::isDigit)) return "contains a digit";
        if (EconomyCraft.isShortMoneySuffix(symbol)) return "clashes with the " + symbol + " amount shorthand";
        return null;
    }

    public static @Nullable String currencyNameProblem(String name) {
        if (name.length() > MAX_CURRENCY_NAME_LENGTH) return "is longer than " + MAX_CURRENCY_NAME_LENGTH + " characters";
        return textProblem(name);
    }

    public static @Nullable String textProblem(String text) {
        if (text.indexOf('§') >= 0) return "contains a § formatting code";
        if (text.chars().anyMatch(Character::isISOControl)) return "contains a control character";
        return null;
    }

    private static double clampRange(String fieldName, double value, double min, double max, String reason) {
        double clamped = Double.isNaN(value) ? min : Math.clamp(value, min, max);
        if (clamped != value) LOGGER.warn("[EconomyCraft] {} ({}) is outside the valid {}-{} range{}; clamping to {}.", fieldName, value, min, max, reason, clamped);
        return clamped;
    }

    private static double clampPercentage(String fieldName, double value) { return clampRange(fieldName, value, 0.0, 1.0, " (decimal factor, e.g. 0.1 = 10%)"); }

    private static int clampRetentionDays(int days) {
        if (days < MIN_TRANSACTION_LOG_RETENTION_DAYS) return MIN_TRANSACTION_LOG_RETENTION_DAYS;
        if (days > WARN_TRANSACTION_LOG_RETENTION_DAYS) LOGGER.warn("[EconomyCraft] {} is above {} days; logs can take significant disk space.", days, WARN_TRANSACTION_LOG_RETENTION_DAYS);
        return days;
    }

    private static int clampNonNegative(String fieldName, int value) {
        if (value < 0) {
            LOGGER.warn("[EconomyCraft] {} ({}) is negative; clamping to 0 (unlimited).", fieldName, value);
            return 0;
        }
        return value;
    }

    private static long clampNonNegative(String fieldName, long value) {
        if (value < 0) {
            LOGGER.warn("[EconomyCraft] {} ({}) is negative; clamping to 0.", fieldName, value);
            return 0;
        }
        return value;
    }

    private static long clampMaxBalance(long value) {
        long clamped = Math.min(Math.max(value, 1), EconomyManager.ABSOLUTE_MAX_BALANCE);
        if (clamped != value) {
            LOGGER.warn("[EconomyCraft] economy.max_balance ({}) is outside the valid 1-{} range; clamping to {}.",
                    value, EconomyManager.ABSOLUTE_MAX_BALANCE, clamped);
        }
        return clamped;
    }

    private static int clampPort(int port) { return Math.min(clampNonNegative("http_market_port", port), 65535); }

    private static long clampContractReward(long value) { return Math.clamp(value, 1, EconomyManager.MAX); }

    private static int clampDynamicPriceUpdateMinutes(int minutes) {
        int clamped = Math.max(minutes, MIN_DYNAMIC_PRICE_UPDATE_MINUTES);
        if (clamped != minutes) {
            LOGGER.warn("[EconomyCraft] dynamic_prices.update_minutes ({}) is below the minimum {}; clamping to {}.",
                    minutes, MIN_DYNAMIC_PRICE_UPDATE_MINUTES, clamped);
        }
        return clamped;
    }

    public static void normalizeDynamicPriceBounds() {
        EconomyConfig c = INSTANCE;
        c.dynamicPrices.minMultiplier = clampMultiplierRange("dynamic_prices.min_multiplier", c.dynamicPrices.minMultiplier);
        c.dynamicPrices.maxMultiplier = clampMultiplierRange("dynamic_prices.max_multiplier", c.dynamicPrices.maxMultiplier);
        if (c.dynamicPrices.maxMultiplier < c.dynamicPrices.minMultiplier) c.dynamicPrices.maxMultiplier = c.dynamicPrices.minMultiplier;
        syncLegacyAliasesFromSections(c);
    }

    private static double clampMultiplierRange(String fieldName, double value) { return clampRange(fieldName, value, MIN_DYNAMIC_PRICE_MULTIPLIER, MAX_DYNAMIC_PRICE_MULTIPLIER, ""); }

    public static void save() {
        if (file == null) throw new IllegalStateException("[EconomyCraft] EconomyConfig not initialized. Call load() first.");
        try {
            syncLegacyAliasesFromSections(INSTANCE);
            JsonObject current = GSON.fromJson(GSON.toJson(INSTANCE), JsonObject.class);
            overlayKnown(rawRoot, current);
            Files.writeString(file, GSON.toJson(rawRoot), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("[EconomyCraft] Failed to save config.json at " + file, e);
        }
    }

    private static void copyDefaultFromJarOrThrow() {
        try (InputStream in = EconomyConfig.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) throw new IllegalStateException("[EconomyCraft] Missing bundled default " + DEFAULT_RESOURCE_PATH);
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[EconomyCraft] Created {} from bundled default {}", file, DEFAULT_RESOURCE_PATH);
        } catch (IOException e) {
            throw new IllegalStateException("[EconomyCraft] Failed to create config.json at " + file, e);
        }
    }

    private static JsonObject readJsonObject(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        if (json.isBlank()) return new JsonObject();
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalStateException("config.json root is not an object");
        return parsed.getAsJsonObject();
    }

    private static void mergeNewDefaultsFromBundledDefault() {
        JsonObject defaults = readBundledDefaultJson();
        if (defaults == null) return;
        try {
            JsonObject userRoot = readJsonObject(file);
            boolean migratedMotd = migrateMotd(userRoot);
            boolean migratedShopSettings = migrateShopSettings(userRoot, defaults);
            boolean migratedLayout = migrateFlatLayout(userRoot);
            int[] added = {0};
            addMissingRecursive(userRoot, defaults, added);
            if (migratedLayout) userRoot = orderLike(userRoot, defaults);
            if (migratedMotd || migratedShopSettings || migratedLayout || added[0] > 0) {
                Files.writeString(file, GSON.toJson(userRoot), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("[EconomyCraft] Failed to merge defaults into config.json at " + file, ex);
        }
    }

    private static boolean migrateMotd(JsonObject root) {
        JsonElement motdElement = root.get("motd");
        if (motdElement == null || !motdElement.isJsonObject()) return false;
        JsonObject motd = motdElement.getAsJsonObject();
        JsonElement legacyLines = motd.remove("lines");
        if (legacyLines == null) return false;
        if (!motd.has("blocks")) {
            JsonObject block = new JsonObject();
            block.add("lines", legacyLines.deepCopy());
            block.addProperty("next_delay_seconds", MotdBlock.DEFAULT_NEXT_DELAY_SECONDS);
            JsonArray blocks = new JsonArray();
            blocks.add(block);
            motd.add("blocks", blocks);
        }
        return true;
    }

    private static boolean migrateShopSettings(JsonObject target, JsonObject defaults) {
        JsonElement legacyShop = target.remove("server_shop_enabled");
        if (legacyShop != null) {
            if (!target.has("auction_enabled") && target.has("shop_enabled")) target.add("auction_enabled", target.get("shop_enabled").deepCopy());
            target.add("shop_enabled", legacyShop.deepCopy());
            return true;
        }
        if (!target.has("auction_enabled") && target.has("shop_enabled")) {
            target.add("auction_enabled", target.get("shop_enabled").deepCopy());
            JsonElement defaultShop = defaults.get("shop_enabled");
            if (defaultShop != null) target.add("shop_enabled", defaultShop.deepCopy());
            return true;
        }
        return false;
    }

    private static boolean migrateFlatLayout(JsonObject target) {
        String[][] moves = {
                {"startingBalance", "economy", "starting_balance"}, {"max_balance", "economy", "max_balance"},
                {"dailyAmount", "economy", "daily_reward"}, {"taxRate", "economy", "tax_rate"},
                {"balance_separator", "display", "balance_separator"}, {"scoreboard_enabled", "display", "scoreboard_enabled"},
                {"standalone_commands", "commands", "standalone"}, {"standalone_admin_commands", "commands", "standalone_admin"},
                {"shop_enabled", "shop", "enabled"}, {"sell_enabled", "selling", "enabled"},
                {"dailySellLimit", "selling", "daily_limit"}, {"worth_enabled", "selling", "worth_enabled"},
                {"dynamic_prices_enabled", "dynamic_prices", "enabled"}, {"dynamic_price_min_multiplier", "dynamic_prices", "min_multiplier"},
                {"dynamic_price_max_multiplier", "dynamic_prices", "max_multiplier"}, {"dynamic_price_min_active_days", "dynamic_prices", "min_active_days"},
                {"auction_enabled", "auction", "enabled"}, {"auction_expiration_hours", "auction", "expiration_hours"},
                {"max_active_auctions_per_player", "auction", "max_active_per_player"}, {"orders_enabled", "orders", "enabled"},
                {"order_expiration_hours", "orders", "expiration_hours"}, {"max_active_orders_per_player", "orders", "max_active_per_player"},
                {"transaction_log_enabled", "transaction_log", "enabled"}, {"transaction_log_retention_days", "transaction_log", "retention_days"}
        };
        boolean migrated = false;
        for (String[] move : moves) {
            JsonElement value = target.remove(move[0]);
            if (value == null) continue;
            migrated = true;
            JsonObject section;
            JsonElement existingSection = target.get(move[1]);
            if (existingSection == null) {
                section = new JsonObject();
                target.add(move[1], section);
            } else if (existingSection.isJsonObject()) {
                section = existingSection.getAsJsonObject();
            } else {
                throw new IllegalStateException("Cannot migrate " + move[0] + ": " + move[1] + " is not an object");
            }
            JsonElement existing = section.get(move[2]);
            if (existing != null && !existing.equals(value)) {
                throw new IllegalStateException("Ambiguous config migration for " + move[0] + " and " + move[1] + "." + move[2]);
            }
            if (existing == null) section.add(move[2], value);
        }
        if (migrated) LOGGER.info("[EconomyCraft] Migrated config.json to the sectioned layout.");
        return migrated;
    }

    private static JsonObject readBundledDefaultJson() {
        try (InputStream in = EconomyConfig.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) return null;
            JsonElement parsed = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ex) {
            throw new IllegalStateException("[EconomyCraft] Failed to read bundled default config.json", ex);
        }
    }

    private static void addMissingRecursive(JsonObject target, JsonObject defaults, int[] added) {
        for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
            if (!target.has(e.getKey())) {
                target.add(e.getKey(), e.getValue() == null ? JsonNull.INSTANCE : e.getValue().deepCopy());
                added[0]++;
                continue;
            }
            JsonElement current = target.get(e.getKey());
            if (current != null && current.isJsonObject() && e.getValue() != null && e.getValue().isJsonObject()) {
                addMissingRecursive(current.getAsJsonObject(), e.getValue().getAsJsonObject(), added);
            }
        }
    }

    private static JsonObject orderLike(JsonObject source, JsonObject template) {
        JsonObject ordered = new JsonObject();
        for (Map.Entry<String, JsonElement> e : template.entrySet()) {
            JsonElement value = source.get(e.getKey());
            if (value == null) continue;
            if (value.isJsonObject() && e.getValue().isJsonObject()) value = orderLike(value.getAsJsonObject(), e.getValue().getAsJsonObject());
            ordered.add(e.getKey(), value);
        }
        for (Map.Entry<String, JsonElement> e : source.entrySet()) if (!ordered.has(e.getKey())) ordered.add(e.getKey(), e.getValue());
        return ordered;
    }

    private static void overlayKnown(JsonObject target, JsonObject current) {
        for (Map.Entry<String, JsonElement> e : current.entrySet()) {
            JsonElement existing = target.get(e.getKey());
            if (existing != null && existing.isJsonObject() && e.getValue().isJsonObject()) overlayKnown(existing.getAsJsonObject(), e.getValue().getAsJsonObject());
            else target.add(e.getKey(), e.getValue().deepCopy());
        }
    }
}

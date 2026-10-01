package com.reazip.economycraft;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.config.ContainerLockSection;
import com.reazip.economycraft.config.FactionsSection;
import com.reazip.economycraft.config.ProfessionsSection;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

public class EconomyConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEFAULT_RESOURCE_PATH = "/assets/economycraft/config.json";
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public long startingBalance;
    public long dailyAmount;
    public long dailySellLimit;
    public double taxRate;
    @SerializedName("standalone_commands")
    public boolean standaloneCommands;
    @SerializedName("standalone_admin_commands")
    public boolean standaloneAdminCommands;
    @SerializedName("scoreboard_enabled")
    public boolean scoreboardEnabled;
    @SerializedName("shop_enabled")
    public boolean shopEnabled = true;
    @SerializedName("auction_enabled")
    public boolean auctionEnabled = true;
    @SerializedName("sell_enabled")
    public boolean sellEnabled = true;
    @SerializedName("worth_enabled")
    public boolean worthEnabled = true;
    @SerializedName("orders_enabled")
    public boolean ordersEnabled = true;
    @SerializedName("balance_separator")
    public String balanceSeparator = ".";
    @SerializedName("transaction_log_enabled")
    public boolean transactionLogEnabled = true;
    @SerializedName("transaction_log_retention_days")
    public int transactionLogRetentionDays = 7;
    @SerializedName("order_expiration_hours")
    public int orderExpirationHours = 168;
    @SerializedName("auction_expiration_hours")
    public int auctionExpirationHours = 168;
    @SerializedName("max_active_orders_per_player")
    public int maxActiveOrdersPerPlayer = 0;
    @SerializedName("max_active_auctions_per_player")
    public int maxActiveAuctionsPerPlayer = 0;
    @SerializedName("max_active_tolls_per_player")
    public int maxActiveTollsPerPlayer = 10;
    @SerializedName("dynamic_prices_enabled")
    public boolean dynamicPricesEnabled = false;
    @SerializedName("dynamic_price_min_multiplier")
    public double dynamicPriceMinMultiplier = 0.5;
    @SerializedName("dynamic_price_max_multiplier")
    public double dynamicPriceMaxMultiplier = 5.0;
    @SerializedName("dynamic_price_min_active_days")
    public int dynamicPriceMinActiveDays = 30;
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

    /**
     * The four parties and their buffs (Phase 2 onward).
     *
     * <p>Nested on purpose: these arrived in Phase 2, and they are the first keys that are more than one level
     * deep. Keeping them in their own section rather than flattening them into the root is what lets the merge
     * add a <em>whole new party</em> to an existing server's config without touching the server owner's
     * hand-tuned economy keys — see {@code addMissingRecursive} and the {@code EconomyConfigMergeTest} cases
     * that cover exactly this.
     */
    @SerializedName("factions")
    public FactionsSection factions = new FactionsSection();

    /**
     * The five professions and their buffs, with the same reasoning as {@link #factions}.
     *
     * <p>Nothing in either section is wired to gameplay by this phase. They are data and tuning surface only.
     */
    @SerializedName("professions")
    public ProfessionsSection professions = new ProfessionsSection();

    /**
     * The container lock's global default (D10). Its own section because it is the answer for containers whose
     * owner has no preference of their own — see {@code ContainerLockSection} for why that is separate from
     * Communism's {@code container_lock_mode}.
     */
    @SerializedName("container_lock")
    public ContainerLockSection containerLock = new ContainerLockSection();

    public static final int MIN_TRANSACTION_LOG_RETENTION_DAYS = 1;
    public static final int WARN_TRANSACTION_LOG_RETENTION_DAYS = 90;
    public static final double MAX_DYNAMIC_PRICE_MULTIPLIER = 100.0;
    public static final double MAX_MEDIAN_FLOOR_FACTOR = 10.0;
    public static final double MAX_INACTIVE_MULTIPLIER = 100.0;
    public static final double MAX_REBATE_TRIGGER_FACTOR = 100.0;

    private static EconomyConfig INSTANCE = new EconomyConfig();
    private static Path file;

    public static EconomyConfig get() {
        return INSTANCE;
    }

    public static void load(MinecraftServer server) {
        file = EconomyPaths.configDir(server).resolve("config.json");

        if (Files.notExists(file)) {
            copyDefaultFromJarOrThrow();
        } else {
            mergeNewDefaultsFromBundledDefault();
        }

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            EconomyConfig parsed = GSON.fromJson(json, EconomyConfig.class);
            if (parsed == null) {
                throw new IllegalStateException("config.json parsed to null");
            }
            parsed.taxRate = clampPercentage("taxRate", parsed.taxRate);
            if (parsed.dailyAmount < 0) {
                LOGGER.warn("[EconomyCraft] dailyAmount ({}) is negative; clamping to 0.", parsed.dailyAmount);
                parsed.dailyAmount = 0;
            }
            if (parsed.balanceSeparator == null || parsed.balanceSeparator.isEmpty()) {
                LOGGER.warn("[EconomyCraft] balance_separator is empty; defaulting to \".\".");
                parsed.balanceSeparator = ".";
            }
            parsed.transactionLogRetentionDays = clampRetentionDays(parsed.transactionLogRetentionDays);
            parsed.orderExpirationHours = clampNonNegative("order_expiration_hours", parsed.orderExpirationHours);
            parsed.auctionExpirationHours = clampNonNegative("auction_expiration_hours", parsed.auctionExpirationHours);
            parsed.maxActiveOrdersPerPlayer = clampNonNegative("max_active_orders_per_player", parsed.maxActiveOrdersPerPlayer);
            parsed.maxActiveAuctionsPerPlayer = clampNonNegative("max_active_auctions_per_player", parsed.maxActiveAuctionsPerPlayer);
            parsed.maxActiveTollsPerPlayer = clampNonNegative("max_active_tolls_per_player", parsed.maxActiveTollsPerPlayer);
            parsed.dynamicPriceMinActiveDays = clampNonNegative("dynamic_price_min_active_days", parsed.dynamicPriceMinActiveDays);
            parsed.wealthTaxRate = clampPercentage("wealth_tax_rate", parsed.wealthTaxRate);
            parsed.wealthTaxFloor = clampNonNegative("wealth_tax_floor", parsed.wealthTaxFloor);
            parsed.wealthTaxMedianFloorFactor = clampRange("wealth_tax_median_floor_factor", parsed.wealthTaxMedianFloorFactor, 0.0, MAX_MEDIAN_FLOOR_FACTOR, "");
            parsed.wealthTaxInactiveDays = clampNonNegative("wealth_tax_inactive_days", parsed.wealthTaxInactiveDays);
            parsed.wealthTaxInactiveMultiplier = clampRange("wealth_tax_inactive_multiplier", parsed.wealthTaxInactiveMultiplier, 0.0, MAX_INACTIVE_MULTIPLIER, "");
            parsed.wealthTaxMaxCatchupDays = clampNonNegative("wealth_tax_max_catchup_days", parsed.wealthTaxMaxCatchupDays);
            parsed.wealthTaxRebateMaxRate = clampPercentage("wealth_tax_rebate_max_rate", parsed.wealthTaxRebateMaxRate);
            parsed.wealthTaxRebateTriggerFactor = clampRange("wealth_tax_rebate_trigger_factor", parsed.wealthTaxRebateTriggerFactor, 0.0, MAX_REBATE_TRIGGER_FACTOR, "");
            parsed.factions = requireSection("factions", parsed.factions, FactionsSection::new);
            parsed.professions = requireSection("professions", parsed.professions, ProfessionsSection::new);
            parsed.containerLock = requireSection("container_lock", parsed.containerLock, ContainerLockSection::new);
            parsed.factions.clamp();
            parsed.professions.clamp();
            parsed.containerLock.clamp();
            INSTANCE = parsed;
            normalizeDynamicPriceBounds();
        } catch (Exception e) {
            throw new IllegalStateException("[EconomyCraft] Failed to read/parse config.json at " + file, e);
        }
    }

    /**
     * A hand-edited {@code "factions": null} would otherwise leave the whole phase with a live NPE waiting for
     * the first caller, so an unusable section is rebuilt from defaults and the mistake is named in the log.
     */
    private static <T> T requireSection(String name, T section, java.util.function.Supplier<T> fallback) {
        if (section != null) return section;
        LOGGER.warn("[EconomyCraft] {} section is null; rebuilding it from defaults.", name);
        return fallback.get();
    }

    private static double clampRange(String fieldName, double value, double min, double max, String reason) {
        double clamped = Math.clamp(value, min, max);
        if (clamped != value) {
            LOGGER.warn("[EconomyCraft] {} ({}) is outside the valid {}-{} range{}; clamping to {}.",
                    fieldName, value, min, max, reason, clamped);
        }
        return clamped;
    }

    private static double clampPercentage(String fieldName, double value) {
        return clampRange(fieldName, value, 0.0, 1.0, " (decimal factor, e.g. 0.1 = 10%)");
    }

    private static int clampRetentionDays(int days) {
        if (days < MIN_TRANSACTION_LOG_RETENTION_DAYS) {
            LOGGER.warn("[EconomyCraft] transaction_log_retention_days ({}) is below the minimum of {} day(s); clamping to {}.",
                    days, MIN_TRANSACTION_LOG_RETENTION_DAYS, MIN_TRANSACTION_LOG_RETENTION_DAYS);
            return MIN_TRANSACTION_LOG_RETENTION_DAYS;
        }
        if (days > WARN_TRANSACTION_LOG_RETENTION_DAYS) {
            LOGGER.warn("[EconomyCraft] transaction_log_retention_days ({}) is above {} days; transaction logs can take up significant disk space over that long a retention period.",
                    days, WARN_TRANSACTION_LOG_RETENTION_DAYS);
        }
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

    public static void normalizeDynamicPriceBounds() {
        EconomyConfig config = INSTANCE;
        config.dynamicPriceMinMultiplier = clampMultiplierRange("dynamic_price_min_multiplier", config.dynamicPriceMinMultiplier);
        config.dynamicPriceMaxMultiplier = clampMultiplierRange("dynamic_price_max_multiplier", config.dynamicPriceMaxMultiplier);
        if (config.dynamicPriceMaxMultiplier < config.dynamicPriceMinMultiplier) {
            LOGGER.warn("[EconomyCraft] dynamic_price_max_multiplier ({}) is below dynamic_price_min_multiplier ({}); raising it to match.",
                    config.dynamicPriceMaxMultiplier, config.dynamicPriceMinMultiplier);
            config.dynamicPriceMaxMultiplier = config.dynamicPriceMinMultiplier;
        }
    }

    private static double clampMultiplierRange(String fieldName, double value) {
        return clampRange(fieldName, value, 0.0, MAX_DYNAMIC_PRICE_MULTIPLIER, "");
    }

    public static void save() {
        if (file == null) {
            throw new IllegalStateException("[EconomyCraft] EconomyConfig not initialized. Call load() first.");
        }
        try {
            Files.writeString(
                    file,
                    GSON.toJson(INSTANCE),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new IllegalStateException("[EconomyCraft] Failed to save config.json at " + file, e);
        }
    }

    private static void copyDefaultFromJarOrThrow() {
        try (InputStream in = EconomyConfig.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException(
                        "[EconomyCraft] Missing bundled default " + DEFAULT_RESOURCE_PATH +
                                " (did you forget to include it in resources?)"
                );
            }
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("[EconomyCraft] Created {} from bundled default {}", file, DEFAULT_RESOURCE_PATH);
        } catch (IOException e) {
            throw new IllegalStateException("[EconomyCraft] Failed to create config.json at " + file, e);
        }
    }

    private static void mergeNewDefaultsFromBundledDefault() {
        JsonObject defaults = readBundledDefaultJson();
        if (defaults == null) {
            LOGGER.warn("[EconomyCraft] No bundled defaults found; skipping config merge.");
            return;
        }

        JsonObject userRoot;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                userRoot = new JsonObject();
            } else {
                JsonElement parsed = JsonParser.parseString(json);
                if (parsed.isJsonNull()) {
                    userRoot = new JsonObject();
                } else if (parsed.isJsonObject()) {
                    userRoot = parsed.getAsJsonObject();
                } else {
                    LOGGER.warn("[EconomyCraft] config.json root is not an object, skipping merge.");
                    return;
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("[EconomyCraft] Failed to read/parse user config.json for merge at " + file, ex);
        }

        boolean migratedShopSettings = migrateShopSettings(userRoot, defaults);
        int[] added = new int[]{0};
        addMissingRecursive(userRoot, defaults, added);

        if (migratedShopSettings || added[0] > 0) {
            try {
                Files.writeString(file, GSON.toJson(userRoot), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IllegalStateException("[EconomyCraft] Failed to write merged config.json at " + file, ex);
            }
        }
    }

    private static boolean migrateShopSettings(JsonObject target, JsonObject defaults) {
        JsonElement legacyShop = target.remove("server_shop_enabled");
        if (legacyShop != null) {
            JsonElement legacyAuction = target.get("shop_enabled");
            if (!target.has("auction_enabled") && legacyAuction != null) {
                target.add("auction_enabled", legacyAuction.deepCopy());
            }
            target.add("shop_enabled", legacyShop.deepCopy());
            LOGGER.info("[EconomyCraft] Migrated shop settings to shop_enabled and auction_enabled.");
            return true;
        }

        if (!target.has("auction_enabled") && target.has("shop_enabled")) {
            JsonElement legacyAuction = target.get("shop_enabled");
            target.add("auction_enabled", legacyAuction.deepCopy());
            JsonElement defaultShop = defaults.get("shop_enabled");
            if (defaultShop != null) target.add("shop_enabled", defaultShop.deepCopy());
            LOGGER.info("[EconomyCraft] Migrated shop_enabled to auction_enabled.");
            return true;
        }
        return false;
    }

    private static JsonObject readBundledDefaultJson() {
        try (InputStream in = EconomyConfig.class.getResourceAsStream(DEFAULT_RESOURCE_PATH)) {
            if (in == null) return null;

            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return null;

            return parsed.getAsJsonObject();
        } catch (Exception ex) {
            throw new IllegalStateException("[EconomyCraft] Failed to read bundled default config.json from " + DEFAULT_RESOURCE_PATH, ex);
        }
    }

    private static void addMissingRecursive(JsonObject target, JsonObject defaults, int[] added) {
        for (Map.Entry<String, JsonElement> e : defaults.entrySet()) {
            String key = e.getKey();
            JsonElement defVal = e.getValue();

            if (!target.has(key)) {
                target.add(key, defVal == null ? JsonNull.INSTANCE : defVal.deepCopy());
                added[0]++;
                continue;
            }

            JsonElement curVal = target.get(key);
            if (curVal != null && curVal.isJsonObject()
                    && defVal != null && defVal.isJsonObject()) {
                addMissingRecursive(curVal.getAsJsonObject(), defVal.getAsJsonObject(), added);
            }
        }
    }
}

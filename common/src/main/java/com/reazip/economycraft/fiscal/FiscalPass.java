package com.reazip.economycraft.fiscal;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Daily money-policy pass. Levies the wealth tax on balances above the floor, and optionally
 * pays a rebate when the median has fallen below the reference balance.
 *
 * <p>Driven once per epoch day from the server tick. All balance movement goes through
 * {@link EconomyManager#addMoney} / {@link EconomyManager#removeMoney} so the transaction
 * log, sidebar, leaderboard and events stay in step.
 */
public final class FiscalPass {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final EconomyManager eco;
    private final Path file;
    private long lastFiscalDay = -1L;
    private boolean loaded = false;

    public FiscalPass(EconomyManager eco, Path dataDir) {
        this.eco = eco;
        this.file = dataDir.resolve("fiscal.json");
    }

    /** Outcome of one fiscal pass, for logging and the admin preview. */
    public record Report(int daysApplied, boolean clipped, int taxed, long totalTaxed,
                          int rebated, long totalRebated, long floor) {
        public boolean didAnything() {
            return taxed > 0 || rebated > 0;
        }
    }

    /** Runs the pass if the epoch day has rolled over since the last run. Safe to call every tick. */
    public Report runIfDue() {
        load();

        long today = LocalDate.now().toEpochDay();
        if (lastFiscalDay < 0) {
            lastFiscalDay = today;
            save();
            return null;
        }

        FiscalPolicy.Accrual accrual = FiscalPolicy.accrual(
                lastFiscalDay, today, EconomyConfig.get().wealthTaxMaxCatchupDays);
        if (accrual.days() <= 0) return null;

        return apply(accrual.days(), accrual.clipped(), today);
    }

    /** Applies the pass immediately, ignoring the day rollover. Backs the admin force-run. */
    public Report runNow() {
        load();
        return apply(1, false, LocalDate.now().toEpochDay());
    }

    public long getLastFiscalDay() {
        load();
        return lastFiscalDay;
    }

    /** Forgets the recorded day so the next tick treats the server as freshly booted. */
    public void resetState() {
        load();
        lastFiscalDay = -1L;
        save();
    }

    private Report apply(int days, boolean clipped, long today) {
        EconomyConfig config = EconomyConfig.get();

        if (!config.wealthTaxEnabled) {
            lastFiscalDay = today;
            save();
            LOGGER.info("[EconomyCraft] Fiscal pass skipped: wealth_tax_enabled is off.");
            return null;
        }

        if (clipped) {
            LOGGER.warn("[EconomyCraft] Fiscal pass applied {} day(s) for a gap longer than wealth_tax_max_catchup_days ({}); the remainder was dropped.",
                    days, config.wealthTaxMaxCatchupDays);
        }

        int taxed = 0;
        long totalTaxed = 0L;
        int rebated = 0;
        long totalRebated = 0L;
        long floor = 0L;

        for (int day = 0; day < days; day++) {
            Outcome outcome = applyOneDay();
            taxed += outcome.taxed();
            totalTaxed += outcome.totalTaxed();
            rebated += outcome.rebated();
            totalRebated += outcome.totalRebated();
            floor = outcome.floor();
        }

        lastFiscalDay = today;
        save();

        Report report = new Report(days, clipped, taxed, totalTaxed, rebated, totalRebated, floor);
        LOGGER.info("[EconomyCraft] Fiscal pass: {} day(s), floor {}, {} player(s) taxed totalling {}, {} rebated totalling {}.",
                days, floor, taxed, totalTaxed, rebated, totalRebated);
        return report;
    }

    private record Outcome(int taxed, long totalTaxed, int rebated, long totalRebated, long floor) {}

    private Outcome applyOneDay() {
        eco.requireServerThread();

        EconomyConfig config = EconomyConfig.get();

        eco.refreshDynamicPrices();
        double median = eco.getDynamicPriceMedian();
        long floor = FiscalPolicy.floor(median, config.wealthTaxFloor, config.wealthTaxMedianFloorFactor);
        boolean rebateArmed = config.wealthTaxRebateEnabled
                && FiscalPolicy.rebateTriggered(median, config.startingBalance, config.wealthTaxRebateTriggerFactor);

        long now = System.currentTimeMillis();
        int taxed = 0;
        long totalTaxed = 0L;
        int rebated = 0;
        long totalRebated = 0L;

        for (Map.Entry<UUID, Long> entry : new ArrayList<>(eco.getBalances().entrySet())) {
            UUID id = entry.getKey();
            Long balance = entry.getValue();
            if (id == null || balance == null) continue;

            double rate = FiscalPolicy.rateFor(
                    eco.getLastSeenMs(id), now,
                    config.wealthTaxInactiveDays,
                    config.wealthTaxRate,
                    config.wealthTaxInactiveMultiplier);

            long tax = FiscalPolicy.taxFor(balance, floor, rate);
            if (tax > 0 && eco.removeMoney(id, tax, EconomySources.WEALTH_TAX, "Daily wealth tax").successful()) {
                taxed++;
                totalTaxed += tax;
                notify(id, tax, true);
            }

            if (rebateArmed) {
                long rebate = FiscalPolicy.rebateFor(balance, floor, config.wealthTaxRebateMaxRate);
                if (rebate > 0 && eco.addMoney(id, rebate, EconomySources.WEALTH_REBATE, "Daily rebate").successful()) {
                    rebated++;
                    totalRebated += rebate;
                    notify(id, rebate, false);
                }
            }
        }

        eco.save();
        return new Outcome(taxed, totalTaxed, rebated, totalRebated, floor);
    }

    private void notify(UUID id, long amount, boolean paid) {
        ServerPlayer player = eco.getServer().getPlayerList().getPlayer(id);
        if (player == null) return;

        String verb = paid ? "Daily wealth tax" : "Daily rebate";
        player.sendSystemMessage(Component.literal(verb + ": "
                + EconomyCraft.signedMoney(paid ? -amount : amount)));
    }

    private void load() {
        if (loaded) return;
        loaded = true;

        if (!Files.exists(file)) return;

        try {
            State state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), State.class);
            if (state != null) lastFiscalDay = state.lastFiscalDay;
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft] Could not read {}; the first fiscal pass after this will be skipped.", file, e);
        }
    }

    private void save() {
        try {
            Files.writeString(file, GSON.toJson(new State(lastFiscalDay)),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("[EconomyCraft] Failed to write {}", file, e);
        }
    }

    private static final class State {
        @SerializedName("lastFiscalDay")
        long lastFiscalDay = -1L;

        State() {}

        State(long lastFiscalDay) {
            this.lastFiscalDay = lastFiscalDay;
        }
    }
}

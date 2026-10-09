package com.reazip.economycraft.faction;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.api.v1.EconomyCraftApi;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.config.FactionsSection;
import com.reazip.economycraft.db.Documents;
import com.reazip.economycraft.db.EconomyDatabase;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The daily fiscal pass for the two parties that levy one: Capitalism's {@code Nhà nước tư bản} (spec 23) and
 * Monarchy's {@code Cống nạp} (spec 30).
 *
 * <p>Structurally a sibling of {@link com.reazip.economycraft.fiscal.FiscalPass} — epoch-day comparison,
 * capped catch-up, a {@code Report} summary — and deliberately <strong>not</strong> a part of it. D4 froze
 * {@code FiscalPass}, {@code FiscalPolicy}, {@code fiscal.json} and every {@code wealth_tax*} key, so this
 * pass keeps its own {@code lastFiscalDay}, its own computed rates and its own state file. The pre-existing
 * wealth tax cannot regress because nothing here can reach it.
 *
 * <p>Two rules shape the arithmetic and both come from the decisions rather than from convenience:
 *
  * <ul>
  *   <li><strong>All money deducted is burned</strong> (D5). There is no king, no recipient and no treasury:
  *       {@code removeMoney(player, debit, source, …)} debits one balance and credits nobody. A transfer
  *       always has a receiver — its engine and result both reject {@code null} — so a burn is a removal,
  *       never a transfer with a {@code null} recipient.</li>
 *   <li><strong>The two parties are not the same function</strong> (D19). Capitalism's rate multiplies the
 *       server-wide player-activity inflation; Monarchy's multiplies money supply per player. Sharing one
 *       helper between them would erase the only difference D19 actually decided.</li>
 * </ul>
 *
 * <p>A debit that fails is counted as a failure, never as money collected (R9). The balance is clamped so the
 * tax cannot exceed it, so {@code INSUFFICIENT_FUNDS} should be unreachable — but a pass that reports
 * income it did not take is worse than one that reports nothing.
 */
public final class FactionFiscalPass {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final EconomyManager eco;
    private final Path file;
    private final EconomyDatabase db;
    private long lastFiscalDay = -1L;
    private double lastCapitalismRate = 0.05;
    private double lastMonarchyRate = 0.017;
    private boolean loaded = false;

    public FactionFiscalPass(EconomyManager eco, Path dataDir) {
        this(eco, null, dataDir);
    }

    /** Production constructor: persists to the shared database document instead of the file. */
    public FactionFiscalPass(EconomyManager eco, EconomyDatabase db, Path dataDir) {
        this.eco = eco;
        this.db = db;
        this.file = dataDir.resolve("faction_fiscal.json");
    }

    /**
     * What one run did.
     *
     * @param clipped whether the gap was longer than {@code factions.daily_tax_max_catchup_days} and days were
     *                dropped, so a server owner is told rather than left to infer it from the balance
     * @param failed  debits the economy refused, which is money that was <em>not</em> collected
     */
    public record Report(int daysApplied, boolean clipped, int capitalismTaxed, long capitalismTotal,
                         int capitalismFailed, int monarchyTaxed, long monarchyTotal, int monarchyFailed,
                         RateBreakdown capitalismRate, RateBreakdown monarchyRate) {
        public boolean didAnything() {
            return capitalismTaxed > 0 || monarchyTaxed > 0;
        }

        public int failedTotal() {
            return capitalismFailed + monarchyFailed;
        }
    }

    private static class State {
        @SerializedName("last_fiscal_day")
        long lastFiscalDay = -1L;
        @SerializedName("capitalism_rate")
        double capitalismRate = 0.05;
        @SerializedName("monarchy_rate")
        double monarchyRate = 0.017;
    }

    /** Runs the pass if the epoch day has rolled over. Safe to call every tick. */
    public Report runIfDue() {
        load();

        long today = LocalDate.now().toEpochDay();
        if (lastFiscalDay < 0) {
            // First boot: record the day and charge nothing, so a fresh install is not billed for the days
            // before it existed.
            lastFiscalDay = today;
            save();
            return null;
        }

        int days = FactionFiscalPolicy.catchUpDays(lastFiscalDay, today, catchupDays());
        if (days <= 0) return null;

        return apply(days, today);
    }

    /** Forces execution now for admin testing. */
    public Report runNow() {
        load();
        return apply(1, LocalDate.now().toEpochDay());
    }

    public long getLastFiscalDay() {
        load();
        return lastFiscalDay;
    }

    public double getLastCapitalismRate() {
        load();
        return lastCapitalismRate;
    }

    public double getLastMonarchyRate() {
        load();
        return lastMonarchyRate;
    }

    public void resetState() {
        load();
        lastFiscalDay = -1L;
        lastCapitalismRate = 0.05;
        lastMonarchyRate = 0.017;
        save();
    }

    private int catchupDays() {
        return EconomyConfig.get().factions.dailyTaxMaxCatchupDays;
    }

    private Report apply(int days, long today) {
        eco.requireServerThread();

        if (!EconomyConfig.get().factions.enabled) {
            lastFiscalDay = today;
            save();
            LOGGER.info("[EconomyCraft] Faction fiscal pass skipped: factions.enabled is off.");
            return null;
        }

        int capTaxed = 0;
        long capTotal = 0L;
        int capFailed = 0;
        int monTaxed = 0;
        long monTotal = 0L;
        int monFailed = 0;
        RateBreakdown capRate = null;
        RateBreakdown monRate = null;

        for (int day = 0; day < days; day++) {
            Outcome outcome = applyOneDay();
            capTaxed += outcome.capitalismTaxed();
            capTotal += outcome.capitalismTotal();
            capFailed += outcome.capitalismFailed();
            monTaxed += outcome.monarchyTaxed();
            monTotal += outcome.monarchyTotal();
            monFailed += outcome.monarchyFailed();
            capRate = outcome.capitalismRate();
            monRate = outcome.monarchyRate();
        }

        boolean clipped = days >= catchupDays() && catchupDays() > 0
                && today - lastFiscalDay > catchupDays();
        if (clipped) {
            LOGGER.warn("[EconomyCraft] Faction fiscal pass applied {} day(s); the gap was longer than "
                    + "factions.daily_tax_max_catchup_days ({}), so the rest was dropped.", days, catchupDays());
        }

        lastFiscalDay = today;
        lastCapitalismRate = capRate == null ? lastCapitalismRate : capRate.appliedRate();
        lastMonarchyRate = monRate == null ? lastMonarchyRate : monRate.appliedRate();
        save();

        Report report = new Report(days, clipped, capTaxed, capTotal, capFailed,
                monTaxed, monTotal, monFailed, capRate, monRate);
        LOGGER.info("[EconomyCraft] Faction fiscal pass: {} day(s). {}", days, capRate == null ? "nothing to do" : capRate.describe());
        if (monRate != null) LOGGER.info("[EconomyCraft] Faction fiscal pass: {}", monRate.describe());
        if (report.failedTotal() > 0) {
            LOGGER.warn("[EconomyCraft] Faction fiscal pass: {} debit(s) were refused and are not counted as collected.",
                    report.failedTotal());
        }
        return report;
    }

    private record Outcome(int capitalismTaxed, long capitalismTotal, int capitalismFailed,
                           int monarchyTaxed, long monarchyTotal, int monarchyFailed,
                           RateBreakdown capitalismRate, RateBreakdown monarchyRate) {}

    private Outcome applyOneDay() {
        EconomyConfig config = EconomyConfig.get();
        FactionsSection.CapitalismSettings capConfig = config.factions.capitalism;
        FactionsSection.MonarchySettings monConfig = config.factions.monarchy;
        FactionStore factions = eco.getFactions();

        // 1. Gather the aggregates once per day, saturating so a server full of MAX balances cannot overflow
        //    into a negative total and a negative share (D14's third failure mode).
        long totalMoney = 0L;
        long capitalismMoney = 0L;
        long monarchyMoney = 0L;

        for (Map.Entry<UUID, Long> entry : new ArrayList<>(eco.getBalances().entrySet())) {
            UUID id = entry.getKey();
            Long balance = entry.getValue();
            if (id == null || balance == null || balance <= 0L) continue;

            totalMoney = saturatingAdd(totalMoney, balance);
            FactionId faction = factions.factionOf(id);
            if (faction == FactionId.CAPITALISM) {
                capitalismMoney = saturatingAdd(capitalismMoney, balance);
            } else if (faction == FactionId.MONARCHY) {
                monarchyMoney = saturatingAdd(monarchyMoney, balance);
            }
        }

        // 2. Capitalism: base x global player-activity inflation x concentration (D14).
        double capShare = FactionFiscalPolicy.shareOf(capitalismMoney, totalMoney);
        double capInflation = capConfig.useGlobalInflation ? globalInflation() : 1.0;
        double capConcentration = FactionFiscalPolicy.concentrationMultiplier(
                capShare, capConfig.concentrationReferenceShare, capConfig.concentrationElasticity,
                capConfig.concentrationMinMultiplier, capConfig.concentrationMaxMultiplier);
        double capRawRate = FactionFiscalPolicy.capitalismRate(capConfig.dailyTaxRate, capInflation, capConcentration);
        double capAppliedRate = FactionFiscalPolicy.clampRateChange(
                capRawRate, lastCapitalismRate, capConfig.maxRateChangePerDay);

        // 3. Monarchy: base x money-supply-per-player x the same concentration multiplier (D19).
        double monShare = FactionFiscalPolicy.shareOf(monarchyMoney, totalMoney);
        int activePlayers = Math.max(1, eco.getServer().getPlayerList().getPlayerCount());
        double monInflation = FactionFiscalPolicy.monarchyMoneySupplyInflation(
                totalMoney, activePlayers, monConfig.moneySupplyReferencePerPlayer, monConfig.moneySupplyInflationMax);
        double monConcentration = FactionFiscalPolicy.concentrationMultiplier(
                monShare, capConfig.concentrationReferenceShare, capConfig.concentrationElasticity,
                capConfig.concentrationMinMultiplier, capConfig.concentrationMaxMultiplier);
        double monRawRate = FactionFiscalPolicy.monarchyRate(monConfig.dailyTaxRate, monInflation, monConcentration);
        double monAppliedRate = FactionFiscalPolicy.clampRateChange(
                monRawRate, lastMonarchyRate, capConfig.maxRateChangePerDay);

        RateBreakdown capBreakdown = new RateBreakdown("Capitalism", capConfig.dailyTaxRate, capInflation,
                capShare, capConfig.concentrationReferenceShare, capConcentration, capRawRate, capAppliedRate);
        RateBreakdown monBreakdown = new RateBreakdown("Monarchy", monConfig.dailyTaxRate, monInflation,
                monShare, capConfig.concentrationReferenceShare, monConcentration, monRawRate, monAppliedRate);

        int capTaxed = 0;
        long capTotal = 0L;
        int capFailed = 0;
        int monTaxed = 0;
        long monTotal = 0L;
        int monFailed = 0;

        // 4. Charge each account once. The iteration copies the map because a transfer mutates it.
        for (Map.Entry<UUID, Long> entry : new ArrayList<>(eco.getBalances().entrySet())) {
            UUID id = entry.getKey();
            Long balance = entry.getValue();
            if (id == null || balance == null || balance <= 0L) continue;

            FactionId faction = factions.factionOf(id);
            if (faction == FactionId.CAPITALISM) {
                long tax = FactionFiscalPolicy.taxAmount(balance, capAppliedRate);
                if (tax <= 0L) continue;
                if (charge(id, tax, EconomySources.DAILY_TAX, "Daily Capitalism tax")) {
                    capTaxed++;
                    capTotal += tax;
                    notify(id, capBreakdown, tax);
                } else {
                    capFailed++;
                    notifyFailed(id, capBreakdown, tax);
                }
            } else if (faction == FactionId.MONARCHY) {
                long tax = FactionFiscalPolicy.taxAmount(balance, monAppliedRate * monConfig.corruptionMultiplier);
                if (tax <= 0L) continue;
                if (charge(id, tax, EconomySources.CORRUPTION_TAX, "Daily Monarchy corruption tax (Cống nạp)")) {
                    monTaxed++;
                    monTotal += tax;
                    notify(id, monBreakdown, tax);
                } else {
                    monFailed++;
                    // D5's one failure path: the player cannot afford it. Nobody is credited, so there is no
                    // king to be offline and nothing to retry — the balance simply stays where it was.
                    notifyFailed(id, monBreakdown, tax);
                }
            }
        }

        eco.save();
        return new Outcome(capTaxed, capTotal, capFailed, monTaxed, monTotal, monFailed, capBreakdown, monBreakdown);
    }

    /** A pure burn: debit the player, credit nobody (D5). A removal, not a null-receiver transfer. */
    private boolean charge(UUID player, long amount, MutationSource source, String detail) {
        return eco.removeMoney(player, amount, source, detail).successful();
    }

    private void notify(UUID player, RateBreakdown breakdown, long amount) {
        ServerPlayer online = eco.getServer().getPlayerList().getPlayer(player);
        if (online == null) return;
        online.sendSystemMessage(Component.literal("§6" + breakdown.party() + " daily tax: -$"
                + EconomyCraft.formatMoney(amount)).withStyle(ChatFormatting.GOLD));
        // D14: every factor, so any number in the log can be explained without reading the source.
        online.sendSystemMessage(Component.literal("§7" + breakdown.describe()).withStyle(ChatFormatting.GRAY));
    }

    private void notifyFailed(UUID player, RateBreakdown breakdown, long amount) {
        ServerPlayer online = eco.getServer().getPlayerList().getPlayer(player);
        if (online == null) return;
        online.sendSystemMessage(Component.literal("§c" + breakdown.party() + " daily tax of $"
                + EconomyCraft.formatMoney(amount) + " could not be collected.")
                .withStyle(ChatFormatting.RED));
        online.sendSystemMessage(Component.literal("§7" + breakdown.describe()).withStyle(ChatFormatting.GRAY));
    }

    /** The shared, read-only player-activity signal (D14). Never written through. */
    private double globalInflation() {
        try {
            return EconomyCraftApi.get(eco.getServer()).inflationMultiplier();
        } catch (Exception ex) {
            // The API is optional: with no provider installed the signal is simply its neutral value, which
            // leaves the configured base rate in force instead of failing the whole pass.
            return 1.0;
        }
    }

    private static long saturatingAdd(long running, long value) {
        if (value >= EconomyManager.MAX - running) return EconomyManager.MAX;
        return running + value;
    }

    private void load() {
        if (loaded) return;
        loaded = true;

        String json = Documents.read(db, file, "faction_fiscal.json");
        if (json == null) return;

        try {
            State state = GSON.fromJson(json, State.class);
            if (state != null) {
                this.lastFiscalDay = state.lastFiscalDay;
                this.lastCapitalismRate = state.capitalismRate;
                this.lastMonarchyRate = state.monarchyRate;
            }
        } catch (Exception e) {
            LOGGER.error("[EconomyCraft] Failed to load faction fiscal state from {}", file, e);
        }
    }

    private void save() {
        State state = new State();
        state.lastFiscalDay = this.lastFiscalDay;
        state.capitalismRate = this.lastCapitalismRate;
        state.monarchyRate = this.lastMonarchyRate;
        Documents.write(db, file, "faction_fiscal.json", GSON.toJson(state));
    }
}

package com.reazip.economycraft.tax;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.FactionsSection;
import com.reazip.economycraft.faction.FactionId;
import com.reazip.economycraft.faction.FactionStore;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * The production {@link TaxExemption}: every Phase 9 tax rule that keys on a player's party.
 *
 * <ul>
 *   <li><strong>Anarchism {@code Tự do}</strong> (spec 34) — a <em>member</em> is exempt from every scope. The
 *       base amount is untouched, so toll fees and purchase prices are still paid; only the tax disappears. A
 *       player who has not chosen a party is not a member of anything and gets no exemption; see
 *       {@link #chosenFactionOf}.</li>
 *   <li><strong>Communism {@code Đầu tư công}</strong> (spec 12) — a 50 % chance the <em>toll tax</em> is
 *       waived. The toll owner still receives the fee, because the fee is the base and never a tax.</li>
 *   <li><strong>Capitalism {@code Thị trường cạnh tranh}</strong> (spec 21, D8) — a purchase from a
 *       Capitalism <em>seller</em> is exempt. Keyed on the seller, deliberately not the buyer: the buff is a
 *       selling-price advantage, and D8 rejected introducing a seller-side levy to pay for it.</li>
 *   <li><strong>Capitalism {@code Nhà nước tư bản}</strong> (spec 23) — toll tax rate × 1.25.</li>
 *   <li><strong>Monarchy {@code Nhập khẩu}</strong> (spec 31, D7) — a 50 % chance of an extra tax equal to
 *       50 % of the item's own tax, on the import scopes only.</li>
 * </ul>
 *
 * <p>Two rules here are dice rolls, so the randomness is a {@link DoubleSupplier} rather than a direct call
 * to {@code Math.random()}: production passes {@link Math#random} and tests pass a constant, which is what
 * makes a 50 % rule assertable instead of flaky.
 */
public final class FactionTaxRules implements TaxExemption {

    private final FactionsSection config;
    private final FactionStore factions;
    private final DoubleSupplier roll;

    private FactionTaxRules(FactionsSection config, FactionStore factions, DoubleSupplier roll) {
        this.config = config;
        this.factions = factions;
        this.roll = roll;
    }

    /** The rules for a live economy, or {@link TaxExemption#NONE} when the feature is off. */
    public static TaxExemption forEconomy(@Nullable EconomyManager eco) {
        if (eco == null) return TaxExemption.NONE;
        return forEconomy(eco, EconomyConfig.get().factions);
    }

    /** The rules for a live economy, with the faction section supplied so tests can build their own. */
    public static TaxExemption forEconomy(EconomyManager eco, FactionsSection config) {
        if (config == null || !config.enabled) return TaxExemption.NONE;
        return new FactionTaxRules(config, eco.getFactions(), Math::random);
    }

    /**
     * Builds rules over an explicit config and store with an explicit source of randomness.
     *
     * @param roll returns a value in {@code [0, 1)}; a rule fires when {@code roll < chance}
     */
    public static FactionTaxRules of(FactionsSection config, FactionStore factions, DoubleSupplier roll) {
        return new FactionTaxRules(config, factions, roll == null ? Math::random : roll);
    }

    @Override
    public boolean exempts(TaxScope scope, @Nullable UUID payer, @Nullable UUID counterparty) {
        if (!enabled()) return false;

        // D8 first: the seller's party decides, and it does not matter what the buyer belongs to.
        if (scope == TaxScope.TRANSACTION_AUCTION_BUY && counterparty != null
                && chosenFactionOf(counterparty) == FactionId.CAPITALISM) {
            return true;
        }
        if (payer == null) return false;

        FactionId payerFaction = chosenFactionOf(payer);
        if (payerFaction == null) return false;
        if (payerFaction == FactionId.ANARCHISM) return true;
        return payerFaction == FactionId.COMMUNISM
                && scope == TaxScope.TOLL
                && passes(config.communism.tollTaxExemptChance);
    }

    @Override
    public double rateMultiplier(TaxScope scope, @Nullable UUID payer) {
        if (!enabled() || payer == null || scope != TaxScope.TOLL) return 1.0;
        if (chosenFactionOf(payer) != FactionId.CAPITALISM) return 1.0;
        return config.capitalism.tollTaxMultiplier;
    }

    @Override
    public long surcharge(TaxScope scope, @Nullable UUID payer, TaxQuote quoted) {
        if (!enabled() || payer == null || quoted.amount() <= 0) return 0L;
        if (chosenFactionOf(payer) != FactionId.MONARCHY || !isImportScope(scope)) return 0L;
        if (!passes(config.monarchy.importTaxChance)) return 0L;
        // Rounded from the already-rounded base tax, so the surcharge is always a whole multiple of half of a
        // whole number and never a re-rounding of an unrounded product.
        return Math.round(quoted.amount() * config.monarchy.importTaxFactor);
    }

    /**
     * The flows D7 classifies as imports: buying from the fixed-price shop, buying from another player's
     * {@code /ah} listing, and fulfilling an order. Sells are excluded (the seller is exporting) and so are
     * tolls and player-to-player payment, which are not trade in goods.
     */
    public static boolean isImportScope(TaxScope scope) {
        return scope == TaxScope.TRANSACTION_SHOP
                || scope == TaxScope.TRANSACTION_AUCTION_BUY
                || scope == TaxScope.TRANSACTION_ORDER;
    }

    /**
     * The party {@code player} has actually joined, or {@code null} when there is no store to ask or they have
     * joined nothing.
     *
     * <p>The {@code hasChosen} half is load-bearing, and it is why this is not simply {@code factionOf}: the
     * store's answer for a player with no record is {@link FactionId#defaultFaction()}, which is Anarchism. So a
     * player who has never been asked would be handed Anarchism's unconditional exemption from every tax scope on
     * this server — the largest discount in the mod, granted to nobody who asked for it. Every rule here keys on
     * a choice, and a player who has made no choice gets the unmodified tax.
     *
     * @return the chosen party, or {@code null} for no store, no player, or no choice
     */
    @Nullable
    private FactionId chosenFactionOf(@Nullable UUID player) {
        if (factions == null || player == null) return null;
        return factions.hasChosen(player) ? factions.factionOf(player) : null;
    }

    private boolean enabled() {
        return config != null && config.enabled && factions != null;
    }

    /** A chance of {@code 0} never fires and a chance of {@code 1} always does, whatever the source returns. */
    private boolean passes(double chance) {
        if (chance <= 0.0) return false;
        if (chance >= 1.0) return true;
        return roll.getAsDouble() < chance;
    }
}

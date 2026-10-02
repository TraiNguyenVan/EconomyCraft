package com.reazip.economycraft.tax;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.profession.MerchantEffects;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The single place any tax amount is decided.
 *
 * <p>Phase 1 is a <strong>pure refactor</strong>: this reproduces {@code Math.round(base * taxRate)}
 * bit-for-bit, so charges, lore text and affordability checks are unchanged.
 *
 * <p>Phase 7 (Merchants): The {@code Lưỡi không xương} discount reduces the pre-tax base cost by 5 %
 * (Apprentice) or 15 % (Master), scaled by 0.5 when rusty. Tax is then levied on the discounted base,
 * so both the purchase price and the tax are discounted through this single mechanism.
 *
 * <p>{@link #quote(TaxScope, long, double)} is the pure core and takes the rate explicitly, so it is
 * testable without touching {@link EconomyConfig}. {@link #resolve(TaxScope, long)} is the production entry
 * point and reads the configured rate.
 */
public final class TaxPolicy {

    private TaxPolicy() {
    }

    /** Prices {@code base} at the configured rate. Production entry point. */
    public static TaxQuote resolve(TaxScope scope, long base) {
        return quote(scope, base, EconomyConfig.get().taxRate);
    }

    /** Prices {@code base} at the configured rate, applying player discounts if eligible. */
    public static TaxQuote resolve(TaxScope scope, long base, @Nullable UUID player, @Nullable EconomyManager eco) {
        if (player == null || eco == null) {
            return resolve(scope, base);
        }
        double discountRate = MerchantEffects.discountRate(player, eco);
        return quote(scope, base, EconomyConfig.get().taxRate, discountRate);
    }

    /** Prices {@code base} at the configured rate, applying player discounts if eligible. */
    public static TaxQuote resolve(TaxScope scope, long base, @Nullable ServerPlayer player) {
        if (player == null) return resolve(scope, base);
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            return resolve(scope, base, player.getUUID(), eco);
        } catch (Exception ignored) {
            return resolve(scope, base);
        }
    }

    /** Pure core: prices {@code base} at an explicit rate with no discount. No server, no config, no I/O. */
    public static TaxQuote quote(TaxScope scope, long base, double rate) {
        long amount = Math.round(base * rate);
        return new TaxQuote(base, rate, amount, 0L, false, scope.source());
    }

    /** Pure core: prices {@code base} at an explicit tax rate and discount rate. */
    public static TaxQuote quote(TaxScope scope, long base, double rate, double discountRate) {
        if (discountRate <= 0.0) {
            return quote(scope, base, rate);
        }
        long discount = Math.round(base * discountRate);
        long discountedBase = Math.max(0L, base - discount);
        long amount = Math.round(discountedBase * rate);
        return new TaxQuote(base, rate, amount, discount, false, scope.source());
    }

    /** The tax amount alone — for lore text that mirrors a charge. */
    public static long tax(TaxScope scope, long base) {
        return resolve(scope, base).amount();
    }

    public static long tax(TaxScope scope, long base, @Nullable UUID player, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, eco).amount();
    }

    /** What the recipient receives after tax — order fulfilment. */
    public static long net(TaxScope scope, long base) {
        return resolve(scope, base).net();
    }

    public static long net(TaxScope scope, long base, @Nullable UUID player, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, eco).net();
    }

    /** What the payer hands over including tax — toll and auction purchase. */
    public static long total(TaxScope scope, long base) {
        return resolve(scope, base).total();
    }

    public static long total(TaxScope scope, long base, @Nullable UUID player, @Nullable EconomyManager eco) {
        return resolve(scope, base, player, eco).total();
    }

    /**
     * The post-tax unit rate as a {@code double}, for the order-sort/filter comparison only.
     *
     * <p>Kept as {@code base * (1 - rate)} rather than {@code base - tax(base)} so it stays bit-for-bit
     * identical to the pre-refactor expression at {@code OrderFulfillment:315}. The two differ by rounding —
     * for a base of 7 at 10 %, this yields 6.3 where {@code 7 - round(0.7)} yields 6 — and that value is only
     * used to filter and sort listings, never to move money.
     */
    public static double netRate(TaxScope scope, double base) {
        return netRate(base, EconomyConfig.get().taxRate);
    }

    /** Pure core for {@link #netRate(TaxScope, double)}. */
    public static double netRate(double base, double rate) {
        return base * (1.0 - rate);
    }
}

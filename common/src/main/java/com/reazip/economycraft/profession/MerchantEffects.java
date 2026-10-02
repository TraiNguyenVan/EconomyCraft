package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Merchant profession effects: Lưỡi không xương discounts and stick trade validation (spec lines 57–59).
 *
 * <p>All discount calculations route through {@link TaxPolicy} and this resolver, ensuring a single
 * consistent discount mechanism across /ah, /pay, orders, and villager interactions.
 */
public final class MerchantEffects {
    private MerchantEffects() {}

    /**
     * Resolves the Merchant discount rate (0.05 Apprentice, 0.15 Master, halved during rust),
     * or 0.0 for non-merchants.
     */
    public static double discountRate(@Nullable UUID playerId, @Nullable EconomyManager eco) {
        if (playerId == null || eco == null) return 0.0D;
        try {
            if (!EconomyConfig.get().professions.enabled) return 0.0D;

            ProfessionStore store = eco.getProfessions();
            ProfessionId profession = store.professionOf(playerId);
            if (profession != ProfessionId.MERCHANT) return 0.0D;

            ProfessionLevel baseLevel = store.baseLevelOf(playerId);
            if (baseLevel == null) return 0.0D;

            double baseRate = (baseLevel == ProfessionLevel.MASTER)
                    ? EconomyConfig.get().professions.merchant.costFactorMaster
                    : EconomyConfig.get().professions.merchant.costFactorApprentice;

            if (store.levelOf(playerId) == ProfessionLevel.RUSTED) {
                baseRate *= EconomyConfig.get().professions.rustEffectFactor;
            }
            return baseRate;
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    /** Convenience overload resolving the discount rate for an online player. */
    public static double discountRate(@Nullable ServerPlayer player) {
        if (player == null) return 0.0D;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            return discountRate(player.getUUID(), eco);
        } catch (Exception ignored) {
            return 0.0D;
        }
    }

    /**
     * Checks whether a villager trade is a stick trade (spec line 58).
     * Any trade consuming or producing sticks is excluded from Merchant progression.
     */
    public static boolean isStickTrade(@Nullable MerchantOffer offer) {
        if (offer == null) return false;
        if (offer.getCostA().is(Items.STICK)) return true;
        if (offer.getCostB().is(Items.STICK)) return true;
        if (offer.getResult().is(Items.STICK)) return true;
        return false;
    }

    /**
     * Applies the Lưỡi không xương discount to a villager's active offers for the viewing player.
     * Uses specialPriceDiff so the vanilla client displays the discount natively.
     */
    public static void applyVillagerTradeDiscount(@Nullable ServerPlayer player, @Nullable MerchantOffers offers) {
        if (player == null || offers == null) return;
        try {
            for (MerchantOffer offer : offers) {
                int baseCost = offer.getBaseCostA().getCount();
                TaxQuote quote = TaxPolicy.resolve(TaxScope.TRANSACTION_VILLAGER, baseCost, player);
                int discount = (int) quote.discount();
                if (discount > 0) {
                    offer.addToSpecialPriceDiff(-discount);
                }
            }
        } catch (Exception ignored) {
            // Must not disrupt trading screen
        }
    }

    /** Resets any active special price diffs when trading closes. */
    public static void resetVillagerTradeDiscount(@Nullable MerchantOffers offers) {
        if (offers == null) return;
        try {
            for (MerchantOffer offer : offers) {
                offer.resetSpecialPriceDiff();
            }
        } catch (Exception ignored) {
            // Must not disrupt trade closing
        }
    }
}

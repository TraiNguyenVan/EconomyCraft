package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Merchant profession effects: Lưỡi không xương discounts and stick trade validation (spec lines 57–59).
 *
 * <p>Discounts apply exclusively when trading with villagers with a job (just like Hero of the Village effect).
 * Discounts do NOT apply to player-to-player trades (/pay, /ah, orders).
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
     * Checks whether the trader is a normal villager with an active job/profession
     * (excludes unemployed villagers and nitwits).
     */
    public static boolean isVillagerWithJob(@Nullable Merchant trader) {
        if (trader instanceof Villager villager) {
            VillagerData data = villager.getVillagerData();
            net.minecraft.core.Holder<VillagerProfession> profession = data.profession();
            return !profession.is(VillagerProfession.NONE) && !profession.is(VillagerProfession.NITWIT);
        }
        return false;
    }

    /**
     * Calculates the price reduction for a trade offer (just like Hero of the Village).
     * Guarantees at least 1 item reduction for trades costing 2+ items, and never reduces cost below 1.
     */
    public static int calculateOfferDiscount(int baseCost, double discountRate) {
        if (discountRate <= 0.0D || baseCost <= 1) return 0;
        int discount = (int) Math.round(baseCost * discountRate);
        if (discount == 0 && baseCost >= 2) {
            discount = 1;
        }
        return Math.min(discount, baseCost - 1);
    }

    /**
     * Applies the Lưỡi không xương discount to a villager's active offers for the viewing player.
     * Only applies when right-clicking a villager with a job.
     *
     * <p><b>Call this from inside {@code Villager#updateSpecialPrices}, at its RETURN</b> — not from the
     * trading menu. Vanilla recomputes every offer's {@code specialPriceDiff} in that method, and it starts
     * by zeroing the whole field ({@code resetSpecialPrices()}) before re-applying the reputation and
     * Hero of the Village discounts. A discount written from anywhere else is therefore wiped the next
     * time the villager reprices — on restock, on a gossip transfer, on a reputation change, or on a
     * level-up — which is why a menu-open hook makes the buff work for exactly one trade.
     *
     * <p>Because vanilla zeroes first and this only ever <em>adds</em>, the result is additive: reputation,
     * Hero of the Village and Lưỡi không xương all stack instead of overwriting one another, and the
     * {@code Mth.clamp(..., 1, maxStackSize)} in {@code MerchantOffer#getModifiedCostCount} still floors a
     * stacked cost at 1 item.
     */
    public static void applyVillagerTradeDiscount(@Nullable ServerPlayer player, @Nullable Merchant trader) {
        if (player == null || trader == null) return;
        if (!isVillagerWithJob(trader)) return;
        applyVillagerTradeDiscount(player, trader.getOffers());
    }

    /**
     * Applies the Lưỡi không xương discount directly to the offer list.
     *
     * <p>Additive on purpose: whatever discount the villager already granted (reputation, Hero of the
     * Village) stays in {@code specialPriceDiff} and this is subtracted on top of it. Requires the caller
     * to have just reset the field, which {@code updateSpecialPrices} does.
     */
    public static void applyVillagerTradeDiscount(@Nullable ServerPlayer player, @Nullable MerchantOffers offers) {
        if (player == null || offers == null) return;
        try {
            double rate = discountRate(player);
            if (rate <= 0.0D) return;

            for (MerchantOffer offer : offers) {
                int baseCost = offer.getBaseCostA().getCount();
                int discount = calculateOfferDiscount(baseCost, rate);
                if (discount > 0) {
                    offer.addToSpecialPriceDiff(-discount);
                }
            }
        } catch (Exception ignored) {
            // Must not disrupt trading screen
        }
    }

    }

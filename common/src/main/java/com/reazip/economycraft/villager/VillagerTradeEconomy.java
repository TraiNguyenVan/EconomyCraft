package com.reazip.economycraft.villager;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.profession.ProfessionHooks;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * Villager trade economy: thin layer over TaxPolicy + transferMoney with PriceRegistry pricing (spec Phase 7, P7-T5).
 */
public final class VillagerTradeEconomy {
    private VillagerTradeEconomy() {}

    public record TradeResult(boolean success, Component message) {}

    @Nullable
    public static AbstractVillager findTargetVillager(ServerPlayer player) {
        if (player == null) return null;
        Level level = player.level();
        Vec3 eyePos = player.getEyePosition();
        Vec3 viewVec = player.getViewVector(1.0F);
        Vec3 reachVec = eyePos.add(viewVec.scale(5.0D));
        AABB searchBox = player.getBoundingBox().inflate(5.0D);

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player, eyePos, reachVec, searchBox,
                entity -> entity instanceof AbstractVillager && entity.isAlive(), 5.0D
        );
        if (hit != null && hit.getEntity() instanceof AbstractVillager villager) {
            return villager;
        }

        List<AbstractVillager> villagers = level.getEntitiesOfClass(
                AbstractVillager.class, player.getBoundingBox().inflate(4.0D),
                Entity::isAlive
        );
        if (villagers.isEmpty()) return null;
        villagers.sort(Comparator.comparingDouble(player::distanceToSqr));
        return villagers.get(0);
    }

    public static boolean isBuyFromVillager(MerchantOffer offer) {
        if (offer == null) return true;
        if (offer.getCostA().is(Items.EMERALD)) return true;
        if (offer.getResult().is(Items.EMERALD)) return false;
        return true;
    }

    public static long resolveBasePrice(@Nullable PriceRegistry prices, MerchantOffer offer, boolean isBuy) {
        if (offer == null) return 0L;
        long emeraldBuyPrice = EconomyConfig.get().professions.merchant.villagerEmeraldBuyPrice;
        long emeraldSellPrice = EconomyConfig.get().professions.merchant.villagerEmeraldSellPrice;

        if (prices != null) {
            PriceRegistry.PriceEntry emeraldEntry = prices.resolve(new ItemStack(Items.EMERALD));
            if (emeraldEntry != null) {
                if (emeraldEntry.unitBuy() > 0) emeraldBuyPrice = emeraldEntry.unitBuy();
                if (emeraldEntry.unitSell() > 0) emeraldSellPrice = emeraldEntry.unitSell();
            }
        }

        if (isBuy) {
            if (offer.getCostA().is(Items.EMERALD)) {
                return Math.max(1L, offer.getCostA().getCount() * emeraldBuyPrice);
            }
            if (prices != null) {
                PriceRegistry.PriceEntry entry = prices.resolve(offer.getResult());
                if (entry != null && entry.unitBuy() > 0) {
                    return Math.max(1L, entry.unitBuy() * offer.getResult().getCount());
                }
            }
            return Math.max(10L, offer.getCostA().getCount() * emeraldBuyPrice);
        } else {
            if (offer.getResult().is(Items.EMERALD)) {
                return Math.max(1L, offer.getResult().getCount() * emeraldSellPrice);
            }
            if (prices != null) {
                PriceRegistry.PriceEntry entry = prices.resolve(offer.getCostA());
                if (entry != null && entry.unitSell() > 0) {
                    return Math.max(1L, entry.unitSell() * offer.getCostA().getCount());
                }
            }
            return Math.max(5L, offer.getResult().getCount() * emeraldSellPrice);
        }
    }

    public static TradeResult executeTrade(EconomyManager eco, ServerPlayer player, AbstractVillager villager, MerchantOffer offer) {
        if (eco == null || player == null || villager == null || offer == null) {
            return new TradeResult(false, Component.literal("Invalid trade").withStyle(ChatFormatting.RED));
        }
        if (offer.isOutOfStock()) {
            return new TradeResult(false, Component.literal("Offer is out of stock!").withStyle(ChatFormatting.RED));
        }

        boolean isBuy = isBuyFromVillager(offer);
        long basePrice = resolveBasePrice(eco.getPrices(), offer, isBuy);
        TaxQuote quote = TaxPolicy.resolve(TaxScope.TRANSACTION_VILLAGER, basePrice, player.getUUID(), eco);

        if (isBuy) {
            long totalCost = quote.total();
            Long balance = eco.getBalance(player.getUUID(), false);
            if (balance == null || balance < totalCost) {
                return new TradeResult(false, Component.literal("Not enough money! Cost: "
                        + EconomyCraft.formatMoney(totalCost)).withStyle(ChatFormatting.RED));
            }

            BalanceMutationResult debit = eco.removeMoney(player.getUUID(), totalCost, EconomySources.VILLAGER_TRADE, "Villager trade purchase");
            if (!debit.successful()) {
                return new TradeResult(false, Component.literal("Transaction failed").withStyle(ChatFormatting.RED));
            }

            ItemStack resultStack = offer.assemble();
            if (!player.getInventory().add(resultStack)) {
                player.spawnAtLocation(player.level(), resultStack);
            }

            offer.increaseUses();
            if (villager instanceof Villager v) {
                v.setVillagerXp(v.getVillagerXp() + offer.getXp());
            }
            player.level().playSound(null, player.blockPosition(), villager.getNotifyTradeSound(), SoundSource.NEUTRAL, 1.0F, 1.0F);

            ProfessionHooks.onVillagerTrade(player, villager, offer);

            String savedMsg = quote.discounted() ? " (saved " + EconomyCraft.formatMoney(quote.discount()) + ")" : "";
            Component msg = Component.literal("Purchased " + resultStack.getCount() + "x " + resultStack.getHoverName().getString()
                    + " for " + EconomyCraft.formatMoney(totalCost) + savedMsg).withStyle(ChatFormatting.GREEN);
            return new TradeResult(true, msg);
        } else {
            ItemStack required = offer.getCostA();
            int countNeeded = required.getCount();
            int hasCount = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack slot = player.getInventory().getItem(i);
                if (ItemStack.isSameItemSameComponents(slot, required)) {
                    hasCount += slot.getCount();
                }
            }
            if (hasCount < countNeeded) {
                return new TradeResult(false, Component.literal("You need " + countNeeded + "x "
                        + required.getHoverName().getString() + " (you have " + hasCount + ")").withStyle(ChatFormatting.RED));
            }

            int toRemove = countNeeded;
            for (int i = 0; i < player.getInventory().getContainerSize() && toRemove > 0; i++) {
                ItemStack slot = player.getInventory().getItem(i);
                if (ItemStack.isSameItemSameComponents(slot, required)) {
                    int take = Math.min(toRemove, slot.getCount());
                    slot.shrink(take);
                    toRemove -= take;
                }
            }

            long payout = quote.net();
            eco.addMoney(player.getUUID(), payout, EconomySources.VILLAGER_TRADE, "Villager trade sale");

            offer.increaseUses();
            if (villager instanceof Villager v) {
                v.setVillagerXp(v.getVillagerXp() + offer.getXp());
            }
            player.level().playSound(null, player.blockPosition(), villager.getNotifyTradeSound(), SoundSource.NEUTRAL, 1.0F, 1.0F);

            ProfessionHooks.onVillagerTrade(player, villager, offer);

            Component msg = Component.literal("Sold " + countNeeded + "x " + required.getHoverName().getString()
                    + " for " + EconomyCraft.formatMoney(payout)).withStyle(ChatFormatting.GREEN);
            return new TradeResult(true, msg);
        }
    }
}

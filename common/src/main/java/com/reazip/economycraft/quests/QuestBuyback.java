package com.reazip.economycraft.quests;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import com.reazip.economycraft.config.QuestsSection;
import com.reazip.economycraft.util.ExpirationUtil;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/**
 * The Phase 2 buyback market: resells accumulated quest stock to players through ordinary
 * {@code /ah} listings owned by the bot account.
 *
 * <p>Timing, all locked: unlisted stock lists on the next quest sweep (a minute or two after the
 * fill that produced it); a key keeps exactly one open bot listing, and new stock merges into it
 * with the whole stack repriced at the current unit; open listings reprice once a week at the
 * rollover; an expired listing's items flow back into the ledger and relist on the next sweep.
 *
 * <p>Everything here runs on the server thread, called from the quest sweep. The ledger stays the
 * source of truth — a listing is just stock with a price tag — so a crash between deposit and
 * listing loses nothing: the next sweep lists what is still unlisted.
 */
public final class QuestBuyback {
    private static final Logger LOGGER = LogUtils.getLogger();

    private QuestBuyback() {}

    public static boolean isBuybackListing(AuctionListing listing) {
        return listing != null && QuestManager.BOT_UUID.equals(listing.seller);
    }

    /** Lists whatever the ledger holds that has no price tag yet: new keys post, stocked keys top up. */
    public static void sweep(EconomyManager eco) {
        var quests = EconomyConfig.get().quests;
        if (quests == null || !quests.enabled || !quests.buyback.enabled) return;

        boolean changed = false;
        for (var stocked : eco.getQuestStock().snapshot().entrySet()) {
            if (listUnlisted(eco, stocked.getKey(), stocked.getValue(), quests)) changed = true;
        }
        if (changed) eco.getAuctions().save();
    }

    /**
     * Reprices every open bot listing at the current unit. Called from the week rollover only, so
     * prices sit still all week and refresh together with the new board.
     */
    public static void repriceAll(EconomyManager eco) {
        var quests = EconomyConfig.get().quests;
        if (quests == null || !quests.enabled) return;

        boolean changed = false;
        for (AuctionListing listing : eco.getAuctions().getListings()) {
            if (!isBuybackListing(listing) || listing.item == null || listing.item.isEmpty()) continue;
            String key = keyOf(eco, listing.item);
            if (key == null) continue;
            PriceRegistry.PriceEntry entry = eco.getPrices().findByKey(key);
            if (entry == null || entry.customItem() != null) continue;
            long unit = QuestLogic.questUnit(eco.getEffectiveBuyPrice(entry), entry.unitSell(),
                    quests.buyback.priceFactor, quests.sellFallbackMultiplier);
            if (unit <= 0) continue;
            long repriced = listing.item.getCount() * unit;
            if (repriced != listing.price) {
                listing.price = repriced;
                changed = true;
            }
        }
        if (changed) {
            eco.getAuctions().save();
            LOGGER.info("[EconomyCraft] Repriced the open quest buyback listings for the new week.");
        }
    }

    private static boolean listUnlisted(EconomyManager eco, String key, long unlisted,
                                        QuestsSection quests) {
        if (key == null || key.isBlank() || unlisted <= 0) return false;
        PriceRegistry.PriceEntry entry = eco.getPrices().findByKey(key);
        if (entry == null || entry.customItem() != null) {
            LOGGER.warn("[EconomyCraft] {} units of quest stock for '{}' have no catalog entry; leaving them unlisted.",
                    unlisted, key);
            return false;
        }
        ItemStack proto = eco.getPrices().createPrototype(entry);
        if (proto.isEmpty()) {
            LOGGER.warn("[EconomyCraft] Could not build a listing stack for quest stock '{}'; leaving it unlisted.", key);
            return false;
        }
        long unit = QuestLogic.questUnit(eco.getEffectiveBuyPrice(entry), entry.unitSell(),
                quests.buyback.priceFactor, quests.sellFallbackMultiplier);
        if (unit <= 0) {
            LOGGER.warn("[EconomyCraft] Quest stock '{}' has no usable price; leaving it unlisted.", key);
            return false;
        }

        AuctionListing open = findOpen(eco.getAuctions(), eco, key);
        if (open == null) {
            AuctionListing listing = new AuctionListing();
            listing.seller = QuestManager.BOT_UUID;
            listing.item = proto.copyWithCount((int) Math.min(unlisted, Integer.MAX_VALUE));
            listing.price = listing.item.getCount() * unit;
            long now = System.currentTimeMillis();
            listing.createdAt = now;
            listing.expiresAt = ExpirationUtil.expiresAt(now, EconomyConfig.get().auctionExpirationHours);
            eco.getAuctions().addListing(listing);
            eco.getQuestStock().withdraw(key, listing.item.getCount());
            LOGGER.info("[EconomyCraft] Listed quest buyback #{}: {}x {} for {} ({} each).",
                    listing.id, listing.item.getCount(), key, listing.price, unit);
            return true;
        }

        long take = eco.getQuestStock().withdraw(key, unlisted);
        if (take <= 0) return false;
        // Merge: one listing per key, the whole stack repriced at today's unit — a single clean
        // per-unit price rather than old units at old prices beside new units at new ones.
        long merged = (long) open.item.getCount() + take;
        open.item.setCount((int) Math.min(merged, Integer.MAX_VALUE));
        open.price = open.item.getCount() * unit;
        LOGGER.info("[EconomyCraft] Topped up quest buyback #{}: now {}x {} for {} ({} each).",
                open.id, open.item.getCount(), key, open.price, unit);
        return true;
    }

    private static AuctionListing findOpen(AuctionManager auctions, EconomyManager eco, String key) {
        for (AuctionListing listing : auctions.getListings()) {
            if (!isBuybackListing(listing) || listing.item == null || listing.item.isEmpty()) continue;
            if (key.equals(keyOf(eco, listing.item))) return listing;
        }
        return null;
    }

    private static String keyOf(EconomyManager eco, ItemStack stack) {
        PriceRegistry.PriceEntry entry = eco.getPrices().resolve(stack);
        return entry == null ? null : entry.key();
    }
}

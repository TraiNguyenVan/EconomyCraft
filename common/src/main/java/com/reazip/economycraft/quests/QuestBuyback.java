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
 * fill that produced it); a key keeps one open bot listing per stack lot, and new stock tops up an
 * open lot while it has room with the whole lot repriced at the current unit; a fill larger than one
 * lot leaves the remainder in the ledger for the next sweep; open listings reprice once a week at
 * the rollover; an expired listing's items flow back into the ledger and relist on the next sweep.
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
            if (quests.requireShopPrice && eco.getEffectiveBuyPrice(entry) <= 0) continue;
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
        if (quests.requireShopPrice && eco.getEffectiveBuyPrice(entry) <= 0) {
            long dropped = eco.getQuestStock().withdraw(key, unlisted);
            LOGGER.warn("[EconomyCraft] Voided {} units of quest stock for '{}': it has no shop price and require_shop_price is on.",
                    dropped, key);
            return dropped > 0;
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

        // The lot size is the item's own stack size, the same authority the order, shop and auction
        // delivery paths use. A pile bigger than that is not something the game represents, and the
        // catalog's hand-authored `stack` field cannot be trusted for it.
        int lot = Math.max(1, proto.getMaxStackSize());

        AuctionListing open = findOpenWithRoom(eco.getAuctions(), eco, key, lot);
        long withdrawn = eco.getQuestStock().withdraw(key, unlisted);
        if (withdrawn <= 0) return false;
        int take = (int) Math.min(withdrawn, lot);
        if (open != null) {
            // Merge: one lot per key while the lot has room — a single clean per-unit price rather
            // than old units at old prices beside new units at new ones.
            open.item.setCount(open.item.getCount() + take);
            open.price = open.item.getCount() * unit;
            LOGGER.info("[EconomyCraft] Topped up quest buyback #{}: now {}x {} for {} ({} each).",
                    open.id, open.item.getCount(), key, open.price, unit);
            return true;
        }

        AuctionListing listing = new AuctionListing();
        listing.seller = QuestManager.BOT_UUID;
        listing.item = proto.copyWithCount(take);
        listing.price = take * unit;
        long now = System.currentTimeMillis();
        listing.createdAt = now;
        listing.expiresAt = ExpirationUtil.expiresAt(now, EconomyConfig.get().auctionExpirationHours);
        eco.getAuctions().addListing(listing);
        long rest = withdrawn - take;
        // Only one lot posts per sweep; anything over the lot size stays in the ledger for the next
        // sweep rather than piling into an oversized stack. A purchase frees room in the open lot, so
        // the remainder tops that lot up instead of opening a second one.
        eco.getQuestStock().deposit(key, rest);
        LOGGER.info("[EconomyCraft] Listed quest buyback #{}: {}x {} for {} ({} each).{}",
                listing.id, take, key, listing.price, unit,
                rest > 0 ? " " + rest + " more awaiting a free lot." : "");
        return true;
    }

    /**
     * The open bot listing for {@code key} that still has room in its lot, or null when a fresh lot
     * is needed. Room is judged against the item's own stack size, never the catalog's {@code stack}
     * field — that field is hand-authored per entry and disagrees with the game for dozens of items.
     */
    private static AuctionListing findOpenWithRoom(AuctionManager auctions, EconomyManager eco,
                                                   String key, int lot) {
        for (AuctionListing listing : auctions.getListings()) {
            if (!isBuybackListing(listing) || listing.item == null || listing.item.isEmpty()) continue;
            if (!key.equals(keyOf(eco, listing.item))) continue;
            if (QuestLogic.fitsInLot(listing.item.getCount(), lot)) return listing;
        }
        return null;
    }

    private static String keyOf(EconomyManager eco, ItemStack stack) {
        PriceRegistry.PriceEntry entry = eco.getPrices().resolve(stack);
        return entry == null ? null : entry.key();
    }
}

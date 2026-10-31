package com.reazip.economycraft.quests;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.auction.AuctionListing;
import org.slf4j.Logger;

/** Migrates legacy bot-owned AH stock back into the durable quest stock ledger. */
public final class QuestBuyback {
    private static final Logger LOGGER = LogUtils.getLogger();

    private QuestBuyback() {}

    public static boolean isBuybackListing(AuctionListing listing) {
        return listing != null && QuestManager.BOT_UUID.equals(listing.seller);
    }

    /** One atomic removal-and-return migration; subsequent sweeps have no bot listings to process. */
    public static int migrateListingsToStock(EconomyManager eco) {
        if (eco.getAuctions().getListings().stream().noneMatch(QuestBuyback::isBuybackListing)) return 0;
        int[] moved = {0};
        BuybackTransfers.run(eco, () -> {
            for (AuctionListing listing : eco.getAuctions().getListings()) {
                if (!isBuybackListing(listing)) continue;
                AuctionListing removed = eco.getAuctions().removeListing(listing.id);
                if (removed == null || removed.item == null || removed.item.isEmpty()) continue;
                PriceRegistry.PriceEntry entry = eco.getPrices().resolve(removed.item);
                if (entry == null || entry.customItem() != null) {
                    eco.getDeliveries().addDelivery(QuestManager.BOT_UUID, removed.item.copy(), false);
                    LOGGER.warn("[EconomyCraft] Moved legacy quest buyback #{} to the bot mailbox because its item is not in the catalog.",
                            removed.id);
                } else {
                    eco.getQuestStock().deposit(entry.key(), removed.item.getCount(), false);
                }
                moved[0]++;
            }
            if (moved[0] > 0) {
                eco.getAuctions().save();
                eco.getQuestStock().save();
                eco.getDeliveries().save();
            }
        });
        if (moved[0] > 0) {
            LOGGER.info("[EconomyCraft] Returned {} legacy quest buyback listing(s) to quest stock.", moved[0]);
        }
        return moved[0];
    }
}

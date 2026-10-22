package com.reazip.economycraft.quests;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.db.Documents;
import org.slf4j.Logger;

/**
 * Runs one stock-to-listing or listing-to-stock transfer, keeping the durable documents and the live
 * stores in step.
 *
 * <p>The move commits as a single SQLite transaction, so a failure leaves both documents exactly as
 * they were before it. The stores are mutated in memory as the move runs, though, and nothing rewinds
 * them: left alone the live ledger would still show the units gone from the side that wrote first,
 * the live auction map would still hold (or miss) the listing, and because the auction document is
 * only written by {@code AuctionManager.save()} — not by the routine save that writes the ledger — a
 * later save could then persist a transfer the database refused. So a failure reloads the live stores
 * from those rolled-back documents before the exception continues on its way.
 *
 * <p>That includes the stores a transfer changes on its way through: {@code QuestManager} counts an
 * unsold expiry and can quarantine a key, and removing a player listing drops the offers on it. Both
 * write inside the transaction, so both are reloaded too; otherwise the live board would carry an
 * expiry or a missing offer that the database rolled back, and the retry would count or notify twice.
 */
public final class BuybackTransfers {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BuybackTransfers() {}

    public static void run(EconomyManager eco, Runnable transfer) {
        try {
            Documents.atomic(eco.getDatabase(), transfer);
        } catch (RuntimeException failure) {
            try {
                eco.getQuestStock().reload();
                eco.getAuctions().reload();
                eco.getDeliveries().reload();
                eco.getQuests().reload();
                eco.getNegotiations().reload();
            } catch (RuntimeException resync) {
                LOGGER.error("[EconomyCraft] Failed to resync the buyback stores after a rolled-back transfer", resync);
            }
            throw failure;
        }
    }
}

package com.reazip.economycraft.quests;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.db.Documents;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * Runs one quest-stock transfer, keeping the durable documents and live stores in step.
 *
 * <p>The move commits as a single SQLite transaction, so a failure leaves both documents exactly as
 * they were before it. The stores are mutated in memory as the move runs, though, and nothing rewinds
 * them: a later save could persist a stock or listing change that the database refused. So a failure
 * reloads the live stores from those rolled-back documents before the exception continues on its way.
 *
 * <p>That includes quest state and negotiation data, which can change during a legacy listing
 * migration. Reloading them after rollback keeps the live board consistent with the database.
 */
public final class BuybackTransfers {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BuybackTransfers() {}

    public static void run(EconomyManager eco, Runnable transfer) {
        Map<UUID, Long> balanceSnapshot = eco.getBalancesSnapshot();
        Map<String, Long> stockSnapshot = eco.getQuestStock().snapshot();
        try {
            Documents.atomic(eco.getDatabase(), transfer);
        } catch (RuntimeException failure) {
            // A failed persistence read must not leave a staged transfer in the live stock ledger.
            eco.getQuestStock().restoreSnapshot(stockSnapshot);
            try {
                eco.getQuestStock().reload();
                eco.getAuctions().reload();
                eco.getDeliveries().reload();
                eco.getQuests().reload();
                eco.getNegotiations().reload();
            } catch (RuntimeException resync) {
                LOGGER.error("[EconomyCraft] Failed to resync quest stock after a rolled-back transfer", resync);
            }
            if (balanceSnapshot != null) {
                try {
                    eco.restoreBalancesSnapshot(balanceSnapshot);
                } catch (RuntimeException resync) {
                    LOGGER.error("[EconomyCraft] Failed to resync balances after a rolled-back quest stock transfer", resync);
                }
            }
            throw failure;
        }
    }
}

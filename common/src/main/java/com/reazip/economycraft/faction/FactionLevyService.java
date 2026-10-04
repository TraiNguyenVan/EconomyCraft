package com.reazip.economycraft.faction;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.config.FactionsSection;
import com.reazip.economycraft.time.OnlineTimeService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;

/**
 * Communism's two levies, both on the spec's "every 45 minutes <em>online</em>" clock (spec 14, 15-18).
 *
 * <h2>Which clock</h2>
 * {@link OnlineTimeService}, never the wall clock. R10 exists because three clocks meet in this feature — the
 * 30 h lockout is wall clock, these two levies are online time, effect cooldowns are wall clock again — and
 * confusing them is the likeliest bug in the phase. A player who is not online does not accrue a levy.
 *
 * <h2>The order matters</h2>
 * D3 fixes it: the $10 party fee comes off first, and the anti-speculation income tax is then computed on
 * what is left. A player with exactly $10 000 therefore pays the fee, drops to $9 990, and owes no income tax
 * — which is the spec's intent, since the tax is aimed at balances that survived the levy.
 *
 * <h2>What happens when the money is not there</h2>
 * The fee is capped at the balance rather than refused, because a levy that can push a player negative is a
 * money printer in reverse and the spec never asks for debt. Two rules from P9-T4 follow from that and are
 * the reason this class checks every {@code PaymentResult}:
 * <ul>
 *   <li>the 45-minute timer is consumed whatever the outcome, so a broke player is not taxed again 20 ticks
 *       later — that would be the retry loop P9-T4 forbids;</li>
 *   <li>a refused debit is reported as refused and never added to the collected total (R9), so the day's
 *       books match the balances.</li>
 * </ul>
 */
public final class FactionLevyService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FactionLevyService() {}

    /**
     * Applies the party fee and then the income tax to everyone online who has reached the threshold.
     *
     * <p>Called once per server tick with the set of online players. Cheap when idle: the threshold check is
     * a comparison against a counter that only moves for players who are actually online.
     */
    public static void tickLevies(EconomyManager eco, Set<UUID> onlinePlayers) {
        if (eco == null || onlinePlayers == null || onlinePlayers.isEmpty()) return;

        EconomyConfig config = EconomyConfig.get();
        if (!config.factions.enabled) return;

        OnlineTimeService onlineTime = eco.getOnlineTime();
        FactionStore factions = eco.getFactions();
        long thresholdMs = config.factions.levyIntervalMinutes * 60_000L;
        if (thresholdMs <= 0L) return;

        for (UUID player : onlinePlayers) {
            if (factions.factionOf(player) != FactionId.COMMUNISM) continue;
            // Consuming resets the counter, so a player is charged once per interval whether or not the
            // charge succeeds.
            if (!onlineTime.consumeIfThresholdMet(player, thresholdMs)) continue;

            try {
                Result result = applyCommunismLevy(eco, player, config.factions.communism);
                // A levy moves money without a transaction the player initiated, so it is logged. Debug rather
                // than info because it is once per member per 45 minutes, not per tick.
                if (result.chargedAnything() || result.feeRefused()) {
                    LOGGER.debug("[EconomyCraft] Communism levy: fee {}/{}, income tax {}/{}, collected {}",
                            result.partyFeePaid() ? result.partyFeeCharged() : 0L, result.partyFeeCharged(),
                            result.incomeTaxPaid() ? result.incomeTaxCharged() : 0L, result.incomeTaxCharged(),
                            result.collected());
                }
            } catch (Exception e) {
                // One player's levy must not abort the loop: the remaining members are still due, and the
                // caller (tickTagServices) would otherwise skip the profession ticks below on this tick.
                LOGGER.error("[EconomyCraft] Failed to process Communism levy for {}", player, e);
            }
        }
    }

    /**
     * What one levy actually did, so a caller can log it and a test can assert it.
     *
     * @param feeRefused a fee was due but the debit was refused — the fee is capped at the balance, so this
     *                   is not the ordinary "cannot afford it" case and must not be reported as one
     */
    public record Result(long partyFeeCharged, boolean partyFeePaid, long incomeTaxCharged, boolean incomeTaxPaid,
                         boolean feeRefused) {
        public long collected() {
            return (partyFeePaid ? partyFeeCharged : 0L) + (incomeTaxPaid ? incomeTaxCharged : 0L);
        }

        public boolean chargedAnything() {
            return collected() > 0L;
        }
    }

    /**
     * Charges one Communism member: the party fee, then the tiered income tax on the remainder.
     *
     * @return what was charged, whether each debit was accepted, and whether the player could not afford it
     */
    public static Result applyCommunismLevy(EconomyManager eco, UUID player, FactionsSection.CommunismSettings settings) {
        long balance = eco.getBalance(player, false);

        // 1. Party fee: burned, no recipient. Capped at the balance so it can never go negative.
        // A burn is a removal, not a transfer: transferMoney always has a receiver (its engine and its
        // result both reject null), so a null-receiver transfer throws instead of debiting.
        long fee = FactionFiscalPolicy.partyFeeCharge(balance, settings.partyFee);
        boolean feePaid = false;
        if (fee > 0L) {
            feePaid = eco.removeMoney(player, fee, EconomySources.PARTY_FEE, "Party fee").successful();
        }

        // 2. Anti-speculation income tax on whatever the fee left behind (D3). If the fee was refused the
        //    balance is unchanged, and taxing the unchanged balance is the correct reading of "the money you
        //    have now" — not an error to retry.
        long remaining = eco.getBalance(player, false);
        long incomeTax = FactionFiscalPolicy.incomeTaxAmount(
                remaining,
                settings.incomeTaxTier1Threshold, settings.incomeTaxTier1Rate,
                settings.incomeTaxTier2Threshold, settings.incomeTaxTier2Rate,
                settings.incomeTaxTier3Threshold, settings.incomeTaxTier3Rate
        );
        boolean taxPaid = false;
        if (incomeTax > 0L) {
            taxPaid = eco.removeMoney(player, incomeTax, EconomySources.INCOME_TAX,
                    "Anti-speculation income tax").successful();
        }

        notify(eco, player, fee, feePaid, incomeTax, taxPaid);
        return new Result(fee, feePaid, incomeTax, taxPaid, !feePaid && fee > 0L);
    }

    /**
     * One message for both amounts, as P9-T5 requires — a player who is charged twice on the same tick
     * should not have to work out which number is which.
     */
    private static void notify(EconomyManager eco, UUID player, long fee, boolean feePaid,
                               long incomeTax, boolean taxPaid) {
        ServerPlayer online = online(eco, player);
        if (online == null) return;

        if (fee <= 0L && incomeTax <= 0L) return;

        MutableComponent message = Component.literal("[Đảng Cộng sản] ").withStyle(ChatFormatting.RED)
                .append(Component.literal("Đảng phí: ").withStyle(ChatFormatting.GRAY))
                .append(money(feePaid ? -fee : 0L));
        if (incomeTax > 0L) {
            message = message.append(Component.literal(" | Thuế thu nhập: ").withStyle(ChatFormatting.GRAY))
                    .append(money(taxPaid ? -incomeTax : 0L));
        }
        online.sendSystemMessage(message);

        if (fee > 0L && !feePaid) {
            // Not "insufficient funds": the fee was already capped at the balance, so a refusal means the
            // transfer itself was rejected and saying otherwise would send the player hunting for money
            // they already do not have.
            online.sendSystemMessage(Component.literal(
                    "Không thể thu Đảng phí lần này — khoản này đã được bỏ qua.").withStyle(ChatFormatting.GRAY));
        }
    }

    private static MutableComponent money(long signed) {
        return Component.literal((signed < 0 ? "-$" : "$") + EconomyCraft.formatMoney(Math.abs(signed)))
                .withStyle(signed < 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    @Nullable
    private static ServerPlayer online(EconomyManager eco, UUID player) {
        try {
            return eco.getServer().getPlayerList().getPlayer(player);
        } catch (Exception ex) {
            // No server (tests, or a manager torn down mid-tick): the levy still applies, it just cannot talk.
            return null;
        }
    }
}

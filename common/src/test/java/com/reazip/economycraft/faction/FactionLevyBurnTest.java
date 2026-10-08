package com.reazip.economycraft.faction;

import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.BalanceMutationStatus;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.config.FactionsSection;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A party levy burn is a removal, not a transfer.
 *
 * <p>{@code BalanceMutationEngine.transfer} and {@code PaymentResult} both reject a {@code null}
 * receiver, so debiting via {@code transferMoney(player, null, …)} throws {@code NullPointerException}
 * instead of collecting anything (live crash: "Failed to process faction levies", NPE
 * {@code receiverId} via {@code applyCommunismLevy:114}). The levy must debit through
 * {@code removeMoney}, the same burn path the wealth-tax pass, shop purchases and order escrow use.
 *
 * <p>The mocked manager reproduces the real engine's contract — a null-receiver transfer throws —
 * so a regression to the old call shape goes red here instead of in production.
 */
class FactionLevyBurnTest {

    private final UUID player = UUID.randomUUID();
    private final FactionsSection.CommunismSettings settings = new FactionsSection.CommunismSettings();

    /** A manager whose transfer path enforces the real engine's null-receiver rejection. */
    private EconomyManager nullRejectingManager(long balanceBeforeFee, long balanceAfterFee) {
        EconomyManager eco = mock(EconomyManager.class);
        when(eco.getBalance(player, false)).thenReturn(balanceBeforeFee, balanceAfterFee);
        when(eco.removeMoney(eq(player), anyLong(), any(MutationSource.class), anyString()))
                .thenAnswer(call -> {
                    long amount = call.getArgument(1);
                    MutationSource source = call.getArgument(2);
                    return new BalanceMutationResult(BalanceMutationStatus.SUCCESS, BalanceMutationType.REMOVE,
                            player, amount, balanceBeforeFee, balanceBeforeFee - amount, Optional.of(source));
                });
        when(eco.transferMoney(any(), isNull(), anyLong(), anyLong(),
                any(MutationSource.class), anyString()))
                .thenThrow(new NullPointerException("receiverId"));
        return eco;
    }

    @Test
    void moneyFormattingUsesOneCurrencyMarkerAndPreservesGrouping() {
        assertEquals("-$10", FactionLevyService.formatMoney(-10L));
        assertEquals("$10", FactionLevyService.formatMoney(10L));
        assertEquals("-$1.234", FactionLevyService.formatMoney(-1_234L));
    }

    @Test
    void levyBurnsFeeThenTaxThroughRemovals() {
        // 30 000: $10 fee leaves 29 990, tier 3 at 0.625 % is 187 (D3 ordering).
        EconomyManager eco = nullRejectingManager(30_000L, 29_990L);

        FactionLevyService.Result result = FactionLevyService.applyCommunismLevy(eco, player, settings);

        assertEquals(10L, result.partyFeeCharged());
        assertTrue(result.partyFeePaid());
        assertEquals(187L, result.incomeTaxCharged());
        assertTrue(result.incomeTaxPaid());
        verify(eco).removeMoney(player, 10L, EconomySources.PARTY_FEE, "Party fee");
        verify(eco).removeMoney(player, 187L, EconomySources.INCOME_TAX, "Anti-speculation income tax");
        verify(eco, never()).transferMoney(any(), any(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void brokePlayerPaysWhatTheyHaveWithoutIncomeTax() {
        EconomyManager eco = nullRejectingManager(3L, 0L);

        FactionLevyService.Result result = FactionLevyService.applyCommunismLevy(eco, player, settings);

        assertEquals(3L, result.partyFeeCharged());
        assertTrue(result.partyFeePaid());
        assertEquals(0L, result.incomeTaxCharged());
        verify(eco, never()).transferMoney(any(), any(), anyLong(), anyLong(), any(), anyString());
    }

    @Test
    void emptyBalanceChargesNothing() {
        EconomyManager eco = nullRejectingManager(0L, 0L);

        FactionLevyService.Result result = FactionLevyService.applyCommunismLevy(eco, player, settings);

        assertEquals(0L, result.collected());
        assertFalse(result.feeRefused());
        verify(eco, never()).removeMoney(any(), anyLong(), any(), anyString());
        verify(eco, never()).transferMoney(any(), any(), anyLong(), anyLong(), any(), anyString());
    }
}

package com.reazip.economycraft.tax;

import com.reazip.economycraft.EconomyConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity coverage for the Phase 1 tax centralisation.
 *
 * <p>Phase 1 promises <strong>zero behaviour change</strong>. Every other test in this file asserts that the
 * new resolver returns exactly what the old inline expression returned — if one of these fails, the refactor
 * moved money and must not ship.
 */
class TaxPolicyTest {

    private static final long[] BASES = {0L, 1L, 2L, 5L, 9L, 10L, 99L, 100L, 12345L, 999_999_999_999L};
    private static final double[] RATES = {0.0, 0.05, 0.1, 0.25, 0.333, 0.5, 1.0};

    // --- parity with the pre-refactor formula ---

    @Test
    void matchesOldFormulaAcrossSpreadOfBases() {
        for (long base : BASES) {
            long expected = Math.round(base * 0.1);
            assertEquals(expected, TaxPolicy.quote(TaxScope.TOLL, base, 0.1).amount(),
                    "base=" + base);
        }
    }

    @Test
    void matchesOldFormulaAcrossRates() {
        for (double rate : RATES) {
            for (long base : BASES) {
                assertEquals(Math.round(base * rate), TaxPolicy.quote(TaxScope.TOLL, base, rate).amount(),
                        "base=" + base + " rate=" + rate);
            }
        }
    }

    @Test
    void roundsHalfUpExactlyLikeMathRound() {
        // 0.5 rounds up, 1.5 rounds up, -0.5 rounds toward zero — Math.round semantics, preserved.
        assertEquals(1L, TaxPolicy.quote(TaxScope.TOLL, 5L, 0.1).amount());
        assertEquals(2L, TaxPolicy.quote(TaxScope.TOLL, 15L, 0.1).amount());
        assertEquals(0L, TaxPolicy.quote(TaxScope.TOLL, 4L, 0.1).amount());
    }

    @Test
    void zeroBaseProducesZeroTax() {
        TaxQuote quote = TaxPolicy.quote(TaxScope.TOLL, 0L, 0.1);
        assertEquals(0L, quote.amount());
        assertFalse(quote.taxed());
    }

    @Test
    void negativeBaseMatchesOldFormulaRatherThanBeingRejected() {
        // Phase 1 is a pure refactor, so this must NOT start returning 0. Every real call site validates its
        // base as positive first (e.g. TollManager checks fee > 0), so this only pins the math.
        for (long base : new long[]{-1L, -7L, -12345L}) {
            assertEquals(Math.round(base * 0.1), TaxPolicy.quote(TaxScope.TOLL, base, 0.1).amount(),
                    "base=" + base);
        }
    }

    @Test
    void longMaxValueTaxAmountDoesNotOverflow() {
        long base = Long.MAX_VALUE;
        assertEquals(Math.round(base * 0.1), TaxPolicy.quote(TaxScope.TOLL, base, 0.1).amount());
        // At rate 1.0 the whole base is tax; Math.round saturates at Long.MAX_VALUE and we must match it.
        assertEquals(Long.MAX_VALUE, TaxPolicy.quote(TaxScope.TOLL, base, 1.0).amount());
        // net() of a fully-taxed base is zero, not negative.
        assertEquals(0L, TaxPolicy.quote(TaxScope.TOLL, base, 1.0).net());
    }

    // --- derived amounts ---

    @Test
    void netAndTotalAreConsistentWithAmount() {
        for (long base : BASES) {
            TaxQuote quote = TaxPolicy.quote(TaxScope.TRANSACTION_ORDER, base, 0.1);
            if (base <= Long.MAX_VALUE - quote.amount()) {
                assertEquals(base + quote.amount(), quote.total());
            }
            assertEquals(base - quote.amount(), quote.net());
            assertEquals(quote.net() + quote.amount(), quote.base());
        }
    }

    @Test
    void netRateMatchesOldExpression() {
        for (double rate : RATES) {
            for (long base : BASES) {
                double unit = base / 3.0;
                assertEquals(unit * (1.0 - rate), TaxPolicy.netRate(unit, rate), 0.0,
                        "base=" + base + " rate=" + rate);
            }
        }
    }

    @Test
    void taxedIsTrueOnlyWhenTaxIsNonZero() {
        assertTrue(TaxPolicy.quote(TaxScope.TOLL, 100L, 0.1).taxed());
        assertFalse(TaxPolicy.quote(TaxScope.TOLL, 100L, 0.0).taxed());
        assertFalse(TaxPolicy.quote(TaxScope.TOLL, 4L, 0.1).taxed());
    }

    @Test
    void quoteCarriesTheScopeMutationSource() {
        for (TaxScope scope : TaxScope.values()) {
            assertEquals(scope.source(), TaxPolicy.quote(scope, 100L, 0.1).source());
        }
    }

    // --- production entry point reads config ---

    @Test
    void resolveReadsTheConfiguredRate() {
        double original = EconomyConfig.get().taxRate;
        try {
            EconomyConfig.get().taxRate = 0.25;
            assertEquals(Math.round(100L * 0.25), TaxPolicy.resolve(TaxScope.TOLL, 100L).amount());
            EconomyConfig.get().taxRate = 0.0;
            assertEquals(0L, TaxPolicy.resolve(TaxScope.TOLL, 100L).amount());
        } finally {
            EconomyConfig.get().taxRate = original;
        }
    }

    // --- P1-T6: parity proof for the three documented smoke-test flows ---

    @Test
    void parityProofForTollAuctionAndOrderFlows() {
        double rate = 0.1;

        // Toll: visitor pays fee + tax, owner receives fee. Old: Math.round(fee * taxRate).
        long fee = 250L;
        long oldTollTax = Math.round(fee * rate);
        long newTollTax = TaxPolicy.quote(TaxScope.TOLL, fee, rate).amount();
        assertEquals(oldTollTax, newTollTax);
        assertEquals(fee + oldTollTax, TaxPolicy.quote(TaxScope.TOLL, fee, rate).total());

        // /ah purchase: buyer pays price + tax, seller receives price.
        long price = 1_750L;
        long oldAuctionTax = Math.round(price * rate);
        long newAuctionTax = TaxPolicy.quote(TaxScope.TRANSACTION_AUCTION_BUY, price, rate).amount();
        assertEquals(oldAuctionTax, newAuctionTax);
        assertEquals(price + oldAuctionTax, TaxPolicy.quote(TaxScope.TRANSACTION_AUCTION_BUY, price, rate).total());

        // Order fulfilment: requester's payment nets the fulfiller payment - tax.
        long payment = 3_333L;
        long oldOrderTax = Math.round(payment * rate);
        long newOrderTax = TaxPolicy.quote(TaxScope.TRANSACTION_ORDER, payment, rate).amount();
        assertEquals(oldOrderTax, newOrderTax);
        assertEquals(payment - oldOrderTax, TaxPolicy.quote(TaxScope.TRANSACTION_ORDER, payment, rate).net());
    }

    // --- the structural invariant ---

    /**
     * The whole point of Phase 1: no code outside the tax package may multiply by the raw configured rate.
     * Otherwise a phase 9 faction rule lands in one place and misses the other seventeen.
     */
    @Test
    void noTaxRateMultiplicationSurvivesOutsideTheTaxPackage() throws IOException {
        Path sourceRoot = findMainSourceRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (isAllowedToMentionTaxRate(file, sourceRoot)) continue;
                String text = Files.readString(file);
                if (text.contains("taxRate")) {
                    offenders.add(sourceRoot.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(List.of(), offenders,
                "taxRate must only be read by TaxPolicy and edited by AdminSettingsUi/EconomyConfig");
    }

    private static boolean isAllowedToMentionTaxRate(Path file, Path sourceRoot) {
        String relative = sourceRoot.relativize(file).toString().replace('\\', '/');
        return relative.startsWith("com/reazip/economycraft/tax/")
                || relative.endsWith("EconomyConfig.java")
                || relative.endsWith("AdminSettingsUi.java");
    }

    private static Path findMainSourceRoot() {
        Set<Path> candidates = Set.of(
                Path.of("src/main/java"),
                Path.of("common/src/main/java"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate.resolve("com/reazip/economycraft/tax"))) return candidate;
        }
        throw new IllegalStateException("Could not locate src/main/java from " + Path.of("").toAbsolutePath());
    }
}

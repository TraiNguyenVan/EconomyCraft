package com.reazip.economycraft.faction;

import com.reazip.economycraft.config.FactionsSection;
import com.reazip.economycraft.tax.FactionTaxRules;
import com.reazip.economycraft.tax.TaxExemption;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9 (Party / Factions) tax rules.
 *
 * <p>Every rule here is exercised through {@link TaxPolicy#evaluate} with an explicit {@link TaxExemption}
 * built over a real {@link FactionStore} and a fixed source of randomness, so a 50 % rule is asserted
 * rather than sampled.
 */
class FactionRulesTest {

    @TempDir
    Path tempDir;

    private FactionsSection factionsConfig;
    private FactionStore factions;

    @BeforeEach
    void setUp() {
        factionsConfig = new FactionsSection();
        factionsConfig.enabled = true;
        factions = new FactionStore(tempDir.resolve("parties.json"));
    }

    /**
     * The stores write through {@link com.reazip.economycraft.util.AsyncFileWriter}, so a queued write can
     * still be creating a file when JUnit tries to delete {@code @TempDir}. Drain the writer first.
     */
    @org.junit.jupiter.api.AfterEach
    void drainWrites() {
        com.reazip.economycraft.util.AsyncFileWriter.flush();
    }

    private UUID memberOf(FactionId faction) {
        UUID id = UUID.randomUUID();
        factions.select(id, faction);
        return id;
    }

    /** Rules whose dice always land below a chance, so every probabilistic rule fires. */
    private TaxExemption alwaysRoll() {
        return FactionTaxRules.of(factionsConfig, factions, () -> 0.0);
    }

    /** Rules whose dice never land below a chance, so every probabilistic rule fails. */
    private TaxExemption neverRoll() {
        return FactionTaxRules.of(factionsConfig, factions, () -> 0.999);
    }

    private TaxQuote quote(TaxScope scope, long base, double rate, UUID payer, UUID counterparty, TaxExemption ex) {
        return TaxPolicy.evaluate(scope, base, rate, 0.0, payer, counterparty, ex);
    }

    // --- P9-T12: Anarchism "Tự do" (spec 34) ---

    @Test
    void anarchismIsTaxExemptAcrossAllScopes() {
        long base = 1000L;
        double taxRate = 0.10;
        UUID anarchist = memberOf(FactionId.ANARCHISM);

        for (TaxScope scope : TaxScope.values()) {
            TaxQuote quoted = quote(scope, base, taxRate, anarchist, null, alwaysRoll());

            assertTrue(quoted.exempt(), "Anarchism must be tax-exempt on " + scope);
            assertEquals(0L, quoted.amount(), "Anarchism pays 0 tax on " + scope);
            assertEquals(base, quoted.base(), "Base remains untouched on " + scope);
        }
    }

    @Test
    void anarchismStillPaysTheTollFeeAndTheItemPrice() {
        UUID anarchist = memberOf(FactionId.ANARCHISM);
        TaxExemption ex = alwaysRoll();

        TaxQuote toll = quote(TaxScope.TOLL, 500L, 0.10, anarchist, null, ex);
        assertEquals(0L, toll.amount(), "no tax");
        assertEquals(500L, toll.total(), "the toll fee itself is still owed in full");
        assertEquals(500L, toll.net(), "the toll owner still receives the fee");

        TaxQuote buy = quote(TaxScope.TRANSACTION_AUCTION_BUY, 5000L, 0.10, anarchist, null, ex);
        assertEquals(0L, buy.amount());
        assertEquals(5000L, buy.total(), "the listing price is still owed in full");
    }

    @Test
    void anarchismIsNotTheSameAsHavingNoRecord() {
        // The store answers Anarchism for a player who never chose, but it keeps saying "no choice" so a party
        // added later cannot inherit them (FactionStore's rule 1). The exemption above therefore keys off the
        // record, not off the id — see aPlayerWhoHasNotChosenIsNotAnAnarchist.
        UUID neverChose = UUID.randomUUID();
        assertEquals(FactionId.ANARCHISM, factions.factionOf(neverChose));
        assertFalse(factions.hasChosen(neverChose));
        assertFalse(factions.selectionOf(neverChose) != null);

        UUID chose = memberOf(FactionId.ANARCHISM);
        assertTrue(factions.hasChosen(chose));
        assertNotNull(factions.selectionOf(chose));
    }

    @Test
    void aPlayerWhoHasNotChosenIsNotAnAnarchist() {
        // The live trap this guards: `factionOf` says Anarchism for an undecided player, so a rule written
        // against the id alone hands the largest exemption in the mod to every player who has never opened the
        // party menu — which, on a server where parties are opt-in, is nearly all of them.
        UUID neverChose = UUID.randomUUID();
        assertEquals(FactionId.ANARCHISM, factions.factionOf(neverChose), "precondition: the id really is Anarchism");

        for (TaxScope scope : TaxScope.values()) {
            TaxQuote quoted = quote(scope, 1000L, 0.10, neverChose, null, alwaysRoll());
            assertFalse(quoted.exempt(), "no choice must not be an exemption on " + scope);
            assertEquals(100L, quoted.amount(), "full tax on " + scope);
            assertEquals(1100L, quoted.total(), "the base is still added on " + scope);
        }
    }

    @Test
    void aPlayerWhoHasNotChosenGetsNoOtherPartysDiscountEither() {
        // Same reasoning for the rules that key on Communism, Capitalism and Monarchy: none of them apply to a
        // player who has joined nothing, so there is nothing to inherit by default.
        UUID neverChose = UUID.randomUUID();

        assertEquals(1.0, alwaysRoll().rateMultiplier(TaxScope.TOLL, neverChose),
                "no Capitalism toll multiplier without a membership");
        TaxQuote quoted = quote(TaxScope.TRANSACTION_SHOP, 1000L, 0.10, neverChose, null, alwaysRoll());
        assertEquals(0L, alwaysRoll().surcharge(TaxScope.TRANSACTION_SHOP, neverChose, quoted),
                "no Monarchy import surcharge without a membership");
        assertFalse(alwaysRoll().exempts(TaxScope.TOLL, neverChose, neverChose),
                "the seller rule must not fire for two undecided players either");
    }

    // --- P9-T6: Communism "Đầu tư công" (spec 12) ---

    @Test
    void communismTollTaxWaiverRolls() {
        long fee = 200L;
        double taxRate = 0.10;
        UUID member = memberOf(FactionId.COMMUNISM);

        TaxQuote waived = quote(TaxScope.TOLL, fee, taxRate, member, null, alwaysRoll());
        assertTrue(waived.exempt());
        assertEquals(0L, waived.amount());
        assertEquals(fee, waived.total(), "the owner still receives the fee");
        assertEquals(fee, waived.net(), "spec 12: the toll owner still gets paid");

        TaxQuote charged = quote(TaxScope.TOLL, fee, taxRate, member, null, neverRoll());
        assertFalse(charged.exempt());
        assertEquals(20L, charged.amount());
        assertEquals(220L, charged.total());
    }

    @Test
    void communismWaiverAppliesToTollsOnly() {
        UUID member = memberOf(FactionId.COMMUNISM);
        double taxRate = 0.10;

        TaxQuote buy = quote(TaxScope.TRANSACTION_AUCTION_BUY, 1000L, taxRate, member, null, alwaysRoll());
        assertEquals(100L, buy.amount(), "the waiver is scoped to the toll tax, not to every purchase");
    }

    @Test
    void zeroChanceCommunismWaiverNeverFires() {
        factionsConfig.communism.tollTaxExemptChance = 0.0;
        UUID member = memberOf(FactionId.COMMUNISM);
        TaxQuote quoted = quote(TaxScope.TOLL, 1000L, 0.10, member, null, alwaysRoll());
        assertEquals(100L, quoted.amount());
        assertFalse(quoted.exempt());
    }

    // --- P9-T7: Capitalism "Thị trường cạnh tranh" (spec 21, D8) ---

    @Test
    void capitalismSellerGrantsTaxExemptionOnAuctionBuy() {
        long price = 5000L;
        double taxRate = 0.10;
        UUID seller = memberOf(FactionId.CAPITALISM);
        UUID buyer = memberOf(FactionId.COMMUNISM);

        TaxQuote quoted = quote(TaxScope.TRANSACTION_AUCTION_BUY, price, taxRate, buyer, seller, neverRoll());
        assertTrue(quoted.exempt());
        assertEquals(0L, quoted.amount());
        assertEquals(price, quoted.total(), "the buyer pays exactly the listing price");
        assertEquals(price, quoted.net(), "the seller still receives the full price");

        UUID otherSeller = memberOf(FactionId.MONARCHY);
        TaxQuote taxed = quote(TaxScope.TRANSACTION_AUCTION_BUY, price, taxRate, buyer, otherSeller, neverRoll());
        assertFalse(taxed.exempt());
        assertEquals(500L, taxed.amount());
        assertEquals(5500L, taxed.total());
    }

    @Test
    void capitalismSellerExemptionDoesNotLeakIntoOtherScopes() {
        double taxRate = 0.10;
        UUID seller = memberOf(FactionId.CAPITALISM);
        UUID buyer = memberOf(FactionId.CAPITALISM);

        for (TaxScope scope : new TaxScope[]{TaxScope.TOLL, TaxScope.TRANSACTION_ORDER, TaxScope.TRANSACTION_PAY}) {
            TaxQuote quoted = quote(scope, 1000L, taxRate, buyer, seller, neverRoll());
            assertFalse(quoted.exempt(), "D8 is an auction-buy rule only, but " + scope + " was exempt");
            assertTrue(quoted.amount() > 0, scope + " should still be taxed");
        }
    }

    @Test
    void exemptingASellerDoesNotExemptTheBuyersOwnTaxes() {
        // D8 explicitly: do not exempt on the buyer's faction. A Monarchy buyer pays the import tax and the
        // base tax even when the seller is a Capitalism member.
        factionsConfig.monarchy.importTaxChance = 1.0;
        UUID seller = memberOf(FactionId.CAPITALISM);
        UUID buyer = memberOf(FactionId.MONARCHY);

        TaxQuote quoted = quote(TaxScope.TRANSACTION_AUCTION_BUY, 1000L, 0.10, buyer, seller, alwaysRoll());
        assertTrue(quoted.exempt(), "the seller-side rule wins, so there is no base tax to surcharge");
        assertEquals(0L, quoted.amount());
    }

    // --- P9-T8: Capitalism "Nhà nước tư bản" toll tax +25 % (spec 23) ---

    @Test
    void capitalismPayerPaysExtra25PercentTollTax() {
        UUID capitalist = memberOf(FactionId.CAPITALISM);
        // 10 % x 1.25 = 12.5 %
        TaxQuote quoted = quote(TaxScope.TOLL, 1000L, 0.10, capitalist, null, neverRoll());
        assertEquals(125L, quoted.amount());
        assertEquals(1125L, quoted.total());
    }

    @Test
    void tollMultiplierIsAMultiplierNotPercentagePoints() {
        // The Assumption recorded for P9-T8: 1.25x on the tax, not +25 points. At a 4 % base the two
        // readings differ (5 % vs 29 %), so this pins which one shipped.
        factionsConfig.capitalism.tollTaxMultiplier = 1.25;
        UUID capitalist = memberOf(FactionId.CAPITALISM);
        TaxQuote quoted = quote(TaxScope.TOLL, 1000L, 0.04, capitalist, null, neverRoll());
        assertEquals(50L, quoted.amount());
    }

    @Test
    void tollMultiplierAppliesToTollsOnly() {
        UUID capitalist = memberOf(FactionId.CAPITALISM);
        TaxQuote quoted = quote(TaxScope.TRANSACTION_AUCTION_BUY, 1000L, 0.10, capitalist, null, neverRoll());
        assertEquals(100L, quoted.amount(), "spec 23 raises the toll tax, not every tax");
    }

    // --- P9-T9: Monarchy "Nhập khẩu" (spec 31, D7) ---

    @Test
    void monarchyImportTaxExtraFiftyPercent() {
        long base = 1000L;
        double taxRate = 0.10;
        UUID monarch = memberOf(FactionId.MONARCHY);

        // 100 tax + 50 % of 100 = 150
        TaxQuote taxed = quote(TaxScope.TRANSACTION_AUCTION_BUY, base, taxRate, monarch, null, alwaysRoll());
        assertEquals(150L, taxed.amount());
        assertEquals(1150L, taxed.total());
        assertFalse(taxed.exempt());

        TaxQuote notRolled = quote(TaxScope.TRANSACTION_AUCTION_BUY, base, taxRate, monarch, null, neverRoll());
        assertEquals(100L, notRolled.amount());
        assertEquals(1100L, notRolled.total());
    }

    @Test
    void monarchyImportTaxRoundsFromTheAlreadyRoundedTax() {
        // 0.5 % of 10 001 rounds to 50, so the surcharge is round(50 * 0.5) = 25 — never round(50.005 * 0.5).
        factionsConfig.monarchy.importTaxChance = 1.0;
        UUID monarch = memberOf(FactionId.MONARCHY);
        long base = 10_001L;
        TaxQuote quoted = quote(TaxScope.TRANSACTION_ORDER, base, 0.005, monarch, null, alwaysRoll());
        assertEquals(50L + 25L, quoted.amount());
    }

    @Test
    void monarchyImportTaxOnlyOnD7ImportScopes() {
        double taxRate = 0.10;
        UUID monarch = memberOf(FactionId.MONARCHY);
        TaxExemption ex = alwaysRoll();

        // D7: shop buy, /ah buy and order fulfilment are imports.
        assertEquals(150L, quote(TaxScope.TRANSACTION_SHOP, 1000L, taxRate, monarch, null, ex).amount());
        assertEquals(150L, quote(TaxScope.TRANSACTION_AUCTION_BUY, 1000L, taxRate, monarch, null, ex).amount());
        assertEquals(150L, quote(TaxScope.TRANSACTION_ORDER, 1000L, taxRate, monarch, null, ex).amount());

        // Everything else is not an import: a toll is not goods, payment is not goods, and a villager trade
        // is the one D7 left open (see TODO D7).
        assertEquals(100L, quote(TaxScope.TOLL, 1000L, taxRate, monarch, null, ex).amount());
        assertEquals(100L, quote(TaxScope.TRANSACTION_PAY, 1000L, taxRate, monarch, null, ex).amount());
        assertEquals(100L, quote(TaxScope.TRANSACTION_VILLAGER, 1000L, taxRate, monarch, null, ex).amount());
    }

    @Test
    void importSurchargeNeverAppliesToAZeroTax() {
        factionsConfig.monarchy.importTaxChance = 1.0;
        UUID monarch = memberOf(FactionId.MONARCHY);
        TaxQuote quoted = quote(TaxScope.TRANSACTION_ORDER, 1000L, 0.0, monarch, null, alwaysRoll());
        assertEquals(0L, quoted.amount(), "50 % of nothing is nothing");
    }

    // --- The feature switch ---

    @Test
    void disablingFactionsRestoresTheBareRate() {
        factionsConfig.enabled = false;
        UUID anarchist = memberOf(FactionId.ANARCHISM);
        UUID seller = memberOf(FactionId.CAPITALISM);
        UUID buyer = memberOf(FactionId.CAPITALISM);
        TaxExemption ex = alwaysRoll();

        assertEquals(100L, quote(TaxScope.TOLL, 1000L, 0.10, anarchist, null, ex).amount());
        assertEquals(100L, quote(TaxScope.TRANSACTION_AUCTION_BUY, 1000L, 0.10, buyer, seller, ex).amount());
        assertEquals(100L, quote(TaxScope.TRANSACTION_AUCTION_BUY, 1000L, 0.10, buyer, null, ex).amount());
    }

    @Test
    void theNoneExemptionIsInert() {
        UUID payer = memberOf(FactionId.ANARCHISM);
        TaxQuote quoted = TaxPolicy.evaluate(TaxScope.TOLL, 1000L, 0.10, 0.0, payer, null, TaxExemption.NONE);
        assertEquals(100L, quoted.amount());
        assertFalse(quoted.exempt());
    }
}

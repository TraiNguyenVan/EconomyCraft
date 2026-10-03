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
        // A player who never chose is treated as Anarchism for tax purposes, but the store keeps saying
        // "no choice", so a party added later cannot inherit them (FactionStore's rule 1).
        UUID neverChose = UUID.randomUUID();
        assertEquals(FactionId.ANARCHISM, factions.factionOf(neverChose));
        assertFalse(factions.hasChosen(neverChose));
        assertFalse(factions.selectionOf(neverChose) != null);

        UUID chose = memberOf(FactionId.ANARCHISM);
        assertTrue(factions.hasChosen(chose));
        assertNotNull(factions.selectionOf(chose));
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

    // --- P9-T14: the three inputs, in the order D10 fixes (buff, own choice, server default) ---

    @Test
    void effectiveModePrefersTheBuffOverEverythingElse() {
        assertEquals(ContainerLockMode.PARTY_ONLY, ContainerLockPolicy.effectiveMode(
                ContainerLockMode.PARTY_ONLY, ContainerLockMode.PRIVATE, ContainerLockMode.UNLOCKED),
                "the buff outranks an explicit choice, which is what makes it a buff");
        assertEquals(ContainerLockMode.PARTY_ONLY, ContainerLockPolicy.effectiveMode(
                ContainerLockMode.PARTY_ONLY, null, ContainerLockMode.UNLOCKED));
        assertEquals(ContainerLockMode.PARTY_ONLY, ContainerLockPolicy.effectiveMode(
                ContainerLockMode.PARTY_ONLY, ContainerLockMode.UNLOCKED, ContainerLockMode.PRIVATE),
                "a Communism member cannot opt out of the buff from inside the game");
    }

    @Test
    void effectiveModeFallsBackToTheOwnChoiceThenTheServerDefault() {
        assertEquals(ContainerLockMode.PRIVATE, ContainerLockPolicy.effectiveMode(
                ContainerLockMode.UNLOCKED, ContainerLockMode.PRIVATE, ContainerLockMode.PARTY_ONLY));
        assertEquals(ContainerLockMode.UNLOCKED, ContainerLockPolicy.effectiveMode(
                ContainerLockMode.UNLOCKED, ContainerLockMode.UNLOCKED, ContainerLockMode.PARTY_ONLY),
                "an explicit UNLOCKED outranks a restrictive server default");
        assertEquals(ContainerLockMode.UNLOCKED, ContainerLockPolicy.effectiveMode(
                ContainerLockMode.UNLOCKED, null, ContainerLockMode.UNLOCKED));
        assertEquals(ContainerLockMode.PRIVATE, ContainerLockPolicy.effectiveMode(
                null, null, ContainerLockMode.PRIVATE), "no buff and no choice means the server default");
        assertEquals(ContainerLockMode.UNLOCKED, ContainerLockPolicy.effectiveMode(
                null, null, null), "a null default is never a lock");
    }

    @Test
    void aConfiguredBuffOfUnlockedGrantsNothing() {
        assertEquals(ContainerLockMode.PRIVATE, ContainerLockPolicy.effectiveMode(
                ContainerLockMode.UNLOCKED, ContainerLockMode.PRIVATE, ContainerLockMode.UNLOCKED),
                "this is how an admin turns the buff off without disabling the feature");
    }

    @Test
    void privateLockRefusesEveryoneButTheOwner() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();

        assertTrue(ContainerLockPolicy.canOpen(ContainerLockMode.PRIVATE, owner, owner,
                FactionId.CAPITALISM, FactionId.CAPITALISM, false), "the owner always gets in");
        assertFalse(ContainerLockPolicy.canOpen(ContainerLockMode.PRIVATE, owner, stranger,
                FactionId.CAPITALISM, FactionId.CAPITALISM, false));
        assertTrue(ContainerLockPolicy.canOpen(ContainerLockMode.PRIVATE, owner, stranger,
                FactionId.CAPITALISM, FactionId.CAPITALISM, true),
                "an admin bypasses a private lock so a moderator can never be trapped");
    }

    @Test
    void partyOnlyLockSharesWithTheSamePartyOnly() {
        UUID owner = UUID.randomUUID();
        UUID comrade = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();

        assertTrue(ContainerLockPolicy.canOpen(ContainerLockMode.PARTY_ONLY, owner, comrade,
                FactionId.COMMUNISM, FactionId.COMMUNISM, false));
        assertFalse(ContainerLockPolicy.canOpen(ContainerLockMode.PARTY_ONLY, owner, stranger,
                FactionId.COMMUNISM, FactionId.CAPITALISM, false));
        assertFalse(ContainerLockPolicy.canOpen(ContainerLockMode.PARTY_ONLY, owner, stranger,
                FactionId.COMMUNISM, FactionId.MONARCHY, false));
    }

    @Test
    void anarchismSharesNoParty() {
        UUID owner = UUID.randomUUID();
        UUID alsoAnarchist = UUID.randomUUID();

        assertFalse(ContainerLockPolicy.sharesParty(FactionId.ANARCHISM, FactionId.ANARCHISM),
                "spec 32: Anarchism is the absence of a party, so it locks everyone out but the owner");
        assertFalse(ContainerLockPolicy.canOpen(ContainerLockMode.PARTY_ONLY, owner, alsoAnarchist,
                FactionId.ANARCHISM, FactionId.ANARCHISM, false));
        assertTrue(ContainerLockPolicy.canOpen(ContainerLockMode.PARTY_ONLY, owner, alsoAnarchist,
                FactionId.ANARCHISM, FactionId.ANARCHISM, true));
    }

    @Test
    void anUnownedOrUnlockedContainerIsOpenToEveryone() {
        UUID stranger = UUID.randomUUID();
        assertTrue(ContainerLockPolicy.canOpen(ContainerLockMode.UNLOCKED, UUID.randomUUID(), stranger,
                FactionId.COMMUNISM, FactionId.MONARCHY, false));
        assertTrue(ContainerLockPolicy.canOpen(ContainerLockMode.PARTY_ONLY, null, stranger,
                null, FactionId.MONARCHY, false), "a row with no readable owner grants access");
        assertTrue(ContainerLockPolicy.canOpen(null, UUID.randomUUID(), stranger,
                FactionId.COMMUNISM, FactionId.MONARCHY, false), "an unreadable mode fails open");
    }

    @Test
    void lockModeParsingFallsBackOnATypo() {
        assertEquals(ContainerLockMode.PARTY_ONLY, ContainerLockPolicy.parse("party_only", ContainerLockMode.PARTY_ONLY));
        assertEquals(ContainerLockMode.PRIVATE, ContainerLockPolicy.parse(" PRIVATE ", ContainerLockMode.UNLOCKED));
        assertEquals(ContainerLockMode.UNLOCKED, ContainerLockPolicy.parse("nonsense", ContainerLockMode.UNLOCKED));
        assertEquals(ContainerLockMode.UNLOCKED, ContainerLockPolicy.parse(null, ContainerLockMode.UNLOCKED));
        assertEquals(ContainerLockMode.UNLOCKED, ContainerLockPolicy.parse("  ", ContainerLockMode.UNLOCKED));
    }

    // --- P9-T14: Container Lock Store ---

    @Test
    void containerLockStorePersistenceAndOperations() {
        var lockFile = tempDir.resolve("container_locks.json");
        ContainerLockStore store = new ContainerLockStore(lockFile);
        assertEquals(0, store.count());

        UUID owner = UUID.randomUUID();
        store.setLock(null, new net.minecraft.core.BlockPos(10, 64, 20), owner, ContainerLockMode.PRIVATE);
        assertEquals(1, store.count());
        com.reazip.economycraft.util.AsyncFileWriter.flush();

        ContainerLockStore reopened = new ContainerLockStore(lockFile);
        assertEquals(1, reopened.count(), "the lock survived a restart");
        assertEquals(ContainerLockMode.PRIVATE, reopened.get("minecraft:overworld",
                new net.minecraft.core.BlockPos(10, 64, 20), null).mode);

        UUID stranger = UUID.randomUUID();
        assertFalse(reopened.removeLock(null, new net.minecraft.core.BlockPos(10, 64, 20), stranger, false));
        assertEquals(1, reopened.count());
        assertTrue(reopened.removeLock(null, new net.minecraft.core.BlockPos(10, 64, 20), stranger, true),
                "an admin may clear anyone's lock");
        assertEquals(0, reopened.count());

        reopened.setLock(null, new net.minecraft.core.BlockPos(30, 64, 40), owner, ContainerLockMode.PARTY_ONLY);
        assertEquals(1, reopened.count());
        reopened.broken(null, new net.minecraft.core.BlockPos(30, 64, 40));
        assertEquals(0, reopened.count(), "a broken chest does not keep its lock");

        reopened.setLock(null, new net.minecraft.core.BlockPos(1, 1, 1), owner, ContainerLockMode.PRIVATE);
        reopened.setLock(null, new net.minecraft.core.BlockPos(2, 2, 2), owner, ContainerLockMode.PRIVATE);
        assertEquals(2, reopened.count());
        reopened.clearAll();
        assertEquals(0, reopened.count());
    }
}

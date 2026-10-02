package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.time.WallClock;
import com.reazip.economycraft.villager.VillagerTradeEconomy;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Merchant profession:
 * - Stick trade exclusion (spec line 58)
 * - 20-trades-per-villager cap (spec line 58)
 * - Auction purchase progression (spec line 58)
 * - D6 AND level-up semantics (spec line 58, D6)
 * - Lưỡi không xương discount calculations and TaxPolicy integration (spec line 59)
 * - Villager trade economy base pricing and trade logic (P7-T5)
 */
class MerchantEffectsTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    private static ProfessionStore store(@TempDir Path temp) {
        return new ProfessionStore(temp.resolve("professions.json"), WallClock.SYSTEM);
    }

    // --- P7-T1: Stick Trade Exclusions ---

    @Test
    void stickTradeDetectionExcludesStickInCostOrResult() {
        // Trade 1: Sticks for Emerald (Fletcher) -> excluded
        MerchantOffer stickCostOffer = new MerchantOffer(
                new ItemCost(Items.STICK, 32), Optional.empty(), new ItemStack(Items.EMERALD, 1), 0, 16, 2, 0.05F
        );
        assertTrue(MerchantEffects.isStickTrade(stickCostOffer), "Sticks as costA must be detected as stick trade");

        // Trade 2: Sticks as costB -> excluded
        MerchantOffer stickCostBOffer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 1), Optional.of(new ItemCost(Items.STICK, 5)), new ItemStack(Items.BOW, 1), 0, 16, 2, 0.05F
        );
        assertTrue(MerchantEffects.isStickTrade(stickCostBOffer), "Sticks as costB must be detected as stick trade");

        // Trade 3: Stick as result -> excluded
        MerchantOffer stickResultOffer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 1), Optional.empty(), new ItemStack(Items.STICK, 4), 0, 16, 2, 0.05F
        );
        assertTrue(MerchantEffects.isStickTrade(stickResultOffer), "Sticks as result must be detected as stick trade");

        // Trade 4: Regular trade (Emerald for Bread) -> qualifying trade
        MerchantOffer normalOffer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 1), Optional.empty(), new ItemStack(Items.BREAD, 6), 0, 16, 2, 0.05F
        );
        assertFalse(MerchantEffects.isStickTrade(normalOffer), "Normal trade must not be flagged as stick trade");
    }

    // --- P7-T2: 20-Per-Villager Cap & Multi-Villager Tracking ---

    @Test
    void villagerTradesAreCappedAt20PerVillager(@TempDir Path temp) {
        ProfessionStore store = store(temp);
        store.select(ALICE, ProfessionId.MERCHANT);

        String villager1 = UUID.randomUUID().toString();
        String villager2 = UUID.randomUUID().toString();

        // First 20 trades with villager 1 count
        for (int i = 0; i < 20; i++) {
            assertTrue(store.recordVillagerTrade(ALICE, villager1), "Trade " + (i + 1) + " should count");
        }
        assertEquals(20L, store.tradesWith(ALICE, villager1));
        assertEquals(20L, store.totalVillagerTrades(ALICE));

        // 21st trade with villager 1 must be rejected
        assertFalse(store.recordVillagerTrade(ALICE, villager1), "21st trade with same villager must be capped");
        assertEquals(20L, store.tradesWith(ALICE, villager1));

        // Villager 2 still accepts trades
        assertTrue(store.recordVillagerTrade(ALICE, villager2), "Trade with new villager should count");
        assertEquals(1L, store.tradesWith(ALICE, villager2));
        assertEquals(21L, store.totalVillagerTrades(ALICE));
    }

    // --- P7-T3: /ah Auction Purchase Tracking ---

    @Test
    void auctionPurchasesCountUpToCap(@TempDir Path temp) {
        ProfessionStore store = store(temp);
        store.select(ALICE, ProfessionId.MERCHANT);

        for (int i = 0; i < 5; i++) {
            assertTrue(store.recordAuctionPurchase(ALICE), "Auction purchase " + (i + 1) + " should count");
        }
        assertEquals(5L, store.progressOf(ALICE).progress);

        // 6th purchase is capped
        assertFalse(store.recordAuctionPurchase(ALICE), "6th purchase must be rejected when capped");
        assertEquals(5L, store.progressOf(ALICE).progress);
    }

    // --- D6: AND Semantics for Master Promotion ---

    @Test
    void merchantRequiresBothTradesAndAuctionPurchasesToReachMaster(@TempDir Path temp) {
        ProfessionStore store = store(temp);
        store.select(ALICE, ProfessionId.MERCHANT);

        String v1 = UUID.randomUUID().toString();
        String v2 = UUID.randomUUID().toString();
        String v3 = UUID.randomUUID().toString();

        // Complete 50 villager trades across 3 villagers (20 + 20 + 10)
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v1);
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v2);
        for (int i = 0; i < 10; i++) store.recordVillagerTrade(ALICE, v3);

        assertEquals(50L, store.totalVillagerTrades(ALICE));
        assertEquals(0L, store.progressOf(ALICE).progress);
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE),
                "50 trades alone must not promote to Master (D6 AND requirement)");

        // 4 auction purchases: still not Master
        for (int i = 0; i < 4; i++) store.recordAuctionPurchase(ALICE);
        assertEquals(4L, store.progressOf(ALICE).progress);
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE),
                "50 trades + 4 auction purchases must still be Apprentice");

        // 5th auction purchase: both conditions met -> Promoted to Master!
        store.recordAuctionPurchase(ALICE);
        assertEquals(5L, store.progressOf(ALICE).progress);
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE),
                "Meeting both 50 trades and 5 auction purchases must promote to Master");
        assertTrue(store.progressOf(ALICE).everMastered);
    }

    @Test
    void auctionPurchasesFirstThenTradesPromotesOn50thTrade(@TempDir Path temp) {
        ProfessionStore store = store(temp);
        store.select(ALICE, ProfessionId.MERCHANT);

        // 5 auction purchases completed first
        for (int i = 0; i < 5; i++) store.recordAuctionPurchase(ALICE);
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE),
                "5 auction purchases alone must not promote");

        String v1 = UUID.randomUUID().toString();
        String v2 = UUID.randomUUID().toString();
        String v3 = UUID.randomUUID().toString();

        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v1);
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v2);
        for (int i = 0; i < 9; i++) store.recordVillagerTrade(ALICE, v3);

        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE),
                "49 trades + 5 purchases is still Apprentice");

        // 50th trade promotes to Master
        store.recordVillagerTrade(ALICE, v3);
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE),
                "50th trade with 5 purchases must promote to Master");
    }

    // --- Rusted State (D13) ---

    @Test
    void rustedMerchantCannotEarnProgress(@TempDir Path temp) {
        ProfessionStore store = store(temp);
        store.select(ALICE, ProfessionId.MERCHANT);

        // Manually set Master state and switch away to create rust
        String v1 = UUID.randomUUID().toString();
        String v2 = UUID.randomUUID().toString();
        String v3 = UUID.randomUUID().toString();
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v1);
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v2);
        for (int i = 0; i < 10; i++) store.recordVillagerTrade(ALICE, v3);
        for (int i = 0; i < 5; i++) store.recordAuctionPurchase(ALICE);
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE));

        // Switch to Farmer then back to Merchant -> RUSTED
        store.select(ALICE, ProfessionId.FARMER);
        store.select(ALICE, ProfessionId.MERCHANT);

        assertEquals(ProfessionLevel.RUSTED, store.levelOf(ALICE));
        assertTrue(store.progressOf(ALICE).isRusted());

        // Rusted merchant cannot record trades or purchases
        assertFalse(store.recordVillagerTrade(ALICE, v1), "Rusted merchant cannot record trades");
        assertFalse(store.recordAuctionPurchase(ALICE), "Rusted merchant cannot record auction purchases");
    }

    // --- P7-T4: Lưỡi không xương Discount Calculations ---

    @Test
    void taxPolicyAppliesPreTaxDiscountCorrectly() {
        // Base 100, 10% tax rate, 5% Apprentice discount
        TaxQuote quoteApprentice = TaxPolicy.quote(TaxScope.TRANSACTION_AUCTION_BUY, 100L, 0.10D, 0.05D);
        assertEquals(5L, quoteApprentice.discount(), "5% discount on 100 is 5");
        assertEquals(95L, quoteApprentice.discountedBase(), "Discounted base is 95");
        assertEquals(10L, quoteApprentice.amount(), "Tax on 95 at 10% is 10 (round(9.5))");
        assertEquals(105L, quoteApprentice.total(), "Total is 95 + 10 = 105");

        // Base 100, 10% tax rate, 15% Master discount
        TaxQuote quoteMaster = TaxPolicy.quote(TaxScope.TRANSACTION_AUCTION_BUY, 100L, 0.10D, 0.15D);
        assertEquals(15L, quoteMaster.discount(), "15% discount on 100 is 15");
        assertEquals(85L, quoteMaster.discountedBase(), "Discounted base is 85");
        assertEquals(9L, quoteMaster.amount(), "Tax on 85 at 10% is 9 (round(8.5))");
        assertEquals(94L, quoteMaster.total(), "Total is 85 + 9 = 94");

        // Without discount: Total is 100 + 10 = 110
        TaxQuote noDiscount = TaxPolicy.quote(TaxScope.TRANSACTION_AUCTION_BUY, 100L, 0.10D, 0.0D);
        assertEquals(0L, noDiscount.discount());
        assertEquals(100L, noDiscount.discountedBase());
        assertEquals(10L, noDiscount.amount());
        assertEquals(110L, noDiscount.total());
    }

    @Test
    void villagerOfferSpecialPriceDiffApplicationAndReset() {
        MerchantOffers offers = new MerchantOffers();
        MerchantOffer offer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 20), Optional.empty(), new ItemStack(Items.DIAMOND_SWORD, 1), 0, 12, 10, 0.05F
        );
        offers.add(offer);

        assertEquals(0, offer.getSpecialPriceDiff());
        assertEquals(20, offer.getCostA().getCount());

        // Applying a 15% discount on 20 emeralds = 3 emeralds discount
        int discount = (int) Math.round(20 * 0.15D);
        offer.addToSpecialPriceDiff(-discount);

        assertEquals(-3, offer.getSpecialPriceDiff());
        assertEquals(17, offer.getCostA().getCount(), "Modified cost should be 20 - 3 = 17");

        // Reset restores original price
        MerchantEffects.resetVillagerTradeDiscount(offers);
        assertEquals(0, offer.getSpecialPriceDiff());
        assertEquals(20, offer.getCostA().getCount(), "Reset restores original cost to 20");
    }

    // --- P7-T5: Villager Trade Economy Pricing & Direction ---

    @Test
    void villagerTradeEconomyDirectionClassification() {
        // Emerald in cost -> buying from villager
        MerchantOffer buyOffer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 5), Optional.empty(), new ItemStack(Items.IRON_SWORD, 1), 0, 10, 2, 0.05F
        );
        assertTrue(VillagerTradeEconomy.isBuyFromVillager(buyOffer));

        // Emerald in result -> selling to villager
        MerchantOffer sellOffer = new MerchantOffer(
                new ItemCost(Items.WHEAT, 32), Optional.empty(), new ItemStack(Items.EMERALD, 1), 0, 10, 2, 0.05F
        );
        assertFalse(VillagerTradeEconomy.isBuyFromVillager(sellOffer));
    }

    @Test
    void villagerTradeEconomyBasePriceResolution() {
        // Buying with 5 emeralds cost -> 5 * 100 = 500
        MerchantOffer buyOffer = new MerchantOffer(
                new ItemCost(Items.EMERALD, 5), Optional.empty(), new ItemStack(Items.IRON_SWORD, 1), 0, 10, 2, 0.05F
        );
        long buyPrice = VillagerTradeEconomy.resolveBasePrice(null, buyOffer, true);
        assertEquals(500L, buyPrice);

        // Selling for 2 emeralds reward -> 2 * 30 = 60
        MerchantOffer sellOffer = new MerchantOffer(
                new ItemCost(Items.CARROT, 24), Optional.empty(), new ItemStack(Items.EMERALD, 2), 0, 10, 2, 0.05F
        );
        long sellPrice = VillagerTradeEconomy.resolveBasePrice(null, sellOffer, false);
        assertEquals(60L, sellPrice);
    }
}

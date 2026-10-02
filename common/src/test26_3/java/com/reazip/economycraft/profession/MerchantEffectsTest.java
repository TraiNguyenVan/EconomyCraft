package com.reazip.economycraft.profession;

import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.time.WallClock;
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
 * - Lưỡi không xương discount calculations (Hero of the Village style, villager only)
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

        // 1. Give 50 villager trades across 3 villagers (20 + 20 + 10)
        String v1 = UUID.randomUUID().toString();
        String v2 = UUID.randomUUID().toString();
        String v3 = UUID.randomUUID().toString();

        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v1);
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v2);
        for (int i = 0; i < 10; i++) store.recordVillagerTrade(ALICE, v3);

        assertEquals(50L, store.totalVillagerTrades(ALICE));
        // Still Apprentice because auctionPurchases == 0
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE), "50 trades alone must not promote without AH purchases");

        // 2. Perform 4 auction purchases -> still Apprentice
        for (int i = 0; i < 4; i++) {
            store.recordAuctionPurchase(ALICE);
            assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(ALICE));
        }

        // 3. 5th auction purchase fulfills the AND condition -> promoted to MASTER!
        boolean promoted = store.recordAuctionPurchase(ALICE);
        assertTrue(promoted, "5th AH purchase with 50 trades should promote to Master");
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE));
    }

    @Test
    void auctionPurchasesFirstThenTradesPromotesOn50thTrade(@TempDir Path temp) {
        ProfessionStore store = store(temp);
        store.select(BOB, ProfessionId.MERCHANT);

        // 5 AH purchases first
        for (int i = 0; i < 5; i++) {
            store.recordAuctionPurchase(BOB);
        }
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(BOB));

        // Now 49 villager trades
        String v1 = UUID.randomUUID().toString();
        String v2 = UUID.randomUUID().toString();
        String v3 = UUID.randomUUID().toString();

        for (int i = 0; i < 20; i++) store.recordVillagerTrade(BOB, v1);
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(BOB, v2);
        for (int i = 0; i < 9; i++) store.recordVillagerTrade(BOB, v3);
        assertEquals(ProfessionLevel.APPRENTICE, store.levelOf(BOB));

        // 50th trade promotes to MASTER
        boolean promoted = store.recordVillagerTrade(BOB, v3);
        assertTrue(promoted, "50th villager trade with 5 AH purchases should promote to Master");
        assertEquals(ProfessionLevel.MASTER, store.levelOf(BOB));
    }

    @Test
    void rustedMerchantCannotEarnProgress(@TempDir Path temp) {
        ProfessionStore store = store(temp);
        store.select(ALICE, ProfessionId.MERCHANT);

        // Master ALICE
        String v1 = UUID.randomUUID().toString();
        String v2 = UUID.randomUUID().toString();
        String v3 = UUID.randomUUID().toString();
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v1);
        for (int i = 0; i < 20; i++) store.recordVillagerTrade(ALICE, v2);
        for (int i = 0; i < 10; i++) store.recordVillagerTrade(ALICE, v3);
        for (int i = 0; i < 5; i++) store.recordAuctionPurchase(ALICE);
        assertEquals(ProfessionLevel.MASTER, store.levelOf(ALICE));

        // Switch job and switch back -> RUSTED
        store.select(ALICE, ProfessionId.BUILDER);
        store.select(ALICE, ProfessionId.MERCHANT);
        assertEquals(ProfessionLevel.RUSTED, store.levelOf(ALICE));

        // Cannot earn progress while rusted
        assertFalse(store.recordVillagerTrade(ALICE, UUID.randomUUID().toString()));
        assertFalse(store.recordAuctionPurchase(ALICE));
    }

    // --- Villager Offer Discount (Hero of the Village Style) ---

    @Test
    void calculateOfferDiscountHeroOfTheVillageSemantics() {
        double apprenticeRate = 0.05D;
        double masterRate = 0.15D;
        double masterRustyRate = 0.075D;

        // 1-item trades never drop below 1
        assertEquals(0, MerchantEffects.calculateOfferDiscount(1, apprenticeRate));
        assertEquals(0, MerchantEffects.calculateOfferDiscount(1, masterRate));

        // 2-item trades get 1 discount (Hero of the Village minimum discount)
        assertEquals(1, MerchantEffects.calculateOfferDiscount(2, apprenticeRate));
        assertEquals(1, MerchantEffects.calculateOfferDiscount(2, masterRate));

        // 5-item trades: 5 * 0.05 = 0.25 -> round 0, but min 1 for cost >= 2 -> 1
        assertEquals(1, MerchantEffects.calculateOfferDiscount(5, apprenticeRate));
        // 5 * 0.15 = 0.75 -> round 1
        assertEquals(1, MerchantEffects.calculateOfferDiscount(5, masterRate));

        // 24-item trades
        // 24 * 0.05 = 1.2 -> 1
        assertEquals(1, MerchantEffects.calculateOfferDiscount(24, apprenticeRate));
        // 24 * 0.15 = 3.6 -> 4
        assertEquals(4, MerchantEffects.calculateOfferDiscount(24, masterRate));

        // 32-item trades: 32 * 0.15 = 4.8 -> 5
        assertEquals(5, MerchantEffects.calculateOfferDiscount(32, masterRate));

        // 64-item trades: 64 * 0.15 = 9.6 -> 10
        assertEquals(10, MerchantEffects.calculateOfferDiscount(64, masterRate));

        // Rusty Master (7.5%): 24 * 0.075 = 1.8 -> 2
        assertEquals(2, MerchantEffects.calculateOfferDiscount(24, masterRustyRate));

        // Non-merchants (0.0%): no discount
        assertEquals(0, MerchantEffects.calculateOfferDiscount(64, 0.0D));
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

        // Applying a 15% discount on 20 emeralds: 20 * 0.15 = 3 emeralds discount
        int discount = MerchantEffects.calculateOfferDiscount(20, 0.15D);
        assertEquals(3, discount);
        offer.addToSpecialPriceDiff(-discount);

        assertEquals(-3, offer.getSpecialPriceDiff());
        assertEquals(17, offer.getCostA().getCount(), "Modified cost should be 20 - 3 = 17");

        // Reset restores original price
        MerchantEffects.resetVillagerTradeDiscount(offers);
        assertEquals(0, offer.getSpecialPriceDiff());
        assertEquals(20, offer.getCostA().getCount(), "Reset restores original cost to 20");
    }
}

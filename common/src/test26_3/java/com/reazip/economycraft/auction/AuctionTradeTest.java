package com.reazip.economycraft.auction;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.api.v1.BalanceMutationStatus;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.api.v1.PaymentResult;
import com.reazip.economycraft.negotiation.NegotiationStore;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The uuid purchase path — the one the HTTP route and, after the refactor, the GUI both run.
 *
 * <p>The ledger mock is the same shape {@code ContractServiceTest} uses: a debit fails on
 * insufficient funds and is otherwise applied, so the settlement and recovery branches behave here
 * the way the BalanceMutations engine makes them behave in production. What these tests are really
 * about is that a purchase addressed by uuid still does everything the in-game one did — refuses
 * your own listing, puts a failed sale back, and gets an offline buyer's item into the deliveries
 * ledger where they can claim it.
 */
class AuctionTradeTest {

    private static final UUID SELLER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID BUYER = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    /** The test ledger: every money movement in these tests lands here. */
    private final Map<UUID, Long> balances = new HashMap<>();

    private MinecraftServer server;
    private PlayerList players;
    private EconomyManager eco;
    private AuctionManager auctions;
    private AuctionListing listing;

    @BeforeAll
    static void bootstrap() {
        // Same order as StorePersistenceTest: the version has to be detected before anything touches
        // DataFixers, and item components have to be readable before any stack is encoded.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    @BeforeEach
    void setUp() {
        // Pin the keys these tests depend on: another class may replace the config instance.
        EconomyConfig cfg = EconomyConfig.get();
        cfg.taxRate = 0.1;
        cfg.factions.enabled = false;    // the base rate, with no faction rule in the way
        cfg.professions.enabled = false; // and no merchant discount either

        balances.clear();
        balances.put(BUYER, 100_000L);
        balances.put(SELLER, 0L);

        server = mock(MinecraftServer.class);
        players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        // Nobody is online unless a test says otherwise.
        when(players.getPlayer(any(UUID.class))).thenReturn(null);

        auctions = mock(AuctionManager.class);
        when(auctions.getServer()).thenReturn(server);

        eco = mock(EconomyManager.class);
        when(eco.getAuctions()).thenReturn(auctions);
        when(eco.getServer()).thenReturn(server);
        when(eco.getNegotiations()).thenReturn(mock(NegotiationStore.class));
        when(eco.getBestName(any(UUID.class)))
                .thenAnswer(call -> "Player-" + call.getArgument(0, UUID.class).toString().substring(0, 8));
        when(eco.transferMoney(any(UUID.class), any(UUID.class), anyLong(), anyLong(),
                any(MutationSource.class), anyString()))
                .thenAnswer(call -> transfer(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3), call.getArgument(4)));

        listing = new AuctionListing();
        listing.id = 7;
        listing.seller = SELLER;
        listing.price = 500L;
        listing.item = new ItemStack(Items.DIAMOND, 3);
        listing.createdAt = 1_000L;
        listing.expiresAt = 0L;
    }

    /**
     * The real engine's two sharp edges, in miniature: the debit is all-or-nothing, and the credit
     * is exactly what the caller asked for. The auction charges the buyer the ask plus the tax and
     * credits the seller the ask, which is why the two amounts differ.
     */
    private PaymentResult transfer(UUID from, UUID to, long debit, long credit, MutationSource source) {
        long fromBefore = balances.getOrDefault(from, 0L);
        long toBefore = balances.getOrDefault(to, 0L);
        if (fromBefore < debit) {
            return new PaymentResult(BalanceMutationStatus.INSUFFICIENT_FUNDS, from, to, debit,
                    fromBefore, fromBefore, toBefore, toBefore, Optional.of(source));
        }
        balances.put(from, fromBefore - debit);
        balances.put(to, toBefore + credit);
        return new PaymentResult(BalanceMutationStatus.SUCCESS, from, to, debit,
                fromBefore, fromBefore - debit, toBefore, toBefore + credit, Optional.of(source));
    }

    private void listedForSale() {
        when(auctions.getListing(listing.id)).thenReturn(listing);
        when(auctions.removeListing(listing.id)).thenReturn(listing);
    }

    @Test
    @DisplayName("buying your own listing is refused and touches neither the money nor the store")
    void buyingYourOwnListingIsRefused() {
        listing.seller = BUYER;
        when(auctions.getListing(listing.id)).thenReturn(listing);

        AuctionTrade.PurchaseResult result = AuctionTrade.purchaseFor(eco, BUYER, listing.id);

        assertEquals(AuctionTrade.PurchaseStatus.OWN_LISTING, result.status());
        assertFalse(result.success());
        verify(auctions, never()).removeListing(anyInt());
        verify(auctions, never()).addDelivery(any(UUID.class), any(ItemStack.class));
        assertEquals(100_000L, balances.get(BUYER), "the buyer's balance must not move");
    }

    @Test
    @DisplayName("a listing that is already gone is refused without claiming anything")
    void listingGoneIsRefused() {
        AuctionTrade.PurchaseResult result = AuctionTrade.purchaseFor(eco, BUYER, listing.id);

        assertEquals(AuctionTrade.PurchaseStatus.LISTING_GONE, result.status());
        assertNull(result.seller());
        verify(auctions, never()).restoreListing(any(AuctionListing.class));
    }

    @Test
    @DisplayName("a payment that fails puts the listing back at its ask and moves no item")
    void failedPaymentRestoresTheListing() {
        balances.put(BUYER, 10L);
        listedForSale();

        AuctionTrade.PurchaseResult result = AuctionTrade.purchaseFor(eco, BUYER, listing.id);

        assertEquals(AuctionTrade.PurchaseStatus.CANT_AFFORD, result.status());
        verify(auctions).restoreListing(listing);
        assertEquals(500L, listing.price, "the listing goes back at its original ask");
        assertEquals(10L, balances.get(BUYER));
        assertEquals(0L, balances.get(SELLER));
        verify(auctions, never()).addDelivery(any(UUID.class), any(ItemStack.class));
        verify(auctions, never()).notifySellerSale(any(AuctionListing.class), any(UUID.class), anyString());
    }

    @Test
    @DisplayName("a seller who cannot receive the money is reported as that, not as a broke buyer")
    void sellerPastTheBalanceCeiling() {
        when(eco.transferMoney(any(UUID.class), any(UUID.class), anyLong(), anyLong(),
                any(MutationSource.class), anyString()))
                .thenAnswer(call -> new PaymentResult(BalanceMutationStatus.MAX_BALANCE_EXCEEDED,
                        call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        100_000L, 100_000L, EconomyManager.MAX, EconomyManager.MAX,
                        Optional.of(call.getArgument(4))));
        listedForSale();

        AuctionTrade.PurchaseResult result = AuctionTrade.purchaseFor(eco, BUYER, listing.id);

        assertEquals(AuctionTrade.PurchaseStatus.SELLER_CANT_RECEIVE, result.status());
        verify(auctions).restoreListing(listing);
    }

    @Test
    @DisplayName("an offline buyer is charged, told the seller, and gets the item in their deliveries")
    void offlineBuyersItemReachesTheDeliveriesLedger() {
        listedForSale();

        AuctionTrade.PurchaseResult result = AuctionTrade.purchaseFor(eco, BUYER, listing.id);

        assertTrue(result.success());
        assertTrue(result.stored(), "an offline buyer has no inventory to put it in");
        verify(auctions).addDelivery(eq(BUYER), any(ItemStack.class));
        assertEquals(100_000L - 550L, balances.get(BUYER), "the ask plus 10% tax");
        assertEquals(500L, balances.get(SELLER), "the seller receives the ask; the tax is burned");
        assertEquals(550L, result.totalPaid());
        verify(auctions).notifySellerSale(eq(listing), eq(BUYER), anyString());
    }

    @Test
    @DisplayName("the seller is named from getBestName when the buyer is not here to be asked")
    void offlineBuyerIsNamedFromTheEconomyManager() {
        listedForSale();

        AuctionTrade.purchaseFor(eco, BUYER, listing.id);

        verify(auctions).notifySellerSale(eq(listing), eq(BUYER), eq("Player-00000000"));
    }

    @Test
    @DisplayName("the item that reaches the ledger is the one that was listed")
    void theDeliveredStackIsTheListedStack() {
        listedForSale();
        ArgumentCaptor<ItemStack> stored = ArgumentCaptor.forClass(ItemStack.class);

        AuctionTrade.purchaseFor(eco, BUYER, listing.id);

        // ItemStack does not override equals, so the delivered stack is compared by what it is, not
        // by which object it is.
        verify(auctions).addDelivery(eq(BUYER), stored.capture());
        assertEquals(Items.DIAMOND, stored.getValue().getItem());
        assertEquals(3, stored.getValue().getCount());
    }

    @Test
    @DisplayName("the GUI entry point runs this implementation, not a second copy of it")
    void guiPurchaseDelegatesToTheUuidPath() {
        ServerPlayer online = mock(ServerPlayer.class);
        when(online.getUUID()).thenReturn(BUYER);
        Inventory inventory = mock(Inventory.class);
        when(online.getInventory()).thenReturn(inventory);
        when(inventory.add(any(ItemStack.class))).thenReturn(true);
        when(players.getPlayer(BUYER)).thenReturn(online);
        listedForSale();

        AuctionTrade.PurchaseResult result = AuctionTrade.purchase(eco, online, listing.id);

        assertTrue(result.success());
        assertFalse(result.stored(), "an online buyer's item goes straight into their inventory");
        verify(inventory).add(any(ItemStack.class));
        // Only the uuid path calls this overload: the old in-GUI copy passed the player object to a
        // method that no longer exists, and this test does not compile against a copied implementation.
        verify(auctions).notifySellerSale(listing, online);
    }
}

package com.reazip.economycraft.market;

import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionManager;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The list transaction's one invariant, stated as a test: an item that has already left its owner's
 * inventory must end up in exactly one place — the new listing, or the deliveries mailbox. It must
 * never end up nowhere, which is what a failed store write would otherwise do.
 *
 * <p>This is why the ordering lives in a class of its own instead of inline in the HTTP listener:
 * the compensation path is unreachable through a request, and it is the path that loses somebody's
 * item when it is wrong.
 */
class ListingTransactionTest {

    private static final UUID SELLER = UUID.fromString("00000000-0000-4000-8000-000000000053");

    private final AuctionManager auctions = mock(AuctionManager.class);

    @BeforeAll
    static void bootstrap() {
        // Same order as StorePersistenceTest: the version has to be detected before anything touches
        // DataFixers, and item components have to be readable before any stack is encoded.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    @Test
    @DisplayName("a listing that is created is not also mailed, and reports the store's own id")
    void aCreatedListingOwnsTheItem() {
        ItemStack item = new ItemStack(Items.DIAMOND, 8);
        // The manager allocates the id, so the transaction can only report what the store handed back.
        doAnswer(call -> {
            call.getArgument(0, AuctionListing.class).id = 42;
            return null;
        }).when(auctions).addListing(any(AuctionListing.class));

        ListingTransaction.Result result = ListingTransaction.list(auctions, SELLER, item, 250L, "spare", 168);

        assertTrue(result.ok());
        assertFalse(result.stored(), "the item is in a listing, not in the mailbox");
        assertNull(result.error());
        assertEquals(42, result.id());
        verify(auctions, never()).addDelivery(any(UUID.class), any(ItemStack.class));

        ArgumentCaptor<AuctionListing> saved = ArgumentCaptor.forClass(AuctionListing.class);
        verify(auctions).addListing(saved.capture());
        assertEquals(SELLER, saved.getValue().seller);
        assertEquals(250L, saved.getValue().price);
        assertEquals("spare", saved.getValue().description);
        assertEquals(8, saved.getValue().item.getCount());
        assertTrue(saved.getValue().expiresAt > saved.getValue().createdAt,
                "the listing has to expire after it was created");
    }

    @Test
    @DisplayName("a listing that cannot be created puts the item in the mailbox instead of losing it")
    void aFailedListingCompensatesIntoTheMailbox() {
        ItemStack item = new ItemStack(Items.DIAMOND, 8);
        doThrow(new IllegalStateException("the auction store could not be written"))
                .when(auctions).addListing(any(AuctionListing.class));

        ListingTransaction.Result result = ListingTransaction.list(auctions, SELLER, item, 250L, "spare", 168);

        assertFalse(result.ok());
        assertNotNull(result.error());
        assertNull(result.id(), "no listing was created");
        assertTrue(result.stored(), "the item went to the deliveries mailbox");

        ArgumentCaptor<ItemStack> stored = ArgumentCaptor.forClass(ItemStack.class);
        verify(auctions).addDelivery(eq(SELLER), stored.capture());
        assertEquals(Items.DIAMOND, stored.getValue().getItem());
        assertEquals(8, stored.getValue().getCount(), "the whole stack, not part of it");
    }

    @Test
    @DisplayName("the mailbox is only ever the compensation: a listing that lands mails nothing")
    void theMailboxIsOnlyTheCompensation() {
        ItemStack item = new ItemStack(Items.EMERALD, 4);
        doAnswer(call -> {
            call.getArgument(0, AuctionListing.class).id = 1031;
            return null;
        }).when(auctions).addListing(any(AuctionListing.class));

        ListingTransaction.Result result = ListingTransaction.list(auctions, SELLER, item, 6400L, null, 0);

        assertTrue(result.ok());
        assertEquals(1031, result.id());
        ArgumentCaptor<AuctionListing> saved = ArgumentCaptor.forClass(AuctionListing.class);
        verify(auctions).addListing(saved.capture());
        assertEquals(0L, saved.getValue().expiresAt,
                "zero configured hours means the listing never expires, not that it expired at creation");
        verify(auctions, never()).addDelivery(any(UUID.class), any(ItemStack.class));
    }
}

package com.reazip.economycraft.negotiation;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.HubUi;
import com.reazip.economycraft.auction.AuctionListing;
import com.reazip.economycraft.auction.AuctionUi;
import com.reazip.economycraft.orders.OrderRequest;
import com.reazip.economycraft.orders.OrdersUi;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomyPermissions.Nodes;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.MenuUiSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One screen for every open price offer the player is party to — the place the offer messages
 * point at, so a click lands on the offers instead of on the auction house where they have to
 * hunt for the right listing again.
 *
 * <p>Incoming rows are per target and open the existing per-listing review screen, which already
 * handles accept, decline and the notify fan-out; this class deliberately does not re-implement
 * any of that. Outgoing rows get their own small screen because withdrawing is the only action an
 * offerer has on an offer.
 */
public final class OffersHubUi {
    private OffersHubUi() {}

    private static final int CONTENT_ROWS = 5;
    private static final int ITEMS_PER_PAGE = CONTENT_ROWS * 9;
    private static final int NAV_ROW = ITEMS_PER_PAGE;
    private static final int ROWS = CONTENT_ROWS + 1;
    private static final int BACK_SLOT = NAV_ROW;
    private static final int PREV_SLOT = NAV_ROW + 3;
    private static final int PAGE_SLOT = NAV_ROW + 4;
    private static final int NEXT_SLOT = NAV_ROW + 5;

    /** The overview: incoming offers first, then the player's own. */
    public static void open(ServerPlayer player) {
        if (!MenuUiSupport.checkOrDeny(player,
                EconomyPermissions.checkCommand(player, Nodes.COMMAND_OFFERS))) return;
        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        MenuUiSupport.openMenu(player, "Price Offers", (id, inv) -> new HubMenu(id, inv, eco, player, 0));
    }

    /**
     * The deep link a notification click carries: {@code /eco offers ah 12} opens the review
     * screen for that one listing rather than the overview, so the message's click lands on the
     * offers it is about. Bot targets are not negotiable, so a link to one is refused instead of
     * opening an empty screen.
     */
    public static void openTarget(ServerPlayer player, NegotiationStore.Kind kind, int targetId) {
        if (!MenuUiSupport.checkOrDeny(player,
                EconomyPermissions.checkCommand(player, Nodes.COMMAND_OFFERS))) return;
        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        if (kind == NegotiationStore.Kind.AH) {
            AuctionListing listing = eco.getAuctions().getListing(targetId);
            if (listing == null || !NegotiationEvents.canNegotiateAuction(listing)) {
                fail(player, "That listing is no longer available.");
                return;
            }
            AuctionUi.openOffersFor(player, targetId);
            return;
        }
        OrderRequest request = eco.getOrders().getRequest(targetId);
        if (request == null || !NegotiationEvents.canNegotiateOrder(request)) {
            fail(player, "That request is no longer available.");
            return;
        }
        OrdersUi.openOffersFor(player, targetId);
    }

    private static void fail(ServerPlayer player, String message) {
        EconomySounds.failure(player);
        player.sendSystemMessage(MenuUiSupport.line(message, ChatFormatting.RED));
        open(player);
    }

    /** The live target snapshot the model reads, taken once per screen build. */
    private static List<OffersHubModel.Target> snapshot(EconomyManager eco) {
        List<OffersHubModel.Target> targets = new ArrayList<>();
        for (AuctionListing listing : eco.getAuctions().getListings()) {
            if (listing == null || listing.id <= 0) continue;
            targets.add(new OffersHubModel.Target(NegotiationStore.Kind.AH, listing.id, listing.seller,
                    EconomyCraft.describeItem(listing.item.getCount(), listing.item.getHoverName().getString()),
                    listing.price, NegotiationEvents.canNegotiateAuction(listing)));
        }
        for (OrderRequest request : eco.getOrders().getRequests()) {
            if (request == null || request.id <= 0) continue;
            targets.add(new OffersHubModel.Target(NegotiationStore.Kind.ORDER, request.id, request.requester,
                    EconomyCraft.describeItem(request.amount, request.item.getHoverName().getString()),
                    request.price, NegotiationEvents.canNegotiateOrder(request)));
        }
        return targets;
    }

    /** Incoming count plus outgoing count, for the hub button and the login prompt. */
    public static int[] counts(EconomyManager eco, UUID player) {
        List<OffersHubModel.Row> rows = OffersHubModel.build(snapshot(eco), eco.getNegotiations(),
                player, id -> NegotiationEvents.displayName(eco.getServer(), id));
        return new int[]{OffersHubModel.incomingOfferCount(rows), OffersHubModel.outgoingCount(rows)};
    }

    private static class HubMenu extends CompatMenu {
        private final EconomyManager eco;
        private final ServerPlayer viewer;
        private final List<OffersHubModel.Row> rows;
        private final SimpleContainer container = new SimpleContainer(ROWS * 9);
        private int page;

        HubMenu(int id, Inventory inv, EconomyManager eco, ServerPlayer viewer, int page) {
            super(MenuUiSupport.getMenuType(ROWS), id);
            this.eco = eco;
            this.viewer = viewer;
            this.rows = OffersHubModel.build(snapshot(eco), eco.getNegotiations(), viewer.getUUID(),
                    id2 -> MenuUiSupport.resolvePlayerName(viewer.level().getServer(), id2));
            int pages = Math.max(1, MenuUiSupport.totalPages(rows.size(), ITEMS_PER_PAGE));
            this.page = Math.max(0, Math.min(page, pages - 1));
            render();
            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, ROWS * 9)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + ROWS * 18 + 14)) {
                this.addSlot(slot);
            }
        }

        private void render() {
            container.clearContent();
            int pages = Math.max(1, MenuUiSupport.totalPages(rows.size(), ITEMS_PER_PAGE));
            int start = page * ITEMS_PER_PAGE;
            for (int i = 0; i < ITEMS_PER_PAGE && start + i < rows.size(); i++) {
                container.setItem(i, rowItem(rows.get(start + i)));
            }
            if (rows.isEmpty()) {
                container.setItem(4, MenuUiSupport.button(Items.BOOK, "No price offers",
                        ChatFormatting.YELLOW,
                        MenuUiSupport.hint("Offer on a listing or a request,"),
                        MenuUiSupport.hint("and they appear here.")));
            }
            container.setItem(BACK_SLOT, MenuUiSupport.backButton());
            if (pages > 1) {
                container.setItem(PREV_SLOT, MenuUiSupport.prevPageButton());
                container.setItem(PAGE_SLOT, MenuUiSupport.pageIndicator(page, pages));
                container.setItem(NEXT_SLOT, MenuUiSupport.nextPageButton());
            }
            MenuUiSupport.fillFooter(container);
        }

        private ItemStack rowItem(OffersHubModel.Row row) {
            boolean incoming = row.side() == OffersHubModel.Side.INCOMING;
            String who = MenuUiSupport.resolvePlayerName(viewer.level().getServer(), row.otherParty());
            if (who == null) who = NegotiationEvents.displayName(eco.getServer(), row.otherParty());
            ItemStack stack = new ItemStack(incoming ? Items.PAPER : Items.MAP);
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.labeledValue(incoming ? "Best offer" : "Your offer",
                    EconomyCraft.formatMoney(row.offerPrice()), MenuUiSupport.LABEL_PRIMARY_COLOR));
            lore.add(MenuUiSupport.labeledValue(incoming ? "Listed at" : "Asking",
                    EconomyCraft.formatMoney(row.targetPrice()), MenuUiSupport.LABEL_SECONDARY_COLOR));
            lore.add(MenuUiSupport.labeledValue(incoming ? "From" : "With", who,
                    MenuUiSupport.LABEL_SECONDARY_COLOR));
            lore.add(MenuUiSupport.labeledValue("On",
                    (row.kind() == NegotiationStore.Kind.AH ? "auction #" : "request #") + row.targetId(),
                    MenuUiSupport.LABEL_SECONDARY_COLOR));
            if (incoming) {
                lore.add(MenuUiSupport.labeledValue("Offers", String.valueOf(row.offerCount()),
                        MenuUiSupport.LABEL_PRIMARY_COLOR));
                lore.add(MenuUiSupport.hint("Click to accept, decline or counter"));
            } else {
                lore.add(MenuUiSupport.hint("Click to withdraw this offer"));
            }
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(row.targetDesc())
                    .withStyle(s -> s.withItalic(false).withBold(true)
                            .withColor(incoming ? ChatFormatting.YELLOW : ChatFormatting.AQUA)));
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private OffersHubModel.Row rowAt(int slot) {
            if (slot < 0 || slot >= ITEMS_PER_PAGE) return null;
            int index = page * ITEMS_PER_PAGE + slot;
            return index < rows.size() ? rows.get(index) : null;
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP) return false;
            OffersHubModel.Row row = rowAt(slot);
            if (row != null) {
                EconomySounds.click(viewer);
                if (row.side() == OffersHubModel.Side.INCOMING) {
                    openTarget(viewer, row.kind(), row.targetId());
                } else {
                    MenuUiSupport.openMenu(viewer, "Your offer", (id, inv) ->
                            new OutgoingOfferMenu(id, inv, eco, viewer, row));
                }
                return true;
            }
            int pages = Math.max(1, MenuUiSupport.totalPages(rows.size(), ITEMS_PER_PAGE));
            if (slot == BACK_SLOT) {
                EconomySounds.click(viewer);
                HubUi.open(viewer);
                return true;
            }
            if (slot == PREV_SLOT && page > 0) {
                EconomySounds.click(viewer);
                page--;
                render();
                return true;
            }
            if (slot == NEXT_SLOT && page + 1 < pages) {
                EconomySounds.click(viewer);
                page++;
                render();
                return true;
            }
            return false;
        }
    }

    /** Withdraw or keep: an offerer's only lever on an offer. */
    private static class OutgoingOfferMenu extends CompatMenu {
        private static final int BACK_SLOT = 0;
        private final EconomyManager eco;
        private final ServerPlayer viewer;
        private final OffersHubModel.Row row;
        private final SimpleContainer container = new SimpleContainer(9);

        OutgoingOfferMenu(int id, Inventory inv, EconomyManager eco, ServerPlayer viewer,
                          OffersHubModel.Row row) {
            super(MenuType.GENERIC_9x1, id);
            this.eco = eco;
            this.viewer = viewer;
            this.row = row;

            container.setItem(BACK_SLOT, MenuUiSupport.backButton());
            container.setItem(MenuUiSupport.ROW_CANCEL, MenuUiSupport.button(
                    Items.BARRIER, "Keep", ChatFormatting.YELLOW));

            String who = MenuUiSupport.resolvePlayerName(viewer.level().getServer(), row.otherParty());
            if (who == null) who = NegotiationEvents.displayName(eco.getServer(), row.otherParty());
            ItemStack subject = new ItemStack(Items.PAPER);
            subject.set(DataComponents.CUSTOM_NAME, Component.literal(
                            EconomyCraft.formatMoney(row.offerPrice()) + " for " + row.targetDesc())
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.AQUA)));
            subject.set(DataComponents.LORE, new ItemLore(List.of(
                    MenuUiSupport.labeledValue("Their price",
                            EconomyCraft.formatMoney(row.targetPrice()),
                            MenuUiSupport.LABEL_PRIMARY_COLOR),
                    MenuUiSupport.labeledValue("Seller", who,
                            MenuUiSupport.LABEL_SECONDARY_COLOR),
                    MenuUiSupport.hint("Only they can accept it."),
                    MenuUiSupport.hint("They are told when you withdraw."))));
            container.setItem(MenuUiSupport.ROW_SUBJECT, subject);
            container.setItem(MenuUiSupport.ROW_CONFIRM, MenuUiSupport.confirmButton("Withdraw"));
            MenuUiSupport.fillFooter(container);

            for (Slot slot : MenuUiSupport.confirmRowSlots(container)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 40)) {
                this.addSlot(slot);
            }
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP) return false;
            if (slot == MenuUiSupport.ROW_CONFIRM) {
                withdraw();
                return true;
            }
            if (slot == BACK_SLOT || slot == MenuUiSupport.ROW_CANCEL) {
                EconomySounds.click(viewer);
                open(viewer);
                return true;
            }
            return false;
        }

        private void withdraw() {
            NegotiationStore.Offer removed = eco.getNegotiations()
                    .removeOffer(row.kind(), row.targetId(), viewer.getUUID());
            if (removed == null) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(MenuUiSupport.line("Offer no longer available.", ChatFormatting.RED));
            } else {
                NegotiationEvents.notifyWithdrawn(eco, row.otherParty(), viewer.getUUID(),
                        row.targetDesc(), removed.price());
                EconomySounds.success(viewer);
                viewer.sendSystemMessage(Component.literal("Offer of "
                                + EconomyCraft.formatMoney(removed.price()) + " withdrawn.")
                        .withStyle(ChatFormatting.GREEN));
            }
            open(viewer);
        }
    }
}

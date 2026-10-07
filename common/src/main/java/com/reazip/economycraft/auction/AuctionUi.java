package com.reazip.economycraft.auction;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.HubUi;
import com.reazip.economycraft.negotiation.NegotiationEvents;
import com.reazip.economycraft.negotiation.NegotiationStore;
import com.reazip.economycraft.orders.OrdersUi;
import com.reazip.economycraft.quests.QuestBuyback;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.ContainerPreviewUi;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomyPermissions.Nodes;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.ExpirationUtil;
import com.reazip.economycraft.util.ItemPickerUi;
import com.reazip.economycraft.util.ItemsCompat;
import com.reazip.economycraft.util.MenuUiSupport;
import com.reazip.economycraft.util.NumberInputUi;
import com.reazip.economycraft.util.SortMode;
import com.reazip.economycraft.util.TextInputUi;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
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
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;

import java.util.Comparator;

import java.util.List;

import java.util.UUID;
public final class AuctionUi {
    private AuctionUi() {}

    /** Buyer-side "Offer a price" button in the buy-confirm row. Set after fillFooter-proof setItem. */
    private static final int OFFER_SLOT = 0;
    /** Owner-side buttons in the remove-confirm row. */
    private static final int EDIT_PRICE_SLOT = 0;
    private static final int EDIT_DESC_SLOT = 1;
    private static final int OFFERS_SLOT = 8;

    public static void open(ServerPlayer player, AuctionManager auctions) {
        open(player, auctions, 0, null, SortMode.DEFAULT, false);
    }

    public static void openSearch(ServerPlayer player, AuctionManager auctions, String query) {
        open(player, auctions, 0, query, SortMode.DEFAULT, false);
    }

    static void open(ServerPlayer player, AuctionManager auctions, int page, @Nullable String query, SortMode sort, boolean mineOnly) {
        if (!MenuUiSupport.checkOrDeny(player, EconomyPermissions.checkCommand(player, Nodes.COMMAND_AUCTION))) return;
        MenuUiSupport.openMenu(player, "Auction House", (id, inv) -> new AuctionMenu(id, inv, auctions, player, page, query, sort, mineOnly));
    }

    private static void openConfirm(ServerPlayer player, AuctionManager auctions, AuctionListing listing,
                                    @Nullable String query, SortMode sort, boolean mineOnly) {
        MenuUiSupport.openMenu(player, "Confirm", (id, inv) ->
                new ConfirmMenu(id, inv, auctions, listing, player, query, sort, mineOnly));
    }

    private static void openRemove(ServerPlayer player, AuctionManager auctions, AuctionListing listing,
                                   @Nullable String query, SortMode sort, boolean mineOnly) {
        MenuUiSupport.openMenu(player, "Remove", (id, inv) ->
                new RemoveMenu(id, inv, auctions, listing, player, query, sort, mineOnly));
    }

    private static boolean canAfford(ServerPlayer player, AuctionListing listing) {
        // A buyback listing charges exactly the sticker price — no buyer tax — so the afford
        // check must not add the base-rate tax the purchase path will never collect.
        long total = QuestBuyback.isBuybackListing(listing) ? listing.price
                : TaxPolicy.total(TaxScope.TRANSACTION_AUCTION_BUY, listing.price);
        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        return eco.getBalance(player.getUUID(), true) >= total;
    }

    /**
     * The buyer-side quote for a row: a buyback listing is tax-free by design, so it renders the
     * exempt quote (the "(tax-free)" suffix) instead of running the faction rules against the bot.
     */
    private static TaxQuote quoteForBuyer(AuctionListing listing, UUID buyer, EconomyManager eco) {
        if (QuestBuyback.isBuybackListing(listing)) {
            return new TaxQuote(listing.price, 0.0, 0L, 0L, true, TaxScope.TRANSACTION_AUCTION_BUY.source());
        }
        return TaxPolicy.resolve(TaxScope.TRANSACTION_AUCTION_BUY, listing.price, buyer, listing.seller, eco);
    }

    /** Marks a bot-owned row as quest surplus, mirroring the bounty tag on quest orders. */
    private static void addBuybackLore(List<Component> lore, AuctionListing listing) {
        if (QuestBuyback.isBuybackListing(listing)) {
            lore.add(MenuUiSupport.labeledValue("Buy-back", "quest surplus", MenuUiSupport.LABEL_SECONDARY_COLOR));
        }
    }

    private static void addDescriptionLore(List<Component> lore, AuctionListing listing) {
        if (listing.description != null && !listing.description.isBlank()) {
            net.minecraft.network.chat.MutableComponent line = Component.literal("Note: ")
                    .withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD));
            line.append(com.reazip.economycraft.motd.MotdFormatter.formatLine(listing.description));
            lore.add(line);
        }
    }

    private static Component createPriceLore(long price, long tax) {
        String value = EconomyCraft.formatMoney(price) +
                (tax > 0 ? " (+" + EconomyCraft.formatMoney(tax) + " tax)" : "");
        return MenuUiSupport.labeledValue("Price", value, MenuUiSupport.LABEL_PRIMARY_COLOR);
    }

    private static Component createPriceLore(long price, TaxQuote quote) {
        String value = EconomyCraft.formatMoney(price);
        if (quote.exempt()) {
            value += " (tax-free)";
        } else if (quote.amount() > 0) {
            value += " (+" + EconomyCraft.formatMoney(quote.amount()) + " tax)";
        }
        return MenuUiSupport.labeledValue("Price", value, MenuUiSupport.LABEL_PRIMARY_COLOR);
    }

    /**
     * " (buyers pay $X)" at the seller-aware quote, or " (tax-free for buyers)" when a faction rule waived
     * it — the suffix for the two "Listed …" chat lines.
     */
    public static String buyerTaxSuffix(TaxQuote quote) {
        if (quote.exempt()) {
            return " (tax-free for buyers)";
        }
        if (quote.amount() > 0) {
            return " (buyers pay " + EconomyCraft.formatMoney(quote.total()) + ")";
        }
        return "";
    }

    private static void startListing(ServerPlayer player, AuctionManager auctions) {
        if (auctions.hasReachedLimit(player.getUUID())) {
            EconomySounds.failure(player);
            player.sendSystemMessage(MenuUiSupport.line("You have reached your limit of "
                    + auctions.getEffectiveLimit(player.getUUID()) + " active listing(s).", ChatFormatting.RED));
            open(player, auctions);
            return;
        }
        ItemPickerUi.open(player, "Pick an item to sell", ItemPickerUi.Source.INVENTORY, null,
                (picker, choice) -> chooseAmount(picker, auctions, choice),
                p -> open(p, auctions));
    }

    private static void chooseAmount(ServerPlayer player, AuctionManager auctions, ItemPickerUi.Choice choice) {
        ItemStack prototype = choice.prototype();
        int max = Math.min(choice.heldCount(), prototype.getMaxStackSize());
        if (max <= 0) {
            EconomySounds.failure(player);
            player.sendSystemMessage(MenuUiSupport.line("You no longer have that item.", ChatFormatting.RED));
            open(player, auctions);
            return;
        }
        if (max == 1) {
            choosePrice(player, auctions, prototype, 1);
            return;
        }

        NumberInputUi.openCount(player, "How many?", prototype, "Amount", max, 1, max,
                (p, amount) -> choosePrice(p, auctions, prototype, amount.intValue()),
                p -> startListing(p, auctions));
    }

    private static void choosePrice(ServerPlayer player, AuctionManager auctions, ItemStack prototype, int amount) {
        NumberInputUi.openMoney(player, "Set your price", prototype.copyWithCount(amount), "Price",
                100, 1, EconomyManager.MAX, "Next: Description", price -> listingLore(player, amount, price),
                (p, price) -> chooseDescription(p, auctions, prototype, amount, price),
                p -> backFromPrice(player, auctions, prototype));
    }

    private static void chooseDescription(ServerPlayer player, AuctionManager auctions, ItemStack prototype, int amount, long price) {
        TextInputUi.open(player, "Add a note/description", "", Items.NAME_TAG,
                "Note: ", "Optional note (blank to skip)", true,
                (p, text) -> {
                    String desc = text.isBlank() ? null : text.trim();
                    if (desc != null && desc.length() > 100) {
                        desc = desc.substring(0, 100);
                    }
                    createListing(p, auctions, prototype, amount, price, desc);
                },
                p -> choosePrice(p, auctions, prototype, amount));
    }

    private static void backFromPrice(ServerPlayer player, AuctionManager auctions, ItemStack prototype) {
        int held = countHeld(player, prototype);
        if (Math.min(held, prototype.getMaxStackSize()) <= 1) {
            startListing(player, auctions);
        } else {
            chooseAmount(player, auctions, new ItemPickerUi.Choice(prototype, held));
        }
    }

    private static List<Component> listingLore(ServerPlayer seller, int amount, long price) {
        EconomyManager eco = EconomyCraft.getManager(seller.level().getServer());
        TaxQuote quote = TaxPolicy.quoteForSale(TaxScope.TRANSACTION_AUCTION_BUY, price, seller.getUUID(), eco);
        List<Component> lore = new ArrayList<>();
        lore.add(MenuUiSupport.labeledValue("Amount", String.valueOf(amount), MenuUiSupport.LABEL_PRIMARY_COLOR));
        lore.add(MenuUiSupport.labeledValue("You receive", EconomyCraft.formatMoney(price),
                MenuUiSupport.LABEL_PRIMARY_COLOR));
        if (quote.exempt()) {
            lore.add(MenuUiSupport.hint("Buyers pay " + EconomyCraft.formatMoney(price) + " — tax-free"));
        } else if (quote.amount() > 0) {
            lore.add(MenuUiSupport.hint("The buyer pays " + EconomyCraft.formatMoney(quote.total())));
        }
        return lore;
    }

    private static void createListing(ServerPlayer player, AuctionManager auctions, ItemStack prototype, int amount, long price) {
        createListing(player, auctions, prototype, amount, price, null);
    }

    private static void createListing(ServerPlayer player, AuctionManager auctions, ItemStack prototype, int amount, long price, @org.jetbrains.annotations.Nullable String description) {
        if (auctions.hasReachedLimit(player.getUUID())) {
            EconomySounds.failure(player);
            player.sendSystemMessage(MenuUiSupport.line("You have reached your limit of "
                    + auctions.getEffectiveLimit(player.getUUID()) + " active listing(s).", ChatFormatting.RED));
            open(player, auctions);
            return;
        }
        if (!takeFromInventory(player, prototype, amount)) {
            EconomySounds.failure(player);
            player.sendSystemMessage(MenuUiSupport.line("You no longer have " + amount + "x "
                    + prototype.getHoverName().getString() + ".", ChatFormatting.RED));
            open(player, auctions);
            return;
        }

        AuctionListing listing = new AuctionListing();
        listing.seller = player.getUUID();
        listing.price = price;
        listing.item = prototype.copyWithCount(amount);
        listing.createdAt = System.currentTimeMillis();
        listing.expiresAt = ExpirationUtil.expiresAt(listing.createdAt, EconomyConfig.get().auctionExpirationHours);
        listing.description = description;
        auctions.addListing(listing);

        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        TaxQuote quote = TaxPolicy.quoteForSale(TaxScope.TRANSACTION_AUCTION_BUY, price, player.getUUID(), eco);
        EconomySounds.success(player);
        player.sendSystemMessage(Component.literal("Listed " + amount + "x " + prototype.getHoverName().getString()
                        + " for " + EconomyCraft.formatMoney(price)
                        + buyerTaxSuffix(quote))
                .withStyle(ChatFormatting.GREEN));
        open(player, auctions);
    }

    private static int countHeld(ServerPlayer player, ItemStack prototype) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < ItemPickerUi.MAIN_INVENTORY_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, prototype)) total += stack.getCount();
        }
        return total;
    }

    private static boolean takeFromInventory(ServerPlayer player, ItemStack prototype, int amount) {
        if (countHeld(player, prototype) < amount) return false;
        Inventory inv = player.getInventory();
        int remaining = amount;
        for (int i = 0; i < ItemPickerUi.MAIN_INVENTORY_SLOTS && remaining > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!ItemStack.isSameItemSameComponents(stack, prototype)) continue;
            int take = Math.min(stack.getCount(), remaining);
            stack.shrink(take);
            if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
            remaining -= take;
        }
        return remaining == 0;
    }

    private static void sendStoredMessage(ServerPlayer player) {
        ClickEvent ev = ChatCompat.runCommandEvent("/eco deliveries");
        if (ev != null) {
            player.sendSystemMessage(Component.literal("Item stored: ")
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("[Claim]")
                            .withStyle(s -> s.withUnderlined(true).withColor(ChatFormatting.GREEN).withClickEvent(ev))));
        } else {
            ChatCompat.sendRunCommandTellraw(player, "Item stored: ", "[Claim]", "/eco deliveries");
        }
    }

    private static class AuctionMenu extends CompatMenu {
        private final AuctionManager auctions;
        private final ServerPlayer viewer;
        @Nullable private final String query;
        private SortMode sort;
        private boolean mineOnly;
        private List<AuctionListing> listings;
        private final SimpleContainer container;
        private final int rows;
        private final int itemsPerPage;
        private final int navRowStart;
        private int page;
        private final Runnable listener = this::updatePage;

        AuctionMenu(int id, Inventory inv, AuctionManager auctions, ServerPlayer viewer, int page, @Nullable String query,
                 SortMode sort, boolean mineOnly) {
            this(id, inv, auctions, viewer, page, query, sort, mineOnly, resolveListings(auctions, query, sort, mineOnly, viewer));
        }

        private AuctionMenu(int id, Inventory inv, AuctionManager auctions, ServerPlayer viewer, int page, @Nullable String query,
                         SortMode sort, boolean mineOnly, List<AuctionListing> resolved) {
            super(MenuUiSupport.getMenuType(MenuUiSupport.listMenuRows(resolved.size())), id);
            this.auctions = auctions;
            this.viewer = viewer;
            this.page = page;
            this.query = query;
            this.sort = sort;
            this.mineOnly = mineOnly;
            this.rows = MenuUiSupport.listMenuRows(resolved.size());
            this.itemsPerPage = (rows - 1) * 9;
            this.navRowStart = itemsPerPage;
            this.container = new SimpleContainer(rows * 9);
            this.listings = resolved;
            renderPage();
            auctions.addListener(listener);
            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, rows * 9)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + rows * 18 + 14)) {
                this.addSlot(slot);
            }
        }

        private static List<AuctionListing> resolveListings(AuctionManager auctions, @Nullable String query, SortMode sort,
                                                         boolean mineOnly, ServerPlayer viewer) {
            List<AuctionListing> list = new ArrayList<>(auctions.getListings());
            var server = viewer.level().getServer();
            list.removeIf(l -> MenuUiSupport.resolvePlayerName(server, l.seller) == null);
            if (query != null && !query.isBlank()) {
                list.removeIf(l -> !MenuUiSupport.matchesSearch(l.item, query));
            }
            if (mineOnly) {
                list.removeIf(l -> !viewer.getUUID().equals(l.seller));
            }
            if (sort == SortMode.PRICE_ASC) {
                list.sort(Comparator.comparingLong(l -> l.price));
            } else if (sort == SortMode.PRICE_DESC) {
                list.sort((a, b) -> Long.compare(b.price, a.price));
            }
            return list;
        }

        private void updatePage() {
            List<AuctionListing> updated = resolveListings(auctions, query, sort, mineOnly, viewer);
            if (MenuUiSupport.listMenuRows(updated.size()) != rows) {
                AuctionUi.open(viewer, auctions, 0, query, sort, mineOnly);
                return;
            }
            listings = updated;
            renderPage();
        }

        private void cycleSort() {
            if (mineOnly) {
                mineOnly = false;
                sort = SortMode.DEFAULT;
            } else if (sort == SortMode.DEFAULT) {
                sort = SortMode.PRICE_ASC;
            } else if (sort == SortMode.PRICE_ASC) {
                sort = SortMode.PRICE_DESC;
            } else {
                sort = SortMode.DEFAULT;
                mineOnly = true;
            }
        }

        private void renderPage() {
            container.clearContent();
            int totalPages = MenuUiSupport.totalPages(listings.size(), itemsPerPage);
            page = Math.min(page, totalPages - 1);
            int start = page * itemsPerPage;

            for (int i = 0; i < itemsPerPage; i++) {
                int idx = start + i;
                if (idx >= listings.size()) break;

                AuctionListing l = listings.get(idx);
                ItemStack display = l.item.copy();

                String sellerName = MenuUiSupport.resolvePlayerName(viewer.level().getServer(), l.seller);
                boolean mine = viewer.getUUID().equals(l.seller);
                EconomyManager eco = EconomyCraft.getManager(viewer.level().getServer());
                TaxQuote quote = quoteForBuyer(l, viewer.getUUID(), eco);
                List<Component> lore = new ArrayList<>();
                lore.add(createPriceLore(l.price, quote));
                lore.add(MenuUiSupport.labeledValue("Seller", mine ? "you" : sellerName, MenuUiSupport.LABEL_PRIMARY_COLOR));
                addBuybackLore(lore, l);
                addDescriptionLore(lore, l);
                lore.add(MenuUiSupport.hint(ExpirationUtil.expiresInLabel(l.expiresAt)));
                lore.add(MenuUiSupport.labeledValue("Click", mine ? "Remove listing" : "Buy it", MenuUiSupport.LABEL_SECONDARY_COLOR));
                if (MenuUiSupport.hasContainerContents(l.item)) {
                    lore.add(MenuUiSupport.labeledValue("Ctrl+Q", "Preview contents", MenuUiSupport.LABEL_SECONDARY_COLOR));
                }
                display.set(DataComponents.LORE, new ItemLore(lore));
                container.setItem(i, display);
            }

            if (listings.isEmpty()) {
                container.setItem(Math.min(4, itemsPerPage - 1), MenuUiSupport.button(Items.BOOK, "Nothing for sale",
                        ChatFormatting.YELLOW, MenuUiSupport.hint("Be the first: click \"Sell an item\" below")));
            }

            if (page > 0) container.setItem(navRowStart + 3, MenuUiSupport.prevPageButton());
            if (start + itemsPerPage < listings.size()) container.setItem(navRowStart + 5, MenuUiSupport.nextPageButton());

            container.setItem(navRowStart, MenuUiSupport.createBalanceItem(viewer));

            container.setItem(navRowStart + 1, MenuUiSupport.button(Items.HOPPER, "Sort",
                    MenuUiSupport.LABEL_PRIMARY_COLOR,
                    MenuUiSupport.italicHint("Click to cycle"),
                    MenuUiSupport.toggleOption("Recently Listed", !mineOnly && sort == SortMode.DEFAULT),
                    MenuUiSupport.toggleOption("Lowest Price", !mineOnly && sort == SortMode.PRICE_ASC),
                    MenuUiSupport.toggleOption("Highest Price", !mineOnly && sort == SortMode.PRICE_DESC),
                    MenuUiSupport.toggleOption("Mine Only", mineOnly)));

            container.setItem(navRowStart + 2, MenuUiSupport.button(Items.WRITABLE_BOOK, "Sell an item",
                    ChatFormatting.GREEN, MenuUiSupport.hint("Pick an item, set a price, done.")));

            container.setItem(navRowStart + 4, MenuUiSupport.pageIndicator(page, totalPages));

            if (EconomyPermissions.checkCommand(viewer, Nodes.COMMAND_DELIVERIES)) {
                container.setItem(navRowStart + 6, MenuUiSupport.button(Items.ENDER_CHEST, "Deliveries",
                        ChatFormatting.LIGHT_PURPLE, MenuUiSupport.hint("Items waiting to be collected")));
            }

            container.setItem(navRowStart + 7, MenuUiSupport.button(Items.NETHER_STAR, "Main menu", ChatFormatting.YELLOW));

            boolean searching = query != null && !query.isBlank();
            container.setItem(navRowStart + 8, searching
                    ? MenuUiSupport.clearSearchButton(query)
                    : MenuUiSupport.searchButton());

            MenuUiSupport.fillFooter(container);
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind == ClickKind.THROW && slot >= 0 && slot < navRowStart) {
                int index = page * itemsPerPage + slot;
                if (index < listings.size() && MenuUiSupport.hasContainerContents(listings.get(index).item)) {
                    ContainerPreviewUi.open(viewer, listings.get(index).item,
                            () -> AuctionUi.open(viewer, auctions, page, query, sort, mineOnly));
                }
                return true;
            }
            if (kind != ClickKind.PICKUP) return false;

            if (slot >= 0 && slot < navRowStart) {
                int index = page * itemsPerPage + slot;
                if (index < listings.size()) {
                    AuctionListing listing = listings.get(index);
                    if (listing.seller.equals(viewer.getUUID())) {
                        EconomySounds.click(viewer);
                        openRemove(viewer, auctions, listing, query, sort, mineOnly);
                    } else {
                        // No affordability gate here: the confirm screen is also where the
                        // "Offer a price" button lives, and buyers who can't afford the sticker
                        // price are its main audience. Buying itself still fails gracefully
                        // with CANT_AFFORD inside AuctionTrade.purchase.
                        EconomySounds.click(viewer);
                        openConfirm(viewer, auctions, listing, query, sort, mineOnly);
                    }
                    return true;
                }
            }
            if (slot == navRowStart + 3 && page > 0) { EconomySounds.page(viewer); page--; updatePage(); return true; }
            if (slot == navRowStart + 5 && (page + 1) * itemsPerPage < listings.size()) { EconomySounds.page(viewer); page++; updatePage(); return true; }
            if (slot == navRowStart + 1) {
                EconomySounds.click(viewer);
                cycleSort();
                page = 0;
                updatePage();
                return true;
            }
            if (slot == navRowStart + 2) {
                EconomySounds.click(viewer);
                viewer.closeContainer();
                startListing(viewer, auctions);
                return true;
            }
            if (slot == navRowStart + 6) {
                if (EconomyPermissions.checkCommand(viewer, Nodes.COMMAND_DELIVERIES)) {
                    EconomySounds.click(viewer);
                    viewer.closeContainer();
                    OrdersUi.openClaims(viewer, EconomyCraft.getManager(viewer.level().getServer()));
                }
                return true;
            }
            if (slot == navRowStart + 7) {
                EconomySounds.click(viewer);
                viewer.closeContainer();
                HubUi.open(viewer);
                return true;
            }
            if (slot == navRowStart + 8) {
                EconomySounds.click(viewer);
                if (query != null && !query.isBlank()) {
                    AuctionUi.open(viewer, auctions, 0, null, sort, mineOnly);
                } else {
                    TextInputUi.openSearch(viewer, "Search Auction House", (p, q) -> AuctionUi.open(p, auctions, 0, q, sort, mineOnly));
                }
                return true;
            }
            return false;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            auctions.removeListener(listener);
        }
    }

    private static class ConfirmMenu extends CompatMenu {
        private final AuctionManager auctions;
        private final AuctionListing listing;
        @Nullable private final String query;
        private final SortMode sort;
        private final boolean mineOnly;
        private final SimpleContainer container = new SimpleContainer(9);

        ConfirmMenu(int id, Inventory inv, AuctionManager auctions, AuctionListing listing, ServerPlayer viewer,
                    @Nullable String query, SortMode sort, boolean mineOnly) {
            super(MenuType.GENERIC_9x1, id);
            this.auctions = auctions;
            this.listing = listing;
            this.query = query;
            this.sort = sort;
            this.mineOnly = mineOnly;

            container.setItem(MenuUiSupport.ROW_CONFIRM, MenuUiSupport.confirmButton("Confirm"));

            String sellerName = MenuUiSupport.resolvePlayerName(viewer.level().getServer(), listing.seller);

            ItemStack item = listing.item.copy();
            EconomyManager eco = EconomyCraft.getManager(viewer.level().getServer());
            TaxQuote quote = quoteForBuyer(listing, viewer.getUUID(), eco);
            List<Component> lore = new ArrayList<>();
            lore.add(createPriceLore(listing.price, quote));
            lore.add(MenuUiSupport.labeledValue("Seller", sellerName, MenuUiSupport.LABEL_PRIMARY_COLOR));
            addBuybackLore(lore, listing);
            addDescriptionLore(lore, listing);
            lore.add(MenuUiSupport.hint(ExpirationUtil.expiresInLabel(listing.expiresAt)));
            if (!canAfford(viewer, listing)) {
                lore.add(MenuUiSupport.line("You can't afford this — but you can offer a price.",
                        ChatFormatting.RED));
            }
            if (MenuUiSupport.hasContainerContents(listing.item)) {
                lore.add(MenuUiSupport.labeledValue("Ctrl+Q", "Preview contents", MenuUiSupport.LABEL_SECONDARY_COLOR));
            }
            item.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(MenuUiSupport.ROW_SUBJECT, item);

            container.setItem(MenuUiSupport.ROW_CANCEL, MenuUiSupport.cancelButton());
            if (NegotiationEvents.canNegotiateAuction(listing)) {
                container.setItem(OFFER_SLOT, MenuUiSupport.button(Items.PAPER, "Offer a price",
                        ChatFormatting.GOLD,
                        MenuUiSupport.hint("Suggest a different price"),
                        MenuUiSupport.hint("Binding: the seller can charge"),
                        MenuUiSupport.hint("you immediately on accept.")));
            }
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
            if (kind == ClickKind.THROW && slot == MenuUiSupport.ROW_SUBJECT && MenuUiSupport.hasContainerContents(listing.item)) {
                ContainerPreviewUi.open((ServerPlayer) player, listing.item,
                        () -> AuctionUi.openConfirm((ServerPlayer) player, auctions, listing, query, sort, mineOnly));
                return true;
            }
            if (kind != ClickKind.PICKUP) return false;

            if (slot == MenuUiSupport.ROW_CONFIRM) {
                ServerPlayer sp = (ServerPlayer) player;
                var server = sp.level().getServer();
                EconomyManager eco = EconomyCraft.getManager(server);

                AuctionTrade.PurchaseResult result = AuctionTrade.purchase(eco, sp, listing.id);
                switch (result.status()) {
                    case OK -> {
                        EconomySounds.success(sp);
                        if (result.stored()) {
                            sendStoredMessage(sp);
                        } else {
                            String sellerName = MenuUiSupport.resolvePlayerName(server, result.seller());
                            sp.sendSystemMessage(
                                    Component.literal("Purchased " + result.item().getCount() + "x "
                                                    + result.item().getHoverName().getString() + " from " + sellerName +
                                                    " for " + EconomyCraft.formatMoney(result.totalPaid()))
                                            .withStyle(ChatFormatting.GREEN));
                        }
                    }
                    case OWN_LISTING -> fail(sp, "You cannot buy your own listing");
                    case CANT_AFFORD -> fail(sp, "Not enough balance");
                    case SELLER_CANT_RECEIVE -> fail(sp, "Seller cannot receive this payment");
                    default -> fail(sp, "Listing no longer available");
                }
                player.closeContainer();
                AuctionUi.open(sp, auctions, 0, query, sort, mineOnly);
                return true;
            }

            if (slot == MenuUiSupport.ROW_CANCEL) {
                EconomySounds.click((ServerPlayer) player);
                player.closeContainer();
                AuctionUi.open((ServerPlayer) player, auctions, 0, query, sort, mineOnly);
                return true;
            }

            if (slot == OFFER_SLOT && NegotiationEvents.canNegotiateAuction(listing)) {
                ServerPlayer sp = (ServerPlayer) player;
                AuctionListing current = auctions.getListing(listing.id);
                if (current == null || !NegotiationEvents.canNegotiateAuction(current)) {
                    fail(sp, "Listing no longer available");
                    sp.closeContainer();
                    AuctionUi.open(sp, auctions, 0, query, sort, mineOnly);
                    return true;
                }
                if (current.seller.equals(sp.getUUID())) {
                    fail(sp, "You cannot offer on your own listing");
                    sp.closeContainer();
                    AuctionUi.open(sp, auctions, 0, query, sort, mineOnly);
                    return true;
                }
                EconomySounds.click(sp);
                ItemStack subject = current.item.copy();
                NumberInputUi.openMoney(sp, "Offer a price", subject, "Offer", current.price,
                        1, EconomyManager.MAX, "Send offer",
                        offerPrice -> List.of(
                                MenuUiSupport.labeledValue("Listed at",
                                        EconomyCraft.formatMoney(current.price),
                                        MenuUiSupport.LABEL_PRIMARY_COLOR),
                                MenuUiSupport.hint("The seller is notified, not committed.")),
                        (p, offerPrice) -> submitOffer(p, auctions, current.id, offerPrice, query, sort, mineOnly),
                        p -> AuctionUi.openConfirm(p, auctions, listing, query, sort, mineOnly));
                return true;
            }
            return false;
        }

        private static void fail(ServerPlayer player, String message) {
            EconomySounds.failure(player);
            player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        }

        private static void submitOffer(ServerPlayer player, AuctionManager auctions, int listingId,
                                        long offerPrice, @Nullable String query, SortMode sort, boolean mineOnly) {
            AuctionListing current = auctions.getListing(listingId);
            if (current == null || !NegotiationEvents.canNegotiateAuction(current)) {
                fail(player, "Listing no longer available");
                player.closeContainer();
                AuctionUi.open(player, auctions, 0, query, sort, mineOnly);
                return;
            }
            if (current.seller.equals(player.getUUID())) {
                fail(player, "You cannot offer on your own listing");
                player.closeContainer();
                AuctionUi.open(player, auctions, 0, query, sort, mineOnly);
                return;
            }
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            eco.getNegotiations().makeOffer(NegotiationStore.Kind.AH, listingId, player.getUUID(), offerPrice);
            NegotiationEvents.notifyNewOffer(eco, current.seller, player.getUUID(),
                    EconomyCraft.describeItem(current.item.getCount(), current.item.getHoverName().getString()),
                    offerPrice, listingId, true);
            EconomySounds.success(player);
            player.sendSystemMessage(Component.literal("Offered " + EconomyCraft.formatMoney(offerPrice)
                            + " for " + current.item.getHoverName().getString()
                            + " — the seller was notified.")
                    .withStyle(ChatFormatting.GREEN));
            player.closeContainer();
            AuctionUi.open(player, auctions, 0, query, sort, mineOnly);
        }
    }

    private static class RemoveMenu extends CompatMenu {
        private final AuctionManager auctions;
        private final AuctionListing listing;
        private final ServerPlayer viewer;
        @Nullable private final String query;
        private final SortMode sort;
        private final boolean mineOnly;
        private final SimpleContainer container = new SimpleContainer(9);

        RemoveMenu(int id, Inventory inv, AuctionManager auctions, AuctionListing listing, ServerPlayer viewer,
                   @Nullable String query, SortMode sort, boolean mineOnly) {
            super(MenuType.GENERIC_9x1, id);
            this.auctions = auctions;
            this.listing = listing;
            this.viewer = viewer;
            this.query = query;
            this.sort = sort;
            this.mineOnly = mineOnly;

            container.setItem(MenuUiSupport.ROW_CONFIRM, MenuUiSupport.confirmButton("Confirm"));

            ItemStack item = listing.item.copy();
            EconomyManager eco = EconomyCraft.getManager(viewer.level().getServer());
            TaxQuote quote = TaxPolicy.quoteForSale(TaxScope.TRANSACTION_AUCTION_BUY, listing.price,
                    listing.seller, eco);
            List<Component> lore = new ArrayList<>();
            lore.add(createPriceLore(listing.price, quote));
            lore.add(MenuUiSupport.labeledValue("Seller", "you", MenuUiSupport.LABEL_PRIMARY_COLOR));
            addDescriptionLore(lore, listing);
            lore.add(MenuUiSupport.hint(ExpirationUtil.expiresInLabel(listing.expiresAt)));
            lore.add(MenuUiSupport.line("This will remove the listing", ChatFormatting.RED));
            item.set(DataComponents.LORE, new ItemLore(lore));
            container.setItem(MenuUiSupport.ROW_SUBJECT, item);

            container.setItem(MenuUiSupport.ROW_CANCEL, MenuUiSupport.cancelButton());
            if (NegotiationEvents.canNegotiateAuction(listing)) {
                container.setItem(EDIT_PRICE_SLOT, MenuUiSupport.button(Items.NAME_TAG, "Edit price",
                        ChatFormatting.AQUA, MenuUiSupport.hint("Reprice without relisting")));
                container.setItem(EDIT_DESC_SLOT, MenuUiSupport.button(Items.WRITABLE_BOOK, "Edit note",
                        ChatFormatting.AQUA, MenuUiSupport.hint("Update or clear note")));
                int offerCount = eco.getNegotiations().countFor(NegotiationStore.Kind.AH, listing.id);
                if (offerCount > 0) {
                    container.setItem(OFFERS_SLOT, MenuUiSupport.button(Items.BOOK,
                            "Offers (" + offerCount + ")", ChatFormatting.GOLD,
                            MenuUiSupport.hint("Review buyer offers")));
                }
            }
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
                ServerPlayer sp = (ServerPlayer) player;
                AuctionTrade.CancelResult result = AuctionTrade.cancel(auctions, sp, listing.id);
                switch (result.status()) {
                    case OK -> {
                        EconomySounds.itemPickedUp(viewer);
                        if (result.stored()) {
                            sendStoredMessage(sp);
                        } else {
                            viewer.sendSystemMessage(Component.literal("Listing removed"));
                        }
                    }
                    case NOT_OWNER -> {
                        EconomySounds.failure(viewer);
                        viewer.sendSystemMessage(Component.literal("You don't own this listing").withStyle(ChatFormatting.RED));
                    }
                    default -> {
                        EconomySounds.failure(viewer);
                        viewer.sendSystemMessage(Component.literal("Listing no longer available"));
                    }
                }
                player.closeContainer();
                AuctionUi.open(sp, auctions, 0, query, sort, mineOnly);
                return true;
            }
            if (slot == MenuUiSupport.ROW_CANCEL) {
                EconomySounds.click((ServerPlayer) player);
                player.closeContainer();
                AuctionUi.open((ServerPlayer) player, auctions, 0, query, sort, mineOnly);
                return true;
            }
            if (slot == EDIT_PRICE_SLOT && NegotiationEvents.canNegotiateAuction(listing)) {
                ServerPlayer sp = (ServerPlayer) player;
                AuctionListing current = auctions.getListing(listing.id);
                if (current == null || !current.seller.equals(sp.getUUID())) {
                    EconomySounds.failure(sp);
                    sp.sendSystemMessage(Component.literal("Listing no longer available")
                            .withStyle(ChatFormatting.RED));
                    sp.closeContainer();
                    AuctionUi.open(sp, auctions, 0, query, sort, mineOnly);
                    return true;
                }
                EconomySounds.click(sp);
                NumberInputUi.openMoney(sp, "Edit price", current.item.copy(), "Price", current.price,
                        1, EconomyManager.MAX, "Confirm and update",
                        newPrice -> listingLore(sp, current.item.getCount(), newPrice),
                        (p, newPrice) -> applyPriceEdit(p, auctions, current.id, newPrice, query, sort, mineOnly),
                        p -> openRemove(p, auctions, listing, query, sort, mineOnly));
                return true;
            }
            if (slot == EDIT_DESC_SLOT && NegotiationEvents.canNegotiateAuction(listing)) {
                ServerPlayer sp = (ServerPlayer) player;
                AuctionListing current = auctions.getListing(listing.id);
                if (current == null || !current.seller.equals(sp.getUUID())) {
                    EconomySounds.failure(sp);
                    sp.sendSystemMessage(Component.literal("Listing no longer available")
                            .withStyle(ChatFormatting.RED));
                    sp.closeContainer();
                    AuctionUi.open(sp, auctions, 0, query, sort, mineOnly);
                    return true;
                }
                EconomySounds.click(sp);
                TextInputUi.open(sp, "Edit note/description", current.description == null ? "" : current.description,
                        Items.NAME_TAG, "Note: ", "Optional note (blank to clear)", true,
                        (p, newDesc) -> applyDescriptionEdit(p, auctions, current.id, newDesc, query, sort, mineOnly),
                        p -> openRemove(p, auctions, listing, query, sort, mineOnly));
                return true;
            }
            if (slot == OFFERS_SLOT && NegotiationEvents.canNegotiateAuction(listing)) {
                EconomySounds.click((ServerPlayer) player);
                openOffers((ServerPlayer) player, auctions, listing.id, query, sort, mineOnly);
                return true;
            }
            return false;
        }
    }

    private static void applyDescriptionEdit(ServerPlayer player, AuctionManager auctions, int listingId,
                                             String newDesc, @Nullable String query, SortMode sort, boolean mineOnly) {
        String desc = (newDesc == null || newDesc.isBlank()) ? null : newDesc.trim();
        if (desc != null && desc.length() > 100) {
            desc = desc.substring(0, 100);
        }
        if (!auctions.setDescription(listingId, player.getUUID(), desc)) {
            EconomySounds.failure(player);
            player.sendSystemMessage(Component.literal("Listing no longer available")
                    .withStyle(ChatFormatting.RED));
            player.closeContainer();
            AuctionUi.open(player, auctions, 0, query, sort, mineOnly);
            return;
        }
        EconomySounds.success(player);
        if (desc == null) {
            player.sendSystemMessage(Component.literal("Cleared listing note.")
                    .withStyle(ChatFormatting.GREEN));
        } else {
            Component msg = Component.literal("Updated listing note: ").withStyle(ChatFormatting.GREEN)
                    .append(com.reazip.economycraft.motd.MotdFormatter.formatLine(desc));
            player.sendSystemMessage(msg);
        }
        player.closeContainer();
        AuctionUi.open(player, auctions, 0, query, sort, mineOnly);
    }

    private static void applyPriceEdit(ServerPlayer player, AuctionManager auctions, int listingId,
                                       long newPrice, @Nullable String query, SortMode sort, boolean mineOnly) {
        if (!auctions.setPrice(listingId, player.getUUID(), newPrice)) {
            EconomySounds.failure(player);
            player.sendSystemMessage(Component.literal("Listing no longer available")
                    .withStyle(ChatFormatting.RED));
            player.closeContainer();
            AuctionUi.open(player, auctions, 0, query, sort, mineOnly);
            return;
        }
        AuctionListing current = auctions.getListing(listingId);
        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        String desc = current == null
                ? "listing #" + listingId
                : EconomyCraft.describeItem(current.item.getCount(), current.item.getHoverName().getString());
        for (NegotiationStore.Offer offer : eco.getNegotiations().offersFor(NegotiationStore.Kind.AH, listingId)) {
            NegotiationEvents.notifyRepriced(eco, offer.proposer(), desc, newPrice);
        }
        EconomySounds.success(player);
        player.sendSystemMessage(Component.literal("Repriced to " + EconomyCraft.formatMoney(newPrice))
                .withStyle(ChatFormatting.GREEN));
        player.closeContainer();
        AuctionUi.open(player, auctions, 0, query, sort, mineOnly);
    }

    /**
     * Entry point for the offers hub: the review screen for one listing, with no list-screen
     * context behind it, so Back falls out to the auction house rather than to the previous
     * page. Deliberately no permission check — the caller (the hub) already gated on its own
     * node, and this only ever shows offers on the viewer's own listing.
     */
    public static void openOffersFor(ServerPlayer player, int listingId) {
        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        openOffers(player, eco.getAuctions(), listingId, null, SortMode.DEFAULT, false);
    }

    private static void openOffers(ServerPlayer player, AuctionManager auctions, int listingId,
                                   @Nullable String query, SortMode sort, boolean mineOnly) {
        MenuUiSupport.openMenu(player, "Offers", (id, inv) ->
                new OffersMenu(id, inv, auctions, player, listingId, query, sort, mineOnly));
    }

    private static class OffersMenu extends CompatMenu {
        private final AuctionManager auctions;
        private final ServerPlayer viewer;
        private final int listingId;
        @Nullable private final String query;
        private final SortMode sort;
        private final boolean mineOnly;
        private final List<NegotiationStore.Offer> offers;
        private final SimpleContainer container;
        private final int rows;
        private final int itemsPerPage;
        private final int navRowStart;

        OffersMenu(int id, Inventory inv, AuctionManager auctions, ServerPlayer viewer, int listingId,
                   @Nullable String query, SortMode sort, boolean mineOnly) {
            super(MenuUiSupport.getMenuType(MenuUiSupport.listMenuRows(
                    Math.max(1, EconomyCraft.getManager(viewer.level().getServer())
                            .getNegotiations().countFor(NegotiationStore.Kind.AH, listingId)))), id);
            this.auctions = auctions;
            this.viewer = viewer;
            this.listingId = listingId;
            this.query = query;
            this.sort = sort;
            this.mineOnly = mineOnly;
            EconomyManager eco = EconomyCraft.getManager(viewer.level().getServer());
            List<NegotiationStore.Offer> all =
                    eco.getNegotiations().offersFor(NegotiationStore.Kind.AH, listingId);
            List<NegotiationStore.Offer> visible = new ArrayList<>();
            for (NegotiationStore.Offer offer : all) {
                if (MenuUiSupport.resolvePlayerName(viewer.level().getServer(), offer.proposer()) != null) {
                    visible.add(offer);
                }
            }
            this.offers = visible;
            this.rows = MenuUiSupport.listMenuRows(Math.max(1, offers.size()));
            this.itemsPerPage = (rows - 1) * 9;
            this.navRowStart = itemsPerPage;
            this.container = new SimpleContainer(rows * 9);
            renderPage();
            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, rows * 9)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + rows * 18 + 14)) {
                this.addSlot(slot);
            }
        }

        private void renderPage() {
            container.clearContent();
            AuctionListing listing = auctions.getListing(listingId);
            for (int i = 0; i < itemsPerPage && i < offers.size(); i++) {
                NegotiationStore.Offer offer = offers.get(i);
                String name = MenuUiSupport.resolvePlayerName(viewer.level().getServer(), offer.proposer());
                ItemStack row = new ItemStack(Items.PAPER);
                List<Component> lore = new ArrayList<>();
                lore.add(MenuUiSupport.labeledValue("Offer",
                        EconomyCraft.formatMoney(offer.price()), MenuUiSupport.LABEL_PRIMARY_COLOR));
                if (listing != null) {
                    lore.add(MenuUiSupport.labeledValue("Listed at",
                            EconomyCraft.formatMoney(listing.price), MenuUiSupport.LABEL_PRIMARY_COLOR));
                }
                lore.add(MenuUiSupport.labeledValue("Click", "Accept or decline",
                        MenuUiSupport.LABEL_SECONDARY_COLOR));
                row.set(DataComponents.CUSTOM_NAME, Component.literal(name == null ? "?" : name)
                        .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.YELLOW)));
                row.set(DataComponents.LORE, new ItemLore(lore));
                container.setItem(i, row);
            }
            if (offers.isEmpty()) {
                container.setItem(4, MenuUiSupport.button(Items.BOOK, "No offers",
                        ChatFormatting.YELLOW, MenuUiSupport.hint("New offers appear here")));
            }
            container.setItem(navRowStart + 4, MenuUiSupport.button(Items.BARRIER, "Back",
                    ChatFormatting.RED));
            MenuUiSupport.fillFooter(container);
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP) return false;
            if (slot >= 0 && slot < navRowStart && slot < offers.size()) {
                EconomySounds.click(viewer);
                NegotiationStore.Offer offer = offers.get(slot);
                MenuUiSupport.openMenu(viewer, "Offer", (id, inv) ->
                        new OfferDecisionMenu(id, inv, auctions, viewer, listingId, offer,
                                query, sort, mineOnly));
                return true;
            }
            if (slot == navRowStart + 4) {
                EconomySounds.click(viewer);
                AuctionListing listing = auctions.getListing(listingId);
                viewer.closeContainer();
                if (listing != null && listing.seller.equals(viewer.getUUID())) {
                    openRemove(viewer, auctions, listing, query, sort, mineOnly);
                } else {
                    AuctionUi.open(viewer, auctions, 0, query, sort, mineOnly);
                }
                return true;
            }
            return false;
        }
    }

    private static class OfferDecisionMenu extends CompatMenu {
        private static final int BACK_SLOT = 0;
        private final AuctionManager auctions;
        private final ServerPlayer viewer;
        private final int listingId;
        private final NegotiationStore.Offer offer;
        @Nullable private final String query;
        private final SortMode sort;
        private final boolean mineOnly;
        private final SimpleContainer container = new SimpleContainer(9);

        OfferDecisionMenu(int id, Inventory inv, AuctionManager auctions, ServerPlayer viewer, int listingId,
                          NegotiationStore.Offer offer, @Nullable String query, SortMode sort, boolean mineOnly) {
            super(MenuType.GENERIC_9x1, id);
            this.auctions = auctions;
            this.viewer = viewer;
            this.listingId = listingId;
            this.offer = offer;
            this.query = query;
            this.sort = sort;
            this.mineOnly = mineOnly;

            container.setItem(BACK_SLOT, MenuUiSupport.backButton());
            container.setItem(MenuUiSupport.ROW_CANCEL, MenuUiSupport.button(
                    ItemsCompat.redStainedGlassPane(), "Decline", ChatFormatting.DARK_RED));

            String name = MenuUiSupport.resolvePlayerName(viewer.level().getServer(), offer.proposer());
            ItemStack subject = new ItemStack(Items.PAPER);
            subject.set(DataComponents.CUSTOM_NAME, Component.literal(
                            EconomyCraft.formatMoney(offer.price()) + " from " + (name == null ? "?" : name))
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.YELLOW)));
            subject.set(DataComponents.LORE, new ItemLore(List.of(
                    MenuUiSupport.hint("Accept & sell moves the item"),
                    MenuUiSupport.hint("immediately — even if they are offline."))));
            container.setItem(MenuUiSupport.ROW_SUBJECT, subject);

            container.setItem(MenuUiSupport.ROW_CONFIRM, MenuUiSupport.confirmButton("Accept & sell"));
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
                accept();
                return true;
            }
            if (slot == MenuUiSupport.ROW_CANCEL) {
                decline();
                return true;
            }
            if (slot == BACK_SLOT) {
                EconomySounds.click(viewer);
                openOffers(viewer, auctions, listingId, query, sort, mineOnly);
                return true;
            }
            return false;
        }

        private EconomyManager eco() {
            return EconomyCraft.getManager(viewer.level().getServer());
        }

        private String describe(AuctionListing listing) {
            return EconomyCraft.describeItem(listing.item.getCount(),
                    listing.item.getHoverName().getString());
        }

        private void accept() {
            AuctionTrade.AcceptResult result =
                    AuctionTrade.acceptOffer(eco(), viewer, listingId, offer.proposer());
            switch (result.status()) {
                case OK -> {
                    String desc = EconomyCraft.describeItem(result.item().getCount(),
                            result.item().getHoverName().getString());
                    String buyerName = NegotiationEvents.displayName(eco().getServer(), result.buyer());
                    List<NegotiationStore.Offer> rest = eco().getNegotiations()
                            .removeForTarget(NegotiationStore.Kind.AH, listingId);
                    NegotiationEvents.notifySoldToBuyer(eco(), result.buyer(), desc,
                            result.totalPaid(), result.stored());
                    for (NegotiationStore.Offer other : rest) {
                        if (!other.proposer().equals(result.buyer())) {
                            NegotiationEvents.notifyDeclined(eco(), other.proposer(), desc, other.price());
                        }
                    }
                    EconomySounds.success(viewer);
                    viewer.sendSystemMessage(Component.literal("Sold " + desc + " to " + buyerName
                                    + " for " + EconomyCraft.formatMoney(result.price()) + ".")
                            .withStyle(ChatFormatting.GREEN));
                    viewer.closeContainer();
                    AuctionUi.open(viewer, auctions, 0, query, sort, mineOnly);
                }
                case CANT_AFFORD -> {
                    String name = NegotiationEvents.displayName(eco().getServer(), offer.proposer());
                    failAndReview(name + " can't afford it right now — the offer was kept.");
                }
                case SELLER_CANT_RECEIVE ->
                        failAndReview("Your balance can't take this sale (maximum reached).");
                default -> fail("Listing no longer available");
            }
        }

        private void failAndReview(String message) {
            EconomySounds.failure(viewer);
            viewer.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
            viewer.closeContainer();
            openOffers(viewer, auctions, listingId, query, sort, mineOnly);
        }

        private void decline() {
            NegotiationStore.Offer removed = eco().getNegotiations()
                    .removeOffer(NegotiationStore.Kind.AH, listingId, offer.proposer());
            AuctionListing listing = auctions.getListing(listingId);
            String desc = listing == null ? "listing #" + listingId : describe(listing);
            if (removed != null) {
                NegotiationEvents.notifyDeclined(eco(), removed.proposer(), desc, removed.price());
                EconomySounds.success(viewer);
                viewer.sendSystemMessage(Component.literal("Offer declined.")
                        .withStyle(ChatFormatting.GREEN));
            } else {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("Offer no longer available.")
                        .withStyle(ChatFormatting.RED));
            }
            viewer.closeContainer();
            openOffers(viewer, auctions, listingId, query, sort, mineOnly);
        }

        private void fail(String message) {
            EconomySounds.failure(viewer);
            viewer.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
            viewer.closeContainer();
            AuctionUi.open(viewer, auctions, 0, query, sort, mineOnly);
        }
    }
}

package com.reazip.economycraft.shop;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.HubUi;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.SellService;
import com.reazip.economycraft.admin.AdminShopUi;
import com.reazip.economycraft.quests.BuybackTransfers;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.quests.QuestLogic;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.ContainerPreviewUi;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomyPermissions.Nodes;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.MenuUiSupport;
import com.reazip.economycraft.util.SortMode;
import com.reazip.economycraft.util.TextInputUi;
import com.reazip.economycraft.util.TransactionItems;
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

public final class ShopUi {
    private static final Component STORED_MSG = Component.literal("Item stored: ")
            .withStyle(ChatFormatting.YELLOW);

    private ShopUi() {}

    public static void open(ServerPlayer player, EconomyManager eco) {
        open(player, eco, null);
    }

    public static void open(ServerPlayer player, EconomyManager eco, @Nullable String category) {
        if (!MenuUiSupport.checkOrDeny(player, EconomyPermissions.checkCommand(player, Nodes.COMMAND_SHOP))) return;
        // The persisted ledger is authoritative. Re-read it before building the category or item
        // list so a stale in-memory copy cannot make fulfilled quest stock disappear from /shop.
        eco.getQuestStock().reload();
        if (category == null || category.isBlank()) {
            openRoot(player, eco);
            return;
        }

        PriceRegistry prices = eco.getPrices();
        String cat = category.trim();
        if (!prices.isCategoryEnabled(cat) && !hasStockInCategory(eco, cat)) {
            openRoot(player, eco);
            return;
        }
        if (cat.contains(".")) {
            openItems(player, eco, cat);
            return;
        }

        List<String> subs = stockedSubcategories(eco, cat);
        if (!subs.isEmpty()) {
            openSubcategories(player, eco, cat);
            return;
        }

        openItems(player, eco, cat);
    }

    public static void openSearch(ServerPlayer player, EconomyManager eco, String query) {
        if (!MenuUiSupport.checkOrDeny(player, EconomyPermissions.checkCommand(player, Nodes.COMMAND_SHOP))) return;
        openSearchResults(player, eco, null, query, 0, SortMode.DEFAULT);
    }

    private static void openSearchResults(ServerPlayer player, EconomyManager eco, @Nullable String category,
                                          String query, int page, SortMode sort) {
        eco.getQuestStock().reload();
        MenuUiSupport.openMenu(player, "Search: " + query, (id, inv) ->
                new ItemMenu(id, inv, eco, category, null, query, page, player, sort));
    }

    private static void openRoot(ServerPlayer player, EconomyManager eco) {
        MenuUiSupport.openMenu(player, "Shop", (id, inv) -> new CategoryMenu(id, inv, eco, player));
    }

    private static void openSubcategories(ServerPlayer player, EconomyManager eco, String topCategory) {
        MenuUiSupport.openMenu(player, ShopDisplay.getCategoryName(eco.getPrices(), topCategory, topCategory), (id, inv) ->
                new SubcategoryMenu(id, inv, eco, topCategory, player));
    }

    private static void openItems(ServerPlayer player, EconomyManager eco, String category) {
        openItems(player, eco, category, null, 0, SortMode.DEFAULT);
    }

    private static void openItems(ServerPlayer player, EconomyManager eco, String category, @Nullable String displayTitle) {
        openItems(player, eco, category, displayTitle, 0, SortMode.DEFAULT);
    }

    private static void openItems(ServerPlayer player, EconomyManager eco, String category,
                                  @Nullable String displayTitle, int page, SortMode sort) {
        String title = ShopDisplay.getCategoryName(eco.getPrices(), category,
                displayTitle != null ? displayTitle : category);
        MenuUiSupport.openMenu(player, title, (id, inv) ->
                new ItemMenu(id, inv, eco, category, displayTitle, null, page, player, sort));
    }

    private static boolean stockShopEnabled(EconomyManager eco) {
        var quests = EconomyConfig.get().quests;
        return EconomyConfig.get().shop.enabled && quests != null && quests.enabled
                && quests.buyback != null && quests.buyback.enabled;
    }

    private static boolean hasStockInCategory(EconomyManager eco, String category) {
        if (!stockShopEnabled(eco)) return false;
        String prefix = category == null ? "" : category.toLowerCase(java.util.Locale.ROOT);
        return eco.getPrices().allEntries().stream().anyMatch(entry -> {
            String entryCategory = entry.category() == null ? "" : entry.category().toLowerCase(java.util.Locale.ROOT);
            return (prefix.isBlank() || entryCategory.equals(prefix) || entryCategory.startsWith(prefix + "."))
                    && eco.getQuestStock().get(entry.key()) > 0;
        });
    }

    private static List<String> stockedSubcategories(EconomyManager eco, String topCategory) {
        return eco.getPrices().allSubcategories(topCategory).stream()
                .filter(sub -> hasStockInCategory(eco, topCategory + "." + sub)).toList();
    }

    private static long stockUnitPrice(EconomyManager eco, PriceRegistry.PriceEntry entry) {
        return eco.getQuests().marketUnit(eco, entry, System.currentTimeMillis());
    }

    private static class CategoryMenu extends CompatMenu {
        private final EconomyManager eco;
        private final PriceRegistry prices;
        private final ServerPlayer viewer;
        private List<String> categories = new ArrayList<>();
        private final SimpleContainer container;
        private final int itemsPerPage = 45;
        private final int navRowStart = 45;
        private final int[] slotToIndex = new int[54];
        private int page;

        CategoryMenu(int id, Inventory inv, EconomyManager eco, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x6, id);
            this.eco = eco;
            this.viewer = viewer;
            this.prices = eco.getPrices();

            refreshCategories();
            this.container = new SimpleContainer(54);
            setupSlots(inv);
            updatePage();
        }

        private void refreshCategories() {
            categories = new ArrayList<>();
            for (String cat : prices.allTopCategories()) {
                if (hasStockInCategory(eco, cat)) categories.add(cat);
            }
        }

        private void setupSlots(Inventory inv) {
            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, 54)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + 6 * 18 + 14)) {
                this.addSlot(slot);
            }
        }

        private void updatePage() {
            container.clearContent();
            java.util.Arrays.fill(slotToIndex, -1);
            int start = page * itemsPerPage;
            int totalPages = MenuUiSupport.totalPages(categories.size(), itemsPerPage);

            if (categories.isEmpty()) {
                boolean enabled = stockShopEnabled(eco);
                container.setItem(22, MenuUiSupport.button(Items.BARRIER,
                        enabled ? "No quest stock available" : "Quest stock shop is disabled",
                        ChatFormatting.GRAY,
                        MenuUiSupport.hint(enabled
                                ? "Items from completed server quests appear here."
                                : "An administrator can enable the quest stock shop.")));
            }

            for (int i = 0; i < itemsPerPage; i++) {
                int idx = start + i;
                if (idx >= categories.size()) break;

                String cat = categories.get(idx);
                ItemStack icon = ShopDisplay.createCategoryIcon(cat, cat, prices, viewer, false);
                if (icon.isEmpty()) continue;

                icon.set(DataComponents.CUSTOM_NAME, Component.literal(ShopDisplay.getCategoryName(prices, cat, cat))
                        .withStyle(s -> s.withItalic(false)
                                .withColor(ShopDisplay.getCategoryColor(prices, cat, cat)).withBold(true)));
                icon.set(DataComponents.LORE, new ItemLore(List.of(MenuUiSupport.hint("Click to view items"))));
                int slot = ShopDisplay.STAR_SLOT_ORDER.get(i);
                container.setItem(slot, icon);
                slotToIndex[slot] = idx;
            }

            if (page > 0) container.setItem(navRowStart + 3, MenuUiSupport.prevPageButton());
            if (start + itemsPerPage < categories.size()) container.setItem(navRowStart + 5, MenuUiSupport.nextPageButton());

            container.setItem(navRowStart, MenuUiSupport.createBalanceItem(viewer));
            container.setItem(navRowStart + 4, MenuUiSupport.pageIndicator(page, totalPages));

            if (EconomyPermissions.checkAdmin(viewer, Nodes.ADMIN_SHOP)) {
                container.setItem(navRowStart + 1, MenuUiSupport.button(Items.COMMAND_BLOCK, "Edit shop",
                        ChatFormatting.LIGHT_PURPLE,
                        MenuUiSupport.hint("Add, edit or remove items"),
                        MenuUiSupport.hint("Only admins can see this")));
            }

            container.setItem(navRowStart + 7, MenuUiSupport.button(Items.NETHER_STAR, "Main menu",
                    ChatFormatting.YELLOW, MenuUiSupport.hint("Everything EconomyCraft can do")));

            container.setItem(navRowStart + 8, MenuUiSupport.searchButton());

            MenuUiSupport.fillBackground(container);
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return false;

            if (slot >= 0 && slot < navRowStart) {
                int index = slotToIndex[slot];
                if (index >= 0 && index < categories.size()) {
                    EconomySounds.click(viewer);
                    String cat = categories.get(index);
                    if (stockedSubcategories(eco, cat).isEmpty()) {
                        openItems(viewer, eco, cat);
                    } else {
                        openSubcategories(viewer, eco, cat);
                    }
                    return true;
                }
            }
            if (slot == navRowStart + 3 && page > 0) { EconomySounds.page(viewer); page--; updatePage(); return true; }
            if (slot == navRowStart + 5 && (page + 1) * itemsPerPage < categories.size()) { EconomySounds.page(viewer); page++; updatePage(); return true; }
            if (slot == navRowStart + 1 && EconomyPermissions.checkAdmin(viewer, Nodes.ADMIN_SHOP)) {
                EconomySounds.click(viewer);
                AdminShopUi.open(viewer, eco, AdminShopUi.Origin.SHOP);
                return true;
            }
            if (slot == navRowStart + 7) {
                EconomySounds.click(viewer);
                HubUi.open(viewer);
                return true;
            }
            if (slot == navRowStart + 8) {
                EconomySounds.click(viewer);
                TextInputUi.openSearch(viewer, "Search Shop", (p, q) -> ShopUi.openSearch(p, eco, q));
                return true;
            }
            return false;
        }
    }

    private static class SubcategoryMenu extends CompatMenu {
        private final EconomyManager eco;
        private final PriceRegistry prices;
        private final ServerPlayer viewer;
        private final String topCategory;
        private List<String> subcategories = new ArrayList<>();
        private final SimpleContainer container;
        private final int rows;
        private final int itemsPerPage;
        private final int navRowStart;
        private int page;

        SubcategoryMenu(int id, Inventory inv, EconomyManager eco, String topCategory, ServerPlayer viewer) {
            super(MenuUiSupport.getMenuType(MenuUiSupport.requiredRows(stockedSubcategories(eco, topCategory).size())), id);
            this.eco = eco;
            this.viewer = viewer;
            this.topCategory = topCategory;
            this.prices = eco.getPrices();
            refresh();
            this.rows = MenuUiSupport.requiredRows(subcategories.size());
            this.itemsPerPage = (rows - 1) * 9;
            this.navRowStart = itemsPerPage;
            this.container = new SimpleContainer(rows * 9);
            setupSlots(inv);
            updatePage();
        }

        private void refresh() {
            subcategories = stockedSubcategories(eco, topCategory);
        }

        private void setupSlots(Inventory inv) {
            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, rows * 9)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + rows * 18 + 14)) {
                this.addSlot(slot);
            }
        }

        private void updatePage() {
            container.clearContent();
            int start = page * itemsPerPage;
            int totalPages = MenuUiSupport.totalPages(subcategories.size(), itemsPerPage);

            for (int i = 0; i < itemsPerPage; i++) {
                int idx = start + i;
                if (idx >= subcategories.size()) break;

                String sub = subcategories.get(idx);
                String full = topCategory + "." + sub;
                ItemStack icon = ShopDisplay.createCategoryIcon(sub, full, prices, viewer, false);
                if (icon.isEmpty()) continue;

                icon.set(DataComponents.CUSTOM_NAME, Component.literal(ShopDisplay.getCategoryName(prices, full, sub))
                        .withStyle(s -> s.withItalic(false)
                                .withColor(ShopDisplay.getCategoryColor(prices, full, full)).withBold(true)));
                icon.set(DataComponents.LORE, new ItemLore(List.of(MenuUiSupport.hint("Click to view items"))));
                container.setItem(i, icon);
            }

            if (page > 0) container.setItem(navRowStart + 3, MenuUiSupport.prevPageButton());
            if (start + itemsPerPage < subcategories.size()) container.setItem(navRowStart + 5, MenuUiSupport.nextPageButton());

            container.setItem(navRowStart + 8, MenuUiSupport.backButton());
            container.setItem(navRowStart, MenuUiSupport.createBalanceItem(viewer));
            container.setItem(navRowStart + 4, MenuUiSupport.pageIndicator(page, totalPages));

            MenuUiSupport.fillBackground(container);
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return false;

            if (slot >= 0 && slot < navRowStart) {
                int index = page * itemsPerPage + slot;
                if (index < subcategories.size()) {
                    EconomySounds.click(viewer);
                    String sub = subcategories.get(index);
                    openItems(viewer, eco, topCategory + "." + sub, sub);
                    return true;
                }
            }
            if (slot == navRowStart + 3 && page > 0) { EconomySounds.page(viewer); page--; updatePage(); return true; }
            if (slot == navRowStart + 5 && (page + 1) * itemsPerPage < subcategories.size()) { EconomySounds.page(viewer); page++; updatePage(); return true; }
            if (slot == navRowStart + 8) { EconomySounds.click(viewer); openRoot(viewer, eco); return true; }
            return false;
        }
    }

    private static class ItemMenu extends CompatMenu {
        private final EconomyManager eco;
        private final PriceRegistry prices;
        private final ServerPlayer viewer;
        @Nullable private final String category;
        @Nullable private final String displayTitle;
        @Nullable private final String searchQuery;
        private SortMode sort;
        private List<PriceRegistry.PriceEntry> entries;
        private final SimpleContainer container;
        private final int rows;
        private final int itemsPerPage;
        private final int navRowStart;
        private int page;

        ItemMenu(int id, Inventory inv, EconomyManager eco, @Nullable String category, @Nullable String displayTitle,
                 @Nullable String searchQuery, int page, ServerPlayer viewer, SortMode sort) {
            this(id, inv, eco, category, displayTitle, searchQuery, page, viewer, sort,
                    applySort(eco, resolveEntries(eco, category, searchQuery), sort));
        }

        private ItemMenu(int id, Inventory inv, EconomyManager eco, @Nullable String category, @Nullable String displayTitle,
                         @Nullable String searchQuery, int page, ServerPlayer viewer, SortMode sort,
                         List<PriceRegistry.PriceEntry> resolved) {
            super(MenuUiSupport.getMenuType(MenuUiSupport.requiredRows(resolved.size())), id);
            this.eco = eco;
            this.viewer = viewer;
            this.category = category;
            this.displayTitle = displayTitle;
            this.searchQuery = searchQuery;
            this.sort = sort;
            this.prices = eco.getPrices();

            this.entries = resolved;
            this.rows = MenuUiSupport.requiredRows(resolved.size());
            this.itemsPerPage = (rows - 1) * 9;
            this.navRowStart = itemsPerPage;
            this.page = page;
            this.container = new SimpleContainer(rows * 9);
            setupSlots(inv);
            updatePage();
        }

        private static List<PriceRegistry.PriceEntry> resolveEntries(EconomyManager eco, @Nullable String category,
                                                                    @Nullable String searchQuery) {
            List<PriceRegistry.PriceEntry> candidates = searchQuery != null
                    ? eco.getPrices().searchAll(searchQuery, category)
                    : category == null ? eco.getPrices().allEntries() : eco.getPrices().allByCategory(category);
            return candidates.stream().filter(entry -> eco.getQuestStock().get(entry.key()) > 0).toList();
        }

        private static List<PriceRegistry.PriceEntry> applySort(EconomyManager eco, List<PriceRegistry.PriceEntry> list, SortMode sort) {
            if (sort == SortMode.DEFAULT) return list;
            List<PriceRegistry.PriceEntry> copy = new ArrayList<>(list);
            Comparator<PriceRegistry.PriceEntry> cmp = Comparator.comparingLong(entry -> stockUnitPrice(eco, entry));
            copy.sort(sort == SortMode.PRICE_DESC ? cmp.reversed() : cmp);
            return copy;
        }

        private void setupSlots(Inventory inv) {
            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, rows * 9)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + rows * 18 + 14)) {
                this.addSlot(slot);
            }
        }

        private void updatePage() {
            refreshEntries();
            int totalPages = MenuUiSupport.totalPages(entries.size(), itemsPerPage);
            page = Math.clamp(page, 0, totalPages - 1);
            container.clearContent();
            int start = page * itemsPerPage;

            for (int i = 0; i < itemsPerPage; i++) {
                int idx = start + i;
                if (idx >= entries.size()) break;

                PriceRegistry.PriceEntry entry = entries.get(idx);
                ItemStack display = ShopDisplay.createDisplayStack(entry, viewer);
                if (display.isEmpty()) continue;

                int stackSize = Math.max(1, entry.stack());
                long unitBuy = stockUnitPrice(eco, entry);
                long stock = eco.getQuestStock().get(entry.key());
                int stackAmount = (int) Math.min(stackSize, Math.min(Integer.MAX_VALUE, stock));
                boolean canSell = entry.unitSell() > 0 && EconomyConfig.get().selling.enabled;
                boolean hasContents = MenuUiSupport.hasContainerContents(display);

                List<Component> lore = new ArrayList<>();
                Component buyLore = MenuUiSupport.labeledValue("Buy", EconomyCraft.formatMoney(unitBuy), MenuUiSupport.LABEL_PRIMARY_COLOR);
                lore.add(MenuUiSupport.labeledValue("Stock", Long.toString(stock), MenuUiSupport.LABEL_PRIMARY_COLOR));
                if (canSell) {
                    Component sellLore = MenuUiSupport.labeledValue("Sell", EconomyCraft.formatMoney(entry.unitSell()), MenuUiSupport.LABEL_PRIMARY_COLOR);
                    lore.add(MenuUiSupport.joinLore(buyLore, sellLore));
                } else {
                    lore.add(buyLore);
                }

                if (stackSize > 1) {
                    Long buyStack = ShopDisplay.safeMultiply(unitBuy, stackAmount);
                    Long sellStack = ShopDisplay.safeMultiply(entry.unitSell(), stackSize);
                    if (buyStack != null) {
                        String label = "In stock (" + stackAmount + ")";
                        if (canSell && sellStack != null) {
                            lore.add(MenuUiSupport.labeledValues(label, MenuUiSupport.LABEL_PRIMARY_COLOR,
                                    EconomyCraft.formatMoney(buyStack), EconomyCraft.formatMoney(sellStack)));
                        } else {
                            lore.add(MenuUiSupport.labeledValue(label, EconomyCraft.formatMoney(buyStack), MenuUiSupport.LABEL_PRIMARY_COLOR));
                        }
                    }
                }

                lore.add(MenuUiSupport.labeledValue("Left click", "Buy 1x", MenuUiSupport.LABEL_SECONDARY_COLOR));
                if (canSell) {
                    lore.add(MenuUiSupport.labeledValue("Right click", "Sell 1x", MenuUiSupport.LABEL_SECONDARY_COLOR));
                }
                if (stackSize > 1) {
                    lore.add(MenuUiSupport.labeledValue("Shift-click", (canSell ? "Buy/Sell " : "Buy ") + stackSize + "x", MenuUiSupport.LABEL_SECONDARY_COLOR));
                }
                if (hasContents) {
                    lore.add(MenuUiSupport.labeledValue("Ctrl+Q", "Preview contents", MenuUiSupport.LABEL_SECONDARY_COLOR));
                }

                display.set(DataComponents.LORE, new ItemLore(lore));
                display.setCount(1);
                container.setItem(i, display);
            }

            if (page > 0) container.setItem(navRowStart + 3, MenuUiSupport.prevPageButton());
            if (start + itemsPerPage < entries.size()) container.setItem(navRowStart + 5, MenuUiSupport.nextPageButton());

            container.setItem(navRowStart + 8, MenuUiSupport.backButton());

            if (!searching()) {
                container.setItem(navRowStart + 7, MenuUiSupport.searchButton());
            }

            container.setItem(navRowStart, MenuUiSupport.createBalanceItem(viewer));

            container.setItem(navRowStart + 1, MenuUiSupport.button(Items.HOPPER, "Sort",
                    MenuUiSupport.LABEL_PRIMARY_COLOR,
                    MenuUiSupport.italicHint("Click to cycle"),
                    MenuUiSupport.toggleOption("Default", sort == SortMode.DEFAULT),
                    MenuUiSupport.toggleOption("Lowest Price", sort == SortMode.PRICE_ASC),
                    MenuUiSupport.toggleOption("Highest Price", sort == SortMode.PRICE_DESC)));

            container.setItem(navRowStart + 4, MenuUiSupport.pageIndicator(page, totalPages));

            MenuUiSupport.fillFooter(container);
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind == ClickKind.THROW && slot >= 0 && slot < navRowStart) {
                int index = page * itemsPerPage + slot;
                if (index < entries.size()) {
                    ItemStack display = ShopDisplay.createDisplayStack(entries.get(index), viewer);
                    if (MenuUiSupport.hasContainerContents(display)) {
                        ContainerPreviewUi.open(viewer, display, () -> {
                            if (searchQuery != null) {
                                openSearchResults(viewer, eco, category, searchQuery, page, sort);
                            } else {
                                openItems(viewer, eco, category, displayTitle, page, sort);
                            }
                        });
                    }
                }
                return true;
            }
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return false;

            if (slot >= 0 && slot < navRowStart) {
                int index = page * itemsPerPage + slot;
                if (index < entries.size()) {
                    PriceRegistry.PriceEntry entry = prices.findByKey(entries.get(index).key());
                    if (entry == null) {
                        EconomySounds.failure(viewer);
                        viewer.sendSystemMessage(Component.literal("This item is no longer in the shop.")
                                .withStyle(ChatFormatting.RED));
                        return true;
                    }
                    int amount = kind == ClickKind.QUICK_MOVE ? Math.max(1, entry.stack()) : 1;
                    if (dragType == 1) {
                        handleSell(entry, amount);
                    } else {
                        handlePurchase(entry, amount);
                    }
                    return true;
                }
            }
            if (slot == navRowStart + 3 && page > 0) { EconomySounds.page(viewer); page--; updatePage(); return true; }
            if (slot == navRowStart + 5 && (page + 1) * itemsPerPage < entries.size()) { EconomySounds.page(viewer); page++; updatePage(); return true; }
            if (slot == navRowStart + 7 && !searching()) {
                EconomySounds.click(viewer);
                TextInputUi.openSearch(viewer, "Search Shop", (p, q) -> ShopUi.openSearchResults(p, eco, category, q, 0, sort));
                return true;
            }
            if (slot == navRowStart + 1) {
                EconomySounds.click(viewer);
                sort = sort.next();
                entries = applySort(eco, resolveEntries(eco, category, searchQuery), sort);
                page = 0;
                updatePage();
                return true;
            }
            if (slot == navRowStart + 8) {
                EconomySounds.click(viewer);
                if (category != null && category.contains(".")) {
                    openSubcategories(viewer, eco, category.substring(0, category.indexOf('.')));
                } else {
                    openRoot(viewer, eco);
                }
                return true;
            }
            return false;
        }

        private boolean searching() {
            return searchQuery != null && !searchQuery.isBlank();
        }

        private void handlePurchase(PriceRegistry.PriceEntry entry, int amount) {
            if (!stockShopEnabled(eco)) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("Quest stock shop is disabled.").withStyle(ChatFormatting.RED));
                return;
            }
            ItemStack base = prices.createPrototype(entry);
            if (base.isEmpty()) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("Item unavailable.")
                        .withStyle(ChatFormatting.RED));
                return;
            }

            long available = eco.getQuestStock().get(entry.key());
            if (available <= 0) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("This item is out of stock.").withStyle(ChatFormatting.RED));
                refreshEntries();
                updatePage();
                return;
            }
            int purchaseAmount = QuestLogic.stockPurchaseAmount(amount, available);
            if (purchaseAmount <= 0) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("This item is out of stock.").withStyle(ChatFormatting.RED));
                return;
            }
            long unitPrice = stockUnitPrice(eco, entry);
            if (unitPrice <= 0) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("This item has no valid market price.").withStyle(ChatFormatting.RED));
                return;
            }
            Long total = ShopDisplay.safeMultiply(unitPrice, purchaseAmount);
            if (total == null) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("Price too large.")
                        .withStyle(ChatFormatting.RED));
                return;
            }

            long balance = eco.getBalance(viewer.getUUID(), true);
            if (balance < total) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("Not enough balance.")
                        .withStyle(ChatFormatting.RED));
                return;
            }

            String detail = EconomyCraft.describeItem(purchaseAmount, base.getHoverName().getString());
            boolean[] purchased = {false};
            BuybackTransfers.run(eco, () -> {
                long taken = eco.getQuestStock().withdraw(entry.key(), purchaseAmount, false);
                if (taken != purchaseAmount) {
                    if (taken > 0) eco.getQuestStock().deposit(entry.key(), taken, false);
                    return;
                }
                var result = eco.transferMoney(viewer.getUUID(), QuestManager.BOT_UUID, total, total,
                        EconomySources.QUEST_BUYBACK, detail, TransactionItems.keyOf(base), purchaseAmount);
                if (!result.successful()) {
                    eco.getQuestStock().deposit(entry.key(), taken, false);
                    return;
                }
                eco.getQuestStock().save();
                eco.getQuests().onBuybackPurchased(entry.key());
                purchased[0] = true;
            });
            if (!purchased[0]) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("Purchase failed. Check your balance and try again.")
                        .withStyle(ChatFormatting.RED));
                return;
            }

            boolean stored = giveToPlayer(base, purchaseAmount);

            EconomySounds.success(viewer);
            String message = purchaseAmount < amount ? "Only " + purchaseAmount + " were in stock; purchased " : "Purchased ";
            viewer.sendSystemMessage(Component.literal(message + purchaseAmount + "x " + base.getHoverName().getString()
                            + " for " + EconomyCraft.formatMoneyChat(total))
                    .withStyle(ChatFormatting.GREEN));

            if (stored) {
                sendStoredMessage(viewer);
            }

            refreshEntries();
            updatePage();
        }

        private void refreshEntries() {
            entries = applySort(eco, resolveEntries(eco, category, searchQuery), sort);
        }

        private void handleSell(PriceRegistry.PriceEntry entry, int amount) {
            if (!EconomyConfig.get().selling.enabled || entry.unitSell() <= 0) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("This item cannot be sold.").withStyle(ChatFormatting.RED));
                return;
            }

            boolean excludeEnchanted = entry.customItem() == null;
            int have = SellService.countMatching(viewer, prices, entry, excludeEnchanted);
            if (have <= 0) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("You have none to sell.").withStyle(ChatFormatting.RED));
                return;
            }

            int toSell = Math.min(amount, have);
            Long total = ShopDisplay.safeMultiply(entry.unitSell(), toSell);
            if (total == null) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("Price too large.").withStyle(ChatFormatting.RED));
                return;
            }

            long remaining = eco.getDailySellRemaining(viewer.getUUID());
            if (EconomyConfig.get().selling.dailyLimit > 0 && total > remaining) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal(remaining <= 0
                        ? "Daily sell limit reached. Try again tomorrow."
                        : "That exceeds your daily sell limit.").withStyle(ChatFormatting.RED));
                return;
            }

            ItemStack disp = ShopDisplay.createDisplayStack(entry, viewer);
            String name = disp.isEmpty() ? entry.id().path() : disp.getHoverName().getString();

            var result = eco.addMoney(viewer.getUUID(), total, EconomySources.SHOP_SALE,
                    EconomyCraft.describeItem(toSell, name), TransactionItems.keyOf(disp));
            if (!result.successful()) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(Component.literal("Your balance is too high to receive this sale.")
                        .withStyle(ChatFormatting.RED));
                return;
            }
            if (EconomyConfig.get().selling.dailyLimit > 0) {
                eco.recordDailySell(viewer.getUUID(), total);
            }

            SellService.removeMatching(viewer, prices, entry, toSell, excludeEnchanted);

            EconomySounds.success(viewer);
            viewer.sendSystemMessage(Component.literal("Sold " + toSell + "x " + name + " for " + EconomyCraft.formatMoneyChat(total))
                    .withStyle(ChatFormatting.GREEN));
            updatePage();
        }

        private boolean giveToPlayer(ItemStack base, int amount) {
            int remaining = amount;
            boolean stored = false;
            while (remaining > 0) {
                int give = Math.min(base.getMaxStackSize(), remaining);
                ItemStack stack = base.copyWithCount(give);
                if (!viewer.getInventory().add(stack)) {
                    eco.getDeliveries().addDelivery(viewer.getUUID(), stack);
                    stored = true;
                }
                remaining -= give;
            }
            return stored;
        }

        private void sendStoredMessage(ServerPlayer player) {
            ClickEvent ev = ChatCompat.runCommandEvent("/eco deliveries");
            if (ev != null) {
                player.sendSystemMessage(STORED_MSG.copy()
                        .append(Component.literal("[Claim]")
                                .withStyle(s -> s.withUnderlined(true)
                                        .withColor(ChatFormatting.GREEN)
                                        .withClickEvent(ev))));
            } else {
                ChatCompat.sendRunCommandTellraw(player, "Item stored: ", "[Claim]", "/eco deliveries");
            }
        }
    }
}

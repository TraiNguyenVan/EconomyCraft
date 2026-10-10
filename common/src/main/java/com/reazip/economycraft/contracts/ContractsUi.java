package com.reazip.economycraft.contracts;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.HubUi;
import com.reazip.economycraft.contracts.Contract.Category;
import com.reazip.economycraft.contracts.Contract.Status;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.ConfirmUi;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.ExpirationUtil;
import com.reazip.economycraft.util.MenuUiSupport;
import com.reazip.economycraft.util.NumberInputUi;
import com.reazip.economycraft.util.PlayerPickerUi;
import com.reazip.economycraft.util.TextInputUi;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Vanilla chest UIs for the contracts subsystem: browsing public postings, a work-detail review
 * screen for requester and contractor, a guided creation flow, and a personal contracts list.
 *
 * <p>Every action button revalidates through {@link ContractService} at click time, so a stale
 * open menu can never force a transition the service would refuse. Long text is an anvil-input
 * flow (vanilla clients have no text fields); amounts are steppers.
 */
public final class ContractsUi {
    private ContractsUi() {}

    // === entry points ===

    /** Browse board: every OPEN contract this viewer may take. */
    public static void open(ServerPlayer player, EconomyManager eco) {
        if (!EconomyConfig.get().contractsEnabled) {
            player.sendSystemMessage(Component.literal("Contracts are disabled.").withStyle(ChatFormatting.RED));
            return;
        }
        open(player, eco, 0, null);
    }

    private static void open(ServerPlayer player, EconomyManager eco, int page, @Nullable Category filter) {
        List<Contract> contracts = resolveBrowse(eco, player);
        if (filter != null) {
            contracts.removeIf(c -> c.category != filter);
        }
        int rows = MenuUiSupport.listMenuRows(contracts.size());
        MenuUiSupport.openMenu(player, "Contracts", (id, inv) ->
                new BrowseMenu(id, inv, eco, player, page, filter, contracts, rows));
    }

    /** Personal list: contracts the viewer posted or accepted, split by tab. */
    public static void openMine(ServerPlayer player, EconomyManager eco) {
        if (!EconomyConfig.get().contractsEnabled) {
            player.sendSystemMessage(Component.literal("Contracts are disabled.").withStyle(ChatFormatting.RED));
            return;
        }
        MenuUiSupport.openMenu(player, "My Contracts", (id, inv) ->
                new MineMenu(id, inv, eco, player, 0, true));
    }

    /** Detail/review screen for one contract. */
    public static void openDetails(ServerPlayer player, EconomyManager eco, int id) {
        Contract c = eco.getContracts().getContract(id);
        if (c == null) {
            EconomySounds.failure(player);
            player.sendSystemMessage(Component.literal("Contract #" + id + " does not exist.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        MenuUiSupport.openMenu(player, "Contract #" + id, (menuId, inv) ->
                new DetailsMenu(menuId, inv, eco, player, id));
    }

    // === creation flow ===

    /**
     * Guided creation: visibility, optional target, category, title, description, reward,
     * deadline, then a confirmation that shows the exact escrow debit before anything moves.
     */
    public static void startCreate(ServerPlayer player, EconomyManager eco) {
        if (!EconomyConfig.get().contractsEnabled) {
            player.sendSystemMessage(Component.literal("Contracts are disabled.").withStyle(ChatFormatting.RED));
            return;
        }
        if (eco.getContracts().hasReachedLimit(player.getUUID())) {
            EconomySounds.failure(player);
            player.sendSystemMessage(Component.literal("You have reached your limit of "
                    + eco.getContracts().getEffectiveLimit() + " active contract(s).")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        MenuUiSupport.openMenu(player, "Contract visibility", (id, inv) ->
                new VisibilityMenu(id, inv, player, eco));
    }

    /** Mutable draft carried through the creation steps. */
    private static final class Draft {
        boolean targeted;
        @Nullable UUID target;
        @Nullable String targetName;
        Category category = Category.OTHER;
        String title = "";
        @Nullable String description;
        long reward;
        long deadlineDays;
    }

    private static void chooseCategory(ServerPlayer player, EconomyManager eco, Draft draft) {
        MenuUiSupport.openMenu(player, "Contract category", (id, inv) ->
                new CategoryMenu(id, inv, player, eco, draft));
    }

    private static void chooseTitle(ServerPlayer player, EconomyManager eco, Draft draft) {
        TextInputUi.open(player, "Contract title", draft.title, Items.PAPER,
                "Title: ", "Type the title (max " + Contract.MAX_TITLE_LENGTH + ")",
                false,
                (picker, text) -> {
                    String clean = Contract.sanitize(text, Contract.MAX_TITLE_LENGTH);
                    if (clean == null) {
                        EconomySounds.failure(picker);
                        chooseTitle(picker, eco, draft);
                        return;
                    }
                    draft.title = clean;
                    chooseDescription(picker, eco, draft);
                },
                picker -> chooseCategory(picker, eco, draft));
    }

    private static void chooseDescription(ServerPlayer player, EconomyManager eco, Draft draft) {
        TextInputUi.open(player, "Contract details", draft.description == null ? "" : draft.description,
                Items.WRITABLE_BOOK, "Details: ", "Describe the work (optional)",
                true,
                (picker, text) -> {
                    draft.description = Contract.sanitize(text, Contract.MAX_TEXT_LENGTH);
                    chooseReward(picker, eco, draft);
                },
                picker -> chooseTitle(picker, eco, draft));
    }

    private static void chooseReward(ServerPlayer player, EconomyManager eco, Draft draft) {
        long max = Math.min(EconomyConfig.get().maxContractReward, EconomyManager.MAX);
        long initial = draft.reward > 0 ? Math.min(draft.reward, max) : Math.min(100, max);
        NumberInputUi.openMoney(player, "Contract reward",
                MenuUiSupport.createBalanceItem(player),
                "Reward", initial, 1, max,
                (picker, amount) -> {
                    draft.reward = amount;
                    chooseDeadline(picker, eco, draft);
                },
                picker -> chooseDescription(picker, eco, draft));
    }

    private static void chooseDeadline(ServerPlayer player, EconomyManager eco, Draft draft) {
        int defaultDays = Math.max(1, EconomyConfig.get().contractDefaultDurationHours / 24);
        int maxDays = Math.max(defaultDays, EconomyConfig.get().contractMaxDurationHours / 24);
        long initial = draft.deadlineDays > 0 ? Math.min(draft.deadlineDays, maxDays) : defaultDays;
        NumberInputUi.openDays(player, "Contract deadline",
                MenuUiSupport.button(Items.CLOCK, "Deadline", ChatFormatting.GOLD),
                "Work time", initial, 1, maxDays,
                (picker, days) -> {
                    draft.deadlineDays = days;
                    confirmCreate(picker, eco, draft);
                },
                picker -> chooseReward(picker, eco, draft));
    }

    private static void confirmCreate(ServerPlayer player, EconomyManager eco, Draft draft) {
        long tax = TaxPolicy.resolve(TaxScope.TRANSACTION_CONTRACT, draft.reward).amount();
        List<Component> lore = new ArrayList<>(buildTermsLore(eco, player.getUUID(), draft));
        lore.add(MenuUiSupport.line("The full reward is reserved in escrow now.", ChatFormatting.YELLOW));
        lore.add(MenuUiSupport.line("You get it back if the contract is cancelled or expires.", ChatFormatting.YELLOW));
        ConfirmUi.open(player, "Create contract",
                MenuUiSupport.button(Items.WRITABLE_BOOK, draft.title, ChatFormatting.GOLD,
                        lore.toArray(new Component[0])),
                "Create and reserve " + EconomyCraft.formatMoney(draft.reward),
                List.of(MenuUiSupport.hint("Click to post the contract")),
                picker -> {
                    long deadline = System.currentTimeMillis() + draft.deadlineDays * 24 * 3_600_000L;
                    ContractService.CreationResult result = ContractService.create(eco, picker.getUUID(),
                            new ContractService.CreationRequest(draft.title, draft.description,
                                    draft.category, draft.targeted, draft.target, draft.reward, deadline));
                    if (result.success()) {
                        EconomySounds.success(picker);
                        picker.sendSystemMessage(Component.literal("Contract \"" + draft.title
                                + "\" posted (#" + result.contract().id + "). "
                                + EconomyCraft.formatMoney(draft.reward) + " reserved in escrow.")
                                .withStyle(ChatFormatting.GREEN));
                        openMine(picker, eco);
                    } else {
                        EconomySounds.failure(picker);
                        picker.sendSystemMessage(Component.literal(createFailureMessage(result.status()))
                                .withStyle(ChatFormatting.RED));
                        open(picker, eco);
                    }
                },
                picker -> chooseDeadline(picker, eco, draft));
    }

    private static String createFailureMessage(ContractService.CreateStatus status) {
        return switch (status) {
            case DISABLED -> "Contracts are disabled.";
            case INVALID_TITLE -> "That title is not usable. Try a different one.";
            case INVALID_REWARD -> "That reward is outside the allowed range.";
            case INVALID_DEADLINE -> "That deadline is outside the allowed range.";
            case INVALID_TARGET -> "That contractor is not valid.";
            case LIMIT_REACHED -> "You have reached your active contract limit.";
            case INSUFFICIENT_FUNDS -> "You can't afford to reserve that reward.";
            case OK -> "Contract created.";
        };
    }

    // === shared rendering ===

    private static List<Contract> resolveBrowse(EconomyManager eco, ServerPlayer viewer) {
        UUID id = viewer.getUUID();
        List<Contract> out = new ArrayList<>();
        for (Contract c : eco.getContracts().getContracts()) {
            if (c.status != Status.OPEN) continue;
            if (c.requester.equals(id)) continue;
            if (c.target != null && !c.target.equals(id)) continue;
            out.add(c);
        }
        out.sort(Comparator.comparingInt((Contract c) -> c.id).reversed());
        return out;
    }

    private static ItemStack contractRowItem(EconomyManager eco, Contract c, UUID viewer) {
        String ownerMark = c.requester.equals(viewer) ? " (you)" : "";
        List<Component> lore = new ArrayList<>();
        lore.add(MenuUiSupport.labeledValue("Category", c.category.label, MenuUiSupport.LABEL_PRIMARY_COLOR));
        lore.add(MenuUiSupport.labeledValue("Posted by", displayName(eco, c.requester) + ownerMark,
                MenuUiSupport.LABEL_PRIMARY_COLOR));
        if (c.target != null) {
            lore.add(MenuUiSupport.labeledValue("For", displayName(eco, c.target),
                    MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        long tax = TaxPolicy.resolve(TaxScope.TRANSACTION_CONTRACT, c.reward).amount();
        String rewardLine = EconomyCraft.formatMoney(c.reward);
        if (tax > 0) {
            rewardLine += " (" + EconomyCraft.formatMoney(c.reward - tax) + " after tax)";
        }
        lore.add(MenuUiSupport.labeledValue("Reward", rewardLine, MenuUiSupport.LABEL_PRIMARY_COLOR));
        lore.add(MenuUiSupport.labeledValue("Deadline", deadlineLabel(c), MenuUiSupport.LABEL_PRIMARY_COLOR));
        if (c.status != Status.OPEN) {
            lore.add(MenuUiSupport.labeledValue("Status", c.status.label, MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        lore.add(MenuUiSupport.hint("Click to view and accept"));
        return MenuUiSupport.button(Items.PAPER, "#" + c.id + " " + c.title, ChatFormatting.GOLD,
                lore.toArray(new Component[0]));
    }

    private static List<Component> buildTermsLore(EconomyManager eco, UUID viewer, Draft draft) {
        List<Component> lore = new ArrayList<>();
        lore.add(MenuUiSupport.labeledValue("Category", draft.category.label, MenuUiSupport.LABEL_PRIMARY_COLOR));
        lore.add(MenuUiSupport.labeledValue("Visibility",
                draft.targeted ? "Targeted: " + (draft.targetName == null ? "?" : draft.targetName) : "Public",
                MenuUiSupport.LABEL_PRIMARY_COLOR));
        long tax = TaxPolicy.resolve(TaxScope.TRANSACTION_CONTRACT, draft.reward).amount();
        lore.add(MenuUiSupport.labeledValue("Reward", EconomyCraft.formatMoney(draft.reward),
                MenuUiSupport.LABEL_PRIMARY_COLOR));
        if (tax > 0) {
            lore.add(MenuUiSupport.labeledValue("Contractor receives", EconomyCraft.formatMoney(draft.reward - tax),
                    MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        lore.add(MenuUiSupport.labeledValue("Work time", draft.deadlineDays + " day" + (draft.deadlineDays == 1 ? "" : "s"),
                MenuUiSupport.LABEL_PRIMARY_COLOR));
        return lore;
    }

    private static String deadlineLabel(Contract c) {
        if (c.workDeadline <= 0) return "No deadline";
        long left = c.workDeadline - System.currentTimeMillis();
        if (left <= 0) return "Expired";
        return ExpirationUtil.expiresInLabel(c.workDeadline);
    }

    private static String displayName(EconomyManager eco, @Nullable UUID id) {
        if (id == null) return "—";
        String name = eco.getBestName(id);
        if (name != null && !name.isBlank()) return name;
        name = MenuUiSupport.resolvePlayerName(eco.getServer(), id);
        return name == null || name.isBlank() ? "Unknown player" : name;
    }

    private static ChatFormatting statusColor(Status status) {
        return switch (status) {
            case OPEN -> ChatFormatting.GREEN;
            case IN_PROGRESS -> ChatFormatting.AQUA;
            case SUBMITTED -> ChatFormatting.YELLOW;
            case DISPUTED -> ChatFormatting.RED;
            case COMPLETED -> ChatFormatting.GOLD;
            case CANCELLED, EXPIRED -> ChatFormatting.GRAY;
        };
    }

    private static void addMultilineLore(List<Component> lore, String label, @Nullable String text) {
        if (text == null) return;
        lore.add(MenuUiSupport.labeledValue(label, "", MenuUiSupport.LABEL_PRIMARY_COLOR));
        for (String line : text.split("\n")) {
            lore.add(MenuUiSupport.line("  " + line, ChatFormatting.WHITE));
        }
    }

    // === browse menu ===

    private static final class BrowseMenu extends CompatMenu {
        private final EconomyManager eco;
        private final ServerPlayer viewer;
        private int page;
        private @Nullable Category filter;
        private List<Contract> contracts;
        private final SimpleContainer container;
        private final int rows;
        private final int gridSlots;
        private final int navRowStart;
        private final Runnable listener = this::refresh;

        BrowseMenu(int id, Inventory inv, EconomyManager eco, ServerPlayer viewer, int page,
                   @Nullable Category filter, List<Contract> contracts, int rows) {
            super(MenuUiSupport.getMenuType(rows), id);
            this.eco = eco;
            this.viewer = viewer;
            this.page = page;
            this.filter = filter;
            this.contracts = contracts;
            this.rows = rows;
            this.gridSlots = (rows - 1) * 9;
            this.navRowStart = gridSlots;
            this.container = new SimpleContainer(rows * 9);
            addSlotRange(readOnlyGridSlots());
            addSlotRange(MenuUiSupport.playerInventorySlots(inv, 18 + rows * 18 + 14));
            eco.getContracts().addListener(listener);
            updatePage();
        }

        private List<net.minecraft.world.inventory.Slot> readOnlyGridSlots() {
            return MenuUiSupport.readOnlyGridSlots(container, rows * 9);
        }

        private void addSlotRange(List<net.minecraft.world.inventory.Slot> slots) {
            for (net.minecraft.world.inventory.Slot s : slots) addSlot(s);
        }

        private int totalPages() {
            return Math.max(1, MenuUiSupport.totalPages(contracts.size(), gridSlots));
        }

        private void refresh() {
            List<Contract> fresh = resolveBrowse(eco, viewer);
            if (filter != null) fresh.removeIf(c -> c.category != filter);
            this.contracts = fresh;
            updatePage();
        }

        private void updatePage() {
            int pages = totalPages();
            if (page >= pages) {
                page = pages - 1;
                open(viewer, eco, page, filter);
                return;
            }
            if (rows != MenuUiSupport.listMenuRows(contracts.size())) {
                open(viewer, eco, page, filter);
                return;
            }
            for (int i = 0; i < gridSlots; i++) {
                int index = page * gridSlots + i;
                container.setItem(i, index < contracts.size()
                        ? contractRowItem(eco, contracts.get(index), viewer.getUUID())
                        : ItemStack.EMPTY);
            }
            MenuUiSupport.fillFooter(container);
            container.setItem(navRowStart, MenuUiSupport.createBalanceItem(eco, viewer.getUUID(), viewer, null));
            container.setItem(navRowStart + 1, MenuUiSupport.button(Items.HOPPER,
                    "Filter: " + (filter == null ? "All" : filter.label), ChatFormatting.AQUA,
                    MenuUiSupport.hint("Click to cycle category")));
            container.setItem(navRowStart + 2, MenuUiSupport.button(Items.WRITABLE_BOOK, "New Contract",
                    ChatFormatting.GREEN, MenuUiSupport.hint("Post work for others")));
            container.setItem(navRowStart + 3, MenuUiSupport.prevPageButton());
            container.setItem(navRowStart + 4, MenuUiSupport.pageIndicator(page + 1, pages));
            container.setItem(navRowStart + 5, MenuUiSupport.nextPageButton());
            container.setItem(navRowStart + 6, MenuUiSupport.button(Items.BOOK, "My Contracts",
                    ChatFormatting.YELLOW, MenuUiSupport.hint("Posted and accepted")));
            container.setItem(navRowStart + 7, MenuUiSupport.button(Items.NETHER_STAR, "Main Menu",
                    ChatFormatting.GOLD, MenuUiSupport.hint("Back to the hub")));
            broadcastChanges();
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP) return false;
            if (!(player instanceof ServerPlayer clicker)) return false;
            if (slot >= 0 && slot < navRowStart) {
                int index = page * gridSlots + slot;
                if (index < contracts.size()) {
                    EconomySounds.click(clicker);
                    openDetails(clicker, eco, contracts.get(index).id);
                }
                return true;
            }
            if (slot == navRowStart + 1) {
                EconomySounds.click(clicker);
                // Cycle All -> each category in order -> back to All.
                Category[] values = Category.values();
                filter = filter == null ? values[0]
                        : filter.ordinal() + 1 < values.length ? values[filter.ordinal() + 1] : null;
                refresh();
                int pages = totalPages();
                if (page >= pages) page = pages - 1;
                updatePage();
                return true;
            }
            if (slot == navRowStart + 2) {
                EconomySounds.click(clicker);
                startCreate(clicker, eco);
                return true;
            }
            if (slot == navRowStart + 3 && page > 0) {
                page--;
                EconomySounds.page(clicker);
                updatePage();
                return true;
            }
            if (slot == navRowStart + 5 && page + 1 < totalPages()) {
                page++;
                EconomySounds.page(clicker);
                updatePage();
                return true;
            }
            if (slot == navRowStart + 6) {
                EconomySounds.click(clicker);
                openMine(clicker, eco);
                return true;
            }
            if (slot == navRowStart + 7) {
                EconomySounds.click(clicker);
                HubUi.open(clicker);
                return true;
            }
            return slot >= navRowStart;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            eco.getContracts().removeListener(listener);
        }
    }

    // === personal list ===

    private static final class MineMenu extends CompatMenu {
        private final EconomyManager eco;
        private final ServerPlayer viewer;
        private int page;
        private boolean postedTab;
        private List<Contract> contracts;
        private final SimpleContainer container;
        private final int rows;
        private final int gridSlots;
        private final int navRowStart;
        private final Runnable listener = this::refresh;

        MineMenu(int id, Inventory inv, EconomyManager eco, ServerPlayer viewer, int page, boolean postedTab) {
            super(MenuUiSupport.getMenuType(6), id);
            this.eco = eco;
            this.viewer = viewer;
            this.page = page;
            this.postedTab = postedTab;
            this.contracts = resolveMine(eco, viewer, postedTab);
            this.rows = 6;
            this.gridSlots = 45;
            this.navRowStart = 45;
            this.container = new SimpleContainer(54);
            addSlotRange(MenuUiSupport.readOnlyGridSlots(container, 54));
            addSlotRange(MenuUiSupport.playerInventorySlots(inv, 18 + 6 * 18 + 14));
            eco.getContracts().addListener(listener);
            updatePage();
        }

        private void addSlotRange(List<net.minecraft.world.inventory.Slot> slots) {
            for (net.minecraft.world.inventory.Slot s : slots) addSlot(s);
        }

        private int totalPages() {
            return Math.max(1, MenuUiSupport.totalPages(contracts.size(), gridSlots));
        }

        private void refresh() {
            contracts = resolveMine(eco, viewer, postedTab);
            updatePage();
        }

        private void updatePage() {
            int pages = totalPages();
            if (page >= pages) page = pages - 1;
            for (int i = 0; i < gridSlots; i++) {
                int index = page * gridSlots + i;
                container.setItem(i, index < contracts.size()
                        ? contractRowItem(eco, contracts.get(index), viewer.getUUID())
                        : ItemStack.EMPTY);
            }
            MenuUiSupport.fillFooter(container);
            container.setItem(navRowStart, MenuUiSupport.createBalanceItem(eco, viewer.getUUID(), viewer, null));
            container.setItem(navRowStart + 1, MenuUiSupport.button(Items.BOOK,
                    postedTab ? "Showing: Posted" : "Showing: Accepted", ChatFormatting.AQUA,
                    MenuUiSupport.hint("Click to switch")));
            container.setItem(navRowStart + 2, MenuUiSupport.button(Items.WRITABLE_BOOK, "New Contract",
                    ChatFormatting.GREEN, MenuUiSupport.hint("Post work for others")));
            container.setItem(navRowStart + 3, MenuUiSupport.prevPageButton());
            container.setItem(navRowStart + 4, MenuUiSupport.pageIndicator(page + 1, pages));
            container.setItem(navRowStart + 5, MenuUiSupport.nextPageButton());
            container.setItem(navRowStart + 6, MenuUiSupport.button(Items.PAPER, "Browse Contracts",
                    ChatFormatting.YELLOW, MenuUiSupport.hint("Find work to accept")));
            container.setItem(navRowStart + 7, MenuUiSupport.button(Items.NETHER_STAR, "Main Menu",
                    ChatFormatting.GOLD, MenuUiSupport.hint("Back to the hub")));
            broadcastChanges();
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP) return false;
            if (!(player instanceof ServerPlayer clicker)) return false;
            if (slot >= 0 && slot < navRowStart) {
                int index = page * gridSlots + slot;
                if (index < contracts.size()) {
                    EconomySounds.click(clicker);
                    openDetails(clicker, eco, contracts.get(index).id);
                }
                return true;
            }
            if (slot == navRowStart + 1) {
                EconomySounds.click(clicker);
                postedTab = !postedTab;
                page = 0;
                refresh();
                return true;
            }
            if (slot == navRowStart + 2) {
                EconomySounds.click(clicker);
                startCreate(clicker, eco);
                return true;
            }
            if (slot == navRowStart + 3 && page > 0) {
                page--;
                EconomySounds.page(clicker);
                updatePage();
                return true;
            }
            if (slot == navRowStart + 5 && page + 1 < totalPages()) {
                page++;
                EconomySounds.page(clicker);
                updatePage();
                return true;
            }
            if (slot == navRowStart + 6) {
                EconomySounds.click(clicker);
                open(clicker, eco);
                return true;
            }
            if (slot == navRowStart + 7) {
                EconomySounds.click(clicker);
                HubUi.open(clicker);
                return true;
            }
            return slot >= navRowStart;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            eco.getContracts().removeListener(listener);
        }
    }

    private static List<Contract> resolveMine(EconomyManager eco, ServerPlayer viewer, boolean postedTab) {
        UUID id = viewer.getUUID();
        List<Contract> out = new ArrayList<>();
        for (Contract c : eco.getContracts().getContracts()) {
            if (postedTab ? c.requester.equals(id) : id.equals(c.contractor)) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparingInt((Contract c) -> c.id).reversed());
        return out;
    }

    // === detail / review screen ===

    private static final class DetailsMenu extends CompatMenu {
        private static final int SUBJECT = 4;
        private static final int BACK = 0;
        private static final int CLOSE = 8;
        private static final int HOME = 40;
        private static final int ACTION_ROW = 27;

        private final EconomyManager eco;
        private final ServerPlayer viewer;
        private final int contractId;
        private final SimpleContainer container = new SimpleContainer(45);
        private final Runnable listener = this::refresh;

        DetailsMenu(int id, Inventory inv, EconomyManager eco, ServerPlayer viewer, int contractId) {
            super(MenuType.GENERIC_9x5, id);
            this.eco = eco;
            this.viewer = viewer;
            this.contractId = contractId;
            addSlotRange(MenuUiSupport.readOnlyGridSlots(container, 45));
            addSlotRange(MenuUiSupport.playerInventorySlots(inv, 18 + 5 * 18 + 14));
            eco.getContracts().addListener(listener);
            updatePage();
        }

        private void addSlotRange(List<net.minecraft.world.inventory.Slot> slots) {
            for (net.minecraft.world.inventory.Slot s : slots) addSlot(s);
        }

        private void refresh() {
            if (eco.getContracts().getContract(contractId) == null) {
                viewer.closeContainer();
                return;
            }
            updatePage();
        }

        private void updatePage() {
            Contract c = eco.getContracts().getContract(contractId);
            if (c == null) {
                viewer.closeContainer();
                return;
            }
            MenuUiSupport.fillBackground(container);
            container.setItem(BACK, MenuUiSupport.backButton());
            container.setItem(CLOSE, MenuUiSupport.closeButton());
            container.setItem(SUBJECT, MenuUiSupport.button(Items.BOOK,
                    "#" + c.id + " " + c.title, ChatFormatting.GOLD,
                    buildSubjectLore(eco, viewer.getUUID(), c).toArray(new Component[0])));
            container.setItem(HOME, MenuUiSupport.button(Items.NETHER_STAR, "Main Menu",
                    ChatFormatting.GOLD, MenuUiSupport.hint("Back to the hub")));

            List<ItemStack> actions = buildActions(c);
            int start = ACTION_ROW + Math.max(0, (9 - actions.size()) / 2);
            for (int i = 0; i < actions.size(); i++) {
                container.setItem(start + i, actions.get(i));
            }
            broadcastChanges();
        }

        private List<ItemStack> buildActions(Contract c) {
            List<ItemStack> actions = new ArrayList<>();
            UUID me = viewer.getUUID();
            boolean isRequester = me.equals(c.requester);
            boolean isContractor = me.equals(c.contractor);
            switch (c.status) {
                case OPEN -> {
                    if (!isRequester && (c.target == null || isContractor || c.target.equals(me))) {
                        actions.add(MenuUiSupport.button(Items.EMERALD, "Accept Contract",
                                ChatFormatting.GREEN,
                                MenuUiSupport.labeledValue("You receive",
                                        EconomyCraft.formatMoney(netAfterTax(c.reward)),
                                        MenuUiSupport.LABEL_PRIMARY_COLOR),
                                MenuUiSupport.hint("Click to accept this work")));
                    }
                }
                case IN_PROGRESS -> {
                    if (isContractor) {
                        actions.add(MenuUiSupport.button(Items.WRITABLE_BOOK, "Submit Work",
                                ChatFormatting.YELLOW, MenuUiSupport.hint("Mark the work done for review")));
                    }
                }
                case SUBMITTED -> {
                    if (isRequester) {
                        actions.add(MenuUiSupport.button(Items.EMERALD, "Approve and Pay",
                                ChatFormatting.GREEN,
                                MenuUiSupport.labeledValue("Contractor receives",
                                        EconomyCraft.formatMoney(netAfterTax(c.reward)),
                                        MenuUiSupport.LABEL_PRIMARY_COLOR),
                                MenuUiSupport.hint("Release the escrow")));
                        actions.add(MenuUiSupport.button(Items.REDSTONE, "Request Revision",
                                ChatFormatting.YELLOW,
                                MenuUiSupport.labeledValue("Revisions used",
                                        c.revisions + " of " + EconomyConfig.get().contractMaxRevisions,
                                        MenuUiSupport.LABEL_PRIMARY_COLOR),
                                MenuUiSupport.hint("Send back with feedback")));
                    }
                }
                default -> {
                }
            }
            if (c.status.active() && c.status != Status.DISPUTED && (isRequester || isContractor)) {
                boolean disputable = c.status == Status.SUBMITTED
                        || (c.status == Status.IN_PROGRESS
                        && c.revisions >= EconomyConfig.get().contractMaxRevisions);
                if (disputable) {
                    actions.add(MenuUiSupport.button(Items.TNT, "Raise Dispute", ChatFormatting.RED,
                            MenuUiSupport.hint("Freeze the contract for admin review")));
                }
                if (c.status == Status.OPEN ? isRequester : true) {
                    actions.add(MenuUiSupport.button(Items.BARRIER, "Cancel Contract",
                            ChatFormatting.RED,
                            c.status == Status.OPEN
                                    ? MenuUiSupport.hint("Refund the full escrow now")
                                    : MenuUiSupport.hint("Propose cancelling; both sides agree")));
                }
            }
            actions.add(MenuUiSupport.button(Items.WRITABLE_BOOK, "Copy to Book",
                    ChatFormatting.YELLOW,
                    MenuUiSupport.hint("Copy the details into a Book and Quill")));
            return actions;
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP) return false;
            if (!(player instanceof ServerPlayer clicker)) return false;
            if (slot == BACK) {
                EconomySounds.click(clicker);
                openMine(clicker, eco);
                return true;
            }
            if (slot == CLOSE) {
                EconomySounds.click(clicker);
                clicker.closeContainer();
                return true;
            }
            if (slot == HOME) {
                EconomySounds.click(clicker);
                HubUi.open(clicker);
                return true;
            }
            if (slot < ACTION_ROW || slot >= ACTION_ROW + 9) return slot >= 0 && slot < 45;
            Contract c = eco.getContracts().getContract(contractId);
            if (c == null) {
                clicker.closeContainer();
                return true;
            }
            // Match the pressed button to its action by position in the current action list.
            List<ItemStack> actions = buildActions(c);
            int start = ACTION_ROW + Math.max(0, (9 - actions.size()) / 2);
            int pressed = slot - start;
            if (pressed < 0 || pressed >= actions.size()) return true;
            String name = actions.get(pressed).getHoverName().getString();
            EconomySounds.click(clicker);
            switch (name) {
                case "Accept Contract" -> doAccept(clicker, c.id);
                case "Submit Work" -> askSubmissionNotes(clicker, c.id);
                case "Approve and Pay" -> askApprove(clicker, c);
                case "Request Revision" -> askRevisionReason(clicker, c.id);
                case "Raise Dispute" -> askDisputeReason(clicker, c.id);
                case "Cancel Contract" -> askCancel(clicker, c);
                case "Copy to Book" -> doCopyToBook(clicker, eco, c.id);
                default -> {
                }
            }
            return true;
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            eco.getContracts().removeListener(listener);
        }
    }

    private static long netAfterTax(long reward) {
        return reward - TaxPolicy.resolve(TaxScope.TRANSACTION_CONTRACT, reward).amount();
    }

    private static List<String> buildBookLines(EconomyManager eco, UUID viewer, Contract c) {
        List<String> lines = new ArrayList<>();
        lines.add("#" + c.id + " " + c.title);
        lines.add("Status: " + c.status.label + " | " + c.category.label);
        lines.add("Posted by: " + displayName(eco, c.requester)
                + (c.requester.equals(viewer) ? " (you)" : ""));
        if (c.target != null) {
            lines.add("Offered to: " + displayName(eco, c.target));
        }
        if (c.contractor != null) {
            lines.add("Contractor: " + displayName(eco, c.contractor)
                    + (c.contractor.equals(viewer) ? " (you)" : ""));
        }
        lines.add("Reward: " + EconomyCraft.formatMoney(c.reward));
        lines.add("Deadline: " + deadlineLabel(c));
        if (c.description != null) lines.add("Details: " + c.description);
        if (c.submissionNotes != null) lines.add("Submitted: " + c.submissionNotes);
        if (c.reviewNotes != null) lines.add("Feedback: " + c.reviewNotes);
        if (c.status == Status.DISPUTED && c.disputeReason != null) {
            lines.add("Dispute: " + c.disputeReason);
        }
        if (c.resolution != null) lines.add("Resolution: " + c.resolution);
        return lines;
    }

    private static void doCopyToBook(ServerPlayer player, EconomyManager eco, int id) {
        Contract c = eco.getContracts().getContract(id);
        if (c == null) {
            EconomySounds.failure(player);
            player.closeContainer();
            return;
        }
        ItemStack book = ContractBook.makeBook(ContractBook.paginate(buildBookLines(eco, player.getUUID(), c)));
        boolean stored = false;
        if (!player.getInventory().add(book)) {
            eco.getDeliveries().addDelivery(player.getUUID(), book);
            stored = true;
        }
        EconomySounds.success(player);
        player.sendSystemMessage(Component.literal("Contract #" + id + " copied to a Book and Quill"
                        + (stored ? " in your deliveries." : " in your inventory."))
                .withStyle(ChatFormatting.GREEN));
    }

    private static List<Component> buildSubjectLore(EconomyManager eco, UUID viewer, Contract c) {
        List<Component> lore = new ArrayList<>();
        lore.add(MenuUiSupport.labeledValue("Status", c.status.label, statusColor(c.status)));
        lore.add(MenuUiSupport.labeledValue("Category", c.category.label, MenuUiSupport.LABEL_PRIMARY_COLOR));
        String poster = displayName(eco, c.requester) + (c.requester.equals(viewer) ? " (you)" : "");
        lore.add(MenuUiSupport.labeledValue("Posted by", poster, MenuUiSupport.LABEL_PRIMARY_COLOR));
        if (c.target != null) {
            lore.add(MenuUiSupport.labeledValue("Offered to", displayName(eco, c.target),
                    MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        if (c.contractor != null) {
            String who = displayName(eco, c.contractor) + (c.contractor.equals(viewer) ? " (you)" : "");
            lore.add(MenuUiSupport.labeledValue("Contractor", who, MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        lore.add(MenuUiSupport.labeledValue("Reward", EconomyCraft.formatMoney(c.reward),
                MenuUiSupport.LABEL_PRIMARY_COLOR));
        long tax = c.reward - netAfterTax(c.reward);
        if (tax > 0) {
            lore.add(MenuUiSupport.labeledValue("Contractor receives", EconomyCraft.formatMoney(c.reward - tax),
                    MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        if (c.escrow > 0 && c.status.active()) {
            lore.add(MenuUiSupport.labeledValue("In escrow", EconomyCraft.formatMoney(c.escrow),
                    MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        lore.add(MenuUiSupport.labeledValue("Deadline", deadlineLabel(c), MenuUiSupport.LABEL_PRIMARY_COLOR));
        if (c.status == Status.SUBMITTED && c.reviewDeadline > 0) {
            lore.add(MenuUiSupport.labeledValue("Auto-approves",
                    ExpirationUtil.expiresInLabel(c.reviewDeadline), MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        if (c.revisions > 0 || !c.revisionHistory.isEmpty()) {
            lore.add(MenuUiSupport.labeledValue("Revisions",
                    c.revisions + " of " + EconomyConfig.get().contractMaxRevisions,
                    MenuUiSupport.LABEL_PRIMARY_COLOR));
        }
        addMultilineLore(lore, "Details", c.description);
        addMultilineLore(lore, "Submitted work", c.submissionNotes);
        addMultilineLore(lore, "Latest feedback", c.reviewNotes);
        if (c.status == Status.DISPUTED) {
            lore.add(MenuUiSupport.labeledValue("Disputed by", displayName(eco, c.disputedBy), ChatFormatting.RED));
            addMultilineLore(lore, "Dispute reason", c.disputeReason);
        }
        if (c.resolution != null) {
            addMultilineLore(lore, "Resolution", c.resolution);
        }
        return lore;
    }

    // === detail actions: every one revalidates in ContractService at click time ===

    private static void doAccept(ServerPlayer player, int id) {
        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        ContractService.AcceptStatus result = ContractService.accept(eco, player.getUUID(), id);
        switch (result) {
            case OK -> {
                EconomySounds.success(player);
                player.sendSystemMessage(Component.literal("Contract #" + id + " accepted. Good luck!")
                        .withStyle(ChatFormatting.GREEN));
                openDetails(player, eco, id);
            }
            case NOT_OPEN -> {
                EconomySounds.failure(player);
                player.sendSystemMessage(Component.literal("That contract is no longer open.")
                        .withStyle(ChatFormatting.RED));
                open(player, eco);
            }
            case OWN_CONTRACT -> {
                EconomySounds.failure(player);
                player.sendSystemMessage(Component.literal("You can't accept your own contract.")
                        .withStyle(ChatFormatting.RED));
                openDetails(player, eco, id);
            }
            case WRONG_TARGET -> {
                EconomySounds.failure(player);
                player.sendSystemMessage(Component.literal("That contract was offered to someone else.")
                        .withStyle(ChatFormatting.RED));
                open(player, eco);
            }
            case LIMIT_REACHED -> {
                EconomySounds.failure(player);
                player.sendSystemMessage(Component.literal("You have reached your active contract limit.")
                        .withStyle(ChatFormatting.RED));
                openDetails(player, eco, id);
            }
            case NOT_FOUND -> {
                EconomySounds.failure(player);
                player.sendSystemMessage(Component.literal("That contract no longer exists.")
                        .withStyle(ChatFormatting.RED));
                open(player, eco);
            }
            case DISABLED -> {
                EconomySounds.failure(player);
                player.sendSystemMessage(Component.literal("Contracts are disabled.")
                        .withStyle(ChatFormatting.RED));
                player.closeContainer();
            }
        }
    }

    private static void askSubmissionNotes(ServerPlayer player, int id) {
        TextInputUi.open(player, "Submit work", "", Items.WRITABLE_BOOK,
                "Notes: ", "What did you deliver? (optional)", true,
                (picker, notes) -> {
                    EconomyManager eco = EconomyCraft.getManager(picker.level().getServer());
                    ContractService.SubmitStatus result = ContractService.submit(eco, picker.getUUID(), id,
                            Contract.sanitize(notes, Contract.MAX_TEXT_LENGTH));
                    switch (result) {
                        case OK -> {
                            EconomySounds.success(picker);
                            picker.sendSystemMessage(Component.literal("Work submitted for contract #" + id
                                    + ". The requester has been notified.").withStyle(ChatFormatting.GREEN));
                        }
                        case DEADLINE_MISSED -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal(
                                    "The deadline passed; this contract will expire instead.")
                                    .withStyle(ChatFormatting.RED));
                        }
                        case NOT_CONTRACTOR -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("Only the contractor can submit work.")
                                    .withStyle(ChatFormatting.RED));
                        }
                        case NOT_ACCEPTED, NOT_FOUND -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("That contract can't accept a submission.")
                                    .withStyle(ChatFormatting.RED));
                        }
                    }
                    openDetails(picker, eco, id);
                },
                picker -> openDetails(picker, EconomyCraft.getManager(picker.level().getServer()), id));
    }

    private static void askApprove(ServerPlayer player, Contract snapshot) {
        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        Contract c = eco.getContracts().getContract(snapshot.id);
        if (c == null) {
            openMine(player, eco);
            return;
        }
        long net = netAfterTax(c.reward);
        ConfirmUi.open(player, "Approve contract #" + c.id,
                MenuUiSupport.button(Items.EMERALD, "Pay " + EconomyCraft.formatMoney(net),
                        ChatFormatting.GREEN,
                        MenuUiSupport.labeledValue("Contractor", displayName(eco, c.contractor),
                                MenuUiSupport.LABEL_PRIMARY_COLOR),
                        MenuUiSupport.hint("This releases the escrow and cannot be undone")),
                "Approve and pay",
                List.of(MenuUiSupport.hint("Click to release the payment")),
                picker -> {
                    EconomyManager live = EconomyCraft.getManager(picker.level().getServer());
                    ContractService.ApproveResult result =
                            ContractService.approve(live, picker.getUUID(), snapshot.id);
                    switch (result.status()) {
                        case OK -> {
                            EconomySounds.moneyReceived(picker);
                            picker.sendSystemMessage(Component.literal("Contract #" + snapshot.id
                                    + " completed. Paid " + EconomyCraft.formatMoney(result.paid())
                                    + (result.tax() > 0 ? " (" + EconomyCraft.formatMoney(result.tax())
                                    + " tax)" : "") + ".").withStyle(ChatFormatting.GREEN));
                        }
                        case PAYOUT_FAILED -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal(
                                    "The payout failed and stays pending; it will retry automatically.")
                                    .withStyle(ChatFormatting.RED));
                        }
                        case NOT_SUBMITTED, REFUND_PENDING -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("That contract can't be approved right now.")
                                    .withStyle(ChatFormatting.RED));
                        }
                        case NOT_REQUESTER -> {
                            EconomySounds.failure(picker);
                            MenuUiSupport.denyPermission(picker);
                        }
                        case NOT_FOUND -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("That contract no longer exists.")
                                    .withStyle(ChatFormatting.RED));
                        }
                    }
                    openDetails(picker, live, snapshot.id);
                },
                picker -> openDetails(picker, EconomyCraft.getManager(picker.level().getServer()), snapshot.id));
    }

    private static void askRevisionReason(ServerPlayer player, int id) {
        TextInputUi.open(player, "Request revision", "", Items.REDSTONE,
                "Reason: ", "What needs to change?", false,
                (picker, reason) -> {
                    EconomyManager eco = EconomyCraft.getManager(picker.level().getServer());
                    ContractService.ReviseStatus result = ContractService.requestRevision(eco,
                            picker.getUUID(), id, reason);
                    switch (result) {
                        case OK -> {
                            EconomySounds.success(picker);
                            picker.sendSystemMessage(Component.literal("Revision requested for contract #" + id
                                    + ". The contractor has been notified.").withStyle(ChatFormatting.GREEN));
                        }
                        case REVISIONS_EXHAUSTED -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal(
                                    "No revisions left; raise a dispute instead if you can't agree.")
                                    .withStyle(ChatFormatting.RED));
                        }
                        case INVALID_REASON -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("Give a reason so the contractor knows what to fix.")
                                    .withStyle(ChatFormatting.RED));
                            askRevisionReason(picker, id);
                            return;
                        }
                        case NOT_REQUESTER -> {
                            EconomySounds.failure(picker);
                            MenuUiSupport.denyPermission(picker);
                        }
                        case NOT_SUBMITTED, NOT_FOUND -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("That contract can't be revised right now.")
                                    .withStyle(ChatFormatting.RED));
                        }
                    }
                    openDetails(picker, eco, id);
                },
                picker -> openDetails(picker, EconomyCraft.getManager(picker.level().getServer()), id));
    }

    private static void askDisputeReason(ServerPlayer player, int id) {
        TextInputUi.open(player, "Raise dispute", "", Items.TNT,
                "Reason: ", "What went wrong?", false,
                (picker, reason) -> {
                    EconomyManager eco = EconomyCraft.getManager(picker.level().getServer());
                    ContractService.DisputeStatus result = ContractService.raiseDispute(eco,
                            picker.getUUID(), id, reason);
                    switch (result) {
                        case OK -> {
                            EconomySounds.success(picker);
                            picker.sendSystemMessage(Component.literal("Contract #" + id
                                    + " is disputed and frozen. An administrator will resolve it.")
                                    .withStyle(ChatFormatting.YELLOW));
                        }
                        case NOT_DISPUTABLE -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal(
                                    "Only submitted work — or contracts out of revisions — can be disputed.")
                                    .withStyle(ChatFormatting.RED));
                        }
                        case INVALID_REASON -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("Give a reason for the dispute.")
                                    .withStyle(ChatFormatting.RED));
                            askDisputeReason(picker, id);
                            return;
                        }
                        case SETTLEMENT_PENDING -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal(
                                    "A payout or refund is already in progress for this contract.")
                                    .withStyle(ChatFormatting.RED));
                        }
                        case NOT_PARTICIPANT -> {
                            EconomySounds.failure(picker);
                            MenuUiSupport.denyPermission(picker);
                        }
                        case NOT_FOUND -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("That contract no longer exists.")
                                    .withStyle(ChatFormatting.RED));
                        }
                    }
                    openDetails(picker, eco, id);
                },
                picker -> openDetails(picker, EconomyCraft.getManager(picker.level().getServer()), id));
    }

    private static void askCancel(ServerPlayer player, Contract snapshot) {
        EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
        Contract c = eco.getContracts().getContract(snapshot.id);
        if (c == null) {
            openMine(player, eco);
            return;
        }
        boolean instantRefund = c.status == Status.OPEN;
        ConfirmUi.open(player, "Cancel contract #" + c.id,
                MenuUiSupport.button(Items.BARRIER, "Cancel \"" + c.title + "\"", ChatFormatting.RED,
                        instantRefund
                                ? MenuUiSupport.labeledValue("Refund",
                                        EconomyCraft.formatMoney(c.escrow), MenuUiSupport.LABEL_PRIMARY_COLOR)
                                : MenuUiSupport.hint("The other side must also agree")),
                instantRefund ? "Cancel and refund" : "Propose cancellation",
                List.of(MenuUiSupport.hint("Click to confirm")),
                picker -> {
                    EconomyManager live = EconomyCraft.getManager(picker.level().getServer());
                    ContractService.CancelStatus result =
                            ContractService.cancel(live, picker.getUUID(), snapshot.id);
                    switch (result) {
                        case OK -> {
                            EconomySounds.success(picker);
                            picker.sendSystemMessage(Component.literal("Contract #" + snapshot.id + " cancelled.")
                                    .withStyle(ChatFormatting.GREEN));
                            openMine(picker, live);
                        }
                        case REQUEST_SENT -> {
                            EconomySounds.success(picker);
                            picker.sendSystemMessage(Component.literal("Cancellation proposed for contract #"
                                    + snapshot.id + ". The other side must confirm.")
                                    .withStyle(ChatFormatting.YELLOW));
                            openDetails(picker, live, snapshot.id);
                        }
                        case REQUEST_PENDING -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal(
                                    "A cancellation or settlement is already in progress.")
                                    .withStyle(ChatFormatting.RED));
                            openDetails(picker, live, snapshot.id);
                        }
                        case REFUND_FAILED -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal(
                                    "The refund failed and stays pending; it will retry automatically.")
                                    .withStyle(ChatFormatting.RED));
                            openDetails(picker, live, snapshot.id);
                        }
                        case DISPUTED -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal(
                                    "A disputed contract can only be resolved by an administrator.")
                                    .withStyle(ChatFormatting.RED));
                            openDetails(picker, live, snapshot.id);
                        }
                        case NOT_PARTICIPANT -> {
                            EconomySounds.failure(picker);
                            MenuUiSupport.denyPermission(picker);
                        }
                        case NOT_CANCELLABLE, NOT_FOUND -> {
                            EconomySounds.failure(picker);
                            picker.sendSystemMessage(Component.literal("That contract can't be cancelled.")
                                    .withStyle(ChatFormatting.RED));
                            openMine(picker, live);
                        }
                    }
                },
                picker -> openDetails(picker, EconomyCraft.getManager(picker.level().getServer()), snapshot.id));
    }

    // === creation menus ===

    private static void openVisibility(ServerPlayer player, EconomyManager eco) {
        MenuUiSupport.openMenu(player, "Contract visibility", (id, inv) ->
                new VisibilityMenu(id, inv, player, eco));
    }

    private static final class VisibilityMenu extends CompatMenu {
        private static final int CANCEL = 2;
        private static final int PUBLIC = 3;
        private static final int TARGETED = 5;

        private final ServerPlayer viewer;
        private final EconomyManager eco;
        private final SimpleContainer container = new SimpleContainer(9);

        VisibilityMenu(int id, Inventory inv, ServerPlayer viewer, EconomyManager eco) {
            super(MenuType.GENERIC_9x1, id);
            this.viewer = viewer;
            this.eco = eco;
            addSlotRange(MenuUiSupport.readOnlyGridSlots(container, 9));
            addSlotRange(MenuUiSupport.playerInventorySlots(inv, 18 + 18 + 14));
            MenuUiSupport.fillBackground(container);
            container.setItem(CANCEL, MenuUiSupport.cancelButton());
            container.setItem(PUBLIC, MenuUiSupport.button(Items.PAPER, "Public Contract",
                    ChatFormatting.GREEN,
                    MenuUiSupport.hint("Anyone can accept it.")));
            container.setItem(TARGETED, MenuUiSupport.button(Items.PLAYER_HEAD, "Targeted Contract",
                    ChatFormatting.AQUA,
                    MenuUiSupport.hint("Only one chosen player can accept.")));
            broadcastChanges();
        }

        private void addSlotRange(List<net.minecraft.world.inventory.Slot> slots) {
            for (net.minecraft.world.inventory.Slot s : slots) addSlot(s);
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP) return false;
            if (!(player instanceof ServerPlayer clicker)) return false;
            if (slot == CANCEL) {
                EconomySounds.click(clicker);
                open(clicker, eco);
                return true;
            }
            if (slot == PUBLIC) {
                EconomySounds.click(clicker);
                Draft draft = new Draft();
                draft.targeted = false;
                chooseCategory(clicker, eco, draft);
                return true;
            }
            if (slot == TARGETED) {
                EconomySounds.click(clicker);
                PlayerPickerUi.open(clicker, "Contract for whom?", false,
                        (picker, target) -> {
                            Draft draft = new Draft();
                            draft.targeted = true;
                            draft.target = target.id();
                            draft.targetName = target.name();
                            chooseCategory(picker, eco, draft);
                        },
                        picker -> openVisibility(picker, eco));
                return true;
            }
            return slot >= 0 && slot < 9;
        }
    }

    private static final class CategoryMenu extends CompatMenu {
        private static final int CANCEL = 8;

        private final ServerPlayer viewer;
        private final EconomyManager eco;
        private final Draft draft;
        private final SimpleContainer container = new SimpleContainer(9);

        private record CategoryIcon(Category category, net.minecraft.world.item.Item item) {}

        private static final CategoryIcon[] ICONS = {
                new CategoryIcon(Category.BUILD, Items.BRICKS),
                new CategoryIcon(Category.EXCAVATE, Items.DIAMOND_SHOVEL),
                new CategoryIcon(Category.FARM, Items.WHEAT),
                new CategoryIcon(Category.HUNT, Items.BOW),
                new CategoryIcon(Category.EXPLORE, Items.COMPASS),
                new CategoryIcon(Category.OTHER, Items.BOOK),
        };

        CategoryMenu(int id, Inventory inv, ServerPlayer viewer, EconomyManager eco, Draft draft) {
            super(MenuType.GENERIC_9x1, id);
            this.viewer = viewer;
            this.eco = eco;
            this.draft = draft;
            addSlotRange(MenuUiSupport.readOnlyGridSlots(container, 9));
            addSlotRange(MenuUiSupport.playerInventorySlots(inv, 18 + 18 + 14));
            MenuUiSupport.fillBackground(container);
            for (int i = 0; i < ICONS.length; i++) {
                container.setItem(i + 1, MenuUiSupport.button(ICONS[i].item(), ICONS[i].category().label,
                        ChatFormatting.GOLD));
            }
            container.setItem(CANCEL, MenuUiSupport.cancelButton());
            broadcastChanges();
        }

        private void addSlotRange(List<net.minecraft.world.inventory.Slot> slots) {
            for (net.minecraft.world.inventory.Slot s : slots) addSlot(s);
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (kind != ClickKind.PICKUP) return false;
            if (!(player instanceof ServerPlayer clicker)) return false;
            if (slot == CANCEL) {
                EconomySounds.click(clicker);
                openVisibility(clicker, eco);
                return true;
            }
            int index = slot - 1;
            if (index >= 0 && index < ICONS.length) {
                EconomySounds.click(clicker);
                draft.category = ICONS[index].category();
                chooseTitle(clicker, eco, draft);
                return true;
            }
            return slot >= 0 && slot < 9;
        }
    }
}

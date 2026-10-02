package com.reazip.economycraft.tag;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.TagSettings;
import com.reazip.economycraft.faction.FactionId;
import com.reazip.economycraft.faction.FactionStore;
import com.reazip.economycraft.profession.ProfessionId;
import com.reazip.economycraft.profession.ProfessionStore;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.ConfirmUi;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.MenuUiSupport;
import com.reazip.economycraft.util.TimeFormat;
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

/**
 * The D16 tag-selection menu: {@code /tag} opens this, and it is also reachable from the {@code /eco} hub.
 *
 * <p>Two sections — Party and Profession — each behind a {@link ConfirmUi} screen that states the 30 h lockout
 * in plain words (D17) <em>before</em> the player commits. An option whose 30 h clock has not expired is shown
 * visibly locked with its remaining time rather than silently rejected on click, because a 30 h commitment
 * should never be a surprise.
 *
 * <p>Menu style is deliberately the existing one — {@code MenuUiSupport}/{@code ConfirmUi}, no registered
 * {@code MenuType} — so this stays inside §1 rule 1.
 */
public final class TagUi {
    private TagUi() {}

    private static final int BACK = 0;
    private static final int PARTY_ROW = 11;
    private static final int PROFESSION_ROW = 15;
    private static final int CURRENT_TAGS = 22;

    public static void open(ServerPlayer player) {
        if (!EconomyPermissions.checkCommand(player, EconomyPermissions.Nodes.COMMAND_TAG)) {
            MenuUiSupport.denyPermission(player);
            return;
        }
        openMain(player);
    }

    private static void openMain(ServerPlayer player) {
        MenuUiSupport.openMenu(player, "Tags", (id, inv) -> new MainMenu(id, inv, player));
    }

    private static void openPartyMenu(ServerPlayer player) {
        MenuUiSupport.openMenu(player, "Choose Party", (id, inv) -> new PartyMenu(id, inv, player));
    }

    private static void openProfessionMenu(ServerPlayer player) {
        MenuUiSupport.openMenu(player, "Choose Profession", (id, inv) -> new ProfessionMenu(id, inv, player));
    }

    private static class MainMenu extends CompatMenu {
        private final ServerPlayer viewer;
        private final EconomyManager eco;
        private final SimpleContainer container = new SimpleContainer(27);

        MainMenu(int id, Inventory inv, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.eco = EconomyCraft.getManager(viewer.level().getServer());

            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, 27)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + 3 * 18 + 14)) {
                this.addSlot(slot);
            }
            render();
        }

        private void render() {
            container.clearContent();
            container.setItem(BACK, MenuUiSupport.closeButton());
            container.setItem(CURRENT_TAGS, createCurrentTags());
            container.setItem(PARTY_ROW, MenuUiSupport.button(Items.DIAMOND, "Party", ChatFormatting.GOLD,
                    MenuUiSupport.hint("Choose your political party.")));
            container.setItem(PROFESSION_ROW, MenuUiSupport.button(Items.IRON_PICKAXE, "Profession", ChatFormatting.AQUA,
                    MenuUiSupport.hint("Choose your profession.")));
            MenuUiSupport.fillBackground(container);
        }

        private ItemStack createCurrentTags() {
            ItemStack stack = new ItemStack(Items.NAME_TAG);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Your Tags")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.YELLOW)));
            List<Component> lore = new ArrayList<>();
            List<TagStyle.Tagged> tags = eco.getTagDisplay().tagsOf(viewer.getUUID());
            if (tags.isEmpty()) {
                lore.add(MenuUiSupport.hint("No tags selected yet"));
            } else {
                for (TagStyle.Tagged tag : tags) {
                    lore.add(Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY))
                            .append(tag.full()));
                }
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (slot < 0 || slot >= 27) return false;
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return true;

            if (slot == BACK) {
                EconomySounds.click(viewer);
                viewer.closeContainer();
                return true;
            }
            if (slot == PARTY_ROW) {
                EconomySounds.click(viewer);
                viewer.closeContainer();
                openPartyMenu(viewer);
                return true;
            }
            if (slot == PROFESSION_ROW) {
                EconomySounds.click(viewer);
                viewer.closeContainer();
                openProfessionMenu(viewer);
                return true;
            }
            return true;
        }
    }

    private static class PartyMenu extends CompatMenu {
        private final ServerPlayer viewer;
        private final EconomyManager eco;
        private final SimpleContainer container = new SimpleContainer(27);
        private final FactionId[] factions = FactionId.values();
        private final int start = 9 + (9 - FactionId.values().length) / 2;

        PartyMenu(int id, Inventory inv, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.eco = EconomyCraft.getManager(viewer.level().getServer());

            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, 27)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + 3 * 18 + 14)) {
                this.addSlot(slot);
            }
            render();
        }

        private void render() {
            container.clearContent();
            container.setItem(BACK, MenuUiSupport.backButton());
            for (int i = 0; i < factions.length; i++) {
                container.setItem(start + i, createFactionButton(factions[i]));
            }
            MenuUiSupport.fillBackground(container);
        }

        private ItemStack createFactionButton(FactionId faction) {
            TagSettings settings = faction.settings();
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(faction.displayName())
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(settings.color)));
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint("Current: " + currentLabel()));
            long lockout = EconomyConfig.get().factions.selectionLockoutHours;
            if (!eco.getFactions().canChange(viewer.getUUID(), lockout)) {
                lore.add(MenuUiSupport.line("Locked for "
                        + TimeFormat.formatDuration(eco.getFactions().remainingCooldownMillis(viewer.getUUID(), lockout)),
                        ChatFormatting.RED));
            } else {
                lore.add(MenuUiSupport.line("Click to select", ChatFormatting.GREEN));
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private String currentLabel() {
            FactionStore store = eco.getFactions();
            if (store.hasChosen(viewer.getUUID())) return store.factionOf(viewer.getUUID()).displayName();
            return FactionId.defaultFaction().displayName() + " (default)";
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (slot < 0 || slot >= 27) return false;
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return true;

            if (slot == BACK) {
                EconomySounds.click(viewer);
                viewer.closeContainer();
                openMain(viewer);
                return true;
            }
            for (int i = 0; i < factions.length; i++) {
                if (slot == start + i) {
                    confirmParty(factions[i]);
                    return true;
                }
            }
            return true;
        }

        private void confirmParty(FactionId faction) {
            long lockout = EconomyConfig.get().factions.selectionLockoutHours;
            if (!eco.getFactions().canChange(viewer.getUUID(), lockout)) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(MenuUiSupport.line(
                        "You cannot change party yet. Try again in "
                                + TimeFormat.formatDuration(eco.getFactions().remainingCooldownMillis(viewer.getUUID(), lockout)) + ".",
                        ChatFormatting.RED));
                openMain(viewer);
                return;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint("Party: " + faction.displayName()));
            lore.add(MenuUiSupport.line("This starts a 30 h lockout.", ChatFormatting.YELLOW));
            lore.add(MenuUiSupport.hint("You cannot change party or profession"));
            lore.add(MenuUiSupport.hint("for 30 hours after choosing."));
            ConfirmUi.open(viewer, "Confirm Party", new ItemStack(Items.DIAMOND), "Confirm " + faction.displayName(), lore,
                    p -> {
                        eco.getFactions().select(p.getUUID(), faction);
                        eco.getTagDisplay().refresh(p);
                        EconomySounds.success(p);
                        p.sendSystemMessage(MenuUiSupport.line("Party set to " + faction.displayName(), ChatFormatting.GREEN));
                        openMain(p);
                    },
                    p -> openMain(p));
        }
    }

    private static class ProfessionMenu extends CompatMenu {
        private final ServerPlayer viewer;
        private final EconomyManager eco;
        private final SimpleContainer container = new SimpleContainer(27);
        private final ProfessionId[] professions = ProfessionId.values();
        private final int start = 9 + (9 - ProfessionId.values().length) / 2;

        ProfessionMenu(int id, Inventory inv, ServerPlayer viewer) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.eco = EconomyCraft.getManager(viewer.level().getServer());

            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, 27)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + 3 * 18 + 14)) {
                this.addSlot(slot);
            }
            render();
        }

        private void render() {
            container.clearContent();
            container.setItem(BACK, MenuUiSupport.backButton());
            for (int i = 0; i < professions.length; i++) {
                container.setItem(start + i, createProfessionButton(professions[i]));
            }
            MenuUiSupport.fillBackground(container);
        }

        private ItemStack createProfessionButton(ProfessionId profession) {
            TagSettings settings = profession.settings();
            ItemStack stack = new ItemStack(Items.IRON_PICKAXE);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(profession.displayName())
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(settings.color)));
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint("Current: " + currentLabel()));
            long lockout = EconomyConfig.get().professions.selectionLockoutHours;
            if (!eco.getProfessions().canChange(viewer.getUUID(), lockout)) {
                lore.add(MenuUiSupport.line("Locked for "
                        + TimeFormat.formatDuration(eco.getProfessions().remainingCooldownMillis(viewer.getUUID(), lockout)),
                        ChatFormatting.RED));
            } else {
                lore.add(MenuUiSupport.line("Click to select", ChatFormatting.GREEN));
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private String currentLabel() {
            ProfessionStore store = eco.getProfessions();
            ProfessionId current = store.professionOf(viewer.getUUID());
            if (current == null) return "None";
            var level = store.levelOf(viewer.getUUID());
            return level == null ? current.displayName() : current.displayName() + " (" + level.displayName() + ")";
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (slot < 0 || slot >= 27) return false;
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return true;

            if (slot == BACK) {
                EconomySounds.click(viewer);
                viewer.closeContainer();
                openMain(viewer);
                return true;
            }
            for (int i = 0; i < professions.length; i++) {
                if (slot == start + i) {
                    confirmProfession(professions[i]);
                    return true;
                }
            }
            return true;
        }

        private void confirmProfession(ProfessionId profession) {
            long lockout = EconomyConfig.get().professions.selectionLockoutHours;
            if (!eco.getProfessions().canChange(viewer.getUUID(), lockout)) {
                EconomySounds.failure(viewer);
                viewer.sendSystemMessage(MenuUiSupport.line(
                        "You cannot change profession yet. Try again in "
                                + TimeFormat.formatDuration(eco.getProfessions().remainingCooldownMillis(viewer.getUUID(), lockout)) + ".",
                        ChatFormatting.RED));
                openMain(viewer);
                return;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint("Profession: " + profession.displayName()));
            lore.add(MenuUiSupport.line("This starts a 30 h lockout.", ChatFormatting.YELLOW));
            lore.add(MenuUiSupport.hint("You cannot change party or profession"));
            lore.add(MenuUiSupport.hint("for 30 hours after choosing."));
            lore.add(MenuUiSupport.hint("Changing profession resets progress."));
            ConfirmUi.open(viewer, "Confirm Profession", new ItemStack(Items.IRON_PICKAXE), "Confirm " + profession.displayName(), lore,
                    p -> {
                        eco.getProfessions().select(p.getUUID(), profession);
                        eco.getTagDisplay().refresh(p);
                        EconomySounds.success(p);
                        p.sendSystemMessage(MenuUiSupport.line("Profession set to " + profession.displayName(), ChatFormatting.GREEN));
                        openMain(p);
                    },
                    p -> openMain(p));
        }
    }
}

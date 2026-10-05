package com.reazip.economycraft.admin;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.QuestsSection;
import com.reazip.economycraft.quests.QuestLogic;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.ConfirmUi;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomyPermissions.Nodes;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.MenuUiSupport;
import com.reazip.economycraft.util.NumberInputUi;
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

import java.util.List;

/**
 * The quest board's admin screen: live tuning without touching files.
 *
 * <p>Both fractions apply on the next quest sweep (a minute or two) — factor edits converge the
 * open orders automatically, and the shop-price gate takes new draws and buyback listings with
 * it. The reset period is read live too, so shortening it rolls the board over on the next sweep;
 * that case confirms first, since it cancels the quests players are standing on. Only hand-editing
 * the JSON files on disk still needs Reload from disk.
 */
public final class AdminQuestsUi {
    private AdminQuestsUi() {}

    private static final int SIZE = 27;
    private static final int BOARD = 10;
    private static final int BUYBACK = 11;
    private static final int SHOP_GATE = 12;
    private static final int PRICE_FACTOR = 13;
    private static final int BUYBACK_FACTOR = 14;
    private static final int BUDGET = 15;
    private static final int FORCE_REDRAW = 16;
    private static final int PERIOD = 17;
    private static final int BACK = 18;

    public static void open(ServerPlayer player, EconomyManager eco) {
        if (!MenuUiSupport.checkOrDeny(player, EconomyPermissions.checkAdmin(player, Nodes.ADMIN_SETTINGS))) return;
        MenuUiSupport.openMenu(player, "Server Quests", (id, inv) -> new QuestsMenu(id, inv, player, eco));
    }

    private static class QuestsMenu extends CompatMenu {
        private final ServerPlayer viewer;
        private final EconomyManager eco;
        private final SimpleContainer container = new SimpleContainer(SIZE);

        QuestsMenu(int id, Inventory inv, ServerPlayer viewer, EconomyManager eco) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.eco = eco;

            for (Slot slot : MenuUiSupport.readOnlyGridSlots(container, SIZE)) {
                this.addSlot(slot);
            }
            for (Slot slot : MenuUiSupport.playerInventorySlots(inv, 18 + 3 * 18 + 14)) {
                this.addSlot(slot);
            }
            render();
        }

        private void render() {
            container.clearContent();
            var quests = EconomyConfig.get().quests;

            container.setItem(BOARD, MenuUiSupport.button(Items.WRITABLE_BOOK, "Bounty Board", ChatFormatting.GREEN,
                    stateLine(quests.enabled),
                    MenuUiSupport.hint("The server's automatic buy orders."),
                    MenuUiSupport.hint("Click to switch on or off.")));

            container.setItem(BUYBACK, MenuUiSupport.button(Items.CHEST, "Buyback Market", ChatFormatting.GREEN,
                    stateLine(quests.buyback.enabled),
                    MenuUiSupport.hint("Resell quest stock on /ah."),
                    MenuUiSupport.hint("Click to switch on or off.")));

            container.setItem(SHOP_GATE, MenuUiSupport.button(Items.EMERALD, "Shop-Only Pool", ChatFormatting.AQUA,
                    stateLine(quests.requireShopPrice),
                    MenuUiSupport.hint("Only items with a shop price"),
                    MenuUiSupport.hint("can be drawn or listed back."),
                    MenuUiSupport.hint("Click to switch on or off.")));

            container.setItem(PRICE_FACTOR, MenuUiSupport.button(Items.PAPER, "Order Fraction", ChatFormatting.YELLOW,
                    MenuUiSupport.line("Pays " + percent(quests.priceFactor) + " of effective buy.", ChatFormatting.WHITE),
                    MenuUiSupport.hint("Click to change. Live on next sweep.")));

            container.setItem(BUYBACK_FACTOR, MenuUiSupport.button(Items.PAPER, "Listing Fraction", ChatFormatting.YELLOW,
                    MenuUiSupport.line("Charges " + percent(quests.buyback.priceFactor) + " of effective buy.", ChatFormatting.WHITE),
                    MenuUiSupport.hint("Click to change. Live on next sweep.")));

            container.setItem(BUDGET, MenuUiSupport.button(Items.GOLD_INGOT, "Period Budget", ChatFormatting.GOLD,
                    MenuUiSupport.line(EconomyCraft.formatMoney(quests.weeklyBudget) + " per period.", ChatFormatting.WHITE),
                    MenuUiSupport.hint("Click to change.")));

            container.setItem(PERIOD, MenuUiSupport.button(Items.DAYLIGHT_DETECTOR, "Reset Period", ChatFormatting.LIGHT_PURPLE,
                    MenuUiSupport.line("Board re-draws every " + pluralDays(quests.periodDays) + ".", ChatFormatting.WHITE),
                    periodCountdownLine(eco),
                    MenuUiSupport.hint("Click to change.")));

            if (EconomyPermissions.checkAdmin(viewer, Nodes.ADMIN_RESET)) {
                container.setItem(FORCE_REDRAW, MenuUiSupport.button(Items.RECOVERY_COMPASS, "Force Re-draw", ChatFormatting.RED,
                        MenuUiSupport.hint("Cancels every quest order and"),
                        MenuUiSupport.hint("posts a fresh board right now."),
                        MenuUiSupport.line("Escrow refunds. Mint cap stays spent.", ChatFormatting.RED)));
            }

            container.setItem(BACK, MenuUiSupport.backButton());
            MenuUiSupport.fillBackground(container);
        }

        private static Component stateLine(boolean on) {
            return MenuUiSupport.line(on ? "On" : "Off", on ? ChatFormatting.GREEN : ChatFormatting.RED);
        }

        private static String percent(double fraction) {
            return Math.round(fraction * 100) + "%";
        }

        private static String pluralDays(int days) {
            return days + " day" + (days == 1 ? "" : "s");
        }

        /**
         * The next reset, as {@code Nd Nh}. Worth showing here because the period is anchored to the
         * last rollover rather than to a calendar boundary, so an admin who never touched the setting
         * has no other way to see when the board will actually turn over.
         */
        private static Component periodCountdownLine(EconomyManager eco) {
            long remaining = eco.getQuests().millisUntilPeriodEnd(System.currentTimeMillis());
            if (remaining <= 0) {
                return MenuUiSupport.hint("No board running — next sweep draws one.");
            }
            long totalMinutes = remaining / 60_000L;
            long days = totalMinutes / 1440L;
            long hours = (totalMinutes % 1440L) / 60L;
            return MenuUiSupport.hint("Next reset in " + (days > 0 ? days + "d " : "") + hours + "h.");
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (slot < 0 || slot >= SIZE) return false;
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return true;
            if (!EconomyPermissions.checkAdmin(viewer, Nodes.ADMIN_SETTINGS)) return true;

            var quests = EconomyConfig.get().quests;
            switch (slot) {
                case BOARD -> {
                    EconomySounds.click(viewer);
                    quests.enabled = !quests.enabled;
                    EconomyConfig.save();
                    render();
                }
                case BUYBACK -> {
                    EconomySounds.click(viewer);
                    quests.buyback.enabled = !quests.buyback.enabled;
                    EconomyConfig.save();
                    render();
                }
                case SHOP_GATE -> {
                    EconomySounds.click(viewer);
                    quests.requireShopPrice = !quests.requireShopPrice;
                    EconomyConfig.save();
                    render();
                }
                case PRICE_FACTOR -> {
                    EconomySounds.click(viewer);
                    NumberInputUi.openPercent(viewer, "Order fraction", new ItemStack(Items.PAPER),
                            "Order fraction", Math.round(quests.priceFactor * 100),
                            (p, next) -> {
                                EconomyConfig.get().quests.priceFactor = next / 100.0;
                                EconomyConfig.save();
                                EconomySounds.click(p);
                                open(p, eco);
                            },
                            p -> open(p, eco));
                }
                case BUYBACK_FACTOR -> {
                    EconomySounds.click(viewer);
                    NumberInputUi.openPercent(viewer, "Listing fraction", new ItemStack(Items.PAPER),
                            "Listing fraction", Math.round(quests.buyback.priceFactor * 100),
                            (p, next) -> {
                                EconomyConfig.get().quests.buyback.priceFactor = next / 100.0;
                                EconomyConfig.save();
                                EconomySounds.click(p);
                                open(p, eco);
                            },
                            p -> open(p, eco));
                }
                case BUDGET -> {
                    EconomySounds.click(viewer);
                    NumberInputUi.openMoney(viewer, "Weekly budget", new ItemStack(Items.GOLD_INGOT),
                            "Weekly budget", quests.weeklyBudget, 0, EconomyManager.MAX,
                            (p, next) -> {
                                EconomyConfig.get().quests.weeklyBudget = next;
                                EconomyConfig.save();
                                EconomySounds.click(p);
                                open(p, eco);
                            },
                            p -> open(p, eco));
                }
                case PERIOD -> {
                    EconomySounds.click(viewer);
                    openPeriodInput(viewer, eco, quests.periodDays);
                }
                case FORCE_REDRAW -> {
                    if (!EconomyPermissions.checkAdmin(viewer, Nodes.ADMIN_RESET)) return true;
                    EconomySounds.click(viewer);
                    confirmForceRedraw(viewer, eco);
                }
                case BACK -> {
                    EconomySounds.click(viewer);
                    AdminUi.open(viewer, eco);
                }
                default -> {
                }
            }
            return true;
        }
    }

    /**
     * Changing the period to something shorter than the time already served rolls the board over on the
     * next sweep, which cancels every open quest. That is the honest reading of "reset every 3 days"
     * applied to a board five days in, but it is a destructive action discovered by a player finding
     * their bounty cancelled — so it gets a confirmation naming exactly what is still open.
     */
    private static void openPeriodInput(ServerPlayer viewer, EconomyManager eco, int currentDays) {
        NumberInputUi.openDays(viewer, "Reset period", new ItemStack(Items.DAYLIGHT_DETECTOR),
                "Reset period", currentDays, 1, QuestsSection.MAX_PERIOD_DAYS,
                (p, next) -> {
                    int days = (int) next.longValue();
                    if (days < currentDays) {
                        confirmShortenPeriod(p, eco, currentDays, days);
                        return;
                    }
                    EconomyConfig.get().quests.periodDays = days;
                    EconomyConfig.save();
                    EconomySounds.click(p);
                    open(p, eco);
                },
                p -> open(p, eco));
    }

    private static void confirmShortenPeriod(ServerPlayer viewer, EconomyManager eco, int fromDays, int toDays) {
        long remaining = eco.getQuests().millisUntilPeriodEnd(System.currentTimeMillis());
        int open = eco.getQuests().openQuestCountNow(eco);
        boolean rollsNow = remaining <= 0 || remaining <= QuestLogic.periodMillis(toDays);

        ItemStack icon = new ItemStack(Items.DAYLIGHT_DETECTOR);
        icon.set(DataComponents.CUSTOM_NAME, Component.literal("Shorten the reset period?")
                .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
        List<Component> warning = List.of(
                MenuUiSupport.line("From " + fromDays + " day" + (fromDays == 1 ? "" : "s")
                        + " down to " + toDays + ".", ChatFormatting.WHITE),
                MenuUiSupport.line(open + " open quest order" + (open == 1 ? "" : "s")
                        + (open == 1 ? " is" : " are") + " cancelled.", ChatFormatting.RED),
                MenuUiSupport.hint("Escrow refunds to the server. The mint cap is spent either way."),
                rollsNow
                        ? MenuUiSupport.line("The board re-draws within a minute.", ChatFormatting.RED)
                        : MenuUiSupport.line("The current board runs out as it stands.", ChatFormatting.GOLD));
        ConfirmUi.open(viewer, "Reset every " + toDays + " day" + (toDays == 1 ? "" : "s") + "?", icon,
                "Shorten period",
                warning,
                p -> {
                    EconomyConfig.get().quests.periodDays = toDays;
                    EconomyConfig.save();
                    EconomySounds.click(p);
                    p.sendSystemMessage(Component.literal("Quest board now re-draws every "
                            + toDays + " day" + (toDays == 1 ? "" : "s") + ".")
                            .withStyle(ChatFormatting.GOLD));
                    open(p, eco);
                },
                p -> open(p, eco));
    }

    private static void confirmForceRedraw(ServerPlayer viewer, EconomyManager eco) {
        ItemStack icon = new ItemStack(Items.RECOVERY_COMPASS);
        icon.set(DataComponents.CUSTOM_NAME, Component.literal("Force Re-draw")
                .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
        ConfirmUi.open(viewer, "Re-draw the quest board?", icon,
                "Re-draw now",
                List.of(MenuUiSupport.line("Every open quest order is cancelled.", ChatFormatting.RED),
                        MenuUiSupport.line("A fresh board posts immediately.", ChatFormatting.RED),
                        MenuUiSupport.hint("Escrow refunds to the server. The week's mint cap stays spent.")),
                p -> {
                    int posted = eco.getQuests().forceNewWeek(eco);
                    EconomySounds.click(p);
                    p.sendSystemMessage(Component.literal("Quest board re-drawn: " + posted + " quest(s) posted.")
                            .withStyle(ChatFormatting.GREEN));
                    open(p, eco);
                },
                p -> open(p, eco));
    }
}

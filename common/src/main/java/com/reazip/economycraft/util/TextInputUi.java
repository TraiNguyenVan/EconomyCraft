package com.reazip.economycraft.util;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.function.BiConsumer;

public final class TextInputUi {
    private TextInputUi() {}

    public static void openSearch(ServerPlayer player, String title, BiConsumer<ServerPlayer, String> onSearch) {
        open(player, title, "", Items.COMPASS, "Search: ", "Type to search", onSearch);
    }

    public static void open(ServerPlayer player, String title, String initial, Item icon,
                            String confirmPrefix, String placeholder, BiConsumer<ServerPlayer, String> onConfirm) {
        open(player, title, initial, icon, confirmPrefix, placeholder, false, onConfirm, null);
    }

    public static void open(ServerPlayer player, String title, String initial, Item icon,
                            String confirmPrefix, String placeholder, boolean allowEmpty,
                            BiConsumer<ServerPlayer, String> onConfirm, java.util.function.Consumer<ServerPlayer> onCancel) {
        MenuUiSupport.openMenu(player, title, (id, inv) ->
                new InputMenu(id, inv, initial, icon, confirmPrefix, placeholder, allowEmpty, onConfirm, onCancel));
    }

    private static class InputMenu extends AnvilMenu {
        private final Item icon;
        private final String confirmPrefix;
        private final String placeholder;
        private final boolean allowEmpty;
        private final BiConsumer<ServerPlayer, String> onConfirm;
        private final java.util.function.Consumer<ServerPlayer> onCancel;
        private String text;

        InputMenu(int id, Inventory inv, String initial, Item icon, String confirmPrefix, String placeholder,
                  boolean allowEmpty, BiConsumer<ServerPlayer, String> onConfirm, java.util.function.Consumer<ServerPlayer> onCancel) {
            super(id, inv, ContainerLevelAccess.NULL);
            this.icon = icon;
            this.confirmPrefix = confirmPrefix;
            this.placeholder = placeholder;
            this.allowEmpty = allowEmpty;
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
            this.text = initial == null ? "" : initial;

            ItemStack input = new ItemStack(Items.PAPER);
            input.set(DataComponents.CUSTOM_NAME, Component.literal(this.text));
            this.inputSlots.setItem(INPUT_SLOT, input);
            lockInputSlot(INPUT_SLOT);
            lockInputSlot(ADDITIONAL_SLOT);
            renderResult();
        }

        private void lockInputSlot(int index) {
            Slot original = this.slots.get(index);
            this.slots.set(index, MenuUiSupport.lockedSlot(this.inputSlots, index, original.x, original.y));
        }

        @Override
        public boolean setItemName(String name) {
            this.text = name;
            renderResult();
            return true;
        }

        private void renderResult() {
            String value = text == null ? "" : text;
            ItemStack result = new ItemStack(icon);
            Component name;
            String hintText;
            if (value.isBlank()) {
                name = Component.literal(placeholder).withStyle(s -> s.withItalic(false));
                hintText = allowEmpty ? "Click here to skip (leave empty)" : "Type in the field above";
            } else {
                name = Component.literal(confirmPrefix + value)
                        .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GREEN));
                hintText = "Click here to confirm";
            }
            result.set(DataComponents.CUSTOM_NAME, name);
            result.set(DataComponents.LORE, new ItemLore(List.of(MenuUiSupport.hint(hintText))));
            this.resultSlots.setItem(0, result);
        }

        private boolean tookResult = false;

        @Override
        protected boolean mayPickup(Player player, boolean hasItem) {
            return hasItem && (allowEmpty || (text != null && !text.isBlank()));
        }

        @Override
        protected void onTake(Player player, ItemStack stack) {
            this.tookResult = true;
            String value = text;
            this.setCarried(ItemStack.EMPTY);
            player.closeContainer();
            ServerPlayer serverPlayer = (ServerPlayer) player;
            serverPlayer.connection.send(new ClientboundSetExperiencePacket(
                    serverPlayer.experienceProgress, serverPlayer.totalExperience, serverPlayer.experienceLevel));
            EconomySounds.click(serverPlayer);
            onConfirm.accept(serverPlayer, (value != null && !value.isBlank()) ? value.trim() : "");
        }

        @Override
        public void removed(Player player) {
            super.removed(player);
            if (!tookResult && onCancel != null && player instanceof ServerPlayer serverPlayer) {
                onCancel.accept(serverPlayer);
            }
        }

        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return ItemStack.EMPTY;
        }
    }
}

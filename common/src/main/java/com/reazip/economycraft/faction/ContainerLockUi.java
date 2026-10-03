package com.reazip.economycraft.faction;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.MenuUiSupport;
import com.reazip.economycraft.util.PermissionCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Menu UI for choosing container lock modes (spec line 10, D10).
 *
 * <p>Provides accessible, visual buttons for players to toggle between the 2 or 3 lock modes:
 * <ul>
 *   <li>{@link ContainerLockMode#UNLOCKED} — Ai cũng có thể mở (Mặc định).</li>
 *   <li>{@link ContainerLockMode#PRIVATE} — Chỉ có chính bạn mới có thể mở.</li>
 *   <li>{@link ContainerLockMode#PARTY_ONLY} — Chỉ thành viên Đảng Cộng sản mới có thể mở.</li>
 * </ul>
 * Also supports resetting/clearing saved lock to return to the server default.
 */
public final class ContainerLockUi {
    private ContainerLockUi() {}

    private static final int CLOSE_SLOT = 0;
    private static final int INFO_SLOT = 4;
    private static final int UNLOCKED_SLOT = 11;
    private static final int PRIVATE_SLOT = 13;
    private static final int PARTY_SLOT = 15;
    private static final int RESET_SLOT = 22;

    public static boolean open(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !(be instanceof Container)) {
            player.sendSystemMessage(Component.literal("Khối này không phải là thùng chứa đồ (Container).").withStyle(ChatFormatting.RED));
            return false;
        }

        EconomyManager eco = EconomyCraft.getManager(level.getServer());
        ContainerLockStore locks = eco.getContainerLocks();
        String dimension = level.dimension().identifier().toString();
        var existing = locks.get(dimension, pos, level);
        boolean admin = PermissionCompat.isAdmin(player);
        UUID owner = locks.ownerOf(existing);
        boolean ownsExisting = existing == null || (owner != null && owner.equals(player.getUUID()));

        if (!admin && !ownsExisting) {
            String ownerName = owner == null ? "người khác" : eco.getBestName(owner);
            player.sendSystemMessage(Component.literal("Rương này thuộc quyền sở hữu của " + ownerName + " (Bạn không thể đổi cài đặt).").withStyle(ChatFormatting.RED));
            EconomySounds.failure(player);
            return false;
        }

        MenuUiSupport.openMenu(player, "Cài đặt khóa rương (Lock Mode)",
                (id, inv) -> new LockMenu(id, inv, player, level, pos));
        return true;
    }

    private static class LockMenu extends CompatMenu {
        private final ServerPlayer viewer;
        private final ServerLevel level;
        private final BlockPos pos;
        private final EconomyManager eco;
        private final ContainerLockStore locks;
        private final SimpleContainer container = new SimpleContainer(27);

        LockMenu(int id, Inventory inv, ServerPlayer viewer, ServerLevel level, BlockPos pos) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.level = level;
            this.pos = pos;
            this.eco = EconomyCraft.getManager(viewer.level().getServer());
            this.locks = eco.getContainerLocks();

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
            String dimension = level.dimension().identifier().toString();
            var existing = locks.get(dimension, pos, level);
            UUID owner = locks.ownerOf(existing);
            ContainerLockMode effective = locks.effectiveMode(existing, owner, eco);
            ContainerLockMode savedChoice = existing == null ? null : existing.mode;
            FactionId viewerFaction = eco.getFactions().factionOf(viewer.getUUID());

            // Slot 0: Close button
            container.setItem(CLOSE_SLOT, MenuUiSupport.closeButton());

            // Slot 4: Info button
            container.setItem(INFO_SLOT, createInfoItem(existing, owner, effective));

            // Slot 11: UNLOCKED
            container.setItem(UNLOCKED_SLOT, createUnlockedItem(savedChoice, effective));

            // Slot 13: PRIVATE
            container.setItem(PRIVATE_SLOT, createPrivateItem(savedChoice, effective));

            // Slot 15: PARTY_ONLY
            container.setItem(PARTY_SLOT, createPartyItem(savedChoice, effective, viewerFaction));

            // Slot 22: RESET / CLEAR
            container.setItem(RESET_SLOT, createResetItem(existing));

            MenuUiSupport.fillBackground(container);
        }

        private ItemStack createInfoItem(ContainerLockStore.LockEntry existing, UUID owner, ContainerLockMode effective) {
            ItemStack stack = new ItemStack(Items.CHEST);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Thông tin rương / khối chứa")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GOLD)));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("Tọa độ: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))
                    .append(Component.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE))));

            String ownerDisplay;
            if (owner == null) {
                ownerDisplay = "Chưa có chủ (Chọn 1 chế độ để nhận quyền sở hữu)";
            } else if (owner.equals(viewer.getUUID())) {
                ownerDisplay = "Bạn (" + viewer.getName().getString() + ")";
            } else {
                ownerDisplay = eco.getBestName(owner);
            }
            lore.add(Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("Chủ sở hữu: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))
                    .append(Component.literal(ownerDisplay).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE))));

            lore.add(Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("Chế độ đang áp dụng: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))
                    .append(Component.literal(vietnameseMode(effective)).withStyle(s -> s.withItalic(false).withBold(true).withColor(modeColor(effective)))));

            String decidedBy = lockDecidedBy(existing, owner, effective, eco);
            if (!decidedBy.isBlank()) {
                lore.add(Component.literal("  " + decidedBy).withStyle(s -> s.withItalic(true).withColor(ChatFormatting.DARK_GRAY)));
            }

            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private ItemStack createUnlockedItem(ContainerLockMode savedChoice, ContainerLockMode effective) {
            ItemStack stack = new ItemStack(Items.TRIPWIRE_HOOK);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("1. Mở khóa (UNLOCKED)")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GREEN)));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("Quyền truy cập: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)))
                    .append(Component.literal("Tất cả người chơi").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE))));
            lore.add(Component.literal("  Ai cũng có thể mở và sử dụng đồ trong rương.").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
            lore.add(Component.empty());

            boolean isActive = (savedChoice == ContainerLockMode.UNLOCKED) || (savedChoice == null && effective == ContainerLockMode.UNLOCKED);
            if (isActive) {
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                lore.add(Component.literal("✔ Đang áp dụng cho rương này").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GREEN)));
            } else {
                lore.add(Component.literal("▶ Nhấp để chọn chế độ Mở khóa").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)));
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private ItemStack createPrivateItem(ContainerLockMode savedChoice, ContainerLockMode effective) {
            ItemStack stack = new ItemStack(Items.IRON_BARS);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("2. Khóa cá nhân (PRIVATE)")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.AQUA)));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("Quyền truy cập: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)))
                    .append(Component.literal("Chỉ duy nhất bạn").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE))));
            lore.add(Component.literal("  Bảo vệ tài sản riêng của bạn an toàn tuyệt đối.").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
            lore.add(Component.literal("  Người chơi khác không thể mở hay phá hủy.").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
            lore.add(Component.empty());

            boolean allowed = EconomyConfig.get().containerLock.allowPrivateChoice;
            if (!allowed) {
                lore.add(Component.literal("✖ Máy chủ đã tắt tính năng khóa cá nhân").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.RED)));
            } else {
                boolean isActive = (savedChoice == ContainerLockMode.PRIVATE) || (savedChoice == null && effective == ContainerLockMode.PRIVATE);
                if (isActive) {
                    stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                    lore.add(Component.literal("✔ Đang áp dụng cho rương này").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GREEN)));
                } else {
                    lore.add(Component.literal("▶ Nhấp để chọn chế độ Khóa cá nhân").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)));
                }
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private ItemStack createPartyItem(ContainerLockMode savedChoice, ContainerLockMode effective, FactionId viewerFaction) {
            ItemStack stack = new ItemStack(Items.REDSTONE);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("3. Khóa Đảng Cộng sản (PARTY_ONLY)")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                    .append(Component.literal("Quyền truy cập: ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)))
                    .append(Component.literal("Thành viên Đảng Cộng sản").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE))));
            lore.add(Component.literal("  Đặc quyền 'Cộng đồng' của phe Đảng Cộng sản.").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
            lore.add(Component.literal("  Chia sẻ tài nguyên chung giữa các đồng chí.").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
            if (viewerFaction != FactionId.COMMUNISM) {
                lore.add(Component.literal("  (Bạn hiện không thuộc Đảng Cộng sản)").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)));
            }
            lore.add(Component.empty());

            boolean isActive = (savedChoice == ContainerLockMode.PARTY_ONLY) || (savedChoice == null && effective == ContainerLockMode.PARTY_ONLY);
            if (isActive) {
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
                lore.add(Component.literal("✔ Đang áp dụng cho rương này").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GREEN)));
            } else {
                lore.add(Component.literal("▶ Nhấp để chọn chế độ Khóa Đảng Cộng sản").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)));
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private ItemStack createResetItem(ContainerLockStore.LockEntry existing) {
            ItemStack stack = new ItemStack(Items.BARRIER);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Khôi phục mặc định máy chủ")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GRAY)));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.literal("• Xóa thiết lập khóa đã lưu của rương này.").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
            lore.add(Component.literal("• Rương sẽ trở về trạng thái mặc định của máy chủ.").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)));
            lore.add(Component.empty());
            if (existing == null) {
                lore.add(Component.literal("Rương này chưa có thiết lập riêng").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY)));
            } else {
                lore.add(Component.literal("▶ Nhấp để khôi phục mặc định").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)));
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        @Override
        protected boolean onClick(int slot, int dragType, ClickKind kind, Player player) {
            if (slot < 0 || slot >= 27) return false;
            if (kind != ClickKind.PICKUP && kind != ClickKind.QUICK_MOVE) return true;

            String dimension = level.dimension().identifier().toString();
            var existing = locks.get(dimension, pos, level);
            boolean admin = PermissionCompat.isAdmin(viewer);
            UUID owner = locks.ownerOf(existing);
            boolean ownsExisting = existing == null || (owner != null && owner.equals(viewer.getUUID()));

            if (!admin && !ownsExisting) {
                viewer.sendSystemMessage(Component.literal("Bạn không có quyền quản lý khóa của rương này!").withStyle(ChatFormatting.RED));
                EconomySounds.failure(viewer);
                return true;
            }

            switch (slot) {
                case CLOSE_SLOT -> {
                    viewer.closeContainer();
                    return true;
                }
                case UNLOCKED_SLOT -> {
                    locks.setLock(level, pos, viewer.getUUID(), ContainerLockMode.UNLOCKED);
                    EconomySounds.success(viewer);
                    viewer.sendSystemMessage(Component.literal("Đã thiết lập rương ở chế độ: Mở khóa (UNLOCKED)")
                            .withStyle(ChatFormatting.GREEN));
                    render();
                    return true;
                }
                case PRIVATE_SLOT -> {
                    if (!EconomyConfig.get().containerLock.allowPrivateChoice) {
                        viewer.sendSystemMessage(Component.literal("Khóa cá nhân đã bị tắt trên máy chủ.").withStyle(ChatFormatting.RED));
                        EconomySounds.failure(viewer);
                        return true;
                    }
                    locks.setLock(level, pos, viewer.getUUID(), ContainerLockMode.PRIVATE);
                    EconomySounds.success(viewer);
                    viewer.sendSystemMessage(Component.literal("Đã thiết lập rương ở chế độ: Khóa cá nhân (PRIVATE)")
                            .withStyle(ChatFormatting.GREEN));
                    render();
                    return true;
                }
                case PARTY_SLOT -> {
                    locks.setLock(level, pos, viewer.getUUID(), ContainerLockMode.PARTY_ONLY);
                    EconomySounds.success(viewer);
                    FactionId faction = eco.getFactions().factionOf(viewer.getUUID());
                    viewer.sendSystemMessage(Component.literal("Đã thiết lập rương ở chế độ: Khóa Đảng Cộng sản (PARTY_ONLY)")
                            .withStyle(ChatFormatting.GREEN));
                    render();
                    return true;
                }
                case RESET_SLOT -> {
                    if (existing == null) {
                        viewer.sendSystemMessage(Component.literal("Rương này chưa có thiết lập riêng.").withStyle(ChatFormatting.GRAY));
                        EconomySounds.failure(viewer);
                        return true;
                    }
                    if (locks.removeLock(level, pos, viewer.getUUID(), admin)) {
                        EconomySounds.click(viewer);
                        viewer.sendSystemMessage(Component.literal("Đã xóa thiết lập riêng, rương trở về mặc định của máy chủ.")
                                .withStyle(ChatFormatting.YELLOW));
                        render();
                    } else {
                        viewer.sendSystemMessage(Component.literal("Không thể xóa thiết lập khóa.").withStyle(ChatFormatting.RED));
                        EconomySounds.failure(viewer);
                    }
                    return true;
                }
            }
            return true;
        }

        private static String vietnameseMode(ContainerLockMode mode) {
            if (mode == null) return "Mở khóa";
            return switch (mode) {
                case UNLOCKED -> "Mở khóa (UNLOCKED)";
                case PRIVATE -> "Khóa cá nhân (PRIVATE)";
                case PARTY_ONLY -> "Khóa Đảng Cộng sản (PARTY_ONLY)";
            };
        }

        private static ChatFormatting modeColor(ContainerLockMode mode) {
            if (mode == null) return ChatFormatting.GREEN;
            return switch (mode) {
                case UNLOCKED -> ChatFormatting.GREEN;
                case PRIVATE -> ChatFormatting.AQUA;
                case PARTY_ONLY -> ChatFormatting.RED;
            };
        }

        private static String lockDecidedBy(ContainerLockStore.LockEntry existing, UUID owner,
                                            ContainerLockMode effective, EconomyManager eco) {
            if (effective == null) return "";
            EconomyConfig config = EconomyConfig.get();
            if (owner != null && config.factions.enabled && eco != null
                    && eco.getFactions().factionOf(owner) == FactionId.COMMUNISM
                    && ContainerLockPolicy.parse(config.factions.communism.containerLockMode, ContainerLockMode.PARTY_ONLY) != ContainerLockMode.UNLOCKED) {
                return "(Quyết định bởi đặc quyền Cộng đồng của Đảng Cộng sản)";
            }
            if (existing != null && existing.mode != null) return "(Quyết định bởi lựa chọn của chủ sở hữu)";
            return "(Quyết định bởi mặc định của máy chủ)";
        }
    }
}

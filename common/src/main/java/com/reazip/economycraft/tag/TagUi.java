package com.reazip.economycraft.tag;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.TagSettings;
import com.reazip.economycraft.faction.FactionId;
import com.reazip.economycraft.faction.FactionStore;
import com.reazip.economycraft.profession.ProfessionId;
import com.reazip.economycraft.profession.ProfessionLevel;
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
import net.minecraft.world.item.Item;
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

    public static void openParty(ServerPlayer player) {
        if (!EconomyPermissions.checkCommand(player, EconomyPermissions.Nodes.COMMAND_TAG)) {
            MenuUiSupport.denyPermission(player);
            return;
        }
        openPartyMenu(player);
    }

    public static void openProfession(ServerPlayer player) {
        if (!EconomyPermissions.checkCommand(player, EconomyPermissions.Nodes.COMMAND_TAG)) {
            MenuUiSupport.denyPermission(player);
            return;
        }
        openProfessionMenu(player);
    }

    private static void openMain(ServerPlayer player) {
        MenuUiSupport.openMenu(player, "Tags (Thẻ danh hiệu)", (id, inv) -> new MainMenu(id, inv, player));
    }

    private static void openPartyMenu(ServerPlayer player) {
        MenuUiSupport.openMenu(player, "Chọn Phe phái (Party)", (id, inv) -> new PartyMenu(id, inv, player));
    }

    private static void openProfessionMenu(ServerPlayer player) {
        MenuUiSupport.openMenu(player, "Chọn Nghề nghiệp (Profession)", (id, inv) -> new ProfessionMenu(id, inv, player));
    }

    public static Item itemFor(ProfessionId profession) {
        return switch (profession) {
            case BUILDER -> Items.BRICK;
            case FARMER -> Items.WHEAT;
            case MINER -> Items.IRON_PICKAXE;
            case MERCHANT -> Items.EMERALD;
            case SOLDIER -> Items.IRON_SWORD;
        };
    }

    public static Item itemFor(FactionId faction) {
        return switch (faction) {
            case COMMUNISM -> Items.REDSTONE;
            case CAPITALISM -> Items.GOLD_INGOT;
            case MONARCHY -> Items.GOLDEN_HELMET;
            case ANARCHISM -> Items.FEATHER;
        };
    }

    private static Component header(String text) {
        return Component.literal(text).withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.GOLD));
    }

    private static Component perkLine(String title, String desc) {
        return Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                .append(Component.literal(title + ": ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)))
                .append(Component.literal(desc).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)));
    }

    private static Component bullet(String text) {
        return Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                .append(Component.literal(text).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)));
    }

    private static Component subBullet(String text) {
        return Component.literal("  " + text).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY));
    }

    private static List<Component> professionBuffsAndReqs(ProfessionId profession) {
        List<Component> list = new ArrayList<>();
        list.add(Component.empty());
        list.add(header("[Kỹ năng & Đặc quyền]"));
        switch (profession) {
            case BUILDER -> {
                list.add(perkLine("Thành thạo", "Tăng tầm với đặt & tương tác block"));
                list.add(subBullet("+1 block (Học nghề) / +2 block (Thợ thầy)"));
                list.add(perkLine("Sửa lỗi", "Nhận Haste I khi đào đá, đất,"));
                list.add(subBullet("cobblestone & các block xây dựng (Thợ thầy)"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Đặt đủ 1,000 block xây dựng"));
                list.add(subBullet("(gỗ, đá, kính, tường, rào, thạch anh, đất...)"));
            }
            case FARMER -> {
                list.add(perkLine("Tươi tốt", "Quét 24 block mỗi 4 phút, có 10%"));
                list.add(subBullet("(Học nghề) / 20% (Thợ thầy) thúc đẩy cây"));
                list.add(perkLine("Chăm sóc", "Giảm 10% / 20% thời gian chờ phối"));
                list.add(subBullet("giống; con non lớn nhanh hơn +15% / +30%"));
                list.add(perkLine("Khéo léo", "Có 1% (Học nghề) / 5% (Thợ thầy) cơ hội"));
                list.add(subBullet("nhận thêm +2 đồ ăn khi nấu nướng / chế tạo"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Đạt 300 lượt thao tác nông nghiệp"));
                list.add(subBullet("(trồng, gặt, cho ăn hoặc phối giống gia súc)"));
            }
            case MINER -> {
                list.add(perkLine("Lanh lợi", "Nhận Haste II khi đào các loại đá,"));
                list.add(subBullet("deepslate, tuff, netherrack và quặng"));
                list.add(perkLine("Khéo tay", "Có 5% (Học nghề) / 15% (Thợ thầy) cơ hội"));
                list.add(subBullet("nhân đôi quặng rơi ra"));
                list.add(perkLine("Bảo hộ", "Hồi máu II trong 4s khi chạm dung nham"));
                list.add(subBullet("(Thợ thầy, thời gian hồi chiêu 5 phút)"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Khai thác đủ 270 quặng các loại"));
                list.add(subBullet("(Quặng Kim cương & Vàng được tính gấp đôi: x2)"));
            }
            case MERCHANT -> {
                list.add(perkLine("Lưỡi không xương", "Giảm giá khi giao dịch Dân làng:"));
                list.add(subBullet("giảm 5% (Học nghề) / 15% (Thợ thầy)"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Giao dịch Dân làng 50 lần & mua /ah 5 lần"));
                list.add(subBullet("(Không tính gậy; tối đa 20 lần mỗi dân làng)"));
            }
            case SOLDIER -> {
                list.add(perkLine("Tôi thép", "-5% nhận / +5% gây sát thương (Học nghề)"));
                list.add(subBullet("-> Nâng lên ±15% sát thương (Thợ thầy)"));
                list.add(perkLine("Adrenaline", "Giảm 50% thời gian hiệu ứng xấu"));
                list.add(subBullet("trong 4 giây đầu (Thợ thầy, hồi chiêu 5 phút)"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Tiêu diệt đủ 100 quái vật thù địch"));
            }
        }
        return list;
    }

    private static List<Component> factionBuffsAndDebuffs(FactionId faction) {
        List<Component> list = new ArrayList<>();
        list.add(Component.empty());
        list.add(header("[Lợi ích - Buff]"));
        switch (faction) {
            case COMMUNISM -> {
                list.add(perkLine("Cộng đồng", "Khóa rương (/eco lock party)"));
                list.add(subBullet("cho phép các thành viên cùng phe dùng chung"));
                list.add(perkLine("Đầu tư công", "50% tỉ lệ miễn thuế khi qua Toll"));
                list.add(Component.empty());
                list.add(Component.literal("[Trách nhiệm - Debuff]").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
                list.add(perkLine("Đảng phí", "Trừ $10 mỗi 45 phút tích lũy online"));
                list.add(perkLine("Thuế thu nhập", "Thuế 0.5% - 1.25% mỗi 45 phút online"));
                list.add(subBullet("khi số dư tài khoản vượt mốc $10,000"));
            }
            case CAPITALISM -> {
                list.add(perkLine("Thị trường", "Miễn phí thuế cho người mua hàng"));
                list.add(subBullet("khi bạn đăng bán trên chợ /ah"));
                list.add(Component.empty());
                list.add(Component.literal("[Trách nhiệm - Debuff]").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
                list.add(perkLine("Thuế tư bản", "Thuế tài sản hàng ngày 5%"));
                list.add(subBullet("(nhân hệ số thị phần & lạm phát)"));
                list.add(perkLine("Thuế cầu đường", "Thuế khi qua trạm Toll tăng +25%"));
            }
            case MONARCHY -> {
                list.add(perkLine("Tự trị", "Giảm 50% chi phí tạo & mở rộng claim"));
                list.add(perkLine("Phép vua", "+15% sát thương gây ra & giảm 15%"));
                list.add(subBullet("sát thương nhận vào khi đứng trong đất claim"));
                list.add(Component.empty());
                list.add(Component.literal("[Trách nhiệm - Debuff]").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
                list.add(perkLine("Cống nạp", "Nộp thêm thuế cống nạp 1.7% hàng ngày"));
                list.add(perkLine("Nhập khẩu", "50% tỉ lệ chịu thêm 50% thuế khi mua đồ"));
            }
            case ANARCHISM -> {
                list.add(perkLine("Tự do", "Miễn hoàn toàn tất cả mọi loại thuế"));
                list.add(perkLine("Thoải mái", "+15% tốc độ chạy & tốc độ trên ngựa"));
                list.add(subBullet("khi đứng ở vùng đất hoang dã chưa bị claim"));
                list.add(Component.empty());
                list.add(Component.literal("[Trách nhiệm - Debuff]").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
                list.add(perkLine("Vô chính phủ", "Không thể sở hữu vùng claim hoặc"));
                list.add(subBullet("được thêm vào danh sách tin tưởng (/claim trust)"));
            }
        }
        return list;
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
            container.setItem(PARTY_ROW, MenuUiSupport.button(Items.REDSTONE, "Phe phái (Party)", ChatFormatting.GOLD,
                    MenuUiSupport.hint("Chọn phe phái chính trị của bạn.")));
            container.setItem(PROFESSION_ROW, MenuUiSupport.button(Items.IRON_PICKAXE, "Nghề nghiệp (Profession)", ChatFormatting.AQUA,
                    MenuUiSupport.hint("Chọn nghề nghiệp của bạn.")));
            MenuUiSupport.fillBackground(container);
        }

        private ItemStack createCurrentTags() {
            ItemStack stack = new ItemStack(Items.NAME_TAG);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("Thẻ của bạn (Your Tags)")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.YELLOW)));
            List<Component> lore = new ArrayList<>();
            List<TagStyle.Tagged> tags = eco.getTagDisplay().tagsOf(viewer.getUUID());
            if (tags.isEmpty()) {
                lore.add(MenuUiSupport.hint("Chưa có thẻ nào"));
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
            ItemStack stack = new ItemStack(itemFor(faction));
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(faction.vietnameseName() + " (" + faction.displayName() + ")")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(settings.color)));
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint("Hiện tại: " + currentLabel()));

            lore.addAll(factionBuffsAndDebuffs(faction));

            long lockout = EconomyConfig.get().factions.selectionLockoutHours;
            lore.add(Component.empty());
            if (!eco.getFactions().canChange(viewer.getUUID(), lockout)) {
                lore.add(MenuUiSupport.line("Đang khóa trong "
                        + TimeFormat.formatDuration(eco.getFactions().remainingCooldownMillis(viewer.getUUID(), lockout)),
                        ChatFormatting.RED));
            } else {
                lore.add(MenuUiSupport.line("Nhấn để chọn phe này", ChatFormatting.GREEN));
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private String currentLabel() {
            FactionStore store = eco.getFactions();
            if (store.hasChosen(viewer.getUUID())) {
                FactionId f = store.factionOf(viewer.getUUID());
                return f.vietnameseName() + " (" + f.displayName() + ")";
            }
            FactionId def = FactionId.defaultFaction();
            return def.vietnameseName() + " (" + def.displayName() + ") [mặc định]";
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
                        "Bạn chưa thể đổi phe. Hãy thử lại sau "
                                + TimeFormat.formatDuration(eco.getFactions().remainingCooldownMillis(viewer.getUUID(), lockout)) + ".",
                        ChatFormatting.RED));
                openMain(viewer);
                return;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint("Phe phái: " + faction.vietnameseName() + " (" + faction.displayName() + ")"));
            lore.add(MenuUiSupport.line("Lựa chọn này sẽ bắt đầu thời gian khóa 30 giờ.", ChatFormatting.YELLOW));
            lore.add(MenuUiSupport.hint("Bạn sẽ không thể đổi phe hoặc nghề"));
            lore.add(MenuUiSupport.hint("trong vòng 30 giờ sau khi chọn."));
            ItemStack subject = new ItemStack(itemFor(faction));
            subject.set(DataComponents.CUSTOM_NAME, Component.literal(faction.vietnameseName() + " (" + faction.displayName() + ")")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(faction.settings().color)));
            ConfirmUi.open(viewer, "Xác nhận phe phái", subject, "Chọn " + faction.vietnameseName(), lore,
                    p -> {
                        eco.getFactions().select(p.getUUID(), faction);
                        eco.getTagDisplay().refresh(p);
                        EconomySounds.success(p);
                        p.sendSystemMessage(MenuUiSupport.line("Đã chọn phe: " + faction.vietnameseName() + " (" + faction.displayName() + ")", ChatFormatting.GREEN));
                        openMain(p);
                    },
                    p -> openPartyMenu(p));
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
            ItemStack stack = new ItemStack(itemFor(profession));
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(profession.vietnameseName() + " (" + profession.displayName() + ")")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(settings.color)));
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint("Hiện tại: " + currentLabel()));

            ProfessionStore store = eco.getProfessions();
            ProfessionId current = store.professionOf(viewer.getUUID());
            if (current == profession) {
                ProfessionLevel level = store.levelOf(viewer.getUUID());
                if (level == ProfessionLevel.APPRENTICE) {
                    if (profession == ProfessionId.MERCHANT) {
                        long trades = store.totalVillagerTrades(viewer.getUUID());
                        int neededTrades = EconomyConfig.get().professions.merchant.villagerTradeCount;
                        long ahBuys = store.progressOf(viewer.getUUID()).progress;
                        int neededAh = EconomyConfig.get().professions.merchant.auctionPurchaseCount;
                        lore.add(MenuUiSupport.line("Tiến độ: " + trades + "/" + neededTrades + " giao dịch | " + ahBuys + "/" + neededAh + " mua AH", ChatFormatting.AQUA));
                    } else {
                        long prog = store.progressOf(viewer.getUUID()).progress;
                        int needed = ProfessionStore.levelUpCountFor(profession);
                        lore.add(MenuUiSupport.line("Tiến độ thăng cấp: " + prog + " / " + needed, ChatFormatting.AQUA));
                    }
                } else if (level == ProfessionLevel.MASTER) {
                    lore.add(MenuUiSupport.line("Trạng thái: Thợ thầy (Tối đa)", ChatFormatting.GOLD));
                } else if (level == ProfessionLevel.RUSTED) {
                    long rustMin = store.rustOnlineMillis(viewer.getUUID()) / 60_000L;
                    int targetMin = EconomyConfig.get().professions.rustOnlineMinutes;
                    lore.add(MenuUiSupport.line("Trạng thái: Lụt nghề (" + rustMin + "/" + targetMin + " phút online)", ChatFormatting.RED));
                }
            }

            lore.addAll(professionBuffsAndReqs(profession));

            long lockout = EconomyConfig.get().professions.selectionLockoutHours;
            lore.add(Component.empty());
            if (!eco.getProfessions().canChange(viewer.getUUID(), lockout)) {
                lore.add(MenuUiSupport.line("Đang khóa trong "
                        + TimeFormat.formatDuration(eco.getProfessions().remainingCooldownMillis(viewer.getUUID(), lockout)),
                        ChatFormatting.RED));
            } else {
                lore.add(MenuUiSupport.line("Nhấn để chọn nghề này", ChatFormatting.GREEN));
            }
            stack.set(DataComponents.LORE, new ItemLore(lore));
            return stack;
        }

        private String currentLabel() {
            ProfessionStore store = eco.getProfessions();
            ProfessionId current = store.professionOf(viewer.getUUID());
            if (current == null) return "Chưa chọn";
            var level = store.levelOf(viewer.getUUID());
            return level == null ? current.vietnameseName() + " (" + current.displayName() + ")"
                    : current.vietnameseName() + " (" + current.displayName() + ") - " + level.vietnameseName();
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
                        "Bạn chưa thể đổi nghề. Hãy thử lại sau "
                                + TimeFormat.formatDuration(eco.getProfessions().remainingCooldownMillis(viewer.getUUID(), lockout)) + ".",
                        ChatFormatting.RED));
                openMain(viewer);
                return;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint("Nghề nghiệp: " + profession.vietnameseName() + " (" + profession.displayName() + ")"));
            lore.add(MenuUiSupport.line("Lựa chọn này sẽ bắt đầu thời gian khóa 30 giờ.", ChatFormatting.YELLOW));
            lore.add(MenuUiSupport.hint("Bạn sẽ không thể đổi phe hoặc nghề nghiệp"));
            lore.add(MenuUiSupport.hint("trong vòng 30 giờ sau khi chọn."));
            lore.add(MenuUiSupport.hint("Đổi sang nghề khác sẽ đặt lại tiến độ cũ."));
            ItemStack subject = new ItemStack(itemFor(profession));
            subject.set(DataComponents.CUSTOM_NAME, Component.literal(profession.vietnameseName() + " (" + profession.displayName() + ")")
                    .withStyle(s -> s.withItalic(false).withBold(true).withColor(profession.settings().color)));
            ConfirmUi.open(viewer, "Xác nhận nghề nghiệp", subject, "Chọn " + profession.vietnameseName(), lore,
                    p -> {
                        eco.getProfessions().select(p.getUUID(), profession);
                        eco.getTagDisplay().refresh(p);
                        EconomySounds.success(p);
                        p.sendSystemMessage(MenuUiSupport.line("Đã chọn nghề: " + profession.vietnameseName() + " (" + profession.displayName() + ")", ChatFormatting.GREEN));
                        openMain(p);
                    },
                    p -> openProfessionMenu(p));
        }
    }
}

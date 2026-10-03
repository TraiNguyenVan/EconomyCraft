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

    private static Component perk(String title, String mainDesc) {
        return Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                .append(Component.literal(title + ": ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.YELLOW)))
                .append(Component.literal(mainDesc).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
    }

    private static Component debuffPerk(String title, String mainDesc) {
        return Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                .append(Component.literal(title + ": ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)))
                .append(Component.literal(mainDesc).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
    }

    private static Component textLine(String mainDesc) {
        return Component.literal("  " + mainDesc).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE));
    }

    private static Component addition(String extraInfo) {
        return Component.literal("  " + extraInfo).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY));
    }

    private static Component bullet(String text) {
        return Component.literal("• ").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY))
                .append(Component.literal(text).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.WHITE)));
    }

    private static List<Component> professionBuffsAndReqs(ProfessionId profession) {
        List<Component> list = new ArrayList<>();
        list.add(Component.empty());
        list.add(header("[Kỹ năng & Đặc quyền]"));
        switch (profession) {
            case BUILDER -> {
                list.add(perk("Thành thạo", "Tăng tầm với khi đặt & tương tác khối"));
                list.add(addition("(+1 block ở Học nghề / +2 block ở Thợ thầy)"));
                list.add(perk("Sửa lỗi", "Nhận Haste I khi đào đá, đất & khối xây"));
                list.add(addition("(Mở khóa khi đạt cấp Thợ thầy)"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Đặt đủ 1,000 khối xây dựng"));
                list.add(addition("(Gỗ, đá, kính, tường, rào, thạch anh, đất...)"));
            }
            case FARMER -> {
                list.add(perk("Tươi tốt", "Tự động kích thích cây trồng phát triển"));
                list.add(addition("(Quét 24 block mỗi 4p: 10% Học nghề / 20% Thợ thầy)"));
                list.add(perk("Chăm sóc", "Động vật phối giống hồi nhanh và mau lớn"));
                list.add(addition("(-10%/-20% hồi chiêu, con non lớn nhanh +15%/+30%)"));
                list.add(perk("Khéo léo", "Cơ hội nhận thêm +2 đồ ăn khi nấu nướng/chế tạo"));
                list.add(addition("(Tỉ lệ 1% ở Học nghề / 5% ở Thợ thầy)"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Đạt 300 lượt thao tác nông nghiệp"));
                list.add(addition("(Trồng cây, thu hoạch, cho ăn hoặc phối giống)"));
            }
            case MINER -> {
                list.add(perk("Lanh lợi", "Nhận Haste II khi đào đá, deepslate & quặng"));
                list.add(addition("(Áp dụng cho mọi loại đá, tuff, netherrack và quặng)"));
                list.add(perk("Khéo tay", "Cơ hội nhân đôi lượng quặng rơi ra khi khai thác"));
                list.add(addition("(Tỉ lệ 5% ở Học nghề / 15% ở Thợ thầy)"));
                list.add(perk("Bảo hộ", "Nhận Hồi máu II (Regen II) khi chạm dung nham"));
                list.add(addition("(Kéo dài 4 giây, hồi chiêu 5 phút - Thợ thầy)"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Khai thác đủ 270 quặng các loại"));
                list.add(addition("(Quặng Kim cương & Quặng Vàng được tính gấp đôi: x2)"));
            }
            case MERCHANT -> {
                list.add(perk("Lưỡi không xương", "Giảm giá khi giao dịch với Dân làng"));
                list.add(addition("(Giảm 5% ở Học nghề / 15% ở Thợ thầy)"));
                list.add(Component.empty());
                list.add(header("[Điều kiện thăng cấp]"));
                list.add(bullet("Giao dịch Dân làng 50 lần & mua 5 lần trên /ah"));
                list.add(addition("(Không tính giao dịch que gỗ; tối đa 20 lần/dân làng)"));
            }
            case SOLDIER -> {
                list.add(perk("Tôi thép", "Tăng sát thương gây ra & giảm sát thương nhận"));
                list.add(addition("(±5% ở Học nghề / ±15% ở Thợ thầy)"));
                list.add(perk("Adrenaline", "Giảm 50% thời gian của các hiệu ứng xấu"));
                list.add(addition("(Kích hoạt trong 4 giây đầu, hồi chiêu 5 phút - Thợ thầy)"));
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
                list.add(perk("Cộng đồng", "Khóa rương dùng chung cho thành viên cùng phe"));
                list.add(addition("(Sử dụng lệnh /eco lock party)"));
                list.add(perk("Đầu tư công", "Cơ hội miễn phí thuế khi đi qua trạm Toll"));
                list.add(addition("(Tỉ lệ 50%, chủ trạm vẫn nhận đủ tiền phí)"));
                list.add(Component.empty());
                list.add(Component.literal("[Trách nhiệm - Debuff]").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
                list.add(debuffPerk("Đảng phí", "Trừ $10 nộp vào quỹ Đảng Cộng sản"));
                list.add(addition("(Thu định kỳ mỗi 45 phút tích lũy online)"));
                list.add(debuffPerk("Thuế thu nhập", "Đánh thuế tài khoản chống đầu cơ"));
                list.add(addition("(Thu từ 0.5% đến 1.25% mỗi 45p khi số dư trên $10,000)"));
            }
            case CAPITALISM -> {
                list.add(perk("Thị trường", "Người mua đồ của bạn trên /ah được miễn thuế"));
                list.add(addition("(Giúp bạn có lợi thế giá bán cạnh tranh hơn)"));
                list.add(Component.empty());
                list.add(Component.literal("[Trách nhiệm - Debuff]").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
                list.add(debuffPerk("Thuế tư bản", "Đóng thuế tài sản hàng ngày 5%"));
                list.add(addition("(Tỉ lệ nhân theo thị phần tài sản của phe & lạm phát)"));
                list.add(debuffPerk("Cầu đường", "Thuế giao dịch khi qua trạm Toll tăng thêm 25%"));
            }
            case MONARCHY -> {
                list.add(perk("Tự trị", "Giảm một nửa chi phí tạo & mở rộng vùng claim"));
                list.add(addition("(Giảm 50% tiền bảo vệ đất đai qua ShopGuard)"));
                list.add(perk("Phép vua", "Tăng 15% sát thương & chống chịu trong đất claim"));
                list.add(addition("(Chỉ áp dụng khi đứng trong vùng đất của chính mình)"));
                list.add(Component.empty());
                list.add(Component.literal("[Trách nhiệm - Debuff]").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
                list.add(debuffPerk("Cống nạp", "Nộp thêm thuế cống nạp 1.7% hàng ngày cho triều đình"));
                list.add(debuffPerk("Nhập khẩu", "Có 50% tỉ lệ phải trả thêm 50% thuế khi mua đồ"));
            }
            case ANARCHISM -> {
                list.add(perk("Tự do", "Miễn hoàn toàn tất cả mọi loại thuế trên server"));
                list.add(addition("(Thuế giao dịch, thuế hàng ngày, thuế toll đều về 0)"));
                list.add(perk("Thoải mái", "Tăng 15% tốc độ chạy và tốc độ trên ngựa"));
                list.add(addition("(Khi đứng trên vùng đất hoang dã chưa bị claim)"));
                list.add(Component.empty());
                list.add(Component.literal("[Trách nhiệm - Debuff]").withStyle(s -> s.withItalic(false).withBold(true).withColor(ChatFormatting.RED)));
                list.add(debuffPerk("Vô chính phủ", "Không được phép sở hữu hoặc claim đất"));
                list.add(addition("(Không thể nhận chuyển nhượng hoặc nhận /claim trust)"));
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

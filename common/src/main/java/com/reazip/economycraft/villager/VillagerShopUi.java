package com.reazip.economycraft.villager;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxQuote;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.util.ClickKind;
import com.reazip.economycraft.util.CompatMenu;
import com.reazip.economycraft.util.EconomySounds;
import com.reazip.economycraft.util.MenuUiSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;

/**
 * Villager shop GUI: permits buying/selling villager offers using economy balance (spec Phase 7, P7-T5).
 */
public final class VillagerShopUi {
    private VillagerShopUi() {}

    private static final int BACK = 0;

    public static void open(ServerPlayer player, AbstractVillager villager, EconomyManager eco) {
        MenuUiSupport.openMenu(player, "Villager Market", (id, inv) ->
                new VillagerMenu(id, inv, player, villager, eco));
    }

    private static class VillagerMenu extends CompatMenu {
        private final ServerPlayer viewer;
        private final AbstractVillager villager;
        private final EconomyManager eco;
        private final SimpleContainer container = new SimpleContainer(27);

        VillagerMenu(int id, Inventory inv, ServerPlayer viewer, AbstractVillager villager, EconomyManager eco) {
            super(MenuType.GENERIC_9x3, id);
            this.viewer = viewer;
            this.villager = villager;
            this.eco = eco;

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

            MerchantOffers offers = villager.getOffers();
            int slotIdx = 9;
            for (int i = 0; i < offers.size() && slotIdx < 27; i++) {
                MerchantOffer offer = offers.get(i);
                container.setItem(slotIdx++, createOfferButton(offer, i));
            }
            MenuUiSupport.fillBackground(container);
        }

        private ItemStack createOfferButton(MerchantOffer offer, int index) {
            boolean isBuy = VillagerTradeEconomy.isBuyFromVillager(offer);
            ItemStack displayStack = isBuy ? offer.getResult().copy() : offer.getCostA().copy();

            long basePrice = VillagerTradeEconomy.resolveBasePrice(eco.getPrices(), offer, isBuy);
            TaxQuote quote = TaxPolicy.resolve(TaxScope.TRANSACTION_VILLAGER, basePrice, viewer.getUUID(), eco);

            displayStack.set(DataComponents.CUSTOM_NAME, Component.literal((isBuy ? "Buy: " : "Sell: ")
                    + displayStack.getHoverName().getString())
                    .withStyle(s -> s.withItalic(false).withBold(true)
                            .withColor(isBuy ? ChatFormatting.GOLD : ChatFormatting.GREEN)));

            List<Component> lore = new ArrayList<>();
            lore.add(MenuUiSupport.hint(isBuy ? "Buy from villager for money" : "Sell to villager for money"));
            if (isBuy) {
                lore.add(MenuUiSupport.line("Price: " + EconomyCraft.formatMoney(quote.total()), ChatFormatting.YELLOW));
                if (quote.discounted()) {
                    lore.add(MenuUiSupport.line("Merchant Discount: -" + EconomyCraft.formatMoney(quote.discount()), ChatFormatting.GREEN));
                }
            } else {
                lore.add(MenuUiSupport.line("Payout: " + EconomyCraft.formatMoney(quote.net()), ChatFormatting.YELLOW));
                lore.add(MenuUiSupport.hint("Required: " + offer.getCostA().getCount() + "x " + offer.getCostA().getHoverName().getString()));
            }

            lore.add(MenuUiSupport.hint("Uses: " + offer.getUses() + " / " + offer.getMaxUses()));
            if (offer.isOutOfStock()) {
                lore.add(MenuUiSupport.line("OUT OF STOCK", ChatFormatting.RED));
            } else {
                lore.add(MenuUiSupport.line("Click to trade!", ChatFormatting.AQUA));
            }

            displayStack.set(DataComponents.LORE, new ItemLore(lore));
            return displayStack;
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

            int offerIndex = slot - 9;
            MerchantOffers offers = villager.getOffers();
            if (offerIndex >= 0 && offerIndex < offers.size()) {
                MerchantOffer offer = offers.get(offerIndex);
                var result = VillagerTradeEconomy.executeTrade(eco, viewer, villager, offer);
                if (result.success()) {
                    EconomySounds.success(viewer);
                    viewer.sendSystemMessage(result.message());
                    render();
                } else {
                    EconomySounds.failure(viewer);
                    viewer.sendSystemMessage(result.message());
                }
                return true;
            }
            return true;
        }
    }
}

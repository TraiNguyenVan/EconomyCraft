package com.reazip.economycraft;

import com.reazip.economycraft.api.v1.MutationSource;

public final class EconomySources {
    public static final MutationSource PLAYER_PAYMENT = MutationSource.of("economycraft:player_payment");
    public static final MutationSource ADMIN_ADD = MutationSource.of("economycraft:admin_add");
    public static final MutationSource ADMIN_REMOVE = MutationSource.of("economycraft:admin_remove");
    public static final MutationSource ADMIN_SET = MutationSource.of("economycraft:admin_set");
    public static final MutationSource ADMIN_RESET = MutationSource.of("economycraft:admin_reset");
    public static final MutationSource DAILY_REWARD = MutationSource.of("economycraft:daily_reward");
    public static final MutationSource SHOP_PURCHASE = MutationSource.of("economycraft:shop_purchase");
    public static final MutationSource SHOP_SALE = MutationSource.of("economycraft:shop_sale");
    public static final MutationSource AUCTION_PURCHASE = MutationSource.of("economycraft:auction_purchase");
    public static final MutationSource ORDER_FULFILLMENT = MutationSource.of("economycraft:order_fulfillment");
    public static final MutationSource ORDER_ESCROW_HOLD = MutationSource.of("economycraft:order_escrow_hold");
    public static final MutationSource ORDER_ESCROW_REFUND = MutationSource.of("economycraft:order_escrow_refund");
    public static final MutationSource QUEST_FUNDING = MutationSource.of("economycraft:quest_funding");
    public static final MutationSource QUEST_FORFEIT = MutationSource.of("economycraft:quest_forfeit");
    public static final MutationSource QUEST_BUYBACK = MutationSource.of("economycraft:quest_buyback");
    public static final MutationSource TOLL_PAYMENT = MutationSource.of("economycraft:toll_payment");
    public static final MutationSource WEALTH_TAX = MutationSource.of("economycraft:wealth_tax");
    public static final MutationSource WEALTH_REBATE = MutationSource.of("economycraft:wealth_rebate");
    public static final MutationSource VILLAGER_TRADE = MutationSource.of("economycraft:villager_trade");
    public static final MutationSource PARTY_FEE = MutationSource.of("economycraft:party_fee");
    public static final MutationSource INCOME_TAX = MutationSource.of("economycraft:income_tax");
    public static final MutationSource IMPORT_TAX = MutationSource.of("economycraft:import_tax");
    public static final MutationSource DAILY_TAX = MutationSource.of("economycraft:daily_tax");
    public static final MutationSource CORRUPTION_TAX = MutationSource.of("economycraft:corruption_tax");

    private EconomySources() {}
}

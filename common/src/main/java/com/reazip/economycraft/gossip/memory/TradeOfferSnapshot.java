package com.reazip.economycraft.gossip.memory;

import org.jetbrains.annotations.Nullable;

/**
 * Immutable snapshot of an active villager trade offer, captured safely on the main game thread.
 */
public record TradeOfferSnapshot(
        String inputA,
        int countA,
        @Nullable String inputB,
        int countB,
        String output,
        int countOutput,
        boolean outOfStock,
        int remainingUses
) {
    public String toPromptDescription() {
        StringBuilder sb = new StringBuilder();
        if (countOutput > 1) {
            sb.append(countOutput).append("x ");
        }
        sb.append(output).append(" for ");
        if (countA > 1) {
            sb.append(countA).append("x ");
        }
        sb.append(inputA);
        if (inputB != null && !inputB.isBlank()) {
            sb.append(" + ");
            if (countB > 1) {
                sb.append(countB).append("x ");
            }
            sb.append(inputB);
        }
        if (outOfStock) {
            sb.append(" [OUT OF STOCK]");
        } else if (remainingUses > 0 && remainingUses <= 3) {
            sb.append(" [Only ").append(remainingUses).append(" left!]");
        } else {
            sb.append(" [In stock]");
        }
        return sb.toString();
    }

    /**
     * Extracts an immutable list of TradeOfferSnapshot from Minecraft MerchantOffers,
     * safely executed on the server thread. Caps extraction to at most maxOffers.
     */
    public static java.util.List<TradeOfferSnapshot> fromOffers(
            @Nullable net.minecraft.world.item.trading.MerchantOffers offers,
            int maxOffers
    ) {
        if (offers == null || offers.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<TradeOfferSnapshot> result = new java.util.ArrayList<>();
        for (net.minecraft.world.item.trading.MerchantOffer offer : offers) {
            if (result.size() >= maxOffers) break;
            try {
                net.minecraft.world.item.ItemStack costA = offer.getCostA();
                net.minecraft.world.item.ItemStack costB = offer.getCostB();
                net.minecraft.world.item.ItemStack res = offer.getResult();

                String inputAName = formatItemStack(costA);
                int countA = costA.getCount();

                String inputBName = !costB.isEmpty() ? formatItemStack(costB) : null;
                int countB = !costB.isEmpty() ? costB.getCount() : 0;

                String outputName = formatItemStack(res);
                int countOutput = res.getCount();

                boolean outOfStock = offer.isOutOfStock();
                int remainingUses = Math.max(0, offer.getMaxUses() - offer.getUses());

                result.add(new TradeOfferSnapshot(
                        inputAName,
                        countA,
                        inputBName,
                        countB,
                        outputName,
                        countOutput,
                        outOfStock,
                        remainingUses
                ));
            } catch (Throwable ignored) {
            }
        }
        return java.util.Collections.unmodifiableList(result);
    }

    /**
     * Formats an ItemStack into a human-readable name, appending enchantment names
     * (e.g. "Enchanted Book (Fortune III)", "Diamond Pickaxe (Efficiency V)").
     */
    public static String formatItemStack(@Nullable net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "Unknown item";
        String baseName = stack.getHoverName().getString();
        try {
            net.minecraft.core.component.DataComponentType<net.minecraft.world.item.enchantment.ItemEnchantments> enchComp =
                    stack.is(net.minecraft.world.item.Items.ENCHANTED_BOOK)
                            ? net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS
                            : net.minecraft.core.component.DataComponents.ENCHANTMENTS;

            net.minecraft.world.item.enchantment.ItemEnchantments enchs = stack.getOrDefault(enchComp, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
            if (!enchs.isEmpty()) {
                java.util.List<String> list = new java.util.ArrayList<>();
                for (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment>> entry : enchs.entrySet()) {
                    var holder = entry.getKey();
                    int level = entry.getIntValue();
                    com.reazip.economycraft.util.IdentifierCompat.Id enchId = holder.unwrapKey()
                            .map(com.reazip.economycraft.util.IdentifierCompat::fromResourceKey)
                            .orElse(null);
                    String enchName = enchId != null ? enchId.path().replace('_', ' ') : "enchantment";
                    // Capitalize words
                    enchName = capitalizeWords(enchName);
                    String levelStr = switch (level) {
                        case 1 -> "I";
                        case 2 -> "II";
                        case 3 -> "III";
                        case 4 -> "IV";
                        case 5 -> "V";
                        default -> String.valueOf(level);
                    };
                    list.add(enchName + " " + levelStr);
                }
                if (!list.isEmpty()) {
                    return baseName + " (" + String.join(", ", list) + ")";
                }
            }
        } catch (Throwable ignored) {
        }
        return baseName;
    }

    private static String capitalizeWords(String str) {
        if (str == null || str.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String word : str.split("\\s+")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                  .append(word.substring(1).toLowerCase(java.util.Locale.ROOT))
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }
}

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

                String inputAName = costA.getHoverName().getString();
                int countA = costA.getCount();

                String inputBName = !costB.isEmpty() ? costB.getHoverName().getString() : null;
                int countB = !costB.isEmpty() ? costB.getCount() : 0;

                String outputName = res.getHoverName().getString();
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
}

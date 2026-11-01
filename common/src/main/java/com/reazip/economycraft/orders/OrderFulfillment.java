package com.reazip.economycraft.orders;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.SellService;
import com.reazip.economycraft.negotiation.NegotiationEvents;
import com.reazip.economycraft.negotiation.NegotiationStore;
import com.reazip.economycraft.quests.QuestManager;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.util.ChatCompat;
import com.reazip.economycraft.util.ExpirationUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class OrderFulfillment {
    private OrderFulfillment() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Status {
        OK, ORDER_GONE, OWN_ORDER, INVALID_AMOUNT, NOT_ENOUGH_ITEMS, REQUESTER_CANT_PAY,
        FULFILLER_CANT_RECEIVE, FULL_AMOUNT_REQUIRED
    }

    /**
     * The outcome of one fulfilment attempt.
     *
     * @param given how many goods reached the buyer
     * @param taken how many goods left the seller without reaching a buyer: they sit in the
     *              seller's deliveries mailbox, so a caller that sells what the seller holds
     *              must not count them a second time
     */
    public record Result(Status status, int given, int taken, long payout, long payment, int remaining, ItemStack item, UUID requester) {
        public boolean success() {
            return status == Status.OK;
        }
    }

    public enum CancelStatus {
        OK, ORDER_GONE, NOT_OWNER, REFUND_FAILED
    }

    public enum RepriceStatus {
        OK, ORDER_GONE, NOT_OWNER, INVALID_PRICE, CANT_AFFORD_RAISE, REFUND_FAILED
    }

    public enum SetDescriptionStatus {
        OK, ORDER_GONE, NOT_OWNER
    }

    /**
     * Updates the description/note of a request in place. Ownership is checked;
     * listeners fire and disk is persisted on change.
     */
    public static SetDescriptionStatus setDescription(EconomyManager eco, UUID requester, int orderId, @org.jetbrains.annotations.Nullable String description) {
        OrderManager orders = eco.getOrders();
        OrderRequest peek = orders.getRequest(orderId);
        if (peek == null) return SetDescriptionStatus.ORDER_GONE;
        if (!peek.requester.equals(requester)) return SetDescriptionStatus.NOT_OWNER;

        peek.description = com.reazip.economycraft.util.ListingDescriptions.sanitize(description);
        orders.markChanged();
        return SetDescriptionStatus.OK;
    }

    /**
     * Reprices a request in place, adjusting the escrow to match. The new order state is staged
     * before the money mutation so the mutation's transactional save commits the price change and
     * the balance movement together — a crash can leave neither a refund against the old reward
     * nor a hold against the new one. A rejected mutation (or a thrown one) restores the previous
     * in-memory state. Raising the reward holds the difference up front; lowering it refunds the
     * difference. Either failure leaves the request untouched.
     */
    public static RepriceStatus setPrice(EconomyManager eco, UUID requester, int orderId, long newPrice) {
        OrderManager orders = eco.getOrders();
        OrderRequest peek = orders.getRequest(orderId);
        if (peek == null) return RepriceStatus.ORDER_GONE;
        if (!peek.requester.equals(requester)) return RepriceStatus.NOT_OWNER;
        if (newPrice < 1 || newPrice > EconomyManager.MAX) return RepriceStatus.INVALID_PRICE;

        long delta = newPrice - peek.price;
        String detail = EconomyCraft.describeItem(peek.amount, peek.item.getHoverName().getString());
        if (delta > 0) {
            long oldPrice = peek.price;
            long oldEscrow = peek.escrow;
            peek.price = newPrice;
            peek.escrow += delta;
            boolean debited;
            try {
                debited = eco.removeMoney(requester, delta, EconomySources.ORDER_ESCROW_HOLD, detail).successful();
            } catch (RuntimeException e) {
                peek.price = oldPrice;
                peek.escrow = oldEscrow;
                throw e;
            }
            if (!debited) {
                peek.price = oldPrice;
                peek.escrow = oldEscrow;
                return RepriceStatus.CANT_AFFORD_RAISE;
            }
            // The debit's own save already persisted the raised request; refresh open UIs only.
            orders.notifyChanged();
        } else if (delta < 0) {
            long refund = Math.min(-delta, Math.max(0, peek.escrow));
            if (refund > 0) {
                long oldPrice = peek.price;
                long oldEscrow = peek.escrow;
                peek.price = newPrice;
                peek.escrow -= refund;
                boolean refunded;
                try {
                    refunded = eco.addMoney(requester, refund, EconomySources.ORDER_ESCROW_REFUND, detail).successful();
                } catch (RuntimeException e) {
                    peek.price = oldPrice;
                    peek.escrow = oldEscrow;
                    throw e;
                }
                if (!refunded) {
                    peek.price = oldPrice;
                    peek.escrow = oldEscrow;
                    return RepriceStatus.REFUND_FAILED;
                }
                // The refund's own save already persisted the lowered request; refresh open UIs only.
                orders.notifyChanged();
            } else {
                // Nothing to move: no money mutation will persist the change, so do it here.
                peek.price = newPrice;
                orders.markChanged();
            }
        } else {
            orders.markChanged();
        }
        return RepriceStatus.OK;
    }

    private record PaymentOutcome(boolean success, boolean committed, long payout, Status failureStatus) {}

    public static OrderRequest createEscrowedRequest(EconomyManager eco, UUID requester, ItemStack item, int amount, long price) {
        return createEscrowedRequest(eco, requester, item, amount, price, null);
    }

    public static OrderRequest createEscrowedRequest(EconomyManager eco, UUID requester, ItemStack item, int amount, long price, @org.jetbrains.annotations.Nullable String description) {
        long now = System.currentTimeMillis();
        return createEscrowedRequest(eco, requester, item, amount, price, description,
                ExpirationUtil.expiresAt(now, EconomyConfig.get().orderExpirationHours), true);
    }

    /**
     * Creates and funds an escrowed request. The order is staged before the debit so the debit's
     * transactional save commits the funded order and the balance movement together — a crash can
     * leave neither a charged requester without an order nor an unfunded order. A rejected debit
     * (or a thrown mutation) unstages the order and leaves nothing behind.
     *
     * <p>The {@code broadcast = false} form with an explicit expiry is the seam the quest board
     * posts through: quest orders live exactly one board week and announce themselves.
     *
     * @return the funded request, or {@code null} when the escrow debit was rejected.
     */
    public static OrderRequest createEscrowedRequest(EconomyManager eco, UUID requester, ItemStack item, int amount, long price,
                                                     @org.jetbrains.annotations.Nullable String description, long expiresAt, boolean broadcast) {
        return createEscrowedRequest(eco, requester, item, amount, price, price, description, expiresAt, broadcast);
    }

    /** Creates a request whose reserved balance may exceed the advertised reward. */
    public static OrderRequest createEscrowedRequest(EconomyManager eco, UUID requester, ItemStack item, int amount,
                                                     long price, long escrow,
                                                     @org.jetbrains.annotations.Nullable String description,
                                                     long expiresAt, boolean broadcast) {
        return createEscrowedRequest(eco, requester, item, amount, price, escrow, description, expiresAt, broadcast, false);
    }

    /** Creates a request whose tax reserve guarantees the fulfiller the advertised reward. */
    public static OrderRequest createEscrowedRequest(EconomyManager eco, UUID requester, ItemStack item, int amount,
                                                     long price, long escrow,
                                                     @org.jetbrains.annotations.Nullable String description,
                                                     long expiresAt, boolean broadcast, boolean serverCoversOrderTax) {
        if (price <= 0 || escrow < price) return null;
        String desc = com.reazip.economycraft.util.ListingDescriptions.sanitize(description);
        String detail = EconomyCraft.describeItem(amount, item.getHoverName().getString());

        OrderRequest request = new OrderRequest();
        request.requester = requester;
        request.price = price;
        request.item = item.copy();
        request.amount = amount;
        request.originalAmount = amount;
        // Escrow is recorded before the debit so the debit's transactional save persists both.
        request.escrow = escrow;
        request.serverCoversOrderTax = serverCoversOrderTax;
        request.createdAt = System.currentTimeMillis();
        request.expiresAt = expiresAt;
        request.description = desc;

        OrderManager orders = eco.getOrders();
        orders.stage(request);
        boolean debited;
        try {
            debited = eco.removeMoney(requester, escrow, EconomySources.ORDER_ESCROW_HOLD, detail).successful();
        } catch (RuntimeException e) {
            orders.unstage(request.id);
            throw e;
        }
        if (!debited) {
            orders.unstage(request.id);
            return null;
        }

        // The debit's own save already persisted the funded request; refresh open UIs only.
        orders.notifyChanged();
        if (broadcast) {
            broadcastNewOrder(eco.getServer(), requester, item, amount, price);
        }
        return request;
    }

    private static void broadcastNewOrder(MinecraftServer server, UUID requester, ItemStack item, int amount, long price) {
        ServerPlayer requesterPlayer = server.getPlayerList().getPlayer(requester);
        String requesterName = requesterPlayer == null ? "A player" : requesterPlayer.getName().getString();
        Component message = Component.literal("[Orders] " + requesterName + " placed an order for "
                + amount + "x " + item.getHoverName().getString() + " (total "
                + EconomyCraft.formatMoney(price) + "). Type /orders to view.")
                .withStyle(ChatFormatting.GOLD);

        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (!online.getUUID().equals(requester)) {
                online.sendSystemMessage(message);
            }
        }
    }

    public static Result fulfill(EconomyManager eco, ServerPlayer fulfiller, int orderId, int requestedAmount) {
        return fulfill(eco, fulfiller, orderId, requestedAmount, false);
    }

    public static Result fulfill(EconomyManager eco, ServerPlayer fulfiller, int orderId, int requestedAmount, boolean excludeArmor) {
        OrderManager orders = eco.getOrders();
        OrderRequest peek = orders.getRequest(orderId);
        if (peek == null || peek.item == null || peek.item.isEmpty()) {
            return new Result(Status.ORDER_GONE, 0, 0, 0, 0, 0, ItemStack.EMPTY, null);
        }
        if (fulfiller.getUUID().equals(peek.requester)) {
            return new Result(Status.OWN_ORDER, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
        }

        ItemStack itemProto = peek.item.copy();
        int held = countHeld(fulfiller, itemProto, excludeArmor);

        OrderManager.ClaimResult claim = orders.claim(orderId, requestedAmount, held, false);
        return applyFulfillment(eco, orders, claim, peek.requester, fulfiller.getUUID(), itemProto,
                inventorySource(eco, fulfiller, itemProto, excludeArmor));
    }

    public static Result fulfillExact(EconomyManager eco, ServerPlayer fulfiller, int orderId, int requestedAmount, ItemStack sourceStack) {
        OrderManager orders = eco.getOrders();
        OrderRequest peek = orders.getRequest(orderId);
        if (peek == null || peek.item == null || peek.item.isEmpty()) {
            return new Result(Status.ORDER_GONE, 0, 0, 0, 0, 0, ItemStack.EMPTY, null);
        }
        if (fulfiller.getUUID().equals(peek.requester)) {
            return new Result(Status.OWN_ORDER, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
        }
        if (sourceStack == null || !ItemStack.isSameItemSameComponents(sourceStack, peek.item)) {
            return new Result(Status.NOT_ENOUGH_ITEMS, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
        }

        ItemStack itemProto = peek.item.copy();
        OrderManager.ClaimResult claim = orders.claim(orderId, requestedAmount, sourceStack.getCount(), true);
        return applyFulfillment(eco, orders, claim, peek.requester, fulfiller.getUUID(), itemProto,
                stackSource(sourceStack));
    }

    /** Fulfills from the exact matching stacks staged in a player menu as one claim and settlement. */
    static Result fulfillExact(EconomyManager eco, ServerPlayer fulfiller, int orderId, int requestedAmount,
                               List<ItemStack> sourceStacks) {
        OrderManager orders = eco.getOrders();
        OrderRequest peek = orders.getRequest(orderId);
        if (peek == null || peek.item == null || peek.item.isEmpty()) {
            return new Result(Status.ORDER_GONE, 0, 0, 0, 0, 0, ItemStack.EMPTY, null);
        }
        if (fulfiller.getUUID().equals(peek.requester)) {
            return new Result(Status.OWN_ORDER, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
        }
        if (requestedAmount <= 0) {
            return new Result(Status.INVALID_AMOUNT, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
        }

        int held = 0;
        for (ItemStack stack : sourceStacks) {
            if (stack == null || stack.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(stack, peek.item)) {
                return new Result(Status.NOT_ENOUGH_ITEMS, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
            }
            try {
                held = Math.addExact(held, stack.getCount());
            } catch (ArithmeticException overflow) {
                return new Result(Status.INVALID_AMOUNT, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
            }
        }
        if (requestedAmount > held) {
            return new Result(Status.NOT_ENOUGH_ITEMS, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
        }
        if (requestedAmount > peek.amount) {
            return new Result(Status.INVALID_AMOUNT, 0, 0, 0, 0, peek.amount, peek.item.copy(), peek.requester);
        }

        ItemStack itemProto = peek.item.copy();
        OrderManager.ClaimResult claim = orders.claim(orderId, requestedAmount, held, true);
        return applyFulfillment(eco, orders, claim, peek.requester, fulfiller.getUUID(), itemProto,
                stackSource(sourceStacks));
    }

    /**
     * Where a fulfilment takes the seller's goods from, and how it puts them back when the
     * settlement does not commit.
     *
     * <p>The goods leave before any money moves, so a crash in between can never duplicate them.
     * The restore side is what makes a settlement that refused to happen give them back.
     */
    interface ItemSource {
        /** Removes up to {@code amount} goods; returns how many were actually removed. */
        int take(int amount);

        /**
         * Puts {@code amount} goods back where they came from; returns how many could not go
         * back and are held in the seller's deliveries mailbox instead.
         */
        int restore(int amount);
    }

    /** Goods held in a single stack the seller is selling from. */
    private static ItemSource stackSource(ItemStack stack) {
        return new ItemSource() {
            @Override
            public int take(int amount) {
                int take = Math.min(amount, stack.getCount());
                stack.shrink(take);
                return take;
            }

            @Override
            public int restore(int amount) {
                // The stack still exists and still holds the goods' place: give them back to it.
                stack.setCount(stack.getCount() + amount);
                return 0;
            }
        };
    }

    /** Goods staged across several matching menu slots. */
    static ItemSource stackSource(List<ItemStack> stacks) {
        int[] taken = new int[stacks.size()];
        return new ItemSource() {
            @Override
            public int take(int amount) {
                int remaining = amount;
                for (int i = 0; i < stacks.size() && remaining > 0; i++) {
                    ItemStack stack = stacks.get(i);
                    int count = stack == null || stack.isEmpty() ? 0 : Math.min(remaining, stack.getCount());
                    if (count <= 0) continue;
                    stack.shrink(count);
                    taken[i] += count;
                    remaining -= count;
                }
                return amount - remaining;
            }

            @Override
            public int restore(int amount) {
                int remaining = amount;
                for (int i = 0; i < stacks.size() && remaining > 0; i++) {
                    int count = Math.min(remaining, taken[i]);
                    if (count <= 0) continue;
                    ItemStack stack = stacks.get(i);
                    stack.setCount(stack.getCount() + count);
                    taken[i] -= count;
                    remaining -= count;
                }
                return remaining;
            }
        };
    }

    /** Goods held in the seller's inventory. */
    private static ItemSource inventorySource(EconomyManager eco, ServerPlayer fulfiller, ItemStack proto,
                                               boolean excludeArmor) {
        return new ItemSource() {
            @Override
            public int take(int amount) {
                return removeItems(fulfiller, proto, amount, excludeArmor);
            }

            @Override
            public int restore(int amount) {
                return giveBackItems(eco, fulfiller, proto, amount);
            }
        };
    }

    private static Result applyFulfillment(EconomyManager eco, OrderManager orders, OrderManager.ClaimResult claim,
                                            UUID requester, UUID fulfillerId, ItemStack itemProto, ItemSource source) {
        switch (claim.status()) {
            case ORDER_GONE -> {
                return new Result(Status.ORDER_GONE, 0, 0, 0, 0, 0, ItemStack.EMPTY, null);
            }
            case INVALID_AMOUNT -> {
                return new Result(Status.INVALID_AMOUNT, 0, 0, 0, 0, claim.order().amount, itemProto, requester);
            }
            case FULL_AMOUNT_REQUIRED -> {
                return new Result(Status.FULL_AMOUNT_REQUIRED, 0, 0, 0, 0, claim.order().amount, itemProto, requester);
            }
            case NOT_ENOUGH_ITEMS -> {
                return new Result(Status.NOT_ENOUGH_ITEMS, 0, 0, 0, 0, claim.order().amount, itemProto, requester);
            }
            default -> {}
        }

        String detail = EconomyCraft.describeItem(claim.given(), itemProto.getHoverName().getString());

        boolean botOrder = QuestManager.BOT_UUID.equals(requester);
        boolean taxCoveredBounty = botOrder && claim.order().serverCoversOrderTax;
        long botTax = 0L;
        if (botOrder) {
            botTax = TaxPolicy.tax(TaxScope.TRANSACTION_ORDER, claim.payment(), fulfillerId, eco);
            if (botTax < 0 || (taxCoveredBounty && claim.order().escrow < botTax)) {
                orders.rollbackClaim(claim.order(), claim.given(), claim.payment(), claim.escrowUsed(), claim.exhausted());
                return new Result(Status.REQUESTER_CANT_PAY, 0, 0, 0, 0, claim.order().amount, itemProto, requester);
            }
            if (taxCoveredBounty) {
                try {
                    claim = new OrderManager.ClaimResult(claim.status(), claim.order(), claim.given(), claim.payment(),
                            Math.addExact(claim.escrowUsed(), botTax), claim.exhausted());
                    claim.order().escrow -= botTax;
                } catch (ArithmeticException overflow) {
                    orders.rollbackClaim(claim.order(), claim.given(), claim.payment(), claim.escrowUsed(), claim.exhausted());
                    return new Result(Status.REQUESTER_CANT_PAY, 0, 0, 0, 0, claim.order().amount, itemProto, requester);
                }
            }
        }

        // The goods leave the fulfiller exactly once, and they leave it BEFORE any money moves:
        // no settlement can commit while they are still in the inventory, because there is no
        // transaction that spans the ledger and Minecraft's own saves. The removal becomes
        // recoverable immediately after — see the handoff below — so neither ordering nor a
        // crash can trade duplication for item loss.
        int taken = source.take(claim.given());
        if (taken < claim.given()) {
            // The seller could not produce the goods the claim reserved. Give back what was taken
            // and roll the claim back, so the order is exactly as it was.
            source.restore(taken);
            orders.rollbackClaim(claim.order(), claim.given(), claim.payment(), claim.escrowUsed(), claim.exhausted());
            return new Result(Status.NOT_ENOUGH_ITEMS, 0, 0, 0, 0, claim.order().amount, itemProto, requester);
        }

        // Recoverable handoff for the taken stacks: they go into the fulfiller's OWN deliveries
        // mailbox and that append is persisted on its own, in a transaction that reports a failed
        // write instead of swallowing it, so from here on the goods are always somewhere durable.
        // If the settlement below never commits — a rejected payout, a thrown save, a crash
        // between the two — the stacks are a claimable delivery, the same compensation the
        // market listing flow uses. If it does commit, the same transactional save that pays and
        // delivers consumes the handoff, so the mailbox never holds a second copy of goods the
        // buyer already received.
        List<ItemStack> handoff = splitForMailbox(itemProto, claim.given());
        for (ItemStack stack : handoff) {
            eco.getDeliveries().addDelivery(fulfillerId, stack, false);
        }
        try {
            eco.getDeliveries().saveReportingFailure();
        } catch (RuntimeException e) {
            // The handoff did not commit, so the goods exist nowhere but in this stack frame: give
            // them back to the seller and roll the claim back, and let the write failure surface.
            for (ItemStack stack : handoff) {
                eco.getDeliveries().removeDelivery(fulfillerId, stack, false);
            }
            source.restore(claim.given());
            orders.rollbackClaim(claim.order(), claim.given(), claim.payment(), claim.escrowUsed(), claim.exhausted());
            throw e;
        }
        // Drop the handoff live but unsaved: the settlement's own save then persists its removal
        // together with the payment, the order reduction and the buyer's delivery/quest stock.
        consumeHandoff(eco, fulfillerId, handoff);

        String stagedStockKey = botOrder ? stageQuestStock(eco, itemProto, claim.given()) : null;
        List<ItemStack> stagedDeliveries = botOrder
                ? List.of()
                : stageDeliveries(eco, requester, itemProto, claim.given());

        PaymentOutcome outcome;
        try {
            outcome = settleOrderPayment(eco, requester, fulfillerId, claim.payment(), claim.escrowUsed(), detail,
                    botOrder, taxCoveredBounty, botTax, claim.order(), claim.exhausted());
        } catch (RuntimeException e) {
            // The save threw before it committed, so the transaction rolled back: the balances go
            // back the way the engine found them, and the handoff plus every staged good and the
            // claim must follow, or a later save would commit goods against a settlement that
            // refused to happen. The goods stay in the mailbox: the database is the thing that
            // just failed, and there they are durable and claimable.
            abandonFulfillment(eco, orders, fulfillerId, handoff, requester, stagedStockKey,
                    stagedDeliveries, claim);
            throw e;
        }
        if (!outcome.success()) {
            // The payout was refused and nothing was saved, so the persisted handoff is the only
            // durable copy of the goods. Give them back to the seller: the handoff is cleared
            // first, because until that write commits the mailbox still holds a copy of goods the
            // seller is about to hold again. The buyer's staged goods come out of the live ledger
            // before that write, so the write cannot persist goods against a settlement that
            // refused to happen.
            unstageGoods(eco, requester, stagedStockKey, stagedDeliveries, claim.given());
            int mailboxHeld = returnGoodsToSeller(eco, source, handoff, fulfillerId, claim.given());
            orders.rollbackClaim(claim.order(), claim.given(), claim.payment(), claim.escrowUsed(), claim.exhausted());
            return new Result(outcome.failureStatus(), 0, mailboxHeld, 0, 0, claim.order().amount, itemProto, requester);
        }

        if (!outcome.committed()) {
            // A zero-money settlement has no mutation save of its own; commit the handoff's
            // consumption, the staged goods and the claim here.
            try {
                eco.save();
            } catch (RuntimeException e) {
                abandonFulfillment(eco, orders, fulfillerId, handoff, requester, stagedStockKey,
                        stagedDeliveries, claim);
                throw e;
            }
        }

        if (claim.exhausted()) {
            NegotiationEvents.invalidateTarget(eco, NegotiationStore.Kind.ORDER, claim.order().id,
                    EconomyCraft.describeItem(claim.given(), itemProto.getHoverName().getString()),
                    "was fully fulfilled");
        }
        notifyRequester(eco.getServer(), requester, claim.given(), itemProto);
        orders.notifyChanged();

        int remaining = claim.exhausted() ? 0 : claim.order().amount;
        return new Result(Status.OK, claim.given(), 0, outcome.payout(), claim.payment(), remaining, itemProto, requester);
    }

    /**
     * Gives the goods back to the seller after a settlement that refused to happen.
     *
     * <p>The handoff is still in the mailbox, durably. It is cleared first, because until that
     * write commits the mailbox holds a copy of goods the seller is about to hold again. When the
     * clear cannot commit, the goods stay in the mailbox instead: one home for them, always, and
     * claimable there.
     *
     * @return how many goods ended up in the mailbox rather than back with the seller
     */
    private static int returnGoodsToSeller(EconomyManager eco, ItemSource source, List<ItemStack> handoff,
                                            UUID fulfillerId, int amount) {
        // Drop the handoff from the live mailbox without persisting it yet.
        consumeHandoff(eco, fulfillerId, handoff);
        try {
            eco.getDeliveries().saveReportingFailure();
        } catch (RuntimeException e) {
            // The mailbox still holds the goods on disk, so the live ledger has to hold them too:
            // put them back and leave them there. They are claimable, which is what matters.
            restoreHandoff(eco, fulfillerId, handoff);
            LOGGER.warn("[EconomyCraft] Could not clear the delivery handoff for {}; the goods stay in their mailbox", fulfillerId);
            return amount;
        }
        // The mailbox no longer holds them: give them back where they came from.
        int parked = source.restore(amount);
        if (parked > 0) {
            // What did not fit back went to the mailbox; make that durable too.
            try {
                eco.getDeliveries().saveReportingFailure();
            } catch (RuntimeException e) {
                LOGGER.warn("[EconomyCraft] Could not persist the returned goods for {}; they are in their mailbox", fulfillerId);
            }
        }
        return parked;
    }

    private static PaymentOutcome settleOrderPayment(EconomyManager eco, UUID requester, UUID fulfillerId, long payment,
                                                       long escrowUsed, String detail, boolean botOrder,
                                                       boolean taxCoveredBounty, long botTax,
                                                       OrderRequest order, boolean exhausted) {
        if (botOrder) {
            long payout = taxCoveredBounty ? payment : payment - botTax;
            if (taxCoveredBounty) {
                long unusedReserve = exhausted ? Math.max(0L, order.escrow) : 0L;
                long refundCapacity = Math.max(0L, EconomyManager.maxBalance() - eco.peekBalance(requester));
                long reserveRefund = Math.min(unusedReserve, refundCapacity);
                if (reserveRefund < unusedReserve) {
                    LOGGER.warn("[EconomyCraft] Could only return {} of {} unused quest tax reserve; the quest wallet is at its balance limit.",
                            reserveRefund, unusedReserve);
                }
                boolean settled = eco.settleQuestOrderPayout(fulfillerId, payout, detail, reserveRefund,
                        "unused quest tax reserve");
                return settled
                        ? new PaymentOutcome(true, true, payout, null)
                        : new PaymentOutcome(false, false, 0, Status.FULFILLER_CANT_RECEIVE);
            }
            var credit = eco.addMoney(fulfillerId, payout, EconomySources.ORDER_FULFILLMENT, detail);
            if (!credit.successful()) {
                return new PaymentOutcome(false, false, 0, Status.FULFILLER_CANT_RECEIVE);
            }
            return new PaymentOutcome(true, true, payout, null);
        }

        long tax = TaxPolicy.tax(TaxScope.TRANSACTION_ORDER, payment, fulfillerId, eco);
        long payout = payment - tax;
        long shortfall = payment - escrowUsed;

        if (shortfall > 0) {
            var transfer = eco.transferMoney(requester, fulfillerId, shortfall, payout, EconomySources.ORDER_FULFILLMENT, detail);
            if (!transfer.successful()) {
                Status status = transfer.status() == com.reazip.economycraft.api.v1.BalanceMutationStatus.MAX_BALANCE_EXCEEDED
                        ? Status.FULFILLER_CANT_RECEIVE : Status.REQUESTER_CANT_PAY;
                return new PaymentOutcome(false, false, 0, status);
            }
            return new PaymentOutcome(true, true, payout, null);
        }
        if (payout > 0) {
            var credit = eco.addMoney(fulfillerId, payout, EconomySources.ORDER_FULFILLMENT, detail);
            if (!credit.successful()) {
                return new PaymentOutcome(false, false, 0, Status.FULFILLER_CANT_RECEIVE);
            }
            return new PaymentOutcome(true, true, payout, null);
        }
        return new PaymentOutcome(true, false, 0, null);
    }

    public static CancelStatus cancel(EconomyManager eco, UUID requester, int orderId) {
        OrderManager orders = eco.getOrders();
        OrderRequest peek = orders.getRequest(orderId);
        if (peek == null) return CancelStatus.ORDER_GONE;
        if (!peek.requester.equals(requester)) return CancelStatus.NOT_OWNER;

        String desc = EconomyCraft.describeItem(peek.amount, peek.item.getHoverName().getString());
        // The removal is not persisted up front: the escrow refund's transactional save commits the
        // removal and the money movement together, so a crash can leave neither a vanished order
        // without its refund nor a refund against a still-open order.
        OrderRequest order = orders.removeRequest(orderId, false);
        if (order == null) return CancelStatus.ORDER_GONE;

        var refund = refundEscrow(eco, orders, order);
        if (refund != null && !refund.successful()) return CancelStatus.REFUND_FAILED;
        if (refund == null) {
            // No escrow moved, so no money mutation will persist the removal — do it here.
            orders.save();
        }

        NegotiationEvents.invalidateTarget(eco, NegotiationStore.Kind.ORDER, orderId, desc, "was removed");
        return CancelStatus.OK;
    }

    public static void expireOverdue(EconomyManager eco) {
        OrderManager orders = eco.getOrders();
        long now = System.currentTimeMillis();
        boolean anyExpired = false;
        for (OrderRequest snapshot : orders.getRequests()) {
            if (!ExpirationUtil.isExpired(snapshot.expiresAt, now)) continue;

            OrderRequest order = orders.removeRequest(snapshot.id, false);
            if (order == null) continue;
            anyExpired = true;

            long refund = order.escrow;
            var result = refundEscrow(eco, orders, order);
            if (result != null && !result.successful()) {
                LOGGER.warn("[EconomyCraft] Expired order {} escrow refund of {} to {} failed ({}); will retry next sweep",
                        order.id, refund, order.requester, result.status());
                continue;
            }

            NegotiationEvents.invalidateTarget(eco, NegotiationStore.Kind.ORDER, order.id,
                    EconomyCraft.describeItem(order.amount, order.item.getHoverName().getString()),
                    "expired");
            notifyExpired(eco, order, refund);
        }
        if (anyExpired) orders.save();
        eco.getNotifications().flush();
    }

    private static BalanceMutationResult refundEscrow(EconomyManager eco, OrderManager orders, OrderRequest order) {
        return refundEscrow(eco, orders, order, true);
    }

    private static BalanceMutationResult refundEscrow(EconomyManager eco, OrderManager orders, OrderRequest order,
                                                      boolean restoreOnFailure) {
        if (order.escrow <= 0) return null;
        String detail = EconomyCraft.describeItem(order.amount, order.item.getHoverName().getString());
        var refund = eco.addMoney(order.requester, order.escrow, EconomySources.ORDER_ESCROW_REFUND, detail);
        if (refund.successful()) {
            order.escrow = 0;
        } else if (restoreOnFailure) {
            orders.restoreRequest(order);
        }
        return refund;
    }

    private static void notifyExpired(EconomyManager eco, OrderRequest order, long refund) {
        String itemName = order.item.getHoverName().getString();
        String message = "Your order for " + order.amount + "x " + itemName + " expired"
                + (refund > 0 ? " and " + EconomyCraft.formatMoney(refund) + " was refunded." : ".");
        eco.getNotifications().notify(order.requester, message);
    }

    public static int clearAll(EconomyManager eco) {
        OrderManager orders = eco.getOrders();
        int cleared = 0;
        for (OrderRequest snapshot : orders.getRequests()) {
            OrderRequest order = orders.removeRequest(snapshot.id, false);
            if (order == null) continue;

            long refund = order.escrow;
            var result = refundEscrow(eco, orders, order);
            if (result != null && !result.successful()) {
                LOGGER.warn("[EconomyCraft] Failed to refund order {} escrow of {} to {} while clearing orders; it was left in place",
                        order.id, refund, order.requester);
                continue;
            }

            cleared++;

            NegotiationEvents.invalidateTarget(eco, NegotiationStore.Kind.ORDER, order.id,
                    EconomyCraft.describeItem(order.amount, order.item.getHoverName().getString()),
                    "was cleared by an admin");
            notifyCleared(eco, order, refund);
        }
        if (cleared > 0) orders.save();
        eco.getNotifications().flush();
        return cleared;
    }

    /**
     * Clears orders during a complete economy reset. A reset is allowed to discard an escrow when
     * the configured balance cap makes a refund impossible; leaving the request in place would
     * violate the reset's promise to cancel every order and strand a liability that the reset has
     * already removed from the balance ledger.
     */
    public static int clearAllForReset(EconomyManager eco) {
        OrderManager orders = eco.getOrders();
        int cleared = 0;
        for (OrderRequest snapshot : orders.getRequests()) {
            OrderRequest order = orders.removeRequest(snapshot.id, false);
            if (order == null) continue;

            long refund = order.escrow;
            BalanceMutationResult result;
            try {
                result = refundEscrow(eco, orders, order, false);
            } catch (RuntimeException e) {
                // Restore the request if persistence itself failed. The reset can be retried
                // safely once the database is healthy again.
                orders.restoreRequest(order);
                throw e;
            }
            if (result != null && !result.successful()) {
                LOGGER.warn("[EconomyCraft] Discarding order {} escrow of {} during complete reset; refund failed with {}",
                        order.id, refund, result.status());
            }

            cleared++;
            NegotiationEvents.invalidateTarget(eco, NegotiationStore.Kind.ORDER, order.id,
                    EconomyCraft.describeItem(order.amount, order.item.getHoverName().getString()),
                    "was cleared by an admin reset");
            notifyCleared(eco, order, refund, result == null || result.successful());
        }
        if (cleared > 0) orders.save();
        eco.getNotifications().flush();
        return cleared;
    }

    private static void notifyCleared(EconomyManager eco, OrderRequest order, long refund) {
        notifyCleared(eco, order, refund, true);
    }

    private static void notifyCleared(EconomyManager eco, OrderRequest order, long refund, boolean refunded) {
        String itemName = order.item.getHoverName().getString();
        String message = "Your order for " + order.amount + "x " + itemName + " was cleared by an admin"
                + (refund > 0 && refunded ? " and " + EconomyCraft.formatMoney(refund) + " was refunded."
                : refund > 0 ? "; its escrow was cleared with the economy reset." : ".");
        eco.getNotifications().notify(order.requester, message);
    }

    public static List<OrderRequest> findBetterOrders(EconomyManager eco, ItemStack proto, UUID seller, long serverUnitSell) {
        PriceRegistry prices = eco.getPrices();
        PriceRegistry.PriceEntry protoPrice = prices.resolve(proto);
        if (protoPrice == null) return List.of();

        List<OrderRequest> out = new ArrayList<>();
        for (OrderRequest order : eco.getOrders().getRequests()) {
            if (order.amount <= 0 || order.item == null || order.item.isEmpty()) continue;
            if (!order.item.is(proto.getItem())) continue;
            if (seller.equals(order.requester)) continue;

            PriceRegistry.PriceEntry orderPrice = prices.resolve(order.item);
            if (orderPrice == null || !orderPrice.key().equals(protoPrice.key())) continue;

            if (netRatePerUnit(order) > serverUnitSell) out.add(order);
        }
        out.sort((a, b) -> Double.compare(netRatePerUnit(b), netRatePerUnit(a)));
        return out;
    }

    public static int countHeld(ServerPlayer player, ItemStack proto) {
        return countHeld(player, proto, false);
    }

    private static int countHeld(ServerPlayer player, ItemStack proto, boolean excludeArmor) {
        if (proto == null || proto.isEmpty()) return 0;
        int total = 0;
        var inv = player.getInventory();
        int limit = excludeArmor ? SellService.MAIN_INVENTORY_SLOTS : inv.getContainerSize();
        for (int i = 0; i < limit; i++) {
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, proto)) total += s.getCount();
        }
        if (excludeArmor) {
            ItemStack offhand = player.getOffhandItem();
            if (ItemStack.isSameItemSameComponents(offhand, proto)) total += offhand.getCount();
        }
        return total;
    }

    public static long payoutFor(OrderRequest order, int give) {
        long payment = OrderManager.partialPayment(order, give);
        if (order != null && QuestManager.BOT_UUID.equals(order.requester) && order.serverCoversOrderTax) return payment;
        long tax = TaxPolicy.tax(TaxScope.TRANSACTION_ORDER, payment);
        return payment - tax;
    }

    private static double netRatePerUnit(OrderRequest order) {
        if (order == null || order.amount <= 0) return 0;
        if (QuestManager.BOT_UUID.equals(order.requester) && order.serverCoversOrderTax) {
            return order.price / (double) order.amount;
        }
        return TaxPolicy.netRate(TaxScope.TRANSACTION_ORDER, order.price / (double) order.amount);
    }

    private static int removeItems(ServerPlayer player, ItemStack proto, int amount, boolean excludeArmor) {
        int remaining = amount;
        var inv = player.getInventory();
        int limit = excludeArmor ? SellService.MAIN_INVENTORY_SLOTS : inv.getContainerSize();
        for (int i = 0; i < limit && remaining > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, proto)) {
                int take = Math.min(s.getCount(), remaining);
                s.shrink(take);
                remaining -= take;
            }
        }
        if (excludeArmor && remaining > 0) {
            ItemStack offhand = player.getOffhandItem();
            if (ItemStack.isSameItemSameComponents(offhand, proto)) {
                int take = Math.min(offhand.getCount(), remaining);
                offhand.shrink(take);
                remaining -= take;
            }
        }
        return amount - remaining;
    }

    /**
     * Puts goods back into the seller's inventory. What does not fit goes to their deliveries
     * mailbox, where it is durable and claimable — the same compensation the rest of the mod
     * gives when a player's inventory is full.
     *
     * @return how many goods went to the mailbox instead of the inventory
     */
    private static int giveBackItems(EconomyManager eco, ServerPlayer fulfiller, ItemStack proto, int amount) {
        int remaining = amount;
        int parked = 0;
        while (remaining > 0) {
            int give = Math.min(proto.getMaxStackSize(), remaining);
            ItemStack stack = proto.copyWithCount(give);
            if (!fulfiller.getInventory().add(stack)) {
                eco.getDeliveries().addDelivery(fulfiller.getUUID(), stack, false);
                parked += give;
            }
            remaining -= give;
        }
        return parked;
    }

    /**
     * Books bot-bound goods into the quest stock ledger instead of a deliveries mailbox, which nobody
     * would ever claim. The finite shop uses the same price key to find and sell that stock.
     * Staged without persisting so the payout's save commits the stock and the money together.
     */
    private static String stageQuestStock(EconomyManager eco, ItemStack proto, int amount) {
        PriceRegistry.PriceEntry entry = eco.getPrices().resolve(proto);
        String key = entry != null ? entry.key() : proto.getItem().toString();
        eco.getQuestStock().deposit(key, amount, false);
        return key;
    }

    private static List<ItemStack> stageDeliveries(EconomyManager eco, UUID requester, ItemStack proto, int amount) {
        List<ItemStack> staged = new ArrayList<>();
        for (ItemStack stack : splitForMailbox(proto, amount)) {
            eco.getDeliveries().addDelivery(requester, stack, false);
            staged.add(stack);
        }
        return staged;
    }

    /** One stack per mailbox entry, bounded by the item's own max stack size. */
    private static List<ItemStack> splitForMailbox(ItemStack proto, int amount) {
        List<ItemStack> stacks = new ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int c = Math.min(proto.getMaxStackSize(), remaining);
            stacks.add(proto.copyWithCount(c));
            remaining -= c;
        }
        return stacks;
    }

    /**
     * Drops the handoff entries from the live mailbox without persisting: the settlement's own
     * transactional save is what removes them from disk, together with everything else it commits.
     */
    private static void consumeHandoff(EconomyManager eco, UUID fulfillerId, List<ItemStack> handoff) {
        for (ItemStack stack : handoff) {
            eco.getDeliveries().removeDelivery(fulfillerId, stack, false);
        }
    }

    /** Puts the handoff entries back in the live mailbox; they are still claimable compensation. */
    private static void restoreHandoff(EconomyManager eco, UUID fulfillerId, List<ItemStack> handoff) {
        for (ItemStack stack : handoff) {
            eco.getDeliveries().addDelivery(fulfillerId, stack, false);
        }
    }

    /**
     * Everything a settlement that did not commit must give back: the handoff entries (the persisted
     * copies from their own save are the durable claim, this re-syncs the live ledger), the staged
     * buyer goods, and the claim.
     */
    private static void abandonFulfillment(EconomyManager eco, OrderManager orders, UUID fulfillerId,
                                           List<ItemStack> handoff, UUID requester, String stagedStockKey,
                                           List<ItemStack> stagedDeliveries, OrderManager.ClaimResult claim) {
        restoreHandoff(eco, fulfillerId, handoff);
        unstageGoods(eco, requester, stagedStockKey, stagedDeliveries, claim.given());
        orders.rollbackClaim(claim.order(), claim.given(), claim.payment(), claim.escrowUsed(), claim.exhausted());
    }

    private static void unstageGoods(EconomyManager eco, UUID requester, String stockKey,
                                     List<ItemStack> deliveries, int amount) {
        if (stockKey != null) {
            eco.getQuestStock().withdraw(stockKey, amount, false);
            return;
        }
        for (ItemStack stack : deliveries) {
            eco.getDeliveries().removeDelivery(requester, stack, false);
        }
    }

    private static void notifyRequester(MinecraftServer server, UUID requester, int amount, ItemStack item) {
        ServerPlayer requesterPlayer = server.getPlayerList().getPlayer(requester);
        if (requesterPlayer == null) return;

        String itemName = item.getHoverName().getString();
        String prefix = amount + "x " + itemName + " of your request has been fulfilled: ";

        ClickEvent ev = ChatCompat.runCommandEvent("/eco deliveries");
        if (ev != null) {
            requesterPlayer.sendSystemMessage(Component.literal(prefix)
                    .withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("[Claim]")
                            .withStyle(s -> s.withUnderlined(true)
                                    .withColor(ChatFormatting.GREEN)
                                    .withClickEvent(ev))));
        } else {
            ChatCompat.sendRunCommandTellraw(requesterPlayer, prefix, "[Claim]", "/eco deliveries");
        }
    }
}

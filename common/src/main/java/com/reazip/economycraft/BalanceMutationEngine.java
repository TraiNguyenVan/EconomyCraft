package com.reazip.economycraft;

import com.reazip.economycraft.api.v1.BalanceChangeEvent;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.BalanceMutationStatus;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.api.v1.PaymentResult;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class BalanceMutationEngine {
    private final Map<UUID, Long> balances;
    private final LongSupplier startingBalance;
    private final Runnable onInitialized;
    private final Runnable onMutationCommitted;
    private final BalanceEventDispatcher events;
    private final Consumer<PaymentResult> onTransfer;

    BalanceMutationEngine(
            Map<UUID, Long> balances,
            LongSupplier startingBalance,
            Runnable onInitialized,
            Runnable onMutationCommitted,
            BalanceEventDispatcher events,
            Consumer<PaymentResult> onTransfer
    ) {
        this.balances = balances;
        this.startingBalance = startingBalance;
        this.onInitialized = onInitialized;
        this.onMutationCommitted = onMutationCommitted;
        this.events = events;
        this.onTransfer = onTransfer;
    }

    long getBalance(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Long existing = balances.get(playerId);
        if (existing != null) return existing;

        long initialized = configuredStartingBalance();
        balances.put(playerId, initialized);
        onInitialized.run();
        return initialized;
    }

    long peekBalance(UUID playerId) {
        return state(Objects.requireNonNull(playerId, "playerId")).balance();
    }

    BalanceMutationResult add(UUID playerId, long amount, MutationSource source, String detail, String item) {
        Objects.requireNonNull(playerId, "playerId");
        BalanceState state = state(playerId);
        if (amount <= 0) return result(BalanceMutationStatus.INVALID_AMOUNT, BalanceMutationType.ADD,
                playerId, amount, state.balance(), state.balance(), source);
        if (amount > EconomyManager.maxBalance() - state.balance()) {
            return result(BalanceMutationStatus.MAX_BALANCE_EXCEEDED, BalanceMutationType.ADD,
                    playerId, amount, state.balance(), state.balance(), source);
        }
        return commitSingle(state, amount, state.balance() + amount, BalanceMutationType.ADD, source, detail, item);
    }

    BalanceMutationResult remove(UUID playerId, long amount, MutationSource source, String detail, String item) {
        Objects.requireNonNull(playerId, "playerId");
        BalanceState state = state(playerId);
        if (amount <= 0) return result(BalanceMutationStatus.INVALID_AMOUNT, BalanceMutationType.REMOVE,
                playerId, amount, state.balance(), state.balance(), source);
        if (state.balance() < amount) {
            return result(BalanceMutationStatus.INSUFFICIENT_FUNDS, BalanceMutationType.REMOVE,
                    playerId, amount, state.balance(), state.balance(), source);
        }
        return commitSingle(state, amount, state.balance() - amount, BalanceMutationType.REMOVE, source, detail, item);
    }

    BalanceMutationResult set(UUID playerId, long requestedBalance, MutationSource source, String detail) {
        Objects.requireNonNull(playerId, "playerId");
        BalanceState state = state(playerId);
        if (requestedBalance < 0) {
            return result(BalanceMutationStatus.INVALID_AMOUNT, BalanceMutationType.SET,
                    playerId, requestedBalance, state.balance(), state.balance(), source);
        }
        if (requestedBalance > EconomyManager.maxBalance()) {
            return result(BalanceMutationStatus.MAX_BALANCE_EXCEEDED, BalanceMutationType.SET,
                    playerId, requestedBalance, state.balance(), state.balance(), source);
        }
        if (requestedBalance == state.balance()) {
            if (!state.existed()) {
                balances.put(playerId, state.balance());
                onInitialized.run();
            }
            return result(BalanceMutationStatus.NO_CHANGE, BalanceMutationType.SET,
                    playerId, requestedBalance, state.balance(), state.balance(), source);
        }
        return commitSingle(state, requestedBalance, requestedBalance, BalanceMutationType.SET, source, detail, null);
    }

    PaymentResult pay(UUID senderId, UUID receiverId, long amount, MutationSource source, String detail) {
        return transfer(senderId, receiverId, amount, amount, source, detail, null);
    }

    PaymentResult transfer(UUID senderId, UUID receiverId, long debitAmount, long creditAmount, MutationSource source, String detail, String item) {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(receiverId, "receiverId");
        BalanceState sender = state(senderId);
        BalanceState receiver = state(receiverId);

        if (senderId.equals(receiverId)) {
            return paymentResult(BalanceMutationStatus.SAME_PLAYER, senderId, receiverId, debitAmount,
                    sender.balance(), sender.balance(), receiver.balance(), receiver.balance(), source);
        }
        if (debitAmount <= 0 || creditAmount < 0) {
            return paymentResult(BalanceMutationStatus.INVALID_AMOUNT, senderId, receiverId, debitAmount,
                    sender.balance(), sender.balance(), receiver.balance(), receiver.balance(), source);
        }
        if (sender.balance() < debitAmount) {
            return paymentResult(BalanceMutationStatus.INSUFFICIENT_FUNDS, senderId, receiverId, debitAmount,
                    sender.balance(), sender.balance(), receiver.balance(), receiver.balance(), source);
        }
        if (creditAmount > EconomyManager.maxBalance() - receiver.balance()) {
            return paymentResult(BalanceMutationStatus.MAX_BALANCE_EXCEEDED, senderId, receiverId, debitAmount,
                    sender.balance(), sender.balance(), receiver.balance(), receiver.balance(), source);
        }

        long senderNew = sender.balance() - debitAmount;
        long receiverNew = receiver.balance() + creditAmount;
        balances.put(senderId, senderNew);
        if (creditAmount > 0) balances.put(receiverId, receiverNew);
        try {
            onMutationCommitted.run();
        } catch (RuntimeException e) {
            restore(sender);
            if (creditAmount > 0) restore(receiver);
            throw e;
        }

        Optional<MutationSource> optionalSource = Optional.ofNullable(source);
        Optional<String> optionalDetail = Optional.ofNullable(detail);
        PaymentResult result = new PaymentResult(BalanceMutationStatus.SUCCESS, senderId, receiverId, debitAmount,
                sender.balance(), senderNew, receiver.balance(), receiverNew, optionalSource);
        events.emit(new BalanceChangeEvent(senderId, sender.balance(), senderNew,
                BalanceMutationType.PAYMENT_SENT, Optional.of(receiverId), optionalSource, optionalDetail), item);
        if (creditAmount > 0) {
            events.emit(new BalanceChangeEvent(receiverId, receiver.balance(), receiverNew,
                    BalanceMutationType.PAYMENT_RECEIVED, Optional.of(senderId), optionalSource, optionalDetail), item);
        }
        onTransfer.accept(result);
        return result;
    }

    /** Settles one debit against several recipients in one balance/database commit. */
    PaymentResult transferMany(UUID senderId, Map<UUID, Long> credits, long debitAmount,
                               MutationSource source, String detail) {
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(credits, "credits");
        BalanceState sender = state(senderId);
        UUID firstReceiver = credits.keySet().stream().findFirst().orElse(senderId);
        if (senderId.equals(firstReceiver)) {
            return paymentResult(BalanceMutationStatus.SAME_PLAYER, senderId, firstReceiver, debitAmount,
                    sender.balance(), sender.balance(), sender.balance(), sender.balance(), source);
        }
        if (debitAmount <= 0 || credits.isEmpty() || credits.values().stream().anyMatch(amount -> amount == null || amount < 0)) {
            return paymentResult(BalanceMutationStatus.INVALID_AMOUNT, senderId, firstReceiver, debitAmount,
                    sender.balance(), sender.balance(), state(firstReceiver).balance(), state(firstReceiver).balance(), source);
        }
        if (sender.balance() < debitAmount) {
            return paymentResult(BalanceMutationStatus.INSUFFICIENT_FUNDS, senderId, firstReceiver, debitAmount,
                    sender.balance(), sender.balance(), state(firstReceiver).balance(), state(firstReceiver).balance(), source);
        }

        Map<UUID, BalanceState> states = new LinkedHashMap<>();
        for (Map.Entry<UUID, Long> entry : credits.entrySet()) {
            UUID receiverId = Objects.requireNonNull(entry.getKey(), "receiver id");
            if (senderId.equals(receiverId)) {
                return paymentResult(BalanceMutationStatus.SAME_PLAYER, senderId, receiverId, debitAmount,
                        sender.balance(), sender.balance(), sender.balance(), sender.balance(), source);
            }
            BalanceState receiver = state(receiverId);
            if (entry.getValue() > EconomyManager.maxBalance() - receiver.balance()) {
                return paymentResult(BalanceMutationStatus.MAX_BALANCE_EXCEEDED, senderId, receiverId, debitAmount,
                        sender.balance(), sender.balance(), receiver.balance(), receiver.balance(), source);
            }
            states.put(receiverId, receiver);
        }

        Map<UUID, Long> staged = new LinkedHashMap<>();
        staged.put(senderId, sender.balance() - debitAmount);
        for (Map.Entry<UUID, Long> entry : credits.entrySet()) {
            BalanceState receiver = states.get(entry.getKey());
            staged.put(entry.getKey(), receiver.balance() + entry.getValue());
        }
        balances.putAll(staged);
        try {
            onMutationCommitted.run();
        } catch (RuntimeException e) {
            restore(sender);
            states.values().forEach(this::restore);
            throw e;
        }

        Optional<MutationSource> optionalSource = Optional.ofNullable(source);
        Optional<String> optionalDetail = Optional.ofNullable(detail);
        events.emit(new BalanceChangeEvent(senderId, sender.balance(), staged.get(senderId),
                BalanceMutationType.PAYMENT_SENT, Optional.of(firstReceiver), optionalSource, optionalDetail), null);
        for (Map.Entry<UUID, Long> entry : credits.entrySet()) {
            BalanceState receiver = states.get(entry.getKey());
            long newBalance = staged.get(entry.getKey());
            events.emit(new BalanceChangeEvent(entry.getKey(), receiver.balance(), newBalance,
                    BalanceMutationType.PAYMENT_RECEIVED, Optional.of(senderId), optionalSource, optionalDetail), null);
            onTransfer.accept(new PaymentResult(BalanceMutationStatus.SUCCESS, senderId, entry.getKey(), entry.getValue(),
                    sender.balance(), staged.get(senderId), receiver.balance(), newBalance, optionalSource));
        }
        return new PaymentResult(BalanceMutationStatus.SUCCESS, senderId, firstReceiver, debitAmount,
                sender.balance(), staged.get(senderId), states.get(firstReceiver).balance(), staged.get(firstReceiver), optionalSource);
    }

    void delete(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Long previous = balances.remove(playerId);
        if (previous == null) return;
        try {
            onMutationCommitted.run();
        } catch (RuntimeException e) {
            balances.put(playerId, previous);
            throw e;
        }
    }

    private BalanceMutationResult commitSingle(
            BalanceState state,
            long requestedAmount,
            long newBalance,
            BalanceMutationType type,
            MutationSource source,
            String detail,
            String item
    ) {
        balances.put(state.playerId(), newBalance);
        try {
            onMutationCommitted.run();
        } catch (RuntimeException e) {
            // The persistence step is what commits: its save runs this store's document inside one
            // SQLite transaction with everything the caller staged around it, so a throw here means
            // that transaction rolled back and nothing was saved. The in-memory ledger must go back
            // to the state the caller found, or a later successful save would persist a charge or a
            // refund the caller — and every handler that restored its own state around this call —
            // believes never happened.
            restore(state);
            throw e;
        }
        Optional<MutationSource> optionalSource = Optional.ofNullable(source);
        BalanceMutationResult result = new BalanceMutationResult(BalanceMutationStatus.SUCCESS, type,
                state.playerId(), requestedAmount, state.balance(), newBalance, optionalSource);
        events.emit(new BalanceChangeEvent(state.playerId(), state.balance(), newBalance, type,
                Optional.empty(), optionalSource, Optional.ofNullable(detail)), item);
        return result;
    }

    private BalanceState state(UUID playerId) {
        Long existing = balances.get(playerId);
        return existing == null
                ? new BalanceState(playerId, configuredStartingBalance(), false)
                : new BalanceState(playerId, existing, true);
    }

    /** Undoes one entry this engine added: back to its previous value, or out of the ledger. */
    private void restore(BalanceState state) {
        if (state.existed()) {
            balances.put(state.playerId(), state.balance());
        } else {
            balances.remove(state.playerId());
        }
    }

    private long configuredStartingBalance() {
        return Math.clamp(startingBalance.getAsLong(), 0, EconomyManager.maxBalance());
    }

    private static BalanceMutationResult result(
            BalanceMutationStatus status,
            BalanceMutationType type,
            UUID playerId,
            long requestedAmount,
            long previousBalance,
            long newBalance,
            MutationSource source
    ) {
        return new BalanceMutationResult(status, type, playerId, requestedAmount,
                previousBalance, newBalance, Optional.ofNullable(source));
    }

    private static PaymentResult paymentResult(
            BalanceMutationStatus status,
            UUID senderId,
            UUID receiverId,
            long amount,
            long senderPrevious,
            long senderNew,
            long receiverPrevious,
            long receiverNew,
            MutationSource source
    ) {
        return new PaymentResult(status, senderId, receiverId, amount, senderPrevious, senderNew,
                receiverPrevious, receiverNew, Optional.ofNullable(source));
    }

    private record BalanceState(UUID playerId, long balance, boolean existed) {}
}

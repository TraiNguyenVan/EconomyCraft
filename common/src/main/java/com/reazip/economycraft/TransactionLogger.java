package com.reazip.economycraft;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.reazip.economycraft.api.v1.BalanceChangeEvent;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.api.v1.PaymentResult;
import com.reazip.economycraft.util.TransactionLogWriter;
import com.reazip.economycraft.util.WebhookNotifier;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;

final class TransactionLogger {
    private static final Gson GSON = new Gson();

    private final Path logsDir;
    private final Function<UUID, String> nameResolver;
    private final Consumer<com.reazip.economycraft.util.TransactionEntry> observer;

    TransactionLogger(Path logsDir, Function<UUID, String> nameResolver,
                      Consumer<com.reazip.economycraft.util.TransactionEntry> observer) {
        this.logsDir = logsDir;
        this.nameResolver = nameResolver;
        this.observer = observer;
    }

    void onBalanceChanged(BalanceChangeEvent event, BalanceEventDispatcher.ItemContext itemContext) {
        EconomyConfig config = EconomyConfig.get();
        WebhookConfig webhook = WebhookConfig.get();
        boolean isTransfer = event.type() == BalanceMutationType.PAYMENT_SENT
                || event.type() == BalanceMutationType.PAYMENT_RECEIVED;
        boolean logging = config.transactionLog.enabled;
        boolean notifying = !isTransfer && webhook.webhookEnabled && !webhook.webhookUrl.isBlank()
                && Math.abs(event.difference()) >= webhook.webhookMinAmount;
        if (!logging && !notifying) return;

        String playerName = displayName(event.playerId());
        String counterpartyName = event.counterpartyId().map(this::displayName).orElse(null);
        String source = event.source().map(MutationSource::asString).orElse(null);

        String item = itemContext != null ? itemContext.key() : null;
        int quantity = itemContext != null ? itemContext.quantity() : 0;
        if (logging) {
            Instant time = Instant.now();
            TransactionLogWriter.append(logsDir, config.transactionLog.retentionDays,
                    toJsonLine(event, playerName, counterpartyName, source, item, quantity, time));
            if (event.type() == BalanceMutationType.PAYMENT_RECEIVED && item != null && quantity > 0) {
                observer.accept(new com.reazip.economycraft.util.TransactionEntry(time, event.type(), event.playerId(),
                        playerName, event.counterpartyId().orElse(null), counterpartyName, event.difference(),
                        event.previousBalance(), event.newBalance(), source, event.detail().orElse(null), item, quantity));
            }
        }
        if (notifying) {
            WebhookNotifier.send(webhook.webhookUrl, toMessage(event, playerName, counterpartyName, source));
        }
    }

    void onTransfer(PaymentResult result, long alertAmount) {
        WebhookConfig webhook = WebhookConfig.get();
        if (!webhook.webhookEnabled || webhook.webhookUrl.isBlank() || alertAmount < webhook.webhookMinAmount) {
            return;
        }

        String senderName = displayName(result.senderId());
        String receiverName = displayName(result.receiverId());
        String source = result.source().map(MutationSource::asString).orElse(null);

        WebhookNotifier.send(webhook.webhookUrl, toTransferMessage(result, senderName, receiverName, source));
    }

    private String displayName(UUID id) {
        String name = nameResolver.apply(id);
        return name == null || name.isBlank() ? null : name;
    }

    private static String toJsonLine(BalanceChangeEvent event, String playerName, String counterpartyName,
                                     String source, String item, int quantity, Instant time) {
        JsonObject obj = new JsonObject();
        obj.addProperty("time", time.toString());
        obj.addProperty("type", event.type().name());
        obj.addProperty("player", event.playerId().toString());
        if (playerName != null) obj.addProperty("player_name", playerName);
        event.counterpartyId().ifPresent(id -> obj.addProperty("counterparty", id.toString()));
        if (counterpartyName != null) obj.addProperty("counterparty_name", counterpartyName);
        obj.addProperty("amount", event.difference());
        obj.addProperty("balance_before", event.previousBalance());
        obj.addProperty("balance_after", event.newBalance());
        if (source != null) obj.addProperty("source", source);
        event.detail().ifPresent(detail -> obj.addProperty("detail", detail));
        if (item != null) obj.addProperty("item", item);
        if (quantity > 0) obj.addProperty("quantity", quantity);
        return GSON.toJson(obj);
    }

    private static String toMessage(BalanceChangeEvent event, String playerName, String counterpartyName, String source) {
        String player = playerName != null ? playerName : event.playerId().toString();
        String counterparty = counterpartyName != null ? counterpartyName
                : event.counterpartyId().map(UUID::toString).orElse("someone");
        String suffix = source != null ? " (" + source + ")" : "";

        return switch (event.type()) {
            case ADD -> player + " received " + EconomyCraft.signedMoney(event.difference())
                    + ", balance now " + EconomyCraft.formatMoney(event.newBalance()) + suffix;
            case REMOVE -> player + " lost " + EconomyCraft.signedMoney(event.difference())
                    + ", balance now " + EconomyCraft.formatMoney(event.newBalance()) + suffix;
            case SET -> player + "'s balance was set to " + EconomyCraft.formatMoney(event.newBalance()) + suffix;
            case PAYMENT_SENT -> player + " paid " + EconomyCraft.formatMoney(-event.difference())
                    + " to " + counterparty + suffix;
            case PAYMENT_RECEIVED -> player + " received " + EconomyCraft.formatMoney(event.difference())
                    + " from " + counterparty + suffix;
        };
    }

    private static String toTransferMessage(PaymentResult result, String senderName, String receiverName, String source) {
        String sender = senderName != null ? senderName : result.senderId().toString();
        String receiver = receiverName != null ? receiverName : result.receiverId().toString();
        String suffix = source != null ? " (" + source + ")" : "";
        long debit = result.amount();
        long credit = result.receiverDifference();

        String message = sender + " paid " + EconomyCraft.formatMoney(debit) + " to " + receiver;
        if (credit != debit) {
            message += ", who received " + EconomyCraft.formatMoney(credit);
        }
        return message + suffix;
    }
}

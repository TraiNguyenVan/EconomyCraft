package com.reazip.economycraft.contracts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The contract data model: a paid commission for work performed by another player.
 *
 * <p>Deliberately item-free — unlike {@code OrderRequest}, a contract carries no {@link
 * net.minecraft.world.item.ItemStack}, so serialization is plain fields only and the model can be
 * round-tripped without a registry. Identity is UUID-based; player names are resolved for display
 * only and are never authoritative.
 *
 * <p>The lifecycle is an explicit state machine owned by {@link ContractService}. The persisted
 * state is untrusted: {@link #load(JsonObject)} validates status values, amounts, ids, timestamps
 * and relationships, dropping or repairing what it cannot place.
 */
public class Contract {

    public enum Status {
        OPEN("Open"), IN_PROGRESS("In Progress"), SUBMITTED("Submitted"), DISPUTED("Disputed"),
        COMPLETED("Completed"), CANCELLED("Cancelled"), EXPIRED("Expired");

        public final String label;

        Status(String label) {
            this.label = label;
        }

        /** True once the financial outcome is final and no further transition is possible. */
        public boolean terminal() {
            return this == COMPLETED || this == CANCELLED || this == EXPIRED;
        }

        /** True while the contract is live — not yet completed, cancelled or expired. */
        public boolean active() {
            return !terminal();
        }

        public static @Nullable Status parse(String name) {
            if (name == null) return null;
            try {
                return Status.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    /** The kind of work the contract commissions. Categories describe work, not items. */
    public enum Category {
        BUILD("Build"), EXCAVATE("Excavate"), FARM("Farm"), HUNT("Hunt"), EXPLORE("Explore"), OTHER("Other");

        public final String label;

        Category(String label) {
            this.label = label;
        }

        public static Category parseOrOther(@Nullable String name) {
            if (name == null) return OTHER;
            try {
                return Category.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                return OTHER;
            }
        }
    }

    /**
     * The financial settlement of a contract, persisted as its own record so a pending payout or
     * refund is a recoverable obligation rather than a boolean flag. {@code status} says whether
     * the money movement has been durably committed — {@code PENDING} means the obligation exists
     * and has not been observed as executed, and {@link ContractService}'s recovery sweep retries
     * it until it is.
     */
    public static class Settlement {
        public Kind kind;
        public State status;
        /** The terminal status the contract takes once this settlement commits. */
        public Status outcome;
        /** The gross amount the settlement moves: the full escrow for a payout or a refund. */
        public long amount;
        /** Who receives the money. */
        public UUID target;
        /** How many times settlement has been attempted; audit information for repeated failures. */
        public int attempt;

        public enum Kind { PAYOUT, REFUND }

        public enum State { PENDING, DONE }

        public static Settlement payout(long amount, UUID target, int attempt) {
            Settlement s = new Settlement();
            s.kind = Kind.PAYOUT;
            s.status = State.PENDING;
            s.outcome = Status.COMPLETED;
            s.amount = amount;
            s.target = target;
            s.attempt = attempt;
            return s;
        }

        public static Settlement refund(long amount, UUID target, int attempt, Status outcome) {
            Settlement s = new Settlement();
            s.kind = Kind.REFUND;
            s.status = State.PENDING;
            s.outcome = outcome;
            s.amount = amount;
            s.target = target;
            s.attempt = attempt;
            return s;
        }

        public boolean pending() {
            return status == State.PENDING;
        }

        public Settlement done() {
            this.status = State.DONE;
            return this;
        }

        public Settlement copy() {
            Settlement s = new Settlement();
            s.kind = kind;
            s.status = status;
            s.outcome = outcome;
            s.amount = amount;
            s.target = target;
            s.attempt = attempt;
            return s;
        }
    }

    /** One auditable revision request: who asked, when, and why. History is bounded by the config. */
    public static class RevisionEntry {
        public String reason;
        public long at;
        public UUID by;
    }

    /** Text bounds: titles are short; notes and reasons stay anvil-scale; descriptions are book-scale. */
    public static final int MAX_TITLE_LENGTH = 40;
    public static final int MAX_TEXT_LENGTH = 100;
    /** Descriptions are written in a book and quill, so they run to book scale, not anvil scale. */
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    /** Revision history entries are bounded so persisted state and processing stay bounded. */
    public static final int MAX_HISTORY_ENTRIES = 10;

    private static final Logger LOGGER = LogUtils.getLogger();

    public int id;
    public UUID requester;
    /** The designated player of a targeted contract; null for a public one. */
    public @Nullable UUID target;
    /** The player who accepted and is performing the work; null until acceptance. */
    public @Nullable UUID contractor;
    public String title;
    public @Nullable String description;
    public Category category;
    public Status status;
    /** The agreed gross reward. */
    public long reward;
    /** The money still held in escrow; the full reward until a settlement releases it. */
    public long escrow;
    public long createdAt;
    /** Work deadline, epoch millis; 0 = never expires. */
    public long workDeadline;
    public long acceptedAt;
    public long submittedAt;
    /** Review deadline, epoch millis; 0 = no review window. */
    public long reviewDeadline;
    /** Completion/cancellation/expiration timestamp; 0 while the contract is live. */
    public long closedAt;
    public int revisions;
    /** The contractor's latest submission notes. */
    public @Nullable String submissionNotes;
    /** The requester's latest review or revision note. */
    public @Nullable String reviewNotes;
    public @Nullable String disputeReason;
    public @Nullable UUID disputedBy;
    public long disputedAt;
    public @Nullable String resolution;
    public @Nullable UUID resolvedBy;
    public long resolvedAt;
    /** The participant that requested a mutual cancellation; null when nobody has. */
    public @Nullable UUID cancelRequestedBy;
    public List<RevisionEntry> revisionHistory = new ArrayList<>();
    /** The pending or completed financial settlement; null while nothing is in flight. */
    public @Nullable Settlement settlement;

    public JsonObject save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("requester", requester.toString());
        if (target != null) obj.addProperty("target", target.toString());
        if (contractor != null) obj.addProperty("contractor", contractor.toString());
        obj.addProperty("title", title);
        if (description != null && !description.isBlank()) obj.addProperty("description", description);
        obj.addProperty("category", category.name());
        obj.addProperty("status", status.name());
        obj.addProperty("reward", reward);
        obj.addProperty("escrow", escrow);
        obj.addProperty("createdAt", createdAt);
        if (workDeadline > 0) obj.addProperty("workDeadline", workDeadline);
        if (acceptedAt > 0) obj.addProperty("acceptedAt", acceptedAt);
        if (submittedAt > 0) obj.addProperty("submittedAt", submittedAt);
        if (reviewDeadline > 0) obj.addProperty("reviewDeadline", reviewDeadline);
        if (closedAt > 0) obj.addProperty("closedAt", closedAt);
        obj.addProperty("revisions", revisions);
        if (submissionNotes != null && !submissionNotes.isBlank()) obj.addProperty("submissionNotes", submissionNotes);
        if (reviewNotes != null && !reviewNotes.isBlank()) obj.addProperty("reviewNotes", reviewNotes);
        if (disputeReason != null && !disputeReason.isBlank()) obj.addProperty("disputeReason", disputeReason);
        if (disputedBy != null) obj.addProperty("disputedBy", disputedBy.toString());
        if (disputedAt > 0) obj.addProperty("disputedAt", disputedAt);
        if (resolution != null && !resolution.isBlank()) obj.addProperty("resolution", resolution);
        if (resolvedBy != null) obj.addProperty("resolvedBy", resolvedBy.toString());
        if (resolvedAt > 0) obj.addProperty("resolvedAt", resolvedAt);
        if (cancelRequestedBy != null) obj.addProperty("cancelRequestedBy", cancelRequestedBy.toString());
        if (!revisionHistory.isEmpty()) {
            JsonArray history = new JsonArray();
            for (RevisionEntry entry : revisionHistory) {
                JsonObject h = new JsonObject();
                h.addProperty("reason", entry.reason);
                h.addProperty("at", entry.at);
                if (entry.by != null) h.addProperty("by", entry.by.toString());
                history.add(h);
            }
            obj.add("revisionHistory", history);
        }
        if (settlement != null) {
            JsonObject s = new JsonObject();
            s.addProperty("kind", settlement.kind.name());
            s.addProperty("status", settlement.status.name());
            if (settlement.outcome != null) s.addProperty("outcome", settlement.outcome.name());
            s.addProperty("amount", settlement.amount);
            if (settlement.target != null) s.addProperty("target", settlement.target.toString());
            s.addProperty("attempt", settlement.attempt);
            obj.add("settlement", s);
        }
        return obj;
    }

    /**
     * Loads one contract from persisted JSON. The payload is untrusted: malformed ids, amounts,
     * statuses and relationships are dropped or repaired, and every repair is logged, so a bad
     * record can never crash the economy or place a live obligation in a state that would settle
     * twice.
     *
     * @return the contract, or {@code null} when the record is unusable and must be dropped.
     */
    public static @Nullable Contract load(JsonObject obj) {
        try {
            Contract c = new Contract();
            c.id = obj.has("id") ? obj.get("id").getAsInt() : -1;
            if (c.id < 1) {
                LOGGER.error("[EconomyCraft] Dropping a contract with no usable id");
                return null;
            }
            c.requester = readUuid(obj, "requester");
            if (c.requester == null) {
                LOGGER.error("[EconomyCraft] Dropping contract {} with no requester", c.id);
                return null;
            }
            c.target = readUuid(obj, "target");
            c.contractor = readUuid(obj, "contractor");
            c.title = sanitize(obj.has("title") && !obj.get("title").isJsonNull()
                    ? obj.get("title").getAsString() : null, MAX_TITLE_LENGTH);
            if (c.title == null) {
                LOGGER.error("[EconomyCraft] Dropping contract {} with no usable title", c.id);
                return null;
            }
            c.description = sanitizeMultiline(optString(obj, "description"), MAX_DESCRIPTION_LENGTH);
            c.category = Category.parseOrOther(optString(obj, "category"));
            Status status = Status.parse(optString(obj, "status"));
            if (status == null) {
                LOGGER.error("[EconomyCraft] Dropping contract {} with unknown status '{}'", c.id, optString(obj, "status"));
                return null;
            }
            c.status = status;
            c.reward = obj.has("reward") ? obj.get("reward").getAsLong() : -1;
            if (c.reward <= 0 || c.reward > com.reazip.economycraft.EconomyManager.MAX) {
                LOGGER.error("[EconomyCraft] Dropping contract {} with unusable reward {}", c.id, c.reward);
                return null;
            }
            c.escrow = Math.clamp(obj.has("escrow") ? obj.get("escrow").getAsLong() : 0L, 0, c.reward);
            c.createdAt = obj.has("createdAt") ? obj.get("createdAt").getAsLong() : 0L;
            c.workDeadline = obj.has("workDeadline") ? Math.max(0L, obj.get("workDeadline").getAsLong()) : 0L;
            c.acceptedAt = obj.has("acceptedAt") ? Math.max(0L, obj.get("acceptedAt").getAsLong()) : 0L;
            c.submittedAt = obj.has("submittedAt") ? Math.max(0L, obj.get("submittedAt").getAsLong()) : 0L;
            c.reviewDeadline = obj.has("reviewDeadline") ? Math.max(0L, obj.get("reviewDeadline").getAsLong()) : 0L;
            c.closedAt = obj.has("closedAt") ? Math.max(0L, obj.get("closedAt").getAsLong()) : 0L;
            c.revisions = obj.has("revisions") ? Math.max(0, obj.get("revisions").getAsInt()) : 0;
            c.submissionNotes = sanitize(optString(obj, "submissionNotes"), MAX_TEXT_LENGTH);
            c.reviewNotes = sanitize(optString(obj, "reviewNotes"), MAX_TEXT_LENGTH);
            c.disputeReason = sanitize(optString(obj, "disputeReason"), MAX_TEXT_LENGTH);
            c.disputedBy = readUuid(obj, "disputedBy");
            c.disputedAt = obj.has("disputedAt") ? Math.max(0L, obj.get("disputedAt").getAsLong()) : 0L;
            c.resolution = sanitize(optString(obj, "resolution"), MAX_TEXT_LENGTH);
            c.resolvedBy = readUuid(obj, "resolvedBy");
            c.resolvedAt = obj.has("resolvedAt") ? Math.max(0L, obj.get("resolvedAt").getAsLong()) : 0L;
            c.cancelRequestedBy = readUuid(obj, "cancelRequestedBy");

            if (c.contractor != null && c.contractor.equals(c.requester)) {
                LOGGER.error("[EconomyCraft] Dropping contract {} whose contractor equals its requester", c.id);
                return null;
            }
            // Relationship repairs: a contractor on a contract that is not accepted cannot be.
            if (c.contractor != null && c.acceptedAt <= 0) {
                LOGGER.warn("[EconomyCraft] Contract {} has a contractor but no acceptance; clearing the contractor.", c.id);
                c.contractor = null;
            }
            if (c.contractor == null && c.status == Status.IN_PROGRESS) {
                LOGGER.warn("[EconomyCraft] Contract {} is IN_PROGRESS with no contractor; reopening it.", c.id);
                c.status = Status.OPEN;
            }
            if ((c.status == Status.SUBMITTED || c.status == Status.DISPUTED) && c.contractor == null) {
                LOGGER.warn("[EconomyCraft] Contract {} is {} with no contractor; reopening it.", c.id, c.status);
                c.status = Status.OPEN;
            }
            if (c.status.terminal() && c.closedAt <= 0) {
                c.closedAt = System.currentTimeMillis();
            }
            c.settlement = readSettlement(obj);
            if (c.settlement != null && c.settlement.pending() && c.settlement.outcome == null) {
                // An unknown outcome must never strand the money: payouts complete, refunds cancel.
                c.settlement.outcome = c.settlement.kind == Settlement.Kind.PAYOUT
                        ? Status.COMPLETED : Status.CANCELLED;
            }
            c.revisionHistory = readHistory(obj, c.id);
            return c;
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Dropping an unreadable contract record", ex);
            return null;
        }
    }

    private static @Nullable String optString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private static @Nullable UUID readUuid(JsonObject obj, String key) {
        String raw = optString(obj, key);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            LOGGER.error("[EconomyCraft] Dropping contract field '{}' with an unreadable id '{}'", key, raw);
            return null;
        }
    }

    private static @Nullable Settlement readSettlement(JsonObject obj) {
        if (!obj.has("settlement") || !obj.get("settlement").isJsonObject()) return null;
        try {
            JsonObject s = obj.getAsJsonObject("settlement");
            Settlement settlement = new Settlement();
            String kind = optString(s, "kind");
            if ("PAYOUT".equals(kind)) settlement.kind = Settlement.Kind.PAYOUT;
            else if ("REFUND".equals(kind)) settlement.kind = Settlement.Kind.REFUND;
            else return null;
            String state = optString(s, "status");
            if ("PENDING".equals(state)) settlement.status = Settlement.State.PENDING;
            else if ("DONE".equals(state)) settlement.status = Settlement.State.DONE;
            else return null;
            String outcome = optString(s, "outcome");
            if (outcome != null) settlement.outcome = Status.parse(outcome);
            settlement.amount = s.has("amount") ? Math.max(0L, s.get("amount").getAsLong()) : 0L;
            settlement.target = readUuid(s, "target");
            settlement.attempt = s.has("attempt") ? Math.max(0, s.get("attempt").getAsInt()) : 0;
            return settlement;
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Dropping an unreadable contract settlement record", ex);
            return null;
        }
    }

    private static List<RevisionEntry> readHistory(JsonObject obj, int id) {
        List<RevisionEntry> out = new ArrayList<>();
        if (!obj.has("revisionHistory") || !obj.get("revisionHistory").isJsonArray()) return out;
        for (JsonElement el : obj.getAsJsonArray("revisionHistory")) {
            if (out.size() >= MAX_HISTORY_ENTRIES) {
                LOGGER.warn("[EconomyCraft] Contract {} revision history exceeds the cap; trailing entries dropped.", id);
                break;
            }
            if (!el.isJsonObject()) continue;
            try {
                JsonObject h = el.getAsJsonObject();
                RevisionEntry entry = new RevisionEntry();
                entry.reason = sanitize(optString(h, "reason"), MAX_TEXT_LENGTH);
                entry.at = h.has("at") ? Math.max(0L, h.get("at").getAsLong()) : 0L;
                entry.by = readUuid(h, "by");
                if (entry.reason == null) continue;
                out.add(entry);
            } catch (Exception ex) {
                LOGGER.error("[EconomyCraft] Dropping an unreadable revision history entry for contract {}", id, ex);
            }
        }
        return out;
    }

    /** Strips newlines, trims, and clamps; blank input collapses to null. */
    public static @Nullable String sanitize(@Nullable String raw, int maxLength) {
        if (raw == null) return null;
        String cleaned = raw.replace("\r", "").replace("\n", "").trim();
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    /**
     * Cleans book text: strips carriage returns, trims, collapses blank runs to one blank line,
     * and clamps; blank input collapses to null. Paragraph breaks are preserved — book pages are
     * paragraphs.
     */
    public static @Nullable String sanitizeMultiline(@Nullable String raw, int maxLength) {
        if (raw == null) return null;
        String cleaned = raw.replace("\r", "").trim();
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        if (cleaned.isEmpty()) return null;
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}

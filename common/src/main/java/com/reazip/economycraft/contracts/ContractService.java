package com.reazip.economycraft.contracts;

import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.contracts.Contract.Category;
import com.reazip.economycraft.contracts.Contract.RevisionEntry;
import com.reazip.economycraft.contracts.Contract.Settlement;
import com.reazip.economycraft.contracts.Contract.Status;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.util.EconomyPermissions;
import com.reazip.economycraft.util.EconomyPermissions.Nodes;
import net.minecraft.commands.CommandSourceStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * The authoritative entry point for every contract transition and financial operation.
 *
 * <p>Commands and GUIs invoke these methods and receive the same validation and failure results;
 * neither layer carries business logic of its own. Every transition revalidates the current state
 * immediately before committing, and every financial operation routes through the centralized
 * mutation engine.
 *
 * <p><strong>Settlement protocol.</strong> There is no transactional join between the balance engine
 * and the contract document, so settlement is a durable, idempotent two-step:
 * <ol>
 *   <li>the pending obligation is persisted first (a {@link Settlement} carrying kind, outcome,
 *       amount, target and attempt);</li>
 *   <li>the terminal flip and escrow release happen in memory, and the money mutation commits them
 *       durably — the mutation's own save wraps the balance and contract documents in one SQLite
 *       transaction, so a crash mid-save leaves either the pending intent (money not moved) or the
 *       settled state (money moved), never a mix that could pay out twice.</li>
 * </ol>
 * A returned failure reverts the flip and keeps the pending obligation; {@link #processDeadlines}
 * retries it until it succeeds. A contract can receive a payout or a refund — never both.
 */
public final class ContractService {
    private ContractService() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long HOUR_MS = 3_600_000L;

    // === result types ===

    /** A create-and-fund request as collected by the UI or a command. */
    public record CreationRequest(String title, @Nullable String description, Category category,
                                  boolean targeted, @Nullable UUID target, long reward, long workDeadline) {}

    public enum CreateStatus {
        OK, DISABLED, INVALID_TITLE, INVALID_REWARD, INVALID_DEADLINE,
        INVALID_TARGET, LIMIT_REACHED, INSUFFICIENT_FUNDS
    }

    public record CreationResult(CreateStatus status, @Nullable Contract contract) {
        public boolean success() { return status == CreateStatus.OK; }
    }

    public enum AcceptStatus {
        OK, DISABLED, NOT_FOUND, NOT_OPEN, OWN_CONTRACT, WRONG_TARGET, LIMIT_REACHED
    }

    public enum SubmitStatus { OK, NOT_FOUND, NOT_CONTRACTOR, NOT_ACCEPTED, DEADLINE_MISSED }

    public enum ReviewStatus { OK, NOT_FOUND, NOT_REQUESTER, NOT_SUBMITTED, REFUND_PENDING }

    public enum ApproveStatus { OK, NOT_FOUND, NOT_REQUESTER, NOT_SUBMITTED, REFUND_PENDING, PAYOUT_FAILED }

    public record ApproveResult(ApproveStatus status, long paid, long tax) {
        public boolean success() { return status == ApproveStatus.OK; }
    }

    public enum ReviseStatus { OK, NOT_FOUND, NOT_REQUESTER, NOT_SUBMITTED, REVISIONS_EXHAUSTED, INVALID_REASON }

    public enum CancelStatus {
        OK, NOT_FOUND, NOT_PARTICIPANT, NOT_CANCELLABLE, DISPUTED, REQUEST_SENT, REQUEST_PENDING, REFUND_FAILED
    }

    public enum DisputeStatus {
        OK, NOT_FOUND, NOT_PARTICIPANT, NOT_DISPUTABLE, INVALID_REASON, SETTLEMENT_PENDING
    }

    public enum ResolveStatus { OK, NOT_FOUND, NOT_ADMIN, NOT_DISPUTED, PAYOUT_FAILED, REFUND_FAILED }

    public enum SettleStatus { SUCCESS, ALREADY_SETTLED, FROZEN, NO_TARGET, NOTHING_IN_ESCROW, REJECTED }

    public record SettleOutcome(SettleStatus status, long paid, long tax) {
        public boolean success() { return status == SettleStatus.SUCCESS; }
    }

    // === creation ===

    /**
     * Creates and funds a contract. The full reward is reserved in escrow before the contract
     * becomes visible, and the escrow debit commits both the balance movement and the contract in
     * one transactional save — a failed creation leaves no money lost and no untracked obligation.
     */
    public static CreationResult create(EconomyManager eco, UUID requester, CreationRequest request) {
        if (!EconomyConfig.get().contractsEnabled) return new CreationResult(CreateStatus.DISABLED, null);

        String title = Contract.sanitize(request.title(), Contract.MAX_TITLE_LENGTH);
        if (title == null) return new CreationResult(CreateStatus.INVALID_TITLE, null);
        String description = Contract.sanitizeMultiline(request.description(), Contract.MAX_DESCRIPTION_LENGTH);
        long reward = request.reward();
        if (reward <= 0 || reward > maxReward()) return new CreationResult(CreateStatus.INVALID_REWARD, null);
        long deadline = request.workDeadline();
        if (deadline < 0) return new CreationResult(CreateStatus.INVALID_DEADLINE, null);
        int maxDurationHours = EconomyConfig.get().contractMaxDurationHours;
        if (deadline > 0 && maxDurationHours > 0
                && deadline > System.currentTimeMillis() + (long) maxDurationHours * HOUR_MS) {
            return new CreationResult(CreateStatus.INVALID_DEADLINE, null);
        }
        UUID target = request.targeted() ? request.target() : null;
        if (request.targeted() && target == null) return new CreationResult(CreateStatus.INVALID_TARGET, null);
        if (target != null && target.equals(requester)) return new CreationResult(CreateStatus.INVALID_TARGET, null);

        ContractManager contracts = eco.getContracts();
        if (contracts.hasReachedLimit(requester)) return new CreationResult(CreateStatus.LIMIT_REACHED, null);

        Contract c = new Contract();
        c.requester = requester;
        c.target = target;
        c.title = title;
        c.description = description;
        c.category = request.category() == null ? Category.OTHER : request.category();
        c.status = Status.OPEN;
        c.reward = reward;
        // Escrow is recorded before the debit so the debit's transactional save persists both.
        c.escrow = reward;
        c.createdAt = System.currentTimeMillis();
        c.workDeadline = deadline;

        contracts.stage(c);
        boolean debited;
        try {
            debited = eco.removeMoney(requester, reward, EconomySources.CONTRACT_ESCROW_HOLD, detail(c)).successful();
        } catch (RuntimeException e) {
            contracts.unstage(c.id);
            throw e;
        }
        if (!debited) {
            contracts.unstage(c.id);
            return new CreationResult(CreateStatus.INSUFFICIENT_FUNDS, null);
        }

        // The debit's own save already persisted the funded contract; refresh open UIs only.
        contracts.notifyChanged();
        broadcastNewContract(eco, c);
        return new CreationResult(CreateStatus.OK, c);
    }

    private static void broadcastNewContract(EconomyManager eco, Contract c) {
        String requesterName = MenuNames.of(eco, c.requester);
        if (c.target != null) {
            eco.getNotifications().notify(c.target, requesterName + " sent you a contract: \""
                    + c.title + "\" (#" + c.id + ", " + EconomyCraft.formatMoney(c.reward)
                    + "). Type /eco contracts view " + c.id + " to review it.");
            return;
        }
        String message = "[Contracts] " + requesterName + " posted a contract: \""
                + c.title + "\" (" + EconomyCraft.formatMoney(c.reward) + "). Type /eco contracts to view.";
        var server = eco.getServer();
        for (var online : server.getPlayerList().getPlayers()) {
            if (!online.getUUID().equals(c.requester)) {
                online.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
            }
        }
    }

    // === acceptance ===

    /**
     * An eligible contractor accepts an {@code OPEN} contract. Exactly one contractor can succeed;
     * the state is revalidated inside the atomic update immediately before committing.
     */
    public static AcceptStatus accept(EconomyManager eco, UUID contractor, int id) {
        if (!EconomyConfig.get().contractsEnabled) return AcceptStatus.DISABLED;
        ContractManager contracts = eco.getContracts();
        Contract peek = contracts.getContract(id);
        if (peek == null) return AcceptStatus.NOT_FOUND;
        if (peek.status != Status.OPEN) return AcceptStatus.NOT_OPEN;
        if (peek.requester.equals(contractor)) return AcceptStatus.OWN_CONTRACT;
        if (peek.target != null && !peek.target.equals(contractor)) return AcceptStatus.WRONG_TARGET;
        if (contracts.hasReachedLimit(contractor)) return AcceptStatus.LIMIT_REACHED;

        Contract updated = contracts.compute(id, c -> {
            if (c.status != Status.OPEN) return null;
            if (c.requester.equals(contractor)) return null;
            if (c.target != null && !c.target.equals(contractor)) return null;
            c.contractor = contractor;
            c.status = Status.IN_PROGRESS;
            c.acceptedAt = System.currentTimeMillis();
            return c;
        });
        if (updated == null) {
            Contract now = contracts.getContract(id);
            if (now == null) return AcceptStatus.NOT_FOUND;
            if (now.requester.equals(contractor)) return AcceptStatus.OWN_CONTRACT;
            if (now.target != null && !now.target.equals(contractor)) return AcceptStatus.WRONG_TARGET;
            if (contracts.hasReachedLimit(contractor)) return AcceptStatus.LIMIT_REACHED;
            return AcceptStatus.NOT_OPEN;
        }
        contracts.markChanged();
        eco.getNotifications().notify(updated.requester, MenuNames.of(eco, contractor)
                + " accepted your contract \"" + updated.title + "\" (#" + updated.id + ").");
        return AcceptStatus.OK;
    }

    // === submission ===

    /**
     * Only the assigned contractor may submit the work. Submission records the timestamp and notes
     * and starts the review window. A submission whose work deadline has already passed is refused —
     * the contract expires instead — but a timely submission is never rejected later merely because
     * the original work deadline passes while the requester is reviewing it.
     */
    public static SubmitStatus submit(EconomyManager eco, UUID contractor, int id, @Nullable String notes) {
        ContractManager contracts = eco.getContracts();
        Contract peek = contracts.getContract(id);
        if (peek == null) return SubmitStatus.NOT_FOUND;
        if (peek.status != Status.IN_PROGRESS) return SubmitStatus.NOT_ACCEPTED;
        if (!contractor.equals(peek.contractor)) return SubmitStatus.NOT_CONTRACTOR;
        long now = System.currentTimeMillis();
        if (peek.workDeadline > 0 && now >= peek.workDeadline) return SubmitStatus.DEADLINE_MISSED;
        String cleanNotes = Contract.sanitize(notes, Contract.MAX_TEXT_LENGTH);
        int reviewHours = reviewHours();

        Contract updated = contracts.compute(id, c -> {
            if (c.status != Status.IN_PROGRESS || !contractor.equals(c.contractor)) return null;
            c.status = Status.SUBMITTED;
            c.submittedAt = now;
            c.submissionNotes = cleanNotes;
            c.reviewDeadline = reviewHours > 0 ? now + reviewHours * HOUR_MS : now;
            c.cancelRequestedBy = null;
            return c;
        });
        if (updated == null) return SubmitStatus.NOT_ACCEPTED;
        contracts.markChanged();
        eco.getNotifications().notify(updated.requester, MenuNames.of(eco, contractor)
                + " submitted work for \"" + updated.title + "\" (#" + updated.id
                + "). Type /eco contracts view " + updated.id + " to review it.");
        return SubmitStatus.OK;
    }

    // === review ===

    /**
     * Only the requester may approve a submission. A pending payout settlement is re-attempted
     * rather than re-created, so repeated approvals cannot duplicate the payout. The contract
     * becomes COMPLETED only once the settlement is durably successful.
     */
    public static ApproveResult approve(EconomyManager eco, UUID requester, int id) {
        ContractManager contracts = eco.getContracts();
        Contract peek = contracts.getContract(id);
        if (peek == null) return new ApproveResult(ApproveStatus.NOT_FOUND, 0, 0);
        if (!requester.equals(peek.requester)) return new ApproveResult(ApproveStatus.NOT_REQUESTER, 0, 0);
        if (peek.settlement != null && peek.settlement.pending() && peek.settlement.kind == Settlement.Kind.REFUND) {
            return new ApproveResult(ApproveStatus.REFUND_PENDING, 0, 0);
        }
        if (peek.status != Status.SUBMITTED
                && !(peek.settlement != null && peek.settlement.pending() && peek.settlement.kind == Settlement.Kind.PAYOUT)) {
            return new ApproveResult(ApproveStatus.NOT_SUBMITTED, 0, 0);
        }
        SettleOutcome outcome = settle(eco, contracts, peek, Settlement.Kind.PAYOUT, Status.COMPLETED, false);
        return switch (outcome.status()) {
            case SUCCESS, ALREADY_SETTLED -> new ApproveResult(ApproveStatus.OK, outcome.paid(), outcome.tax());
            default -> new ApproveResult(ApproveStatus.PAYOUT_FAILED, 0, 0);
        };
    }

    /**
     * Only the requester may request a revision; a revision requires a reason, returns the contract
     * to IN_PROGRESS, clears the submission and review deadline, and records auditable history. Once
     * the revision limit is reached, either participant may escalate the disagreement instead.
     */
    public static ReviseStatus requestRevision(EconomyManager eco, UUID requester, int id, @Nullable String reason) {
        ContractManager contracts = eco.getContracts();
        Contract peek = contracts.getContract(id);
        if (peek == null) return ReviseStatus.NOT_FOUND;
        if (!requester.equals(peek.requester)) return ReviseStatus.NOT_REQUESTER;
        if (peek.status != Status.SUBMITTED) return ReviseStatus.NOT_SUBMITTED;
        String cleanReason = Contract.sanitize(reason, Contract.MAX_TEXT_LENGTH);
        if (cleanReason == null) return ReviseStatus.INVALID_REASON;
        int max = maxRevisions();
        if (peek.revisions >= max) return ReviseStatus.REVISIONS_EXHAUSTED;
        long now = System.currentTimeMillis();
        int extension = revisionExtensionHours();

        Contract updated = contracts.compute(id, c -> {
            if (c.status != Status.SUBMITTED || !requester.equals(c.requester)) return null;
            if (c.revisions >= maxRevisions()) return null;
            c.revisions++;
            c.status = Status.IN_PROGRESS;
            c.submittedAt = 0;
            c.reviewDeadline = 0;
            c.reviewNotes = cleanReason;
            c.workDeadline = extension > 0 ? now + extension * HOUR_MS : c.workDeadline;
            c.cancelRequestedBy = null;
            // The contractor keeps working; a pending payout is superseded and nothing was paid.
            if (c.settlement != null && c.settlement.pending() && c.settlement.kind == Settlement.Kind.PAYOUT) {
                c.settlement = null;
            }
            RevisionEntry entry = new RevisionEntry();
            entry.reason = cleanReason;
            entry.at = now;
            entry.by = requester;
            c.revisionHistory.add(entry);
            return c;
        });
        if (updated == null) {
            return contracts.getContract(id) == null ? ReviseStatus.NOT_FOUND : ReviseStatus.REVISIONS_EXHAUSTED;
        }
        contracts.markChanged();
        eco.getNotifications().notify(updated.contractor, "Your work on \"" + updated.title
                + "\" (#" + updated.id + ") needs revision: " + cleanReason
                + " Type /eco contracts view " + updated.id + ".");
        return ReviseStatus.OK;
    }

    // === cancellation ===

    /**
     * Before acceptance the requester may cancel and receive a full refund. After acceptance neither
     * participant may cancel unilaterally: the first participant's request marks the contract and
     * notifies the other, whose confirmation settles the refund. Disputed contracts are frozen — an
     * administrator resolves them instead.
     */
    public static CancelStatus cancel(EconomyManager eco, UUID actor, int id) {
        ContractManager contracts = eco.getContracts();
        Contract peek = contracts.getContract(id);
        if (peek == null) return CancelStatus.NOT_FOUND;
        boolean isRequester = actor.equals(peek.requester);
        boolean isContractor = actor.equals(peek.contractor);
        if (!isRequester && !isContractor) return CancelStatus.NOT_PARTICIPANT;

        switch (peek.status) {
            case OPEN -> {
                if (!isRequester) return CancelStatus.NOT_PARTICIPANT;
                SettleOutcome outcome = settle(eco, contracts, peek, Settlement.Kind.REFUND, Status.CANCELLED, false);
                return outcome.success() || outcome.status() == SettleStatus.ALREADY_SETTLED
                        ? CancelStatus.OK : CancelStatus.REFUND_FAILED;
            }
            case IN_PROGRESS, SUBMITTED -> {
                if (peek.settlement != null && peek.settlement.pending()) return CancelStatus.REQUEST_PENDING;
                if (peek.cancelRequestedBy != null && !peek.cancelRequestedBy.equals(actor)) {
                    SettleOutcome outcome = settle(eco, contracts, peek, Settlement.Kind.REFUND, Status.CANCELLED, false);
                    return outcome.success() || outcome.status() == SettleStatus.ALREADY_SETTLED
                            ? CancelStatus.OK : CancelStatus.REFUND_FAILED;
                }
                if (peek.cancelRequestedBy != null) return CancelStatus.REQUEST_PENDING;
                Contract updated = contracts.compute(id, c -> {
                    if (!c.status.active() || c.status == Status.DISPUTED) return null;
                    if (!actor.equals(c.requester) && !actor.equals(c.contractor)) return null;
                    c.cancelRequestedBy = actor;
                    return c;
                });
                if (updated == null) return CancelStatus.NOT_CANCELLABLE;
                contracts.markChanged();
                UUID other = isRequester ? updated.contractor : updated.requester;
                eco.getNotifications().notify(other, MenuNames.of(eco, actor) + " proposed cancelling \""
                        + updated.title + "\" (#" + updated.id + "). Type /eco contracts view "
                        + updated.id + " to confirm or decline.");
                return CancelStatus.REQUEST_SENT;
            }
            case DISPUTED -> {
                return CancelStatus.DISPUTED;
            }
            default -> {
                return CancelStatus.NOT_CANCELLABLE;
            }
        }
    }

    // === disputes ===

    /**
     * Either participant may raise a dispute with a reason. Disputes freeze normal settlement — the
     * sweep never touches a disputed contract, and only an administrator can resolve one.
     */
    public static DisputeStatus raiseDispute(EconomyManager eco, UUID actor, int id, @Nullable String reason) {
        ContractManager contracts = eco.getContracts();
        Contract peek = contracts.getContract(id);
        if (peek == null) return DisputeStatus.NOT_FOUND;
        if (!actor.equals(peek.requester) && !actor.equals(peek.contractor)) return DisputeStatus.NOT_PARTICIPANT;
        String cleanReason = Contract.sanitize(reason, Contract.MAX_TEXT_LENGTH);
        if (cleanReason == null) return DisputeStatus.INVALID_REASON;
        if (peek.status == Status.DISPUTED) return DisputeStatus.NOT_DISPUTABLE;
        boolean escalatable = peek.status == Status.SUBMITTED
                || (peek.status == Status.IN_PROGRESS && peek.revisions >= maxRevisions());
        if (!escalatable) return DisputeStatus.NOT_DISPUTABLE;
        if (peek.settlement != null && peek.settlement.pending()) return DisputeStatus.SETTLEMENT_PENDING;
        long now = System.currentTimeMillis();

        Contract updated = contracts.compute(id, c -> {
            if (c.status == Status.DISPUTED || !c.status.active()) return null;
            boolean allowed = c.status == Status.SUBMITTED
                    || (c.status == Status.IN_PROGRESS && c.revisions >= maxRevisions());
            if (!allowed) return null;
            if (!actor.equals(c.requester) && !actor.equals(c.contractor)) return null;
            c.status = Status.DISPUTED;
            c.disputeReason = cleanReason;
            c.disputedBy = actor;
            c.disputedAt = now;
            c.cancelRequestedBy = null;
            return c;
        });
        if (updated == null) return DisputeStatus.NOT_DISPUTABLE;
        contracts.markChanged();
        UUID other = actor.equals(updated.requester) ? updated.contractor : updated.requester;
        eco.getNotifications().notify(other, "Contract \"" + updated.title + "\" (#" + updated.id
                + ") was disputed by " + MenuNames.of(eco, actor) + ": " + cleanReason
                + " An administrator will resolve it.");
        return DisputeStatus.OK;
    }

    /**
     * Administrator resolution: full payout to the contractor or full refund to the requester —
     * never both for the same contract. The administrator privilege is enforced here in the service
     * layer, not merely by command-tree visibility.
     */
    public static ResolveStatus resolveDispute(EconomyManager eco, CommandSourceStack source, int id, boolean pay) {
        ContractManager contracts = eco.getContracts();
        Contract peek = contracts.getContract(id);
        if (peek == null) return ResolveStatus.NOT_FOUND;
        if (!EconomyPermissions.checkAdmin(source, Nodes.ADMIN_CONTRACTS)) return ResolveStatus.NOT_ADMIN;
        if (peek.status != Status.DISPUTED) return ResolveStatus.NOT_DISPUTED;

        SettleOutcome outcome = pay
                ? settle(eco, contracts, peek, Settlement.Kind.PAYOUT, Status.COMPLETED, true)
                : settle(eco, contracts, peek, Settlement.Kind.REFUND, Status.CANCELLED, true);
        if (outcome.success() || outcome.status() == SettleStatus.ALREADY_SETTLED) {
            Contract settled = contracts.getContract(id);
            if (settled != null) {
                String resolution = (pay ? "Paid out" : "Refunded") + " by an administrator after a dispute";
                settled.resolution = resolution;
                settled.resolvedBy = adminId(source);
                settled.resolvedAt = System.currentTimeMillis();
                contracts.markChanged();
                notifyResolved(eco, settled, pay);
            }
            return ResolveStatus.OK;
        }
        return pay ? ResolveStatus.PAYOUT_FAILED : ResolveStatus.REFUND_FAILED;
    }

    private static UUID adminId(CommandSourceStack source) {
        try {
            var player = source.getPlayerOrException();
            return player.getUUID();
        } catch (Exception e) {
            return null;
        }
    }

    private static void notifyResolved(EconomyManager eco, Contract c, boolean pay) {
        String message = "Contract \"" + c.title + "\" (#" + c.id + ") was resolved by an administrator: "
                + (pay ? MenuNames.of(eco, c.contractor) + " was paid." : "the escrow was refunded to you.");
        eco.getNotifications().notify(c.requester, message);
        if (c.contractor != null) {
            String contractorMessage = "The dispute on \"" + c.title + "\" (#" + c.id + ") was resolved: "
                    + (pay ? "you were paid " + EconomyCraft.formatMoney(c.reward)
                            + " minus tax." : "the escrow was refunded to the requester.");
            eco.getNotifications().notify(c.contractor, contractorMessage);
        }
    }

    // === settlement ===

    /**
     * The shared settlement executor for payouts and refunds. See the class javadoc for the protocol.
     * The escrow is the source of all settlement money: a settlement never moves more than the
     * contract actually holds.
     *
     * @param kind                payout or refund
     * @param outcomeForNewIntent the terminal status a fresh intent leads to (a re-attempt keeps the
     *                            outcome already recorded with its intent)
     * @param adminResolution     true for an administrator resolving a dispute — the one legitimate
     *                            settlement path out of DISPUTED
     */
    private static SettleOutcome settle(EconomyManager eco, ContractManager contracts, Contract c,
                                        Settlement.Kind kind, @Nullable Status outcomeForNewIntent,
                                        boolean adminResolution) {
        UUID target = kind == Settlement.Kind.PAYOUT ? c.contractor : c.requester;
        if (target == null) return new SettleOutcome(SettleStatus.NO_TARGET, 0, 0);
        long held = Math.max(0, c.escrow);
        if (held <= 0) return new SettleOutcome(SettleStatus.NOTHING_IN_ESCROW, 0, 0);

        boolean reattempt = c.settlement != null && c.settlement.kind == kind && c.settlement.status == Settlement.State.PENDING;
        Status outcome = reattempt && c.settlement.outcome != null ? c.settlement.outcome : outcomeForNewIntent;
        if (outcome == null) outcome = kind == Settlement.Kind.PAYOUT ? Status.COMPLETED : Status.CANCELLED;
        int attempt = reattempt ? c.settlement.attempt + 1 : 1;
        // Never settle more than the escrow actually holds.
        long amount = reattempt ? Math.min(c.settlement.amount, held) : held;

        // Defense in depth: the state machine owns transitions; the executor refuses to settle
        // anything a validated caller could not have requested.
        if (!adminResolution && c.status == Status.DISPUTED) return new SettleOutcome(SettleStatus.FROZEN, 0, 0);
        if (c.status.terminal() && !reattempt) return new SettleOutcome(SettleStatus.ALREADY_SETTLED, 0, 0);

        long tax = 0;
        long credit = amount;
        if (kind == Settlement.Kind.PAYOUT) {
            // Quoted and charged through the same call, at the plain rate: a service payout inherits
            // neither the Merchant purchase discount nor any faction import surcharge.
            tax = TaxPolicy.resolve(TaxScope.TRANSACTION_CONTRACT, amount).amount();
            credit = amount - tax;
        }
        String detail = detail(c);
        MutationSource source = kind == Settlement.Kind.PAYOUT
                ? EconomySources.CONTRACT_PAYOUT : EconomySources.CONTRACT_ESCROW_REFUND;

        // 1. Durable intent: the obligation is recorded before any money moves, so a crash from here
        //    on leaves a recoverable pending settlement, never an untracked escrow debit.
        c.settlement = kind == Settlement.Kind.PAYOUT
                ? Settlement.payout(amount, target, attempt)
                : Settlement.refund(amount, target, attempt, outcome);
        c.cancelRequestedBy = null;
        contracts.markChanged();

        // 2. Terminal flip in memory; the escrow release and the status land durably with the money
        //    movement in the mutation's own transactional save. A returned failure reverts the flip
        //    and keeps the pending obligation for the sweep to retry.
        Status previousStatus = c.status;
        c.status = outcome;
        c.closedAt = System.currentTimeMillis();
        c.settlement = c.settlement.done();
        c.escrow = Math.max(0, held - amount);

        BalanceMutationResult result = eco.addMoney(target, credit, source, detail);
        if (!result.successful()) {
            LOGGER.warn("[EconomyCraft] Contract {} settlement {} of {} to {} failed ({}); the obligation stays pending.",
                    c.id, kind.name().toLowerCase(java.util.Locale.ROOT), EconomyCraft.formatMoney(amount),
                    MenuNames.of(eco, target), result.status());
            c.status = previousStatus;
            c.closedAt = 0;
            c.escrow = held;
            c.settlement = kind == Settlement.Kind.PAYOUT
                    ? Settlement.payout(amount, target, attempt)
                    : Settlement.refund(amount, target, attempt, outcome);
            contracts.markChanged();
            notifySettlementFailed(eco, c, kind, attempt);
            return new SettleOutcome(SettleStatus.REJECTED, 0, tax);
        }

        // The mutation's save already persisted the settled state with the money movement.
        contracts.notifyChanged();
        notifySettled(eco, c, kind, outcome, credit, tax);
        return new SettleOutcome(SettleStatus.SUCCESS, credit, tax);
    }

    // === deadlines, recovery and notifications ===

    /**
     * One sweep pass, run once a minute from the server tick: pending settlements first (recovery —
     * idempotent by the settlement record), then work deadlines, then review windows. Disputed
     * contracts are frozen and skipped. Tolerates repeated execution and restarts: every state
     * change is guarded and persisted, so a second pass finds nothing to do.
     */
    public static void processDeadlines(EconomyManager eco) {
        ContractManager contracts = eco.getContracts();
        long now = System.currentTimeMillis();
        for (Contract snapshot : contracts.getContracts()) {
            if (snapshot.settlement != null && snapshot.settlement.pending()) {
                Contract live = contracts.getContract(snapshot.id);
                if (live == null) continue;
                settle(eco, contracts, live, snapshot.settlement.kind, null, false);
                continue;
            }
            if (!snapshot.status.active() || snapshot.status == Status.DISPUTED) continue;
            switch (snapshot.status) {
                case OPEN -> {
                    if (deadlinePassed(snapshot, now)) expire(eco, contracts, snapshot, now);
                }
                case IN_PROGRESS -> {
                    if (deadlinePassed(snapshot, now)) expire(eco, contracts, snapshot, now);
                }
                case SUBMITTED -> {
                    if (snapshot.reviewDeadline > 0 && now >= snapshot.reviewDeadline) {
                        autoApprove(eco, contracts, snapshot, now);
                    }
                }
                default -> {
                }
            }
        }
        eco.getNotifications().flush();
    }

    private static boolean deadlinePassed(Contract c, long now) {
        return c.workDeadline > 0 && now >= c.workDeadline;
    }

    /** Expires a contract that missed its work deadline: a full refund to the requester (the default policy). */
    private static void expire(EconomyManager eco, ContractManager contracts, Contract snapshot, long now) {
        Contract live = contracts.getContract(snapshot.id);
        if (live == null) return;
        if (!live.status.active() || live.status == Status.DISPUTED) return;
        if (!deadlinePassed(live, now)) return;

        if (live.escrow <= 0) {
            // Nothing left in escrow: close the contract without a refund instead of retrying forever.
            Contract updated = contracts.compute(live.id, c -> {
                if (!c.status.active() || c.status == Status.DISPUTED || !deadlinePassed(c, now)) return null;
                c.status = Status.EXPIRED;
                c.closedAt = now;
                return c;
            });
            if (updated != null) {
                contracts.markChanged();
                notifyExpired(eco, updated, 0);
            }
            return;
        }
        settle(eco, contracts, live, Settlement.Kind.REFUND, Status.EXPIRED, false);
    }

    /**
     * Automatic approval: a submission that remains valid and undisputed after the configured review
     * period is approved and settled without the requester.
     */
    private static void autoApprove(EconomyManager eco, ContractManager contracts, Contract snapshot, long now) {
        Contract live = contracts.getContract(snapshot.id);
        if (live == null) return;
        if (live.status != Status.SUBMITTED) return;
        if (live.reviewDeadline <= 0 || now < live.reviewDeadline) return;
        SettleOutcome outcome = settle(eco, contracts, live, Settlement.Kind.PAYOUT, Status.COMPLETED, false);
        if (outcome.success()) {
            LOGGER.info("[EconomyCraft] Contract {} auto-approved after the review window.", live.id);
        }
    }

    private static void notifySettled(EconomyManager eco, Contract c, Settlement.Kind kind, Status outcome,
                                      long paid, long tax) {
        String title = "\"" + c.title + "\" (#" + c.id + ")";
        if (kind == Settlement.Kind.PAYOUT) {
            eco.getNotifications().notify(c.contractor, "You were paid " + EconomyCraft.formatMoney(paid)
                    + (tax > 0 ? " (" + EconomyCraft.formatMoney(tax) + " tax)" : "")
                    + " for " + title + ".");
            eco.getNotifications().notify(c.requester, "Your contract " + title + " was completed and "
                    + MenuNames.of(eco, c.contractor) + " was paid.");
            return;
        }
        String verb = outcome == Status.EXPIRED ? "expired" : "was cancelled";
        eco.getNotifications().notify(c.requester, "Your contract " + title + " " + verb
                + (paid > 0 ? " and " + EconomyCraft.formatMoney(paid) + " was refunded." : "."));
        if (c.contractor != null) {
            eco.getNotifications().notify(c.contractor, "Contract " + title + " " + verb
                    + "; the escrow was returned to the requester.");
        }
    }

    private static void notifySettlementFailed(EconomyManager eco, Contract c, Settlement.Kind kind, int attempt) {
        if (attempt > 1) return; // the first failure notifies; retries log only, to avoid spam
        String title = "\"" + c.title + "\" (#" + c.id + ")";
        String what = kind == Settlement.Kind.PAYOUT
                ? "could not be paid out" : "could not be refunded";
        eco.getNotifications().notify(c.requester, "Contract " + title + " " + what
                + "; an administrator should check it. Type /eco contracts view " + c.id + ".");
        if (c.contractor != null) {
            eco.getNotifications().notify(c.contractor, "Contract " + title + " " + what
                    + "; an administrator should check it.");
        }
    }

    private static void notifyExpired(EconomyManager eco, Contract c, long refund) {
        String title = "\"" + c.title + "\" (#" + c.id + ")";
        eco.getNotifications().notify(c.requester, "Your contract " + title + " expired"
                + (refund > 0 ? " and " + EconomyCraft.formatMoney(refund) + " was refunded." : "."));
        if (c.contractor != null) {
            eco.getNotifications().notify(c.contractor, "Contract " + title + " expired; the escrow was returned to the requester.");
        }
    }

    private static String detail(Contract c) {
        return "Contract \"" + c.title + "\" (#" + c.id + ")";
    }

    private static int reviewHours() {
        return EconomyConfig.get().contractReviewHours;
    }

    private static int maxRevisions() {
        return EconomyConfig.get().contractMaxRevisions;
    }

    private static int revisionExtensionHours() {
        return EconomyConfig.get().contractRevisionExtensionHours;
    }

    private static long maxReward() {
        return Math.min(EconomyConfig.get().maxContractReward, EconomyManager.MAX);
    }

    /** Display-name resolution helper; names are display only and never authoritative. */
    private static final class MenuNames {
        private MenuNames() {}

        static String of(EconomyManager eco, UUID id) {
            if (id == null) return "someone";
            String name = eco.getBestName(id);
            return name == null || name.isBlank() ? "someone" : name;
        }
    }
}

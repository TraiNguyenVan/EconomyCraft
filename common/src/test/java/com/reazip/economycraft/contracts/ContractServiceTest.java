package com.reazip.economycraft.contracts;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.NotificationManager;
import com.reazip.economycraft.api.v1.BalanceMutationResult;
import com.reazip.economycraft.api.v1.BalanceMutationStatus;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.MutationSource;
import com.reazip.economycraft.contracts.Contract.Status;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.tax.TaxPolicy;
import com.reazip.economycraft.tax.TaxScope;
import com.reazip.economycraft.util.EconomyPermissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Service-level tests for the full contract lifecycle against a real {@link ContractManager} and
 * in-memory database, with a ledger-backed mock {@link EconomyManager} standing in for the
 * balance engine.
 *
 * <p>The ledger mock enforces the real engine's two sharp edges — debits fail on insufficient
 * funds, credits fail past {@link EconomyManager#MAX} — so settlement-failure and recovery paths
 * behave here the way they behave in production.
 */
class ContractServiceTest {

    private final UUID requester = UUID.randomUUID();
    private final UUID contractor = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @TempDir
    private Path temp;

    private EconomyDatabase db;
    private ContractManager contracts;
    private EconomyManager eco;
    private MinecraftServer server;
    /** The test ledger: every money movement in these tests lands here. */
    private final Map<UUID, Long> balances = new HashMap<>();

    @BeforeEach
    void setUp() {
        // Pin the keys these tests depend on: another test class may replace the config instance.
        EconomyConfig cfg = EconomyConfig.get();
        cfg.contractsEnabled = true;
        cfg.maxActiveContractsPerPlayer = 0;
        cfg.maxContractReward = 1_000_000;
        cfg.contractDefaultDurationHours = 168;
        cfg.contractMaxDurationHours = 720;
        cfg.contractReviewHours = 72;
        cfg.contractMaxRevisions = 2;
        cfg.contractRevisionExtensionHours = 72;

        balances.clear();
        balances.put(requester, 100_000L);
        balances.put(contractor, 0L);
        balances.put(stranger, 100_000L);

        db = EconomyDatabase.createInMemory();
        contracts = new ContractManager(temp.resolve("contracts.json"), db);

        server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());

        eco = mock(EconomyManager.class);
        when(eco.getContracts()).thenReturn(contracts);
        when(eco.getServer()).thenReturn(server);
        when(eco.getBestName(any())).thenAnswer(call -> "Player-" + call.getArgument(0, UUID.class)
                .toString().substring(0, 8));
        when(eco.getNotifications()).thenReturn(mock(NotificationManager.class));

        when(eco.removeMoney(any(), anyLong(), any(MutationSource.class), anyString()))
                .thenAnswer(call -> {
                    UUID id = call.getArgument(0);
                    long amount = call.getArgument(1);
                    MutationSource source = call.getArgument(2);
                    long before = balances.getOrDefault(id, 0L);
                    if (amount <= 0) {
                        return new BalanceMutationResult(BalanceMutationStatus.INVALID_AMOUNT,
                                BalanceMutationType.REMOVE, id, amount, before, before, Optional.of(source));
                    }
                    if (before < amount) {
                        return new BalanceMutationResult(BalanceMutationStatus.INSUFFICIENT_FUNDS,
                                BalanceMutationType.REMOVE, id, amount, before, before, Optional.of(source));
                    }
                    balances.put(id, before - amount);
                    return new BalanceMutationResult(BalanceMutationStatus.SUCCESS,
                            BalanceMutationType.REMOVE, id, amount, before, before - amount,
                            Optional.of(source));
                });
        when(eco.addMoney(any(), anyLong(), any(MutationSource.class), anyString()))
                .thenAnswer(call -> {
                    UUID id = call.getArgument(0);
                    long amount = call.getArgument(1);
                    MutationSource source = call.getArgument(2);
                    long before = balances.getOrDefault(id, 0L);
                    if (before + amount > EconomyManager.MAX) {
                        return new BalanceMutationResult(BalanceMutationStatus.MAX_BALANCE_EXCEEDED,
                                BalanceMutationType.ADD, id, amount, before, before, Optional.of(source));
                    }
                    balances.put(id, before + amount);
                    return new BalanceMutationResult(BalanceMutationStatus.SUCCESS,
                            BalanceMutationType.ADD, id, amount, before, before + amount,
                            Optional.of(source));
                });
    }

    private ContractService.CreationRequest request(long reward) {
        return new ContractService.CreationRequest("Build a wall", "Stone, two high",
                Contract.Category.BUILD, false, null, reward,
                System.currentTimeMillis() + 7 * 24 * 3_600_000L);
    }

    private Contract createFunded(long reward) {
        ContractService.CreationResult result = ContractService.create(eco, requester, request(reward));
        assertEquals(ContractService.CreateStatus.OK, result.status());
        assertNotNull(result.contract());
        return result.contract();
    }

    // === creation ===

    @Test
    @DisplayName("creation reserves the full reward in escrow before the contract opens")
    void createFundsEscrow() {
        Contract c = createFunded(10_000);

        assertEquals(Status.OPEN, c.status);
        assertEquals(10_000, c.escrow);
        assertEquals(90_000, balances.get(requester));
    }

    @Test
    @DisplayName("creation fails without money and leaves no contract behind")
    void createFailsWhenBroke() {
        ContractService.CreationResult result = ContractService.create(eco, contractor, request(10_000));

        assertEquals(ContractService.CreateStatus.INSUFFICIENT_FUNDS, result.status());
        assertNull(result.contract());
        assertTrue(contracts.getContracts().isEmpty());
        assertEquals(0, balances.get(contractor));
    }

    @Test
    @DisplayName("creation validates title, reward and target")
    void createValidates() {
        assertEquals(ContractService.CreateStatus.INVALID_TITLE, ContractService.create(eco, requester,
                new ContractService.CreationRequest("  ", null, Contract.Category.OTHER, false, null,
                        100, System.currentTimeMillis() + 1000)).status());
        assertEquals(ContractService.CreateStatus.INVALID_REWARD,
                ContractService.create(eco, requester, request(0)).status());
        assertEquals(ContractService.CreateStatus.INVALID_REWARD,
                ContractService.create(eco, requester, request(2_000_000)).status());
        assertEquals(ContractService.CreateStatus.INVALID_TARGET, ContractService.create(eco, requester,
                new ContractService.CreationRequest("T", null, Contract.Category.OTHER, true, null,
                        100, System.currentTimeMillis() + 1000)).status());
        assertTrue(contracts.getContracts().isEmpty());
        assertEquals(100_000, balances.get(requester), "failed creations must not move money");
    }

    // === acceptance ===

    @Test
    @DisplayName("exactly one contractor can accept; the requester and outsiders cannot")
    void acceptSingleContractor() {
        Contract c = createFunded(5_000);

        assertEquals(ContractService.AcceptStatus.OK, ContractService.accept(eco, contractor, c.id));
        assertEquals(Status.IN_PROGRESS, contracts.getContract(c.id).status);
        assertEquals(contractor, contracts.getContract(c.id).contractor);

        assertEquals(ContractService.AcceptStatus.NOT_OPEN,
                ContractService.accept(eco, stranger, c.id), "second accept must lose the race");

        Contract own = createFunded(1_000);
        assertEquals(ContractService.AcceptStatus.OWN_CONTRACT,
                ContractService.accept(eco, requester, own.id));
    }

    @Test
    @DisplayName("targeted contracts are invisible to anyone but the target")
    void acceptTargeted() {
        ContractService.CreationRequest targeted = new ContractService.CreationRequest("Secret job", null,
                Contract.Category.OTHER, true, contractor, 1_000,
                System.currentTimeMillis() + 7 * 24 * 3_600_000L);
        ContractService.CreationResult result = ContractService.create(eco, requester, targeted);
        assertEquals(ContractService.CreateStatus.OK, result.status());

        assertEquals(ContractService.AcceptStatus.WRONG_TARGET,
                ContractService.accept(eco, stranger, result.contract().id));
        assertEquals(ContractService.AcceptStatus.OK,
                ContractService.accept(eco, contractor, result.contract().id));
    }

    // === submit / approve / payout ===

    @Test
    @DisplayName("approve pays the contractor exactly once and completes the contract")
    void approvePaysOnce() {
        Contract c = createFunded(10_000);
        assertEquals(ContractService.AcceptStatus.OK, ContractService.accept(eco, contractor, c.id));
        assertEquals(ContractService.SubmitStatus.OK,
                ContractService.submit(eco, contractor, c.id, "Done"));

        long tax = TaxPolicy.resolve(TaxScope.TRANSACTION_CONTRACT, 10_000).amount();
        ContractService.ApproveResult approved = ContractService.approve(eco, requester, c.id);
        assertEquals(ContractService.ApproveStatus.OK, approved.status());
        assertEquals(10_000 - tax, approved.paid());

        Contract settled = contracts.getContract(c.id);
        assertEquals(Status.COMPLETED, settled.status);
        assertEquals(0, settled.escrow);
        assertEquals(90_000, balances.get(requester));
        assertEquals(10_000 - tax, balances.get(contractor));

        assertEquals(ContractService.ApproveStatus.NOT_SUBMITTED,
                ContractService.approve(eco, requester, c.id).status(),
                "re-approving a completed contract must not pay again");
        assertEquals(10_000 - tax, balances.get(contractor), "no duplicate payout");
    }

    @Test
    @DisplayName("only the assigned contractor can submit, and only the requester can approve")
    void submitApproveAuth() {
        Contract c = createFunded(5_000);
        assertEquals(ContractService.AcceptStatus.OK, ContractService.accept(eco, contractor, c.id));

        assertEquals(ContractService.SubmitStatus.NOT_CONTRACTOR,
                ContractService.submit(eco, stranger, c.id, "Mine!"));
        assertEquals(ContractService.ApproveStatus.NOT_REQUESTER,
                ContractService.approve(eco, stranger, c.id).status());
        assertEquals(Status.IN_PROGRESS, contracts.getContract(c.id).status);
        assertEquals(5_000, contracts.getContract(c.id).escrow);
    }

    @Test
    @DisplayName("a failed payout stays pending and the recovery sweep retries it without doubling")
    void failedPayoutRecoversWithoutDoubling() {
        Contract c = createFunded(10_000);
        assertEquals(ContractService.AcceptStatus.OK, ContractService.accept(eco, contractor, c.id));
        assertEquals(ContractService.SubmitStatus.OK, ContractService.submit(eco, contractor, c.id, null));

        // Fill the contractor's account so the credit bounces off the balance cap.
        long tax = TaxPolicy.resolve(TaxScope.TRANSACTION_CONTRACT, 10_000).amount();
        long net = 10_000 - tax;
        balances.put(contractor, EconomyManager.MAX - net + 1);

        ContractService.ApproveResult failed = ContractService.approve(eco, requester, c.id);
        assertEquals(ContractService.ApproveStatus.PAYOUT_FAILED, failed.status());
        Contract pending = contracts.getContract(c.id);
        assertNotNull(pending.settlement, "the obligation must be recorded for recovery");
        assertTrue(pending.settlement.pending());
        assertEquals(90_000, balances.get(requester), "escrow stays reserved while recovery is pending");

        // The sweep retries and fails again — still no money moved, still pending.
        ContractService.processDeadlines(eco);
        assertTrue(contracts.getContract(c.id).settlement.pending());

        // The contractor spends down; the next sweep completes the same obligation exactly once.
        balances.put(contractor, 1_000L);
        ContractService.processDeadlines(eco);
        Contract settled = contracts.getContract(c.id);
        assertEquals(Status.COMPLETED, settled.status);
        assertNotNull(settled.settlement);
        assertFalse(settled.settlement.pending());
        assertEquals(1_000L + net, balances.get(contractor), "paid exactly once");
    }

    // === revisions ===

    @Test
    @DisplayName("revision returns the contract to progress and records history; approval then pays")
    void revisionFlow() {
        Contract c = createFunded(5_000);
        ContractService.accept(eco, contractor, c.id);
        ContractService.submit(eco, contractor, c.id, "First try");

        assertEquals(ContractService.ReviseStatus.OK,
                ContractService.requestRevision(eco, requester, c.id, "Use oak, not birch"));
        Contract revised = contracts.getContract(c.id);
        assertEquals(Status.IN_PROGRESS, revised.status);
        assertEquals(1, revised.revisions);
        assertEquals(1, revised.revisionHistory.size());
        assertEquals(5_000, revised.escrow);

        assertEquals(ContractService.SubmitStatus.OK,
                ContractService.submit(eco, contractor, c.id, "Oak this time"));
        assertEquals(ContractService.ApproveStatus.OK,
                ContractService.approve(eco, requester, c.id).status());
        assertEquals(Status.COMPLETED, contracts.getContract(c.id).status);
    }

    @Test
    @DisplayName("exhausted revisions block further revision and unlock disputes")
    void revisionsExhaustThenDisputable() {
        Contract c = createFunded(5_000);
        ContractService.accept(eco, contractor, c.id);

        for (int i = 0; i < EconomyConfig.get().contractMaxRevisions; i++) {
            assertEquals(ContractService.SubmitStatus.OK, ContractService.submit(eco, contractor, c.id, "try " + i));
            assertEquals(ContractService.ReviseStatus.OK,
                    ContractService.requestRevision(eco, requester, c.id, "again"));
        }
        assertEquals(ContractService.SubmitStatus.OK, ContractService.submit(eco, contractor, c.id, "final"));
        assertEquals(ContractService.ReviseStatus.REVISIONS_EXHAUSTED,
                ContractService.requestRevision(eco, requester, c.id, "one more"));

        assertEquals(ContractService.DisputeStatus.OK,
                ContractService.raiseDispute(eco, contractor, c.id, "Unreasonable demands"));
        assertEquals(Status.DISPUTED, contracts.getContract(c.id).status);
    }

    // === cancellation ===

    @Test
    @DisplayName("an open contract cancels instantly with a full refund")
    void cancelOpenRefunds() {
        Contract c = createFunded(8_000);
        assertEquals(ContractService.CancelStatus.OK, ContractService.cancel(eco, requester, c.id));
        assertEquals(Status.CANCELLED, contracts.getContract(c.id).status);
        assertEquals(100_000, balances.get(requester));
    }

    @Test
    @DisplayName("after acceptance, cancellation needs both sides; the second confirmation refunds")
    void cancelHandshake() {
        Contract c = createFunded(8_000);
        ContractService.accept(eco, contractor, c.id);

        assertEquals(ContractService.CancelStatus.REQUEST_SENT,
                ContractService.cancel(eco, requester, c.id));
        assertEquals(Status.IN_PROGRESS, contracts.getContract(c.id).status);
        assertEquals(92_000, balances.get(requester), "no refund until both sides agree");

        assertEquals(ContractService.CancelStatus.OK,
                ContractService.cancel(eco, contractor, c.id));
        assertEquals(Status.CANCELLED, contracts.getContract(c.id).status);
        assertEquals(100_000, balances.get(requester));
    }

    // === disputes and admin resolution ===

    @Test
    @DisplayName("a dispute freezes the contract: approval and cancellation are refused")
    void disputeFreezes() {
        Contract c = createFunded(6_000);
        ContractService.accept(eco, contractor, c.id);
        ContractService.submit(eco, contractor, c.id, "Done?");
        assertEquals(ContractService.DisputeStatus.OK,
                ContractService.raiseDispute(eco, requester, c.id, "Not what I asked for"));

        assertEquals(Status.DISPUTED, contracts.getContract(c.id).status);
        assertEquals(ContractService.ApproveStatus.NOT_SUBMITTED,
                ContractService.approve(eco, requester, c.id).status());
        assertEquals(ContractService.CancelStatus.DISPUTED,
                ContractService.cancel(eco, requester, c.id));
        assertEquals(6_000, contracts.getContract(c.id).escrow);
    }

    private CommandSourceStack adminSource() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        try {
            when(source.getPlayerOrException()).thenThrow(
                    new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                            () -> "console").create());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new AssertionError(e);
        }
        return source;
    }

    @Test
    @DisplayName("an administrator resolves a dispute by paying out or refunding — never both")
    void adminResolve() {
        Contract pay = createFunded(6_000);
        ContractService.accept(eco, contractor, pay.id);
        ContractService.submit(eco, contractor, pay.id, "Done");
        ContractService.raiseDispute(eco, requester, pay.id, "Meh");

        try (MockedStatic<EconomyPermissions> perms = mockStatic(EconomyPermissions.class)) {
            perms.when(() -> EconomyPermissions.checkAdmin(any(CommandSourceStack.class), anyString()))
                    .thenReturn(true);

            assertEquals(ContractService.ResolveStatus.OK,
                    ContractService.resolveDispute(eco, adminSource(), pay.id, true));
            assertEquals(Status.COMPLETED, contracts.getContract(pay.id).status);
            long contractorBalance = balances.get(contractor);
            assertTrue(contractorBalance > 0, "contractor was paid");

            // Resolving twice cannot pay twice: the contract is terminal.
            assertEquals(ContractService.ResolveStatus.NOT_DISPUTED,
                    ContractService.resolveDispute(eco, adminSource(), pay.id, true));
            assertEquals(contractorBalance, balances.get(contractor));

            Contract refund = createFunded(4_000);
            ContractService.accept(eco, contractor, refund.id);
            ContractService.submit(eco, contractor, refund.id, "Done");
            ContractService.raiseDispute(eco, contractor, refund.id, "Unfair");
            assertEquals(ContractService.ResolveStatus.OK,
                    ContractService.resolveDispute(eco, adminSource(), refund.id, false));
            assertEquals(Status.CANCELLED, contracts.getContract(refund.id).status);
            assertEquals(100_000 - 6_000, balances.get(requester),
                    "first reward spent, second refunded");
        }
    }

    @Test
    @DisplayName("dispute resolution without the admin node is refused")
    void resolveRequiresAdmin() {
        Contract c = createFunded(6_000);
        ContractService.accept(eco, contractor, c.id);
        ContractService.submit(eco, contractor, c.id, "Done");
        ContractService.raiseDispute(eco, requester, c.id, "Meh");

        try (MockedStatic<EconomyPermissions> perms = mockStatic(EconomyPermissions.class)) {
            perms.when(() -> EconomyPermissions.checkAdmin(any(CommandSourceStack.class), anyString()))
                    .thenReturn(false);
            assertEquals(ContractService.ResolveStatus.NOT_ADMIN,
                    ContractService.resolveDispute(eco, adminSource(), c.id, true));
            assertEquals(Status.DISPUTED, contracts.getContract(c.id).status);
            assertEquals(6_000, contracts.getContract(c.id).escrow);
        }
    }

    // === deadlines ===

    @Test
    @DisplayName("a contract past its deadline expires with a full refund")
    void expiryRefunds() {
        ContractService.CreationRequest alreadyLate = new ContractService.CreationRequest(
                "Urgent", null, Contract.Category.OTHER, false, null, 7_000,
                System.currentTimeMillis() - 1_000);
        ContractService.CreationResult result = ContractService.create(eco, requester, alreadyLate);
        assertEquals(ContractService.CreateStatus.OK, result.status());

        ContractService.processDeadlines(eco);
        assertEquals(Status.EXPIRED, contracts.getContract(result.contract().id).status);
        assertEquals(100_000, balances.get(requester));
    }

    @Test
    @DisplayName("an unreviewed submission auto-approves after the review window")
    void autoApprove() {
        Contract c = createFunded(9_000);
        ContractService.accept(eco, contractor, c.id);
        ContractService.submit(eco, contractor, c.id, "Done");

        // Fast-forward past the review window.
        contracts.compute(c.id, live -> {
            live.reviewDeadline = System.currentTimeMillis() - 1;
            return live;
        });

        ContractService.processDeadlines(eco);
        assertEquals(Status.COMPLETED, contracts.getContract(c.id).status);
        long tax = TaxPolicy.resolve(TaxScope.TRANSACTION_CONTRACT, 9_000).amount();
        assertEquals(9_000 - tax, balances.get(contractor));
    }

    @Test
    @DisplayName("the sweep never touches disputed contracts")
    void sweepSkipsDisputes() {
        Contract c = createFunded(6_000);
        ContractService.accept(eco, contractor, c.id);
        ContractService.submit(eco, contractor, c.id, "Done");
        ContractService.raiseDispute(eco, requester, c.id, "Frozen?");

        contracts.compute(c.id, live -> {
            live.workDeadline = System.currentTimeMillis() - 1;
            live.reviewDeadline = System.currentTimeMillis() - 1;
            return live;
        });
        ContractService.processDeadlines(eco);

        assertEquals(Status.DISPUTED, contracts.getContract(c.id).status);
        assertEquals(6_000, contracts.getContract(c.id).escrow);
        assertEquals(94_000, balances.get(requester));
    }

    // === persistence ===

    @Test
    @DisplayName("contracts and pending settlements survive a manager reload")
    void persistenceRoundTrip() {
        Contract c = createFunded(10_000);
        ContractService.accept(eco, contractor, c.id);
        ContractService.submit(eco, contractor, c.id, "Done");
        contracts.save();

        ContractManager reloaded = new ContractManager(temp.resolve("contracts.json"), db);
        Contract back = reloaded.getContract(c.id);
        assertNotNull(back);
        assertEquals(Status.SUBMITTED, back.status);
        assertEquals(10_000, back.escrow);
        assertEquals("Build a wall", back.title);
        assertEquals(contractor, back.contractor);

        // The reloaded manager settles through the same service without double work.
        when(eco.getContracts()).thenReturn(reloaded);
        assertEquals(ContractService.ApproveStatus.OK,
                ContractService.approve(eco, requester, c.id).status());
        assertEquals(Status.COMPLETED, reloaded.getContract(c.id).status);
    }
}

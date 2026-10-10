package com.reazip.economycraft.contracts;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.db.Documents;
import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.util.SequentialIdAllocator;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Owns the contract collection: lookup, id allocation, persistence coordination and the active
 * contract limit. Mirrors {@code OrderManager}; transitions and money live in {@link ContractService}.
 */
public class ContractManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public static final String DOCUMENT_KEY = "contracts.json";

    private final Path file;
    @Nullable private final EconomyDatabase db;
    private final Map<Integer, Contract> contracts = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private int nextId = 1;

    public ContractManager(Path legacyFile, @Nullable EconomyDatabase db) {
        this.file = legacyFile;
        this.db = db;
        load();
    }

    public List<Contract> getContracts() {
        List<Contract> out = new ArrayList<>(contracts.values());
        out.sort((a, b) -> Integer.compare(b.id, a.id));
        return out;
    }

    public @Nullable Contract getContract(int id) {
        return contracts.get(id);
    }

    /**
     * Allocates an id, adds the contract and persists it.
     */
    public void add(Contract contract) {
        contract.id = SequentialIdAllocator.nextId(nextId, contracts, LOGGER, "Contract");
        nextId = SequentialIdAllocator.advance(contract.id);
        putAndPersist(contract);
    }

    /**
     * Adds a contract without persisting. Used by the create-and-fund flow: the escrow debit that
     * follows commits both the balance movement and this contract in one transactional save, so a
     * crash can leave neither an unfunded contract nor a lost escrow debit.
     */
    public void stage(Contract contract) {
        contract.id = SequentialIdAllocator.nextId(nextId, contracts, LOGGER, "Contract");
        nextId = SequentialIdAllocator.advance(contract.id);
        contracts.put(contract.id, contract);
    }

    /** Removes a staged contract after a failed escrow debit; nothing was persisted for it. */
    public void unstage(int id) {
        contracts.remove(id);
        notifyListeners();
    }

    public void markChanged() {
        notifyListeners();
        save();
    }

    /**
     * Fires the UI listeners without saving. Used after a settlement whose money mutation already
     * persisted the contract in its own transactional save.
     */
    public void notifyChanged() {
        notifyListeners();
    }

    /**
     * Atomic check-and-act on one contract: {@code mutator} validates the current state and returns
     * the mutated contract, or null to abort and leave the stored contract untouched. Repeated
     * clicks, sweeps and callbacks therefore cannot apply a transition twice.
     *
     * @return the post-state of the mutated contract, or null when the mutator aborted or the
     *         contract no longer exists.
     */
    public @Nullable Contract compute(int id, UnaryOperator<Contract> mutator) {
        Contract[] outcome = new Contract[1];
        contracts.compute(id, (key, contract) -> {
            if (contract == null) return null;
            Contract updated = mutator.apply(contract);
            outcome[0] = updated;
            return updated != null ? contract : null;
        });
        return outcome[0];
    }

    public boolean remove(int id) {
        Contract removed = contracts.remove(id);
        if (removed != null) {
            notifyListeners();
            save();
        }
        return removed != null;
    }

    /** Active contracts where the player is the requester or the contractor. */
    public int countActive(UUID player) {
        int count = 0;
        for (Contract c : contracts.values()) {
            if (!c.status.active()) continue;
            if (player.equals(c.requester) || (c.contractor != null && player.equals(c.contractor))) count++;
        }
        return count;
    }

    public int getEffectiveLimit() {
        return EconomyConfig.get().maxActiveContractsPerPlayer;
    }

    public boolean hasReachedLimit(UUID player) {
        int limit = getEffectiveLimit();
        return limit > 0 && countActive(player) >= limit;
    }

    public void addListener(Runnable run) {
        listeners.add(run);
    }

    public void removeListener(Runnable run) {
        listeners.remove(run);
    }

    private void putAndPersist(Contract contract) {
        contracts.put(contract.id, contract);
        notifyListeners();
        save();
    }

    private void notifyListeners() {
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    }

    public void load() {
        String json = Documents.read(db, file, DOCUMENT_KEY);
        if (json == null) return;
        try {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;

            if (root.has("nextId")) nextId = root.get("nextId").getAsInt();
            JsonArray saved = root.has("contracts")
                    ? root.getAsJsonArray("contracts")
                    : new JsonArray();
            for (var el : saved) {
                try {
                    Contract c = Contract.load(el.getAsJsonObject());
                    if (c == null) continue;
                    contracts.put(c.id, c);
                    if (c.id >= nextId) nextId = SequentialIdAllocator.advance(c.id);
                } catch (Exception ex) {
                    LOGGER.error("[EconomyCraft] Dropping an unreadable contract in {}", file, ex);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load {}", file, ex);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("nextId", nextId);
        JsonArray arr = new JsonArray();
        for (Contract c : contracts.values()) {
            arr.add(c.save());
        }
        root.add("contracts", arr);
        Documents.write(db, file, DOCUMENT_KEY, GSON.toJson(root));
    }
}

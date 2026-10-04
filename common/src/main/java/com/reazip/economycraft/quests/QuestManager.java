package com.reazip.economycraft.quests;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.PriceRegistry;
import com.reazip.economycraft.orders.OrderFulfillment;
import com.reazip.economycraft.orders.OrderRequest;
import com.reazip.economycraft.util.AsyncFileWriter;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The automatic weekly bounty board.
 *
 * <p>Owns the rolling 7-day week: on rollover it cancels last week's leftover quest orders (escrow
 * refunds to the bot, then the leftover bot balance is burned so refunds never carry purchasing power
 * forward), draws ten fresh items, and posts them all at once as bot buy orders. A week is one-shot —
 * filled or skipped quests never repost, and an unspent budget simply never mints.
 *
 * <p>The first week starts on the first sweep, which is why a fresh boot posts nothing until ticks run.
 * Everything here runs on the server thread, called from the minute-tick gate.
 */
public class QuestManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** Reserved requester for every quest order. Nil on purpose: version 0 is never a real player, so no Mojang lookup is ever attempted. */
    public static final UUID BOT_UUID = new UUID(0L, 0L);

    static final long WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private final Path file;
    private long weekStartMs;
    private long weekSeed;
    private final List<String> drawnKeys = new ArrayList<>();
    private final Set<String> postedKeys = new HashSet<>();
    private final Set<Integer> questOrderIds = new LinkedHashSet<>();
    private long mintedThisWeek;
    private boolean postedThisWeek;

    public QuestManager(MinecraftServer server) {
        this.file = EconomyPaths.dataDir(server).resolve("quests.json");
        load();
    }

    public void sweep(EconomyManager eco) {
        var quests = EconomyConfig.get().quests;
        if (quests == null || !quests.enabled) return;

        eco.rememberPlayerName(BOT_UUID, quests.botName);

        long now = System.currentTimeMillis();
        if (weekStartMs <= 0 || now - weekStartMs >= WEEK_MILLIS) {
            rollover(eco, now);
        }
        if (!postedThisWeek) {
            postDrawn(eco, now);
            postedThisWeek = true;
        }
        save();
    }

    /** Whether an order id belongs to the board — by this week's ledger, or by requester as a backstop across restarts and failed cancels. */
    public boolean isQuestOrder(OrderRequest request) {
        if (request == null) return false;
        return questOrderIds.contains(request.id) || BOT_UUID.equals(request.requester);
    }

    private void rollover(EconomyManager eco, long now) {
        cancelLeftovers(eco);
        burnBotBalance(eco);

        var quests = EconomyConfig.get().quests;
        weekStartMs = now;
        weekSeed = now;
        mintedThisWeek = 0;
        postedThisWeek = false;
        drawnKeys.clear();
        postedKeys.clear();
        questOrderIds.clear();
        drawnKeys.addAll(QuestLogic.draw(candidates(eco), weekSeed, quests.weeklyCount));
        LOGGER.info("[EconomyCraft] Quest week started: drew {} item(s).", drawnKeys.size());
        save();
    }

    private void cancelLeftovers(EconomyManager eco) {
        for (int id : new ArrayList<>(questOrderIds)) {
            OrderFulfillment.CancelStatus status = OrderFulfillment.cancel(eco, BOT_UUID, id);
            // GONE means it was filled or already expired mid-week: the normal outcome, not an error.
            // Anything else is kept: the id stays recognised as a quest order next week.
            if (status == OrderFulfillment.CancelStatus.OK || status == OrderFulfillment.CancelStatus.ORDER_GONE) {
                questOrderIds.remove(id);
            } else {
                LOGGER.warn("[EconomyCraft] Quest order {} survived the week rollover ({}); keeping it on the board.",
                        id, status);
            }
        }
    }

    private void burnBotBalance(EconomyManager eco) {
        Long balance = eco.getBalance(BOT_UUID, false);
        if (balance == null || balance <= 0) return;
        var result = eco.removeMoney(BOT_UUID, balance, EconomySources.QUEST_FORFEIT, "unclaimed quest funds expired");
        if (result.successful()) {
            LOGGER.info("[EconomyCraft] Burned {} in leftover quest funds at the week rollover.", balance);
        } else {
            LOGGER.warn("[EconomyCraft] Failed to burn {} in leftover quest funds; it carries into the new week.", balance);
        }
    }

    /** Price keys the draw may pick: plain catalog entries with a usable price, custom marker-matched tools excluded. */
    private List<String> candidates(EconomyManager eco) {
        var quests = EconomyConfig.get().quests;
        Set<String> blacklist = new HashSet<>(quests.blacklist);
        List<String> out = new ArrayList<>();
        for (PriceRegistry.PriceEntry entry : eco.getPrices().allEntries()) {
            if (entry.customItem() != null) continue;
            long unit = QuestLogic.questUnit(eco.getEffectiveBuyPrice(entry), entry.unitSell(),
                    quests.priceFactor, quests.sellFallbackMultiplier);
            if (QuestLogic.eligible(entry.key(), unit, quests.minQuestUnit, quests.maxQuestUnit, blacklist)) {
                out.add(entry.key());
            }
        }
        return out;
    }

    private void postDrawn(EconomyManager eco, long now) {
        var quests = EconomyConfig.get().quests;
        long share = quests.weeklyCount <= 0 ? 0 : quests.weeklyBudget / quests.weeklyCount;
        long weekEnd = weekStartMs + WEEK_MILLIS;

        for (String key : drawnKeys) {
            if (postedKeys.contains(key)) continue;
            if (openQuestCount(eco) >= quests.maxConcurrent) break;

            PriceRegistry.PriceEntry entry = eco.getPrices().findByKey(key);
            if (entry == null) {
                postedKeys.add(key);
                continue;
            }
            ItemStack proto = eco.getPrices().createPrototype(entry);
            if (proto.isEmpty()) {
                postedKeys.add(key);
                continue;
            }
            long unit = QuestLogic.questUnit(eco.getEffectiveBuyPrice(entry), entry.unitSell(),
                    quests.priceFactor, quests.sellFallbackMultiplier);
            if (!QuestLogic.eligible(key, unit, quests.minQuestUnit, quests.maxQuestUnit, new HashSet<>(quests.blacklist))) {
                postedKeys.add(key);
                continue;
            }
            int amount = QuestLogic.questAmount(share, unit);
            if (amount <= 0) {
                postedKeys.add(key);
                continue;
            }
            long price = amount * unit;
            if (!QuestLogic.fitsBudget(mintedThisWeek, price, quests.weeklyBudget)) break;

            if (!fund(eco, price, quests.weeklyBudget, key)) break;
            post(eco, proto, key, amount, price, unit, now, weekEnd);
        }
    }

    private int openQuestCount(EconomyManager eco) {
        int count = 0;
        for (int id : questOrderIds) {
            if (eco.getOrders().getRequest(id) != null) count++;
        }
        return count;
    }

    /**
     * Tops the bot balance up to a quest's escrow, minting only the shortfall against the week's cap.
     * The bot balance carries refunds and previous mints, so most quests later in the week fund for free.
     *
     * <p>Reads the balance without creating it: a create-on-read would grant the bot the universal
     * new-player starting balance, and that grant would fund escrow outside the mint cap.
     */
    private boolean fund(EconomyManager eco, long price, long budget, String key) {
        Long balance = eco.getBalance(BOT_UUID, false);
        long mint = QuestLogic.mintNeeded(balance == null ? 0 : balance, price, mintedThisWeek, budget);
        if (mint > 0) {
            String detail = "quest funding for " + key;
            var result = eco.addMoney(BOT_UUID, mint, EconomySources.QUEST_FUNDING, detail);
            if (!result.successful()) {
                LOGGER.warn("[EconomyCraft] Failed to mint {} in quest funding for {}; skipping the quest.", mint, key);
                return false;
            }
            mintedThisWeek += mint;
        }
        Long funded = eco.getBalance(BOT_UUID, false);
        return funded != null && funded >= price;
    }

    private void post(EconomyManager eco, ItemStack proto, String key, int amount, long price, long unit,
                      long now, long weekEnd) {
        String detail = amount + "x " + proto.getHoverName().getString();
        if (!eco.removeMoney(BOT_UUID, price, EconomySources.ORDER_ESCROW_HOLD, detail).successful()) {
            LOGGER.warn("[EconomyCraft] Bot could not lock {} in quest escrow for {}; skipping the quest.", price, key);
            return;
        }

        OrderRequest request = new OrderRequest();
        request.requester = BOT_UUID;
        request.price = price;
        request.item = proto.copy();
        request.amount = amount;
        request.escrow = price;
        request.createdAt = now;
        // Quests live exactly one board week, not the global order expiry: the rollover owns their end of life.
        request.expiresAt = weekEnd;
        eco.getOrders().addRequest(request);

        questOrderIds.add(request.id);
        postedKeys.add(key);
        broadcast(eco, amount, proto.getHoverName().getString(), price, unit);
        LOGGER.info("[EconomyCraft] Posted quest #{}: {}x {} for {} ({} each).", request.id, amount,
                key, price, unit);
    }

    private void broadcast(EconomyManager eco, int amount, String itemName, long price, long unit) {
        Component message = Component.literal("[Quests] Server bounty: " + amount + "x " + itemName
                        + " for " + com.reazip.economycraft.EconomyCraft.formatMoney(price)
                        + " (" + com.reazip.economycraft.EconomyCraft.formatMoney(unit) + " each). Type /orders to fill it.")
                .withStyle(ChatFormatting.GOLD);
        for (ServerPlayer online : eco.getServer().getPlayerList().getPlayers()) {
            online.sendSystemMessage(message);
        }
    }

    private void load() {
        if (Files.notExists(file)) return;
        try {
            String json = Files.readString(file);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            if (root == null) return;
            if (root.has("weekStartMs")) weekStartMs = root.get("weekStartMs").getAsLong();
            if (root.has("weekSeed")) weekSeed = root.get("weekSeed").getAsLong();
            if (root.has("mintedThisWeek")) mintedThisWeek = root.get("mintedThisWeek").getAsLong();
            if (root.has("postedThisWeek")) postedThisWeek = root.get("postedThisWeek").getAsBoolean();
            if (root.has("drawnKeys")) {
                for (var el : root.getAsJsonArray("drawnKeys")) drawnKeys.add(el.getAsString());
            }
            if (root.has("postedKeys")) {
                for (var el : root.getAsJsonArray("postedKeys")) postedKeys.add(el.getAsString());
            }
            if (root.has("questOrderIds")) {
                for (var el : root.getAsJsonArray("questOrderIds")) questOrderIds.add(el.getAsInt());
            }
        } catch (Exception ex) {
            LOGGER.error("[EconomyCraft] Failed to load {}; starting a fresh quest week.", file, ex);
        }
    }

    public void save() {
        JsonObject root = new JsonObject();
        root.addProperty("weekStartMs", weekStartMs);
        root.addProperty("weekSeed", weekSeed);
        root.addProperty("mintedThisWeek", mintedThisWeek);
        root.addProperty("postedThisWeek", postedThisWeek);
        JsonArray drawn = new JsonArray();
        for (String key : drawnKeys) drawn.add(key);
        root.add("drawnKeys", drawn);
        JsonArray posted = new JsonArray();
        for (String key : postedKeys) posted.add(key);
        root.add("postedKeys", posted);
        JsonArray ids = new JsonArray();
        for (int id : questOrderIds) ids.add(id);
        root.add("questOrderIds", ids);
        AsyncFileWriter.writeAsync(file, GSON.toJson(root));
    }
}

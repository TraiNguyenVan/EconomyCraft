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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The automatic server bounty board.
 *
 * <p>Owns the rolling period ({@code quests.period_days}, default 7): on rollover it cancels the previous
 * period's leftover quest orders (escrow refunds to the bot, then the leftover bot balance is burned so
 * refunds never carry purchasing power forward), draws ten fresh items, and posts them all at once as bot
 * buy orders. A period is one-shot — filled or skipped quests never repost, and an unspent budget simply
 * never mints.
 *
 * <p>The first period starts on the first sweep, which is why a fresh boot posts nothing until ticks run.
 * Everything here runs on the server thread, called from the minute-tick gate.
 *
 * <p>Retuning {@code quests.price_factor} mid-period needs no rebuild and no new period: every sweep
 * compares each open order's posted unit against the live factor and cancel-reposts drifters at
 * the current unit, remainders kept, escrow refunded and relocked through the ordinary paths.
 */
public class QuestManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    /** Reserved requester for every quest order. Nil on purpose: version 0 is never a real player, so no Mojang lookup is ever attempted. */
    public static final UUID BOT_UUID = new UUID(0L, 0L);

    private final Path file;
    private long weekStartMs;
    private long weekSeed;
    private final List<String> drawnKeys = new ArrayList<>();
    private final Set<String> postedKeys = new HashSet<>();
    private final Set<Integer> questOrderIds = new LinkedHashSet<>();
    /** Per-unit price each open quest order was posted at, by order id — the sweep reprices drifters. */
    private final Map<String, Long> postedUnits = new LinkedHashMap<>();
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
        if (QuestLogic.periodElapsed(weekStartMs, now, quests.periodDays)) {
            rollover(eco, now);
        }
        if (!postedThisWeek) {
            postDrawn(eco, now);
            postedThisWeek = true;
        }
        reconcilePricing(eco);
        QuestBuyback.sweep(eco);
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
        QuestBuyback.repriceAll(eco);
        postedUnits.clear();

        var quests = EconomyConfig.get().quests;
        weekStartMs = now;
        weekSeed = now;
        mintedThisWeek = 0;
        postedThisWeek = false;
        drawnKeys.clear();
        postedKeys.clear();
        questOrderIds.clear();
        drawnKeys.addAll(QuestLogic.draw(candidates(eco), weekSeed, quests.weeklyCount));
        LOGGER.info("[EconomyCraft] Quest period started ({} day(s)): drew {} item(s).",
                quests.periodDays, drawnKeys.size());
        save();
    }

    /**
     * Converges open quest orders to the live price factor: any order posted at a different
     * per-unit price is cancelled (escrow refunds to the bot) and its remainder reposted at the
     * current unit. Retuning {@code quests.price_factor} is therefore a config edit that lands
     * within minutes — no rebuild, no week wait. Fills never trigger this (remainders keep their
     * posted unit in {@link #postedUnits}), and an order whose entry left the catalog is left alone.
     *
     * <p>An order that fails the <em>current</em> eligibility — its buy price was removed mid-week,
     * it was blacklisted, or it drifted out of the unit window — is cancelled and dropped, not
     * reposted. That hot-closes the loop: no stale quest survives its config.
     */
    private void reconcilePricing(EconomyManager eco) {
        var quests = EconomyConfig.get().quests;
        Set<String> blacklist = new HashSet<>(quests.blacklist);
        int repriced = 0;
        int removed = 0;
        for (int id : new ArrayList<>(questOrderIds)) {
            OrderRequest order = eco.getOrders().getRequest(id);
            if (order == null || order.item == null || order.item.isEmpty() || order.amount <= 0) {
                questOrderIds.remove(id);
                postedUnits.remove(String.valueOf(id));
                continue;
            }
            PriceRegistry.PriceEntry entry = eco.getPrices().resolve(order.item);
            if (entry == null || entry.customItem() != null) continue;
            long effectiveBuy = eco.getEffectiveBuyPrice(entry);
            long unit = QuestLogic.questUnit(effectiveBuy, entry.unitSell(),
                    quests.priceFactor, quests.sellFallbackMultiplier);
            if (unit <= 0) continue;
            if (!QuestLogic.eligible(entry.key(), unit, quests.minQuestUnit, quests.maxQuestUnit, blacklist,
                    effectiveBuy > 0, quests.requireShopPrice)) {
                OrderFulfillment.CancelStatus status = OrderFulfillment.cancel(eco, BOT_UUID, order.id);
                questOrderIds.remove(order.id);
                postedUnits.remove(String.valueOf(order.id));
                if (status == OrderFulfillment.CancelStatus.OK
                        || status == OrderFulfillment.CancelStatus.ORDER_GONE) {
                    removed++;
                    LOGGER.info("[EconomyCraft] Removed quest order {} for '{}': no longer eligible under the current config.",
                            order.id, entry.key());
                } else {
                    LOGGER.warn("[EconomyCraft] Quest order {} is ineligible but could not be cancelled ({}); keeping it.",
                            order.id, status);
                }
                continue;
            }
            Long posted = postedUnits.get(String.valueOf(id));
            if (posted != null && posted == unit) continue;

            if (repostRemainder(eco, order, entry.key(), unit)) repriced++;
        }
        if (repriced > 0) {
            Component message = Component.literal("[Quests] Board repriced to the current "
                            + "server rate — " + repriced + " order(s) reposted, remainders kept.")
                    .withStyle(ChatFormatting.GOLD);
            for (ServerPlayer online : eco.getServer().getPlayerList().getPlayers()) {
                online.sendSystemMessage(message);
            }
            LOGGER.info("[EconomyCraft] Repriced {} quest order(s) to the live price factor.", repriced);
        }
        if (removed > 0) {
            Component message = Component.literal("[Quests] " + removed + " order(s) left the board: "
                            + "no longer eligible under the current config. Escrow refunded.")
                    .withStyle(ChatFormatting.GOLD);
            for (ServerPlayer online : eco.getServer().getPlayerList().getPlayers()) {
                online.sendSystemMessage(message);
            }
        }
    }

    /**
     * Cancels one open quest order and reposts its unfilled remainder at the given unit, keeping
     * the week's expiry. The refunded escrow plus the bot's carried balance funds the repost, so a
     * factor cut never mints and a factor hike mints only the shortfall against the week's cap.
     */
    private boolean repostRemainder(EconomyManager eco, OrderRequest order, String key, long unit) {
        int remainder = order.amount;
        ItemStack proto = order.item.copy();
        long price = (long) remainder * unit;

        OrderFulfillment.CancelStatus status = OrderFulfillment.cancel(eco, BOT_UUID, order.id);
        questOrderIds.remove(order.id);
        postedUnits.remove(String.valueOf(order.id));
        if (status == OrderFulfillment.CancelStatus.ORDER_GONE) return false;
        if (status != OrderFulfillment.CancelStatus.OK) {
            LOGGER.warn("[EconomyCraft] Quest order {} could not be cancelled for reprice ({}); keeping it at its old price.",
                    order.id, status);
            return false;
        }

        var quests = EconomyConfig.get().quests;
        if (!fund(eco, price, quests.weeklyBudget, key)) {
            LOGGER.warn("[EconomyCraft] Bot could not fund the repriced quest for {}; it stays off the board this week.", key);
            return false;
        }
        post(eco, proto, key, remainder, price, unit, System.currentTimeMillis(),
                weekStartMs + QuestLogic.periodMillis(quests.periodDays), false);
        return true;
    }

    /**
     * Starts a fresh board on demand (the admin "Force re-draw" button): every open quest order is
     * cancelled through the ordinary path (escrow refunds to the bot), the draw state is wiped, and
     * a new period is drawn and posted immediately — same clock, same cap, no waiting for a sweep.
     *
     * <p>The period's mint cap stays consumed and the bot balance carries (it still burns at the next
     * rollover), so a re-draw can never re-farm the budget. Buyback listings are untouched.
     *
     * @return how many fresh quests posted
     */
    public int forceNewWeek(EconomyManager eco) {
        var quests = EconomyConfig.get().quests;
        if (quests == null || !quests.enabled) return 0;
        long now = System.currentTimeMillis();

        cancelLeftovers(eco);
        questOrderIds.clear();
        postedUnits.clear();
        drawnKeys.clear();
        postedKeys.clear();
        weekStartMs = now;
        weekSeed = now;
        drawnKeys.addAll(QuestLogic.draw(candidates(eco), weekSeed, quests.weeklyCount));
        postedThisWeek = false;
        postDrawn(eco, now);
        postedThisWeek = true;
        save();

        LOGGER.info("[EconomyCraft] Admin forced a quest re-draw: {} quest(s) posted.", questOrderIds.size());
        return questOrderIds.size();
    }

    /** Milliseconds until the current period rolls over; {@code 0} if there is no live period yet. */
    public long millisUntilPeriodEnd(long now) {
        var quests = EconomyConfig.get().quests;
        if (quests == null || !quests.enabled) return 0L;
        return QuestLogic.millisUntilPeriodEnd(weekStartMs, now, quests.periodDays);
    }

    /** Open quest orders right now — how many a re-draw or an early rollover would cancel. */
    public int openQuestCountNow(EconomyManager eco) {
        return openQuestCount(eco);
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
            long effectiveBuy = eco.getEffectiveBuyPrice(entry);
            long unit = QuestLogic.questUnit(effectiveBuy, entry.unitSell(),
                    quests.priceFactor, quests.sellFallbackMultiplier);
            if (QuestLogic.eligible(entry.key(), unit, quests.minQuestUnit, quests.maxQuestUnit, blacklist,
                    effectiveBuy > 0, quests.requireShopPrice)) {
                out.add(entry.key());
            }
        }
        return out;
    }

    private void postDrawn(EconomyManager eco, long now) {
        var quests = EconomyConfig.get().quests;
        long share = quests.weeklyCount <= 0 ? 0 : quests.weeklyBudget / quests.weeklyCount;
        long weekEnd = weekStartMs + QuestLogic.periodMillis(quests.periodDays);

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
            if (!QuestLogic.eligible(key, unit, quests.minQuestUnit, quests.maxQuestUnit, new HashSet<>(quests.blacklist),
                    eco.getEffectiveBuyPrice(entry) > 0, quests.requireShopPrice)) {
                postedKeys.add(key);
                continue;
            }
            int amount = QuestLogic.questAmount(share, unit);
            if (amount <= 0) {
                postedKeys.add(key);
                continue;
            }
            long price = amount * unit;
            // No fitsBudget pre-check here on purpose: the cap tracks minted coins, not escrow
            // locked, and fund() below mints only the shortfall the bot balance does not cover.
            // A full-price-against-cap check would wrongly stop a board the refunds already fund
            // (a forced re-draw late in a spent week posts nothing). fund() false is the stop.
            if (!fund(eco, price, quests.weeklyBudget, key)) break;
            post(eco, proto, key, amount, price, unit, now, weekEnd, true);
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
                      long now, long weekEnd, boolean announce) {
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
        postedUnits.put(String.valueOf(request.id), unit);
        if (announce) {
            broadcast(eco, amount, proto.getHoverName().getString(), price, unit);
        }
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
            if (root.has("postedUnits")) {
                for (var entry : root.getAsJsonObject("postedUnits").entrySet()) {
                    try {
                        postedUnits.put(entry.getKey(), entry.getValue().getAsLong());
                    } catch (Exception ex) {
                        LOGGER.warn("[EconomyCraft] Dropping an unreadable posted quest unit in {}", file);
                    }
                }
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
        JsonObject units = new JsonObject();
        for (var entry : postedUnits.entrySet()) units.addProperty(entry.getKey(), entry.getValue());
        root.add("postedUnits", units);
        AsyncFileWriter.writeAsync(file, GSON.toJson(root));
    }
}

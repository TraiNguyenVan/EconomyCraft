package com.reazip.economycraft.gossip;

import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomySources;
import com.reazip.economycraft.api.v1.BalanceMutationType;
import com.reazip.economycraft.api.v1.FactionIds;
import com.reazip.economycraft.util.TransactionEntry;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Anonymizes player identities into flavorful character archetypes and sanitizes
 * transaction descriptions, item lore, and player text against prompt injection.
 */
public final class TransactionAnonymizer {
    private TransactionAnonymizer() {}

    public static final int MAX_DETAIL_LENGTH = 80;

    // --- Regex Patterns (Thread-safe, pre-compiled) ---
    private static final Pattern MINECRAFT_SECTION_PATTERN =
            Pattern.compile("§[0-9a-fk-orA-FK-OR]|§x(§[0-9a-fA-F]){6}|§.");
    private static final Pattern AMPERSAND_PATTERN =
            Pattern.compile("&[0-9a-fk-orA-FK-OR]|&#[0-9a-fA-F]{6}");
    private static final Pattern CONTROL_CHARS_PATTERN =
            Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Pattern ZERO_WIDTH_CHARS_PATTERN =
            Pattern.compile("[\u200B-\u200D\uFEFF\u00AD\u200E\u200F]");
    private static final Pattern LINE_BREAKS_PATTERN =
            Pattern.compile("[\r\n\t\u2028\u2029]+");
    private static final Pattern MULTIPLE_SPACES_PATTERN =
            Pattern.compile("\\s{2,}");
    private static final Pattern INSTRUCTION_OVERRIDE_PATTERN = Pattern.compile(
            "(?i)\\b(ignore|disregard|forget|override|bypass)\\b[\\s\\S]{0,35}\\b(previous|prior|earlier|above|system|all)\\b[\\s\\S]{0,35}\\b(instructions?|prompts?|rules?|commands?|guidelines?)\\b");
    private static final Pattern ROLE_MARKER_PATTERN = Pattern.compile(
            "(?i)(^|[\\s\\[<(])(system|assistant|user|developer|admin|moderator|model)\\s*[:\\]>)]+\\s*");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile(
            "```[a-zA-Z]*");
    private static final Pattern SPECIAL_TOKEN_PATTERN = Pattern.compile(
            "<\\|?[a-zA-Z0-9_.-]+\\|?>");
    private static final Pattern PROMPT_LEAK_PATTERN = Pattern.compile(
            "(?i)\\b(output|print|repeat|leak|reveal|show)\\b[\\s\\S]{0,25}\\b(system\\s+prompts?|earlier\\s+prompts?|instructions?|hidden\\s+rules?)\\b");

    // --- Archetype Catalogs ---
    public static final String[] CAPITALISM_RICH = {
            "a wealthy tycoon", "a gilded magnate", "an ambitious financier", "a corporate baron"
    };
    public static final String[] CAPITALISM_MID = {
            "a shrewd merchant", "a market speculator", "a private trader", "a commercial contractor"
    };
    public static final String[] CAPITALISM_LOW = {
            "a struggling entrepreneur", "a petty trader", "an eager apprentice"
    };

    public static final String[] COMMUNISM_RICH = {
            "a state commissar", "a party delegate", "a union chairman", "a central planner"
    };
    public static final String[] COMMUNISM_MID = {
            "a collective worker", "a guild comrade", "a cooperative laborer"
    };
    public static final String[] COMMUNISM_LOW = {
            "a humble proletarian", "a shared-wealth farmhand", "a village laborer"
    };

    public static final String[] MONARCHY_RICH = {
            "a crown aristocrat", "a royal duke", "a palace noble", "a highborn lord"
    };
    public static final String[] MONARCHY_MID = {
            "a royal knight", "a feudal vassal", "a courtier merchant"
    };
    public static final String[] MONARCHY_LOW = {
            "a loyal peasant", "a crown subject", "a castle servant"
    };

    public static final String[] ANARCHISM_RICH = {
            "a legendary outlaw", "a contraband baron", "a rogue plunderer"
    };
    public static final String[] ANARCHISM_MID = {
            "a shadowy rebel", "a defiant freebooter", "an independent nomad"
    };
    public static final String[] ANARCHISM_LOW = {
            "a lawless wanderer", "a stateless drifter", "an outlaw rogue"
    };

    public static final String[] NEUTRAL_RICH = {
            "an opulent patron", "a wealthy traveler", "a lavish collector"
    };
    public static final String[] NEUTRAL_MID = {
            "a local merchant", "a traveling trader", "a skilled artisan"
    };
    public static final String[] NEUTRAL_LOW = {
            "a humble harvester", "a foreign trader", "a wandering laborer"
    };

    /**
     * Sanitizes arbitrary text (item names, anvil renames, lore, user text) by stripping
     * formatting codes, control characters, and prompt injection attempts.
     */
    public static String sanitize(@Nullable String input) {
        if (input == null || input.isBlank()) return "";

        String text = input;

        // 1. Strip Minecraft formatting & colors
        text = MINECRAFT_SECTION_PATTERN.matcher(text).replaceAll("");
        text = AMPERSAND_PATTERN.matcher(text).replaceAll("");

        // 2. Strip control & zero-width characters
        text = CONTROL_CHARS_PATTERN.matcher(text).replaceAll("");
        text = ZERO_WIDTH_CHARS_PATTERN.matcher(text).replaceAll("");

        // 3. Flatten newlines & tabs
        text = LINE_BREAKS_PATTERN.matcher(text).replaceAll(" ");

        // 4. Strip code fences & special tokens
        text = CODE_FENCE_PATTERN.matcher(text).replaceAll("");
        text = SPECIAL_TOKEN_PATTERN.matcher(text).replaceAll("");

        // 5. Neutralize injection patterns
        text = INSTRUCTION_OVERRIDE_PATTERN.matcher(text).replaceAll("[redacted]");
        text = ROLE_MARKER_PATTERN.matcher(text).replaceAll("");
        text = PROMPT_LEAK_PATTERN.matcher(text).replaceAll("[redacted]");

        // 6. Normalize whitespace
        text = MULTIPLE_SPACES_PATTERN.matcher(text).replaceAll(" ").trim();

        // 7. Length clamp
        if (text.length() > MAX_DETAIL_LENGTH) {
            text = text.substring(0, MAX_DETAIL_LENGTH).trim() + "...";
        }

        // 8. Fallback if empty or purely redacted
        if (text.isEmpty() || text.equals("[redacted]")) {
            return "[a curious item]";
        }

        return text;
    }

    public static final UUID BOT_UUID = new UUID(0L, 0L);

    public static boolean isServerQuest(@Nullable UUID id, @Nullable String name) {
        if (id != null && (id.equals(BOT_UUID) || (id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() == 0L))) {
            return true;
        }
        return name != null && name.equalsIgnoreCase("Server Quests");
    }

    /**
     * Resolves a player UUID, faction, and wealth balance to a flavorful archetype string.
     */
    public static String resolveArchetype(
            UUID playerId,
            @Nullable String factionId,
            long balance,
            @Nullable String role
    ) {
        if (isServerQuest(playerId, null)) {
            return "the Quest Board";
        }

        String normalizedFaction = factionId != null ? factionId.toLowerCase(Locale.ROOT) : "";

        String[] pool;
        if (FactionIds.CAPITALISM.equals(normalizedFaction)) {
            pool = balance >= 100_000 ? CAPITALISM_RICH : (balance >= 10_000 ? CAPITALISM_MID : CAPITALISM_LOW);
        } else if (FactionIds.COMMUNISM.equals(normalizedFaction)) {
            pool = balance >= 100_000 ? COMMUNISM_RICH : (balance >= 10_000 ? COMMUNISM_MID : COMMUNISM_LOW);
        } else if (FactionIds.MONARCHY.equals(normalizedFaction)) {
            pool = balance >= 100_000 ? MONARCHY_RICH : (balance >= 10_000 ? MONARCHY_MID : MONARCHY_LOW);
        } else if (FactionIds.ANARCHISM.equals(normalizedFaction)) {
            pool = balance >= 100_000 ? ANARCHISM_RICH : (balance >= 10_000 ? ANARCHISM_MID : ANARCHISM_LOW);
        } else {
            pool = balance >= 100_000 ? NEUTRAL_RICH : (balance >= 10_000 ? NEUTRAL_MID : NEUTRAL_LOW);
        }

        int index = Math.floorMod(playerId.hashCode(), pool.length);
        return pool[index];
    }

    /**
     * Capitalizes the first character of a string.
     */
    private static String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Formats a single transaction entry into an anonymized, high-signal event line.
     */
    public static String formatTransaction(
            TransactionEntry entry,
            boolean anonymizePlayers,
            @Nullable Function<UUID, String> factionResolver
    ) {
        boolean isQuestActor = isServerQuest(entry.player(), entry.playerName());
        boolean isQuestCounterparty = isServerQuest(entry.counterparty(), entry.counterpartyName());

        String rawActor;
        if (isQuestActor) {
            rawActor = "the Quest Board";
        } else if (anonymizePlayers) {
            String faction = factionResolver != null ? factionResolver.apply(entry.player()) : null;
            rawActor = resolveArchetype(entry.player(), faction, entry.balanceAfter(), null);
        } else {
            rawActor = entry.playerName() != null && !entry.playerName().isBlank()
                    ? sanitize(entry.playerName())
                    : "a citizen";
        }
        String actor = capitalizeFirst(rawActor);

        String counterparty;
        if (isQuestCounterparty) {
            counterparty = "the Quest Board";
        } else if (entry.counterparty() != null) {
            if (anonymizePlayers) {
                String faction = factionResolver != null ? factionResolver.apply(entry.counterparty()) : null;
                counterparty = resolveArchetype(entry.counterparty(), faction, 0, null);
            } else {
                counterparty = entry.counterpartyName() != null && !entry.counterpartyName().isBlank()
                        ? sanitize(entry.counterpartyName())
                        : "someone";
            }
        } else {
            counterparty = "a local citizen";
        }

        String rawDetail = entry.detail();
        String item = (rawDetail != null && !rawDetail.isBlank()) ? sanitize(rawDetail) : "goods";
        if (item.isEmpty()) {
            item = "goods";
        }

        String formattedAmount = EconomyCraft.formatMoney(Math.abs(entry.amount()));
        String source = entry.source();

        if (source != null) {
            if (source.equals(EconomySources.AUCTION_PURCHASE.asString())) {
                return "- " + actor + " purchased " + item + " from " + counterparty + " for " + formattedAmount + " on the auction house.";
            } else if (source.equals(EconomySources.QUEST_BUYBACK.asString())) {
                return "- " + actor + " purchased " + item + " from the Quest Board surplus for " + formattedAmount + ".";
            } else if (source.equals(EconomySources.SHOP_PURCHASE.asString())) {
                return "- " + actor + " bought " + item + " from the market shop for " + formattedAmount + ".";
            } else if (source.equals(EconomySources.SHOP_SALE.asString())) {
                return "- " + actor + " sold " + item + " to the market shop for " + formattedAmount + ".";
            } else if (source.equals(EconomySources.ORDER_FULFILLMENT.asString())) {
                if (isQuestCounterparty) {
                    return "- " + actor + " fulfilled a bounty of " + item + " for the Quest Board earning " + formattedAmount + ".";
                }
                return "- " + actor + " fulfilled a supply order of " + item + " for " + counterparty + " earning " + formattedAmount + ".";
            } else if (source.equals(EconomySources.ORDER_ESCROW_HOLD.asString())) {
                if (isQuestActor) {
                    return "- The Quest Board posted a bounty depositing " + formattedAmount + " in escrow for " + item + ".";
                }
                return "- " + actor + " placed a buy order depositing " + formattedAmount + " in escrow for " + item + ".";
            } else if (source.equals(EconomySources.ORDER_ESCROW_REFUND.asString())) {
                if (isQuestActor) {
                    return "- The Quest Board recycled " + formattedAmount + " from an expired bounty for " + item + ".";
                }
                return "- " + actor + " reclaimed " + formattedAmount + " from an expired buy order for " + item + ".";
            } else if (source.equals(EconomySources.QUEST_FUNDING.asString())) {
                return "- The Bounty Board funded a bounty of " + formattedAmount + " for commodities.";
            } else if (source.equals(EconomySources.QUEST_FORFEIT.asString())) {
                return "- An unclaimed Bounty Board request expired, burning " + formattedAmount + ".";
            } else if (source.equals(EconomySources.TOLL_PAYMENT.asString())) {
                return "- " + actor + " paid a highway toll of " + formattedAmount + " to " + counterparty + ".";
            } else if (source.equals(EconomySources.WEALTH_TAX.asString())) {
                return "- " + actor + " was assessed a wealth tax levy of " + formattedAmount + ".";
            } else if (source.equals(EconomySources.WEALTH_REBATE.asString())) {
                return "- " + actor + " received a wealth rebate of " + formattedAmount + " from the treasury.";
            } else if (source.equals(EconomySources.DAILY_TAX.asString())) {
                return "- " + actor + " paid " + formattedAmount + " in daily civic taxes.";
            } else if (source.equals(EconomySources.INCOME_TAX.asString())) {
                return "- " + actor + " paid an income tax of " + formattedAmount + ".";
            } else if (source.equals(EconomySources.CORRUPTION_TAX.asString())) {
                return "- A party levy of " + formattedAmount + " was extracted from " + rawActor + ".";
            } else if (source.equals(EconomySources.PARTY_FEE.asString())) {
                return "- " + actor + " paid a " + formattedAmount + " party membership fee.";
            } else if (source.equals(EconomySources.DAILY_REWARD.asString())) {
                return "- " + actor + " claimed a daily stipend of " + formattedAmount + ".";
            } else if (source.equals(EconomySources.VILLAGER_TRADE.asString())) {
                return "- " + actor + " conducted a major trade worth " + formattedAmount + " with a village merchant.";
            }
        }

        // BalanceMutationType fallback
        if (entry.type() == BalanceMutationType.PAYMENT_SENT) {
            return "- " + actor + " transferred " + formattedAmount + " to " + counterparty + ".";
        }

        return "- " + actor + " participated in an economic transaction of " + formattedAmount + ".";
    }

    /**
     * Formats a list of transactions into an event line digest for prompt consumption.
     */
    public static List<String> formatDigest(
            List<TransactionEntry> entries,
            boolean anonymizePlayers,
            @Nullable Function<UUID, String> factionResolver
    ) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(entries.size());
        for (TransactionEntry entry : entries) {
            lines.add(formatTransaction(entry, anonymizePlayers, factionResolver));
        }
        return Collections.unmodifiableList(lines);
    }
}

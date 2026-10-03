package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.EconomyCraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Conditional Haste for Builder (P4-T6) and Miner (P6-T2), per D20.
 *
 * <p>D20's decision is the whole point of this class: Haste is <em>not</em> a timed buff the player carries. It
 * exists only while they are breaking a block from their job's trigger set and is removed on the first tick
 * they are not. Read literally as a potion with no duration, a Builder would walk around permanently Hasted
 * and a Miner could never stop tunnelling, which is the reading the spec's silence does not support.
 *
 * <p>So {@link #onBreakProgress} is driven by the destroy-progress path rather than by break completion. Granting
 * on completion is exactly the mistake that produced the "permanently Hasted Builder" reading.
 *
 * <p>⚠️ <b>Verified against 26.3 bytecode, and the tick rate matters here.</b> The hook is
 * {@code ServerPlayerGameMode#incrementDestroyProgress}, whose only caller is {@code ServerPlayerGameMode#tick}
 * under the {@code isDestroyingBlock} guard — so it fires <em>every tick the player is mining</em>, not once per
 * mining packet. That is better than the design assumed, but it also means the refresh is <em>not</em> what
 * protects against packet spacing; packets cannot be the reason a refresh is ever missed.
 *
 * <p>So {@code haste_refresh_seconds} earns its place from a different gap: the ticks <em>between</em> blocks.
 * Moving from one stone to the next clears {@code isDestroyingBlock} for a tick or two, and without a window the
 * effect would be stripped and immediately re-granted, which a player sees as the mining speed pulsing. It is
 * still only an <em>anti-flicker window</em>, never a duration: expiry is unconditional and is driven by
 * {@link #expireStale}.
 *
 * <p>A rusty player gets no Haste at all. {@link ProfessionEffects} expresses rust as a ×{@code rust_effect_factor}
 * multiplier, and an effect amplifier cannot be halved, so rounding it to "no effect" is the only faithful
 * reading of "effects are at half strength" for an integer amplifier — and a debuff state granting a buff
 * would be odd regardless.
 */
public final class ProfessionHaste {
    /**
     * Expiry bookkeeping: player UUID to the server tick at which their Haste was last refreshed.
     *
     * <p>Deliberately only tracks players this class touched, so a Haste potion the player drank themselves is
     * never stripped. Removal additionally re-checks that the effect on the player is still the short one we
     * applied, which covers a player drinking their own long Haste in the meantime.
     */
    private static final Map<UUID, Integer> LAST_REFRESH = new HashMap<>();

    /**
     * The refresh window used for a player we hold no resolved settings for.
     *
     * <p>That happens when they stop being a Builder or Miner between being granted Haste and the window
     * lapsing. Expiring them on the normal one-second schedule is the safe reading: if the job is gone there is
     * no reason to keep the effect, and holding it longer only delays the correction.
     */
    static final int DEFAULT_REFRESH_SECONDS = 1;

    private ProfessionHaste() {}

    /**
     * Called on each destroy-progress tick while the player is mining {@code state}.
     *
     * <p>Refreshes the Haste when {@code state} is in this job's trigger set, and does nothing otherwise —
     * removal is {@link #expireStale}'s job, because "not breaking anything right now" is indistinguishable
     * here from "this tick carried no packet".
     */
    public static void onBreakProgress(ServerPlayer player, BlockState state) {
        if (player == null || state == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            ProfessionId profession = eco.getProfessions().professionOf(player.getUUID());
            ProfessionSettings settings = settingsFor(profession);
            if (!grantsHaste(settings, eco.getBlockTags(), state,
                    eco.getProfessions().levelOf(player.getUUID()))) {
                return;
            }

            applyShort(player, amplifierFor(settings), windowTicksFor(settings));
            // Both ends of this comparison must read the same clock: the in-game world time from
            // level().getGameTime() and the server's uptime tick count advance together but have different
            // origins, so mixing them makes every window look either instantly stale or never stale.
            LAST_REFRESH.put(player.getUUID(), player.level().getServer().getTickCount());
        } catch (Exception ignored) {
            // An effect hook must never break the block-mining path that triggered it.
        }
    }

    /**
     * Strips the Haste from anyone who has not refreshed recently. Called once per server tick.
     *
     * <p>This is the half of D20 that the break hook cannot do: the hook only ever sees ticks where a packet
     * arrived, so without this a player who stops mining keeps the bridged window's worth of Haste forever.
     */
    public static void expireStale(MinecraftServer server, int currentTick) {
        if (LAST_REFRESH.isEmpty()) return;
        try {
            for (Map.Entry<UUID, Integer> entry : Map.copyOf(LAST_REFRESH).entrySet()) {
                Integer refreshed = entry.getValue();
                if (refreshed == null) continue;

                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    LAST_REFRESH.remove(entry.getKey());
                    continue;
                }

                ProfessionSettings settings = settingsFor(
                        EconomyCraft.getManager(server).getProfessions().professionOf(entry.getKey()));

                if (!isStale(currentTick, refreshed, settings == null
                        ? DEFAULT_REFRESH_SECONDS
                        : settings.hasteRefreshSeconds())) {
                    continue;
                }

                LAST_REFRESH.remove(entry.getKey());
                if (settings != null) removeShort(player, settings.hasteLevel());
            }
        } catch (Exception ignored) {
            // Never let effect cleanup break the tick.
        }
    }

    /** Drops any bookkeeping for players who logged out, so the map cannot grow without bound. */
    public static void forget(UUID playerId) {
        if (playerId != null) LAST_REFRESH.remove(playerId);
    }

    /**
     * (Re)applies the Haste with a fresh window.
     *
     * <p>{@code forceAddEffect} rather than {@code addEffect}: vanilla's version refuses to replace an effect of
     * the same amplifier unless the new one is strictly longer, so re-adding the same window every tick would
     * leave the countdown ticking down to zero between mining packets — exactly the flicker
     * {@code haste_refresh_seconds} exists to prevent.
     */
    private static void applyShort(ServerPlayer player, int amplifier, int windowTicks) {
        player.forceAddEffect(new MobEffectInstance(MobEffects.HASTE, windowTicks, amplifier, true, false, true), player);
    }

    /**
     * Whether a break-progress tick on {@code state} should grant or refresh this job's Haste.
     *
     * <p>Pure on purpose, and the single answer to D20's grant half: the trigger set decides, so a Builder
     * breaking something outside it gets nothing even while mining hard. Removal deliberately does <em>not</em>
     * live here — "this block is not a trigger" and "no mining packet arrived this tick" are indistinguishable
     * at this call site, and only {@link #isStale} can tell the second one apart.
     *
     * <p>A rusty player is refused outright rather than halved, because an effect amplifier is an integer and
     * {@code rust_effect_factor} of 0.5 has no faithful rounding: 0 would mean a debuff state granting no
     * buff, and 1 would mean rust does not weaken Haste at all.
     */
    static boolean grantsHaste(ProfessionSettings settings, BlockTags tags, BlockState state, ProfessionLevel level) {
        if (settings == null || state == null) return false;
        if (level == ProfessionLevel.RUSTED) return false;
        return settings.triggers(tags, state);
    }

    /**
     * Whether a player who last refreshed at {@code refreshedTick} has now stopped long enough to lose the
     * effect.
     *
     * <p>This is the test P4-T6 asks for — the one that separates D20's rule from a permanent buff — and it is
     * deliberately the mirror image of the grant check: the grant happens on a tick that carried a packet,
     * while the expiry has to notice ticks that carried none.
     *
     * <p>Both ends widen to {@code long} before subtracting. The tick counter is an {@code int} and wraps, and a
     * narrowing subtraction across the wrap would come out negative — which reads as "not stale" and so would
     * pin a Hasted player on permanently, the exact regression this method exists to prevent. The window itself
     * is widened too, so a nonsensical {@code haste_refresh_seconds} cannot overflow it back into that trap.
     */
    static boolean isStale(int currentTick, int refreshedTick, int refreshSeconds) {
        long windowTicks = (long) Math.max(1, refreshSeconds) * 20L;
        return (long) currentTick - (long) refreshedTick > windowTicks;
    }

    /**
     * Whether the Haste currently on a player is the short one this class applied, and so may be removed.
     *
     * <p>The amplifier check is what keeps a Haste potion the player drank from being stripped when the window
     * lapses: a differently-sourced or stronger Haste is left alone.
     */
    static boolean isOursToRemove(int activeAmplifier, int hasteLevel) {
        return activeAmplifier == Math.max(1, hasteLevel) - 1;
    }

    /** Haste I is amplifier 0, so the configured level is one higher than the amplifier that represents it. */
    static int amplifierFor(ProfessionSettings settings) {
        return Math.max(0, settings.hasteLevel() - 1);
    }

    static int windowTicksFor(ProfessionSettings settings) {
        return Math.max(1, settings.hasteRefreshSeconds()) * 20;
    }

    /**
     * Removes the Haste, but only while it is still the short one this class applied.
     */
    private static void removeShort(ServerPlayer player, int hasteLevel) {
        MobEffectInstance active = player.getEffect(MobEffects.HASTE);
        if (active == null) return;
        if (!isOursToRemove(active.getAmplifier(), hasteLevel)) return;
        player.removeEffect(MobEffects.HASTE);
    }

    @Nullable
    static ProfessionSettings settingsFor(ProfessionId profession) {
        if (profession == null) return null;
        return switch (profession) {
            case BUILDER -> new ProfessionSettings(
                    EconomyConfig.get().professions.builder.hasteLevel,
                    EconomyConfig.get().professions.builder.hasteRefreshSeconds,
                    Job.BUILDER);
            case MINER -> new ProfessionSettings(
                    EconomyConfig.get().professions.miner.hasteLevel,
                    EconomyConfig.get().professions.miner.hasteRefreshSeconds,
                    Job.MINER);
            default -> null;
        };
    }

    /** Which job's trigger set to test against, since the two sets differ. */
    enum Job {
        BUILDER,
        MINER
    }

    /**
     * The per-job Haste knobs, resolved without reaching back into config at each use.
     *
     * <p>The job selector is kept as a value rather than a resolved block set so the check keeps using the live
     * {@code BlockTags} instance the server built, not a snapshot taken here.
     */
    record ProfessionSettings(int hasteLevel, int hasteRefreshSeconds, Job job) {
        boolean triggers(BlockTags tags, BlockState state) {
            if (tags == null) return false;
            return switch (job) {
                case BUILDER -> tags.triggersBuilderHaste(state);
                case MINER -> tags.triggersMinerHaste(state);
            };
        }
    }
}

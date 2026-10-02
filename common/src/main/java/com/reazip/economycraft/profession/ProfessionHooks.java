package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The one place a gameplay action turns into profession progress.
 *
 * <p>Loaders call in from their own mixins because the placement path has no portable event: Fabric API
 * 0.160.5+26.3 ships {@code PlayerBlockBreakEvents} but has no placement counterpart, and NeoForge's
 * {@code BlockEvent.PlaceEvent} does not exist on Fabric. {@code BlockItem#place} is the single vanilla
 * method both loaders agree on, so each loader mixes that one method and hands the player plus the resulting
 * state here. All counting logic stays in {@code common}, which is what keeps the two loaders from drifting.
 *
 * <p>Progress rules, all from the spec's level-up lines:
 *
 * <ul>
 *   <li><strong>Builder</strong> counts a <em>placed</em> building block (1000), read from
 *       {@code BlockTags} rather than a literal list.</li>
 *   <li><strong>Farmer</strong> counts a planted crop and a harvested mature crop (300).</li>
 *   <li><strong>Miner</strong> counts a <em>broken</em> ore, worth 2 for the configured double-value
 *       ores (270).</li>
 * </ul>
 *
 * <p>{@link ProfessionStore#addProgress} already refuses progress for a rusty player (D13) and for a player
 * who is already Master, so no level gate is duplicated here. That refusal is also the anti-abuse brake:
 * a rusty player cannot shorten their own debuff by working.
 *
 * <p>Harvest counts only when the crop was <em>max age</em> when broken. A player who breaks a seed to
 * tidy a farm gets planting credit, not harvest credit, and cannot farm progress by repeatedly replanting
 * and breaking sprouts.
 */
public final class ProfessionHooks {
    private ProfessionHooks() {}

    /**
     * Awards profession progress for a qualifying action, re-applying persistent effects on promotion.
     *
     * <p>All three entry points funnel through here so a promotion can never be missed: {@code addProgress}
     * returns true exactly when the level changed, and that is the only signal that the reach modifier needs
     * rewriting. Centralising it is what keeps a new action from having to remember.
     */
    private static void award(EconomyManager eco, ServerPlayer player, long amount) {
        if (eco.getProfessions().addProgress(player.getUUID(), amount)) {
            ProfessionEffects.applyPersistent(player);
        }
    }

    private static void award(EconomyManager eco, ServerPlayer player) {
        award(eco, player, 1L);
    }

    /** A block was placed by a player. Counts Builder building-block progress and Farmer planting progress. */
    public static void onBlockPlaced(ServerPlayer player, BlockState placed) {
        if (player == null || placed == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            ProfessionId profession = eco.getProfessions().professionOf(player.getUUID());
            if (profession == null) return;

            switch (profession) {
                case BUILDER -> {
                    if (eco.getBlockTags().isBuildingBlock(placed)) {
                        award(eco, player);
                    }
                }
                case FARMER -> {
                    if (placed.getBlock() instanceof CropBlock) {
                        award(eco, player);
                    }
                }
                default -> {
                    // No other job counts a placement.
                }
            }
        } catch (Exception ignored) {
            // A profession hook must never break the placement that triggered it.
        }
    }

    /**
     * A block was broken by a player.
     *
     * <p>{@code broken} is the state the block had <em>before</em> it was removed, which is the only way to
     * know a crop was mature or a stone was an ore.
     */
    public static void onBlockBroken(ServerPlayer player, BlockState broken) {
        if (player == null || broken == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            ProfessionId profession = eco.getProfessions().professionOf(player.getUUID());
            if (profession == null) return;

            switch (profession) {
                case MINER -> {
                    int worth = eco.getBlockTags().minerProgressFor(broken);
                    award(eco, player, worth);
                }
                case FARMER -> {
                    if (broken.getBlock() instanceof CropBlock crop && crop.isMaxAge(broken)) {
                        award(eco, player);
                    }
                }
                default -> {
                    // Only Miner and Farmer count a break.
                }
            }
        } catch (Exception ignored) {
            // A profession hook must never break the block-breaking path that triggered it.
        }
    }

    /**
     * A player fed an animal a breeding item.
     *
     * <p>The spec lists "feed/breeding animals" as one of Farmer's three qualifying actions, and vanilla
     * expresses both through a single call: {@code Animal#setInLove(Player)} is what a wheat right-click
     * triggers, and the {@code BreedGoal} later turns that love mode into a baby. Hooking the feed alone is
     * therefore both cheaper and stricter than hooking the goal: it counts the player's action once instead
     * of also crediting the automatic goal tick, and a player cannot farm progress by standing near animals
     * that breed on their own.
     *
     * <p>Credit goes to the feeding player, not to the animal's owner, and only on the server.
     */
    public static void onAnimalFed(ServerPlayer player, Animal animal) {
        if (player == null || animal == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            if (eco.getProfessions().professionOf(player.getUUID()) == ProfessionId.FARMER) {
                award(eco, player);
            }
        } catch (Exception ignored) {
            // A profession hook must never break the interaction that triggered it.
        }
    }
}

package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
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
        onBlockBroken(player, player != null ? player.blockPosition() : BlockPos.ZERO, broken);
    }

    /**
     * A block was broken by a player at a specific world position.
     */
    public static void onBlockBroken(ServerPlayer player, BlockPos pos, BlockState broken) {
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
                    MinerEffects.applyDoubleOreDrop(eco, player, pos, broken);
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

    /**
     * An animal bred by a player gave birth to an offspring.
     *
     * <p>Spec 47 awards 1 progress toward the Farmer 300 level-up goal for creating offspring, and spec 49
     * applies the breeding cooldown discount on parents and accelerated growth on the baby.
     */
    public static void onOffspringBred(ServerPlayer player, Animal parent1, Animal parent2, AgeableMob child) {
        if (player == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            if (eco.getProfessions().professionOf(player.getUUID()) == ProfessionId.FARMER) {
                award(eco, player);
                FarmerEffects.applyBreedingEffects(player, parent1, parent2, child);
            }
        } catch (Exception ignored) {
            // A profession hook must never break breeding
        }
    }

    /**
     * A player took a crafted or cooked item from a result slot.
     *
     * <p>Spec 50 (Khéo léo) rolls for +2 bonus items if the item is edible food.
     */
    public static void onFoodTaken(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            if (eco.getProfessions().professionOf(player.getUUID()) == ProfessionId.FARMER) {
                FarmerEffects.applyBonusFoodOutput(player, stack);
            }
        } catch (Exception ignored) {
            // A profession hook must never break taking items
        }
    }

    /**
     * A player completed a trade with a villager.
     *
     * <p>Spec 58: 50 villager trades, excluding stick trades, capped at 20 trades per unique villager.
     */
    public static void onVillagerTrade(ServerPlayer player, AbstractVillager villager, MerchantOffer offer) {
        if (player == null || villager == null || offer == null) return;
        try {
            var memoryService = com.reazip.economycraft.gossip.VillagerGossipListener.getMemoryService();
            if (memoryService != null) {
                String itemName = offer.getResult().getHoverName().getString();
                int count = offer.getResult().getCount();
                String desc = (count > 1 ? count + "x " : "") + itemName;
                int costCount = offer.getCostA().getCount();
                memoryService.recordTrade(villager.getUUID(), player.getUUID(), costCount * 10L, desc);
            }
        } catch (Throwable ignored) {
        }
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            ProfessionStore store = eco.getProfessions();
            if (store.professionOf(player.getUUID()) != ProfessionId.MERCHANT) return;

            if (MerchantEffects.isStickTrade(offer)) {
                return;
            }

            String villagerId = villager.getUUID().toString();
            if (store.recordVillagerTrade(player.getUUID(), villagerId)) {
                if (store.levelOf(player.getUUID()) == ProfessionLevel.MASTER) {
                    ProfessionEffects.applyPersistent(player);
                }
            }
        } catch (Exception ignored) {
            // A profession hook must never break trading
        }
    }

    /**
     * A player completed an auction purchase from /ah.
     *
     * <p>Spec 58: 5 purchases from /ah, counting towards the Merchant level-up (D6).
     */
    public static void onAuctionPurchase(ServerPlayer buyer) {
        if (buyer == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(buyer.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            ProfessionStore store = eco.getProfessions();
            if (store.professionOf(buyer.getUUID()) != ProfessionId.MERCHANT) return;

            if (store.recordAuctionPurchase(buyer.getUUID())) {
                if (store.levelOf(buyer.getUUID()) == ProfessionLevel.MASTER) {
                    ProfessionEffects.applyPersistent(buyer);
                }
            }
        } catch (Exception ignored) {
            // A profession hook must never break auctions
        }
    }

    /**
     * A player killed a mob.
     *
     * <p>Spec 62: 100 mobs killed by the player towards Soldier level-up.
     */
    public static void onMobKilled(ServerPlayer player, LivingEntity victim) {
        if (player == null || victim == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;

            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.SOLDIER) return;

            if (SoldierEffects.isQualifyingMob(victim)) {
                award(eco, player);
            }
        } catch (Exception ignored) {
            // A profession hook must never break entity death
        }
    }
}

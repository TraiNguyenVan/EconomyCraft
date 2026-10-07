package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.EconomyCraft;
import com.reazip.economycraft.EconomyManager;
import com.reazip.economycraft.config.ProfessionsSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BonemealSource;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Farmer profession gameplay effects (Phase 5, spec lines 46-50):
 *
 * <ul>
 *   <li>{@code Tươi tốt} (spec 48) — periodic crop growth boost in radius 24 blocks every 4 minutes.</li>
 *   <li>{@code Chăm sóc} (spec 49) — animal breeding cooldown reduction and offspring growth acceleration.</li>
 *   <li>{@code Khéo léo} (spec 50) — chance of +2 bonus items when crafting or cooking edible food.</li>
 * </ul>
 */
public final class FarmerEffects {
    public static final String CROP_BOOST_COOLDOWN_KEY = "farmer_crop_boost";

    private FarmerEffects() {}

    public static boolean isFarmer(ServerPlayer player) {
        if (player == null) return false;
        try {
            ProfessionId prof = EconomyCraft.getManager(player.level().getServer())
                    .getProfessions().professionOf(player.getUUID());
            return prof == ProfessionId.FARMER;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Checks if a block state represents an agricultural crop, sapling, or boostable plant eligible for {@code Tươi tốt}.
     */
    public static boolean isCrop(BlockState state) {
        if (state == null) return false;
        return state.is(BlockTags.CROPS)
                || state.is(BlockTags.SAPLINGS)
                || state.getBlock() instanceof CropBlock
                || state.getBlock() instanceof StemBlock
                || state.getBlock() instanceof CocoaBlock
                || state.getBlock() instanceof SaplingBlock
                || state.getBlock() instanceof SugarCaneBlock
                || state.getBlock() instanceof CactusBlock
                || state.getBlock() instanceof NetherWartBlock
                || state.getBlock() instanceof SweetBerryBushBlock
                || state.getBlock() instanceof BambooStalkBlock
                || state.getBlock() instanceof BambooSaplingBlock;
    }

    /**
     * Checks if a crop/plant is eligible to be boosted.
     */
    public static boolean canBoostCrop(ServerLevel world, BlockPos pos, BlockState state) {
        if (state == null || world == null || pos == null) return false;
        if (state.getBlock() instanceof BonemealableBlock bonemealable) {
            return bonemealable.isValidBonemealTarget(world, pos, state, BonemealSource.INTERACTION);
        }
        if (state.getBlock() instanceof SugarCaneBlock) {
            if (!world.isEmptyBlock(pos.above())) return false;
            int height = 1;
            while (world.getBlockState(pos.below(height)).is(state.getBlock())) {
                height++;
            }
            return height < 3;
        }
        if (state.getBlock() instanceof CactusBlock) {
            if (!world.isEmptyBlock(pos.above())) return false;
            int height = 1;
            while (world.getBlockState(pos.below(height)).is(state.getBlock())) {
                height++;
            }
            return height < 3 && state.getBlock().defaultBlockState().canSurvive(world, pos.above());
        }
        if (state.getBlock() instanceof NetherWartBlock) {
            return state.hasProperty(NetherWartBlock.AGE)
                    && state.getValue(NetherWartBlock.AGE) < NetherWartBlock.MAX_AGE;
        }
        return false;
    }

    /**
     * Applies growth to the crop/plant.
     */
    public static void performCropBoost(ServerLevel world, BlockPos pos, BlockState state) {
        if (world == null || pos == null || state == null) return;
        if (state.getBlock() instanceof BonemealableBlock bonemealable) {
            bonemealable.performBonemeal(world, world.getRandom(), pos, state, BonemealSource.INTERACTION);
            world.levelEvent(1505, pos, 15);
            return;
        }
        if (state.getBlock() instanceof SugarCaneBlock) {
            BlockPos targetPos = pos.above();
            world.setBlockAndUpdate(targetPos, state.getBlock().defaultBlockState());
            world.setBlock(pos, state.setValue(SugarCaneBlock.AGE, 0), 4);
            world.levelEvent(1505, targetPos, 15);
            return;
        }
        if (state.getBlock() instanceof CactusBlock) {
            BlockPos targetPos = pos.above();
            world.setBlockAndUpdate(targetPos, state.getBlock().defaultBlockState());
            world.setBlock(pos, state.setValue(CactusBlock.AGE, 0), 4);
            world.levelEvent(1505, targetPos, 15);
            return;
        }
        if (state.getBlock() instanceof NetherWartBlock) {
            int age = state.getValue(NetherWartBlock.AGE);
            world.setBlock(pos, state.setValue(NetherWartBlock.AGE, age + 1), 2);
            world.levelEvent(1505, pos, 15);
        }
    }

    /**
     * {@code Tươi tốt} (spec 48): periodically boosts crop growth in a 24-block cylinder around online Farmers.
     */
    public static void tickCropBoost(EconomyManager eco, ServerPlayer player) {
        if (player == null || eco == null) return;
        if (!EconomyConfig.get().professions.enabled) return;

        try {
            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.FARMER) return;
            if (!eco.getCooldowns().isReady(player.getUUID(), CROP_BOOST_COOLDOWN_KEY)) return;

            ProfessionsSection.FarmerSettings settings = EconomyConfig.get().professions.farmer;
            eco.getCooldowns().startMinutes(player.getUUID(), CROP_BOOST_COOLDOWN_KEY,
                    settings.cropBoostCooldownMinutes);

            ProfessionLevel level = eco.getProfessions().baseLevelOf(player.getUUID());
            double rustMultiplier = ProfessionEffects.resolveMultiplier(player, ProfessionId.FARMER);
            double chance = effectiveCropBoostChance(settings, level, rustMultiplier);
            if (chance <= 0.0D) return;

            ServerLevel world = player.level();
            BlockPos center = player.blockPosition();
            int radius = settings.cropBoostRadiusBlocks;
            int radiusSq = radius * radius;

            BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz > radiusSq) continue;
                    for (int dy = -6; dy <= 6; dy++) {
                        mpos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        BlockState state = world.getBlockState(mpos);
                        if (isCrop(state) && canBoostCrop(world, mpos, state)) {
                            if (world.getRandom().nextDouble() < chance) {
                                performCropBoost(world, mpos.immutable(), state);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Crop boost must never break the server tick
        }
    }

    /**
     * {@code Chăm sóc} (spec 49): reduces parent breeding cooldown and accelerates offspring growth.
     */
    public static void applyBreedingEffects(ServerPlayer player, Animal parent1, Animal parent2, AgeableMob child) {
        if (player == null) return;
        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;
            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.FARMER) return;

            ProfessionsSection.FarmerSettings settings = EconomyConfig.get().professions.farmer;
            ProfessionLevel level = eco.getProfessions().baseLevelOf(player.getUUID());
            double rustMultiplier = ProfessionEffects.resolveMultiplier(player, ProfessionId.FARMER);

            // 1. Breeding cooldown discount on parents (spec 49)
            double discount = effectiveBreedingCooldownDiscount(settings, level, rustMultiplier);
            if (discount > 0.0D) {
                int newCooldown = (int) Math.round(6000 * (1.0D - discount));
                if (parent1 != null) parent1.setAge(newCooldown);
                if (parent2 != null) parent2.setAge(newCooldown);
            }

            // 2. Offspring growth acceleration (spec 49)
            if (child != null && child.getAge() < 0) {
                double growthMultiplier = effectiveBabyGrowthMultiplier(settings, level, rustMultiplier);
                if (growthMultiplier > 1.0D) {
                    int newAge = (int) Math.round(child.getAge() / growthMultiplier);
                    child.setAge(newAge);
                }
            }
        } catch (Exception ignored) {
            // Breeding effect must never crash breeding
        }
    }

    /**
     * {@code Khéo léo} (spec 50): rolls for +2 bonus items when crafting or cooking edible food.
     */
    public static void applyBonusFoodOutput(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) return;
        if (!stack.has(DataComponents.FOOD)) return;

        try {
            EconomyManager eco = EconomyCraft.getManager(player.level().getServer());
            if (!EconomyConfig.get().professions.enabled) return;
            if (eco.getProfessions().professionOf(player.getUUID()) != ProfessionId.FARMER) return;

            ProfessionsSection.FarmerSettings settings = EconomyConfig.get().professions.farmer;
            ProfessionLevel level = eco.getProfessions().baseLevelOf(player.getUUID());
            double rustMultiplier = ProfessionEffects.resolveMultiplier(player, ProfessionId.FARMER);

            double chance = effectiveBonusOutputChance(settings, level, rustMultiplier);
            if (chance <= 0.0D) return;

            if (player.getRandom().nextDouble() < chance) {
                ItemStack bonus = stack.copyWithCount(2);
                if (!player.getInventory().add(bonus)) {
                    player.spawnAtLocation(player.level(), bonus);
                }
                player.sendSystemMessage(
                        Component.literal("☘ ")
                                .withColor(0x55FF55)
                                .append(Component.literal("Khéo léo: +2 ").withColor(0xFFFFFF))
                                .append(stack.getHoverName()),
                        true
                );
            }
        } catch (Exception ignored) {
            // Bonus food output must never break item pickup / crafting
        }
    }

    // --- Pure arithmetic helpers for testing ---

    public static double effectiveCropBoostChance(ProfessionsSection.FarmerSettings settings, ProfessionLevel level, double rustMultiplier) {
        if (settings == null || level == null) return 0.0D;
        double base = switch (level) {
            case MASTER -> settings.cropBoostChanceMaster;
            case APPRENTICE -> settings.cropBoostChanceApprentice;
            case RUSTED -> 0.0D;
        };
        return base * rustMultiplier;
    }

    public static double effectiveBreedingCooldownDiscount(ProfessionsSection.FarmerSettings settings, ProfessionLevel level, double rustMultiplier) {
        if (settings == null || level == null) return 0.0D;
        double base = switch (level) {
            case MASTER -> settings.breedingCooldownFactorMaster;
            case APPRENTICE -> settings.breedingCooldownFactorApprentice;
            case RUSTED -> 0.0D;
        };
        return base * rustMultiplier;
    }

    public static double effectiveBabyGrowthMultiplier(ProfessionsSection.FarmerSettings settings, ProfessionLevel level, double rustMultiplier) {
        if (settings == null || level == null) return 1.0D;
        double base = switch (level) {
            case MASTER -> settings.babyGrowthFactorMaster;
            case APPRENTICE -> settings.babyGrowthFactorApprentice;
            case RUSTED -> 1.0D;
        };
        if (base <= 1.0D) return 1.0D;
        return 1.0D + (base - 1.0D) * rustMultiplier;
    }

    public static double effectiveBonusOutputChance(ProfessionsSection.FarmerSettings settings, ProfessionLevel level, double rustMultiplier) {
        if (settings == null || level == null) return 0.0D;
        double base = switch (level) {
            case MASTER -> settings.bonusOutputChanceMaster;
            case APPRENTICE -> settings.bonusOutputChanceApprentice;
            case RUSTED -> 0.0D;
        };
        return base * rustMultiplier;
    }
}

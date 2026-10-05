package com.reazip.economycraft.config;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * The {@code professions} section: the five jobs, what each one counts towards its level-up, and the numbers
 * every one of their effects is read through.
 *
 * <p>Nothing here is wired to gameplay yet. Phase 2 is the data and save layer; the jobs arrive in Phases 4–8.
 *
 * <p>Each job's effect has two values, one per level, and no job reads its own numbers any other way — the
 * framework in Phase 4 resolves them through a single gate so the rust rule cannot be applied to four jobs
 * and forgotten on the fifth.
 */
public class ProfessionsSection {

    @SerializedName("enabled")
    public boolean enabled = true;

    /**
     * The Profession half of D17's two independent 30-hour timers. Changing party never disturbs it, and
     * changing job never disturbs the party clock. {@code 0} disables the lockout.
     */
    @SerializedName("selection_lockout_hours")
    public int selectionLockoutHours = 30;

    /** {@code Lụt nghề}: minutes of accumulated <em>online</em> time a returning Master spends at half effect. */
    @SerializedName("rust_online_minutes")
    public int rustOnlineMinutes = 45;

    /** What "half effect" means, as a multiplier on whatever the job's numbers are. */
    @SerializedName("rust_effect_factor")
    public double rustEffectFactor = 0.5;

    @SerializedName("builder")
    public BuilderSettings builder = new BuilderSettings();

    @SerializedName("farmer")
    public FarmerSettings farmer = new FarmerSettings();

    @SerializedName("miner")
    public MinerSettings miner = new MinerSettings();

    @SerializedName("merchant")
    public MerchantSettings merchant = new MerchantSettings();

    @SerializedName("soldier")
    public SoldierSettings soldier = new SoldierSettings();

    public void clamp() {
        selectionLockoutHours = ConfigClamp.nonNegative("professions.selection_lockout_hours", selectionLockoutHours);
        rustOnlineMinutes = ConfigClamp.nonNegative("professions.rust_online_minutes", rustOnlineMinutes);
        rustEffectFactor = ConfigClamp.multiplier("professions.rust_effect_factor", rustEffectFactor);
        builder.clamp();
        farmer.clamp();
        miner.clamp();
        merchant.clamp();
        soldier.clamp();
    }

    /** Builder: counts blocks placed, grants Haste while mining, and — per D11 — cannot actually grant reach. */
    public static class BuilderSettings extends ProfessionSettings {
        @SerializedName("level_up_count")
        public int levelUpCount = 1000;

        /**
         * {@code Thành thạo}, added to vanilla's {@code player.block_interaction_range} (default 4.5).
         *
         * <p>One {@code AttributeModifier} per level, added with {@code ADD_VALUE}, so Apprentice reaches 5.5
         * and Master 6.5 — and the same value widens the client's raycast and the server's own range check,
         * because both read that attribute (D11).
         */
        @SerializedName("reach_bonus_apprentice_blocks")
        public double reachBonusApprenticeBlocks = 1.0;

        @SerializedName("reach_bonus_master_blocks")
        public double reachBonusMasterBlocks = 2.0;

        /** {@code Sửa lỗi}. Haste I at Master — the spec gates it on reaching Master, not on the level. */
        @SerializedName("haste_level")
        public int hasteLevel = 1;

        /**
         * D20: the Haste is a <em>conditional</em> effect, not something the player carries. It is (re)applied
         * every tick while they are breaking a block from this job's trigger set, and removed on the first tick
         * that they are not — so a Builder breaking a chest gets no Haste, and a Miner walking around with
         * Haste II is not possible.
         *
         * <p>That makes this key a refresh window rather than a duration: it only has to cover the gap between
         * two mining packets, so that breaking consecutive qualifying blocks does not flicker. It is not how long
         * the effect can sit on someone — clearing it is unconditional.
         */
        @SerializedName("haste_refresh_seconds")
        public int hasteRefreshSeconds = 1;

        /**
         * The Haste trigger set: stone, cobblestone and dirt.
         *
         * <p>The spec also names "every building block", and that half is <em>not</em> repeated here:
         * {@code building_blocks} is already the canonical list, and duplicating 33 entries into a second key
         * guarantees the two drift apart. {@code BlockTags} unions this list with {@code building_blocks}.
         */
        @SerializedName("haste_trigger_blocks")
        public List<String> hasteTriggerBlocks = List.of("minecraft:stone", "minecraft:cobblestone", "minecraft:dirt");

        /**
         * The spec's building-block list verbatim, as data. Entries are either a tag ({@code #minecraft:logs})
         * or a block id, resolved by {@code profession/BlockTags} — never a hard-coded literal list, so an admin
         * can extend it without a new build.
         */
        @SerializedName("building_blocks")
        public List<String> buildingBlocks = List.of(
                "#minecraft:logs", "#minecraft:planks", "#minecraft:stairs", "#minecraft:slabs", "#minecraft:walls",
                "#minecraft:fences", "#minecraft:fence_gates", "#minecraft:terracotta", "#minecraft:stone_bricks",
                "minecraft:scaffolding", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
                "minecraft:mud", "minecraft:glass", "minecraft:tinted_glass", "#c:glass_blocks", "#c:glass_panes",
                "minecraft:smooth_stone", "minecraft:bricks", "minecraft:mud_bricks", "minecraft:packed_mud",
                "minecraft:prismarine", "minecraft:dark_prismarine", "minecraft:prismarine_bricks",
                "minecraft:purpur_block", "minecraft:purpur_pillar", "minecraft:end_stone_bricks",
                "minecraft:quartz_block", "minecraft:smooth_quartz", "minecraft:chiseled_quartz_block",
                "minecraft:quartz_pillar", "minecraft:quartz_bricks");

        public void clamp() {
            clampTag("professions.builder");
            levelUpCount = ConfigClamp.nonNegative("professions.builder.level_up_count", levelUpCount);
            reachBonusApprenticeBlocks = ConfigClamp.nonNegative("professions.builder.reach_bonus_apprentice_blocks", reachBonusApprenticeBlocks);
            reachBonusMasterBlocks = ConfigClamp.nonNegative("professions.builder.reach_bonus_master_blocks", reachBonusMasterBlocks);
            hasteLevel = ConfigClamp.effectLevel("professions.builder.haste_level", hasteLevel);
            hasteRefreshSeconds = ConfigClamp.nonNegative("professions.builder.haste_refresh_seconds", hasteRefreshSeconds);
            hasteTriggerBlocks = ConfigClamp.cleanList("professions.builder.haste_trigger_blocks", hasteTriggerBlocks);
            buildingBlocks = ConfigClamp.cleanList("professions.builder.building_blocks", buildingBlocks);
        }

        @Override
        protected String defaultIcon() {
            return "⚒";
        }
    }

    /** Farmer: crops, animals, and a small chance of a second helping. */
    public static class FarmerSettings extends ProfessionSettings {
        @SerializedName("level_up_count")
        public int levelUpCount = 300;

        /** {@code Tươi tốt}: the scan radius, in blocks, around the player. */
        @SerializedName("crop_boost_radius_blocks")
        public int cropBoostRadiusBlocks = 24;

        @SerializedName("crop_boost_cooldown_minutes")
        public int cropBoostCooldownMinutes = 4;

        @SerializedName("crop_boost_chance_apprentice")
        public double cropBoostChanceApprentice = 0.10;

        @SerializedName("crop_boost_chance_master")
        public double cropBoostChanceMaster = 0.20;

        /** {@code Chăm sóc}: how much of the breeding cooldown is removed. */
        @SerializedName("breeding_cooldown_factor_apprentice")
        public double breedingCooldownFactorApprentice = 0.10;

        @SerializedName("breeding_cooldown_factor_master")
        public double breedingCooldownFactorMaster = 0.20;

        /** {@code Chăm sóc}: how much faster offspring grow. */
        @SerializedName("baby_growth_factor_apprentice")
        public double babyGrowthFactorApprentice = 1.15;

        @SerializedName("baby_growth_factor_master")
        public double babyGrowthFactorMaster = 1.30;

        /** {@code Khéo léo}: the chance of extra items from crafting or smelting an edible result. */
        @SerializedName("bonus_output_chance_apprentice")
        public double bonusOutputChanceApprentice = 0.01;

        @SerializedName("bonus_output_chance_master")
        public double bonusOutputChanceMaster = 0.05;

        public void clamp() {
            clampTag("professions.farmer");
            levelUpCount = ConfigClamp.nonNegative("professions.farmer.level_up_count", levelUpCount);
            cropBoostRadiusBlocks = ConfigClamp.nonNegative("professions.farmer.crop_boost_radius_blocks", cropBoostRadiusBlocks);
            cropBoostCooldownMinutes = ConfigClamp.nonNegative("professions.farmer.crop_boost_cooldown_minutes", cropBoostCooldownMinutes);
            cropBoostChanceApprentice = ConfigClamp.chance("professions.farmer.crop_boost_chance_apprentice", cropBoostChanceApprentice);
            cropBoostChanceMaster = ConfigClamp.chance("professions.farmer.crop_boost_chance_master", cropBoostChanceMaster);
            breedingCooldownFactorApprentice = ConfigClamp.chance("professions.farmer.breeding_cooldown_factor_apprentice", breedingCooldownFactorApprentice);
            breedingCooldownFactorMaster = ConfigClamp.chance("professions.farmer.breeding_cooldown_factor_master", breedingCooldownFactorMaster);
            babyGrowthFactorApprentice = ConfigClamp.multiplier("professions.farmer.baby_growth_factor_apprentice", babyGrowthFactorApprentice);
            babyGrowthFactorMaster = ConfigClamp.multiplier("professions.farmer.baby_growth_factor_master", babyGrowthFactorMaster);
            bonusOutputChanceApprentice = ConfigClamp.chance("professions.farmer.bonus_output_chance_apprentice", bonusOutputChanceApprentice);
            bonusOutputChanceMaster = ConfigClamp.chance("professions.farmer.bonus_output_chance_master", bonusOutputChanceMaster);
        }

        @Override
        protected String defaultIcon() {
            return "☘";
        }
    }

    /** Miner: counts ore, and gets Haste, doubled drops and lava protection. */
    public static class MinerSettings extends ProfessionSettings {
        @SerializedName("level_up_count")
        public int levelUpCount = 270;

        /** The ore set, as a tag so modded ores count without a config change. */
        @SerializedName("ore_tags")
        public List<String> oreTags = List.of("#minecraft:ores");

        /** The spec counts each diamond and each gold as two. As ids, because these are single blocks. */
        @SerializedName("double_value_ores")
        public List<String> doubleValueOres = List.of(
                "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
                "minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore");

        /** {@code Lanh lợi}: stone, deepslate, tuff, netherack and any ore. */
        @SerializedName("haste_trigger_blocks")
        public List<String> hasteTriggerBlocks = List.of(
                "minecraft:stone", "minecraft:deepslate", "minecraft:tuff", "minecraft:netherrack", "#minecraft:ores");

        /** {@code Lanh lợi}: Haste II — the spec gives the Miner two amplifier levels, not one. */
        @SerializedName("haste_level")
        public int hasteLevel = 2;

        /**
         * D20: the Haste is a <em>conditional</em> effect, not something the player carries. It is (re)applied
         * every tick while they are breaking a block from this job's trigger set, and removed on the first tick
         * that they are not — so a Builder breaking a chest gets no Haste, and a Miner walking around with
         * Haste II is not possible.
         *
         * <p>That makes this key a refresh window rather than a duration: it only has to cover the gap between
         * two mining packets, so that breaking consecutive qualifying blocks does not flicker. It is not how long
         * the effect can sit on someone — clearing it is unconditional.
         */
        @SerializedName("haste_refresh_seconds")
        public int hasteRefreshSeconds = 1;

        @SerializedName("double_drop_chance_apprentice")
        public double doubleDropChanceApprentice = 0.05;

        @SerializedName("double_drop_chance_master")
        public double doubleDropChanceMaster = 0.15;

        /** {@code Bảo hộ lao động}: Regeneration II for 4 s on lava contact, on a 5-minute cooldown. */
        @SerializedName("lava_regeneration_level")
        public int lavaRegenerationLevel = 2;

        @SerializedName("lava_regeneration_seconds")
        public int lavaRegenerationSeconds = 4;

        @SerializedName("lava_cooldown_minutes")
        public int lavaCooldownMinutes = 5;

        public void clamp() {
            clampTag("professions.miner");
            levelUpCount = ConfigClamp.nonNegative("professions.miner.level_up_count", levelUpCount);
            oreTags = ConfigClamp.cleanList("professions.miner.ore_tags", oreTags);
            doubleValueOres = ConfigClamp.cleanList("professions.miner.double_value_ores", doubleValueOres);
            hasteLevel = ConfigClamp.effectLevel("professions.miner.haste_level", hasteLevel);
            hasteRefreshSeconds = ConfigClamp.nonNegative("professions.miner.haste_refresh_seconds", hasteRefreshSeconds);
            hasteTriggerBlocks = ConfigClamp.cleanList("professions.miner.haste_trigger_blocks", hasteTriggerBlocks);
            doubleDropChanceApprentice = ConfigClamp.chance("professions.miner.double_drop_chance_apprentice", doubleDropChanceApprentice);
            doubleDropChanceMaster = ConfigClamp.chance("professions.miner.double_drop_chance_master", doubleDropChanceMaster);
            lavaRegenerationLevel = ConfigClamp.effectLevel("professions.miner.lava_regeneration_level", lavaRegenerationLevel);
            lavaRegenerationSeconds = ConfigClamp.nonNegative("professions.miner.lava_regeneration_seconds", lavaRegenerationSeconds);
            lavaCooldownMinutes = ConfigClamp.nonNegative("professions.miner.lava_cooldown_minutes", lavaCooldownMinutes);
        }

        @Override
        protected String defaultIcon() {
            return "⛏";
        }
    }

    /** Merchant: counts villager trades and auction buys, and pays everything less. */
    public static class MerchantSettings extends ProfessionSettings {
        @SerializedName("villager_trade_count")
        public int villagerTradeCount = 50;

        /** No single villager may contribute more than this to the level-up count. */
        @SerializedName("max_trades_per_villager")
        public int maxTradesPerVillager = 20;

        @SerializedName("auction_purchase_count")
        public int auctionPurchaseCount = 5;

        /** {@code Lưỡi không xương}: the cost factor, applied through the tax resolver — never a second mechanism. */
        @SerializedName("cost_factor_apprentice")
        public double costFactorApprentice = 0.05;

        @SerializedName("cost_factor_master")
        public double costFactorMaster = 0.15;

        public void clamp() {
            clampTag("professions.merchant");
            villagerTradeCount = ConfigClamp.nonNegative("professions.merchant.villager_trade_count", villagerTradeCount);
            maxTradesPerVillager = ConfigClamp.nonNegative("professions.merchant.max_trades_per_villager", maxTradesPerVillager);
            auctionPurchaseCount = ConfigClamp.nonNegative("professions.merchant.auction_purchase_count", auctionPurchaseCount);
            costFactorApprentice = ConfigClamp.percentage("professions.merchant.cost_factor_apprentice", costFactorApprentice);
            costFactorMaster = ConfigClamp.percentage("professions.merchant.cost_factor_master", costFactorMaster);
        }

        @Override
        protected String defaultIcon() {
            return "⚖";
        }
    }

    /** Soldier: counts kills, shifts damage both ways, and halves a debuff once. */
    public static class SoldierSettings extends ProfessionSettings {
        @SerializedName("kill_count")
        public int killCount = 100;

        /** {@code Sắt được tôi thế đấy}. Multipliers, not percentages: damage taken falls below 1. */
        @SerializedName("damage_taken_factor_apprentice")
        public double damageTakenFactorApprentice = 0.95;

        @SerializedName("damage_taken_factor_master")
        public double damageTakenFactorMaster = 0.85;

        @SerializedName("damage_dealt_factor_apprentice")
        public double damageDealtFactorApprentice = 1.05;

        @SerializedName("damage_dealt_factor_master")
        public double damageDealtFactorMaster = 1.15;

        /** {@code Andrenaline}: effects are only halved while inside this window of the first debuff. */
        @SerializedName("adrenaline_window_seconds")
        public int adrenalineWindowSeconds = 4;

        @SerializedName("adrenaline_cooldown_minutes")
        public int adrenalineCooldownMinutes = 5;

        @SerializedName("adrenaline_duration_factor")
        public double adrenalineDurationFactor = 0.5;

        public void clamp() {
            clampTag("professions.soldier");
            killCount = ConfigClamp.nonNegative("professions.soldier.kill_count", killCount);
            damageTakenFactorApprentice = ConfigClamp.multiplier("professions.soldier.damage_taken_factor_apprentice", damageTakenFactorApprentice);
            damageTakenFactorMaster = ConfigClamp.multiplier("professions.soldier.damage_taken_factor_master", damageTakenFactorMaster);
            damageDealtFactorApprentice = ConfigClamp.multiplier("professions.soldier.damage_dealt_factor_apprentice", damageDealtFactorApprentice);
            damageDealtFactorMaster = ConfigClamp.multiplier("professions.soldier.damage_dealt_factor_master", damageDealtFactorMaster);
            adrenalineWindowSeconds = ConfigClamp.nonNegative("professions.soldier.adrenaline_window_seconds", adrenalineWindowSeconds);
            adrenalineCooldownMinutes = ConfigClamp.nonNegative("professions.soldier.adrenaline_cooldown_minutes", adrenalineCooldownMinutes);
            adrenalineDurationFactor = ConfigClamp.multiplier("professions.soldier.adrenaline_duration_factor", adrenalineDurationFactor);
        }

        @Override
        protected String defaultIcon() {
            return "⚔";
        }
    }
}
package com.reazip.economycraft.config;

import com.reazip.economycraft.faction.ContainerLockMode;
import com.google.gson.annotations.SerializedName;

/**
 * The {@code factions} section: who the parties are, what they look like, and everything the party rules
 * will eventually need in order to be tuned without a code change.
 *
 * <p><strong>Nothing here is wired to gameplay yet.</strong> Phase 2 is the data and save layer; the levies
 * arrive in Phase 9 and the claim-dependent effects in Phase 10. The numbers are the spec's defaults, so a
 * server that enables the feature later behaves as the spec describes.
 *
 * <p>{@link #enabled} follows D4: the wealth tax is opt-in because it is not the spec's feature, whereas the
 * parties <em>are</em>, so they are gated by a single flag an admin can turn off rather than shipped inert.
 */
public class FactionsSection {

    @SerializedName("enabled")
    public boolean enabled = true;

    /**
     * How long a party choice holds, in hours of real time. D17: this is the Party half of the 30-hour
     * lockout and it is independent of the Profession half in {@code professions.selection_lockout_hours}.
     * {@code 0} disables the lockout.
     */
    @SerializedName("selection_lockout_hours")
    public int selectionLockoutHours = 30;

    /**
     * The spec's "45 minutes online" cadence, in minutes of <em>accumulated online</em> time — not wall clock.
     * Backs both the Communism party fee and the income tax that follows it, which is why they share a value.
     */
    @SerializedName("levy_interval_minutes")
    public int levyIntervalMinutes = 45;

    @SerializedName("communism")
    public CommunismSettings communism = new CommunismSettings();

    @SerializedName("capitalism")
    public CapitalismSettings capitalism = new CapitalismSettings();

    @SerializedName("monarchy")
    public MonarchySettings monarchy = new MonarchySettings();

    @SerializedName("anarchism")
    public AnarchismSettings anarchism = new AnarchismSettings();

    public void clamp() {
        selectionLockoutHours = ConfigClamp.nonNegative("factions.selection_lockout_hours", selectionLockoutHours);
        levyIntervalMinutes = ConfigClamp.nonNegative("factions.levy_interval_minutes", levyIntervalMinutes);
        communism.clamp();
        capitalism.clamp();
        monarchy.clamp();
        anarchism.clamp();
    }

    /** Communism: a party fee and an anti-speculation income tax on a 45-minute online cadence. */
    public static class CommunismSettings extends TagSettings {
        /** The {@code Đảng phí} charged per interval. Burned — no recipient anywhere in this package. */
        @SerializedName("party_fee")
        public long partyFee = 10L;

        @SerializedName("income_tax_tier1_threshold")
        public long incomeTaxTier1Threshold = 10_000L;

        @SerializedName("income_tax_tier1_rate")
        public double incomeTaxTier1Rate = 0.005;

        @SerializedName("income_tax_tier2_threshold")
        public long incomeTaxTier2Threshold = 15_000L;

        @SerializedName("income_tax_tier2_rate")
        public double incomeTaxTier2Rate = 0.0075;

        @SerializedName("income_tax_tier3_threshold")
        public long incomeTaxTier3Threshold = 22_000L;

        @SerializedName("income_tax_tier3_rate")
        public double incomeTaxTier3Rate = 0.0125;

        /** {@code Đầu tư công}: how often a toll is paid without tax. The toll owner still receives the fee. */
        @SerializedName("toll_tax_exempt_chance")
        public double tollTaxExemptChance = 0.5;

        /** {@code Cộng đồng}: what a locked container refuses. See {@link ContainerLockMode}. */
        @SerializedName("container_lock_mode")
        public String containerLockMode = ContainerLockMode.PARTY_ONLY.name();

        public void clamp() {
            clampTag("factions.communism");
            partyFee = ConfigClamp.nonNegative("factions.communism.party_fee", partyFee);
            incomeTaxTier1Threshold = ConfigClamp.nonNegative("factions.communism.income_tax_tier1_threshold", incomeTaxTier1Threshold);
            incomeTaxTier1Rate = ConfigClamp.percentage("factions.communism.income_tax_tier1_rate", incomeTaxTier1Rate);
            incomeTaxTier2Threshold = ConfigClamp.nonNegative("factions.communism.income_tax_tier2_threshold", incomeTaxTier2Threshold);
            incomeTaxTier2Rate = ConfigClamp.percentage("factions.communism.income_tax_tier2_rate", incomeTaxTier2Rate);
            incomeTaxTier3Threshold = ConfigClamp.nonNegative("factions.communism.income_tax_tier3_threshold", incomeTaxTier3Threshold);
            incomeTaxTier3Rate = ConfigClamp.percentage("factions.communism.income_tax_tier3_rate", incomeTaxTier3Rate);
            tollTaxExemptChance = ConfigClamp.chance("factions.communism.toll_tax_exempt_chance", tollTaxExemptChance);
            containerLockMode = ConfigClamp.choice("factions.communism.container_lock_mode",
                    containerLockMode, ContainerLockMode.PARTY_ONLY, ContainerLockMode.values()).name();
        }

        @Override
        protected String defaultIcon() {
            return "☭";
        }
    }

    /** Capitalism: a daily rate that scales with how much wealth the party holds, plus a heavier toll tax. */
    public static class CapitalismSettings extends TagSettings {
        @SerializedName("daily_tax_rate")
        public double dailyTaxRate = 0.05;

        /**
         * D14: the global half of the rate. Read from the existing read-only inflation signal rather than a
         * fourth independent measure, so "who counts as active" stays shared with dynamic pricing.
         */
        @SerializedName("use_global_inflation")
        public boolean useGlobalInflation = true;

        /** The party share at which the concentration multiplier is exactly 1.0. */
        @SerializedName("concentration_reference_share")
        public double concentrationReferenceShare = 0.15;

        /** The single difficulty dial: above 1 punishes concentration harder, below 1 is gentle. */
        @SerializedName("concentration_elasticity")
        public double concentrationElasticity = 1.0;

        @SerializedName("concentration_min_multiplier")
        public double concentrationMinMultiplier = 0.0;

        @SerializedName("concentration_max_multiplier")
        public double concentrationMaxMultiplier = 5.0;

        /** D14's griefing brake: how far the computed rate may move in one day. */
        @SerializedName("max_rate_change_per_day")
        public double maxRateChangePerDay = 0.25;

        /** {@code Nhà nước tư bản}: "+25 %" read as 1.25x on the toll <em>tax amount</em>, not +25 points. */
        @SerializedName("toll_tax_multiplier")
        public double tollTaxMultiplier = 1.25;

        public void clamp() {
            clampTag("factions.capitalism");
            dailyTaxRate = ConfigClamp.percentage("factions.capitalism.daily_tax_rate", dailyTaxRate);
            concentrationReferenceShare = ConfigClamp.percentage("factions.capitalism.concentration_reference_share", concentrationReferenceShare);
            concentrationElasticity = ConfigClamp.exponent("factions.capitalism.concentration_elasticity", concentrationElasticity);
            concentrationMinMultiplier = ConfigClamp.multiplier("factions.capitalism.concentration_min_multiplier", concentrationMinMultiplier);
            concentrationMaxMultiplier = ConfigClamp.multiplier("factions.capitalism.concentration_max_multiplier", concentrationMaxMultiplier);
            if (concentrationMaxMultiplier < concentrationMinMultiplier) {
                concentrationMaxMultiplier = concentrationMinMultiplier;
            }
            maxRateChangePerDay = ConfigClamp.percentage("factions.capitalism.max_rate_change_per_day", maxRateChangePerDay);
            tollTaxMultiplier = ConfigClamp.multiplier("factions.capitalism.toll_tax_multiplier", tollTaxMultiplier);
        }

        @Override
        protected String defaultIcon() {
            return "$";
        }
    }

    /** Monarchy: halved claim cost and damage in your own claim, paid for with a daily tax and an import tax. */
    public static class MonarchySettings extends TagSettings {
        /**
         * Assumption, not the spec: the spec gives Monarchy's {@code Cống nạp} as "an amount equal to the daily
         * tax", but never states Monarchy's own daily rate. Defaults to Capitalism's 5 % so the two parties'
         * debuffs are comparable, and is a config key precisely because the number is a guess.
         */
        @SerializedName("daily_tax_rate")
        public double dailyTaxRate = 0.05;

        /** {@code Cống nạp} is this multiple of the daily tax amount, and is a pure burn — the king is flavour. */
        @SerializedName("corruption_multiplier")
        public double corruptionMultiplier = 1.0;

        /** {@code Tự trị}: claim cost factor. Applied in ShopGuard's pricing, never in its config. */
        @SerializedName("claim_cost_multiplier")
        public double claimCostMultiplier = 0.5;

        /** {@code Phép vua}: damage and damage resistance factor while standing in your own claim. */
        @SerializedName("own_claim_damage_multiplier")
        public double ownClaimDamageMultiplier = 1.15;

        /** {@code Nhập khẩu}: how often an import is taxed again… */
        @SerializedName("import_tax_chance")
        public double importTaxChance = 0.5;

        /** …and by how much, as a fraction of the item's own tax. */
        @SerializedName("import_tax_factor")
        public double importTaxFactor = 0.5;

        public void clamp() {
            clampTag("factions.monarchy");
            dailyTaxRate = ConfigClamp.percentage("factions.monarchy.daily_tax_rate", dailyTaxRate);
            corruptionMultiplier = ConfigClamp.multiplier("factions.monarchy.corruption_multiplier", corruptionMultiplier);
            claimCostMultiplier = ConfigClamp.multiplier("factions.monarchy.claim_cost_multiplier", claimCostMultiplier);
            ownClaimDamageMultiplier = ConfigClamp.multiplier("factions.monarchy.own_claim_damage_multiplier", ownClaimDamageMultiplier);
            importTaxChance = ConfigClamp.chance("factions.monarchy.import_tax_chance", importTaxChance);
            importTaxFactor = ConfigClamp.percentage("factions.monarchy.import_tax_factor", importTaxFactor);
        }

        @Override
        protected String defaultIcon() {
            return "♔";
        }
    }

    /**
     * Anarchism: exempt from tax entirely, and faster on land nobody has claimed.
     *
     * <p>There is deliberately no rate key here. "Pays no tax of any kind" is a rule, not a number, and
     * turning it into a {@code 0.0} would suggest an admin can dial it back to something.
     */
    public static class AnarchismSettings extends TagSettings {
        /** {@code Thoải mái}: movement speed factor on unclaimed land. */
        @SerializedName("unclaimed_speed_multiplier")
        public double unclaimedSpeedMultiplier = 1.15;

        /** {@code Thoải mái}: horse speed factor on unclaimed land. Needs its own hook; horses ignore movement speed. */
        @SerializedName("unclaimed_horse_speed_multiplier")
        public double unclaimedHorseSpeedMultiplier = 1.15;

        public void clamp() {
            clampTag("factions.anarchism");
            unclaimedSpeedMultiplier = ConfigClamp.multiplier("factions.anarchism.unclaimed_speed_multiplier", unclaimedSpeedMultiplier);
            unclaimedHorseSpeedMultiplier = ConfigClamp.multiplier("factions.anarchism.unclaimed_horse_speed_multiplier", unclaimedHorseSpeedMultiplier);
        }

        @Override
        protected String defaultIcon() {
            return "Ⓐ";
        }
    }
}
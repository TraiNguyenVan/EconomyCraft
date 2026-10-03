package com.reazip.economycraft.api.v1;

/**
 * The four party ids, as the stable lowercase strings the rest of the ecosystem compares against.
 *
 * <p>They exist because a party name crosses process and repository boundaries — a save file, a command
 * argument, and now ShopGuard's claim pricing — and a consumer in another repository cannot import
 * EconomyCraft's {@code FactionId} enum (it lives in {@code common}, which is not on a consumer's compile
 * classpath). Passing strings across that boundary is unavoidable; what is avoidable is the typo. A
 * consumer writes {@code FactionIds.MONARCHY} and gets a compile error when the party is renamed, instead of
 * a discount that silently never applies because it compared against {@code "Monarchy"}.
 *
 * <p>The ids are lowercase and match the {@code /eco party} subcommand literals exactly, so one string works
 * as a command argument, a config value and an API comparison.
 */
public final class FactionIds {
    public static final String COMMUNISM = "communism";
    public static final String CAPITALISM = "capitalism";
    public static final String MONARCHY = "monarchy";
    public static final String ANARCHISM = "anarchism";

    /**
     * The party a player is treated as belonging to before choosing one: the one that charges no tax.
     * A separate constant rather than an implicit "anarchism", so a consumer that needs to know the
     * fallback does not have to re-derive it from the enum it cannot see.
     */
    public static final String DEFAULT = ANARCHISM;

    private FactionIds() {}
}

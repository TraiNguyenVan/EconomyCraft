package com.reazip.economycraft.faction;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Who may open a locked container — the answer to spec line 10's {@code Cộng đồng}, as pure arithmetic.
 *
 * <p>"Who is it locked to" has <strong>three inputs</strong> (D10), and every one of them used to be decided
 * at a different place, which is how a chest ends up locked by an accident. This class is where the
 * precedence is written down, once:
 *
 * <ol>
 *   <li><strong>The buff, if it is held.</strong> Communism's {@code Cộng đồng} grants
 *       {@link ContainerLockMode#PARTY_ONLY} to its members, and {@code container_lock_mode} in the
 *       {@code communism} config section is the mode it grants. Configured as {@link ContainerLockMode#UNLOCKED}
 *       the buff grants nothing, which is how an admin turns it off without disabling the feature.</li>
 *   <li><strong>The owner's own choice</strong>, stored per container in {@code data/container_locks.json}.</li>
 *   <li><strong>The server default</strong>, {@code container_lock.mode} — which is
 *       {@link ContainerLockMode#UNLOCKED} out of the box, because a lock nobody asked for is worse than no
 *       lock.</li>
 * </ol>
 *
 * <p><strong>The consequence of that order, stated plainly:</strong> a Communism member cannot opt their own
 * container down to {@link ContainerLockMode#UNLOCKED} while the buff is set to grant
 * {@link ContainerLockMode#PARTY_ONLY}, because the buff is checked first. That is the buff being a buff
 * rather than a default, and it is reversible from the server side in one config key. Everyone else keeps
 * the last word about their own chest.
 *
 * <p>All of it is pure: no server, no level, no block entity. {@link ContainerLockStore} does the world
 * lookups and hands the answers here, which is what makes every combination of the three inputs testable.
 */
public final class ContainerLockPolicy {

    private ContainerLockPolicy() {}

    /**
     * Resolves the mode that actually applies to one container.
     *
     * @param buffMode     what the owner's faction buff grants, or {@code null}/{@link ContainerLockMode#UNLOCKED}
     *                     when no buff applies
     * @param ownerChoice  what the owner chose for this container, or {@code null} when they never chose
     * @param globalDefault the server-wide floor
     */
    public static ContainerLockMode effectiveMode(@Nullable ContainerLockMode buffMode,
                                                  @Nullable ContainerLockMode ownerChoice,
                                                  ContainerLockMode globalDefault) {
        if (buffMode != null && buffMode != ContainerLockMode.UNLOCKED) return buffMode;
        if (ownerChoice != null) return ownerChoice;
        return globalDefault == null ? ContainerLockMode.UNLOCKED : globalDefault;
    }

    /**
     * Whether {@code opener} may open a container in {@code mode} that belongs to {@code owner}.
     *
     * <p>The owner and an admin always may — an admin bypass exists so a locked chest can never trap a
     * moderator, and so {@code /eco lock clear} is reachable from inside the lock it clears.
     */
    public static boolean canOpen(ContainerLockMode mode, @Nullable UUID owner, UUID opener,
                                  @Nullable FactionId ownerFaction, @Nullable FactionId openerFaction,
                                  boolean admin) {
        if (admin) return true;
        if (owner == null) return true;
        if (owner.equals(opener)) return true;
        if (mode == null || mode == ContainerLockMode.UNLOCKED) return true;
        if (mode == ContainerLockMode.PRIVATE) return false;
        return sharesParty(ownerFaction, openerFaction);
    }

    /**
     * Whether two players count as the same party for {@link ContainerLockMode#PARTY_ONLY}.
     *
     * <p>{@link FactionId#ANARCHISM} never shares: it is the <em>absence</em> of a party (spec 32), so two
     * players who both defaulted to it have nothing between them and a "party lock" would be a lock that
     * excludes everyone while appearing to include everyone.
     */
    public static boolean sharesParty(@Nullable FactionId ownerFaction, @Nullable FactionId openerFaction) {
        if (ownerFaction == null || openerFaction == null) return false;
        if (ownerFaction == FactionId.ANARCHISM) return false;
        return ownerFaction == openerFaction;
    }

    /** Parses a configured mode name, falling back rather than throwing on a typo. */
    public static ContainerLockMode parse(@Nullable String name, ContainerLockMode fallback) {
        if (name == null || name.isBlank()) return fallback;
        for (ContainerLockMode mode : ContainerLockMode.values()) {
            if (mode.name().equalsIgnoreCase(name.trim())) return mode;
        }
        return fallback;
    }
}

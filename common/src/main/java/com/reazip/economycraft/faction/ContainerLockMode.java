package com.reazip.economycraft.faction;

/**
 * What a container lock refuses (spec line 10, {@code Cộng đồng}).
 *
 * <p>D10, closed by the designer as three separate answers rather than one flag:
 *
 * <ul>
 *   <li>{@link #UNLOCKED} is the server default — containers are not locked unless someone asks;</li>
 *   <li>{@link #PRIVATE} is what a player may choose for their own container, the spec's literal "lock for
 *       yourself";</li>
 *   <li>{@link #PARTY_ONLY} is what Communism's {@code Cộng đồng} buff does while it is active.</li>
 * </ul>
 *
 * <p>Three values rather than a boolean, because a shared bridge toll and a private granary need different
 * answers, and because a lock that appears without being asked for is worse than no lock.
 */
public enum ContainerLockMode {
    /** No restriction; the container is simply not locked. The global default. */
    UNLOCKED,
    /** Everyone except the owner is refused — the spec's literal "lock for yourself". */
    PRIVATE,
    /** Non-party members are refused, which is what the {@code Cộng đồng} buff grants. */
    PARTY_ONLY
}

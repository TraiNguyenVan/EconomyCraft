package com.reazip.economycraft.faction;

/**
 * What a container locked by Communism's {@code Cộng đồng} refuses (spec line 10).
 *
 * <p>D10: the spec offers two options — lock for yourself, or do not lock — and separately says the lock should
 * keep the chest to people who share the Communism tag. Those are two different restrictions, so this is three
 * values and a per-container choice rather than a global flag, because a bridge toll wants a different answer
 * from a private granary.
 *
 * <p><strong>Open question for the designer.</strong> {@link #PARTY_ONLY} is the default because it is the buff's
 * stated intent, but the spec only ever lists the other two. If the intent was literally "lock for yourself",
 * the default should be {@link #PRIVATE} instead. Recorded as an assumption in {@code TODO.md} §4 D10 rather
 * than silently decided here.
 */
public enum ContainerLockMode {
    /** Non-members are refused. The default. */
    PARTY_ONLY,
    /** Everyone except the owner is refused — the spec's literal "lock for yourself". */
    PRIVATE,
    /** No restriction; the container is simply not locked. */
    UNLOCKED
}
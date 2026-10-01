package com.reazip.economycraft.config;

import com.reazip.economycraft.faction.ContainerLockMode;
import com.google.gson.annotations.SerializedName;

/**
 * The {@code Cộng đồng} container lock's global default — the answer for a container whose owner has expressed
 * no preference of their own.
 *
 * <p>It is a separate section rather than another key on the Communism record because D10 ended with three
 * answers that belong to three different places, and collapsing them into one flag is how the spec's two options
 * and its stated intent got confused in the first place:
 *
 * <ul>
 *   <li>the <strong>server default</strong> is {@link ContainerLockMode#UNLOCKED} — do not lock. Locking
 *       containers is opt-in, because a lock that surprises an admin on an existing server is worse than no
 *       lock at all;</li>
 *   <li>a <strong>player</strong> may lock their own container to {@link ContainerLockMode#PRIVATE} — the
 *       spec's literal "lock for yourself" — unless {@link #allowPrivateChoice} is turned off;</li>
 *   <li>holding <strong>Communism's</strong> {@code Cộng đồng} buff is what admits the party, through
 *       {@code factions.communism.container_lock_mode}. That is the buff's mode, not a default.</li>
 * </ul>
 *
 * <p>Nothing enforces any of this yet: the lock itself is Phase 10, and the per-player choice needs a store,
 * which that phase adds.
 */
public class ContainerLockSection {

    /**
     * What a container is restricted to when its owner has chosen nothing.
     *
     * <p>{@link ContainerLockMode#UNLOCKED} by default. A player who wants {@link ContainerLockMode#PRIVATE}
     * sets it themselves; this value is only the floor that applies when they have not.
     */
    @SerializedName("mode")
    public ContainerLockMode mode = ContainerLockMode.UNLOCKED;

    /**
     * Whether a player may lock their own containers to {@link ContainerLockMode#PRIVATE} at all.
     *
     * <p>On by default. Turn it off for a server that would rather no player could restrict a shared chest —
     * note that this does not affect Communism's buff, which is the server's choice, not the player's.
     */
    @SerializedName("allow_private_choice")
    public boolean allowPrivateChoice = true;

    public void clamp() {
        mode = ConfigClamp.choice("container_lock.mode", mode == null ? null : mode.name(),
                ContainerLockMode.UNLOCKED, ContainerLockMode.values());
    }
}
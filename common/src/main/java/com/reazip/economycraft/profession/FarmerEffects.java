package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyCraft;
import net.minecraft.server.level.ServerPlayer;

public final class FarmerEffects {
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
}

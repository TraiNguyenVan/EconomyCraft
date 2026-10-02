package com.reazip.economycraft.profession;

import com.reazip.economycraft.EconomyConfig;
import com.reazip.economycraft.util.IdentifierCompat;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class BuilderEffects {
    private static final IdentifierCompat.Id APPRENTICE_REACH = IdentifierCompat.tryParse("economycraft:builder_reach_apprentice");
    private static final IdentifierCompat.Id MASTER_REACH = IdentifierCompat.tryParse("economycraft:builder_reach_master");

    private BuilderEffects() {}

    public static void applyReach(ServerPlayer player) {
        if (player == null) return;
        try {
            com.reazip.economycraft.EconomyManager eco = com.reazip.economycraft.EconomyCraft.getManager(player.level().getServer());
            ProfessionId prof = eco.getProfessions().professionOf(player.getUUID());
            if (prof != ProfessionId.BUILDER) {
                clearReach(player);
                return;
            }
            ProfessionLevel level = eco.getProfessions().levelOf(player.getUUID());
            AttributeInstance inst = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (inst == null) return;
            if (level == ProfessionLevel.MASTER) {
                inst.removeModifier((Identifier) (Object) MASTER_REACH.mcId());
                inst.removeModifier((Identifier) (Object) APPRENTICE_REACH.mcId());
                inst.addTransientModifier(new AttributeModifier((Identifier) (Object) MASTER_REACH.mcId(), 2.0, AttributeModifier.Operation.ADD_VALUE));
            } else if (level == ProfessionLevel.APPRENTICE) {
                inst.removeModifier((Identifier) (Object) MASTER_REACH.mcId());
                inst.removeModifier((Identifier) (Object) APPRENTICE_REACH.mcId());
                inst.addTransientModifier(new AttributeModifier((Identifier) (Object) APPRENTICE_REACH.mcId(), 1.0, AttributeModifier.Operation.ADD_VALUE));
            } else {
                clearReach(player);
            }
        } catch (Exception ignored) {
        }
    }

    public static void clearReach(ServerPlayer player) {
        if (player == null) return;
        AttributeInstance inst = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (inst == null) return;
        Identifier masterId = (Identifier) (Object) MASTER_REACH.mcId();
        Identifier apprenticeId = (Identifier) (Object) APPRENTICE_REACH.mcId();
        if (inst.hasModifier(masterId)) inst.removeModifier(masterId);
        if (inst.hasModifier(apprenticeId)) inst.removeModifier(apprenticeId);
    }
}

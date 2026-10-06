package com.reazip.economycraft.gossip;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Maps Minecraft Villager professions to GossipCategory dialogue themes.
 */
public final class ProfessionMapper {
    private ProfessionMapper() {}

    /**
     * Maps an entity to its GossipCategory if it is a Villager.
     */
    public static GossipCategory fromEntity(@Nullable Entity entity) {
        if (entity instanceof Villager villager) {
            VillagerData data = villager.getVillagerData();
            return fromHolder(data.profession());
        }
        return GossipCategory.GENERAL;
    }

    /**
     * Maps a Holder of VillagerProfession to its GossipCategory.
     */
    public static GossipCategory fromHolder(@Nullable Holder<VillagerProfession> holder) {
        if (holder == null) {
            return GossipCategory.GENERAL;
        }
        return holder.unwrapKey()
                .map(ProfessionMapper::fromResourceKey)
                .orElseGet(() -> fromProfessionName(holder.getRegisteredName()));
    }

    /**
     * Maps a ResourceKey of VillagerProfession to its GossipCategory.
     */
    public static GossipCategory fromResourceKey(@Nullable ResourceKey<VillagerProfession> key) {
        if (key == null) {
            return GossipCategory.GENERAL;
        }
        return fromIdentifier(key.identifier());
    }

    /**
     * Maps a Resource Identifier to its GossipCategory.
     */
    public static GossipCategory fromIdentifier(@Nullable Identifier id) {
        if (id == null) {
            return GossipCategory.GENERAL;
        }
        return fromProfessionName(id.getPath());
    }

    /**
     * Maps a raw profession name or path to its GossipCategory.
     */
    public static GossipCategory fromProfessionName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return GossipCategory.GENERAL;
        }

        String path = name.toLowerCase(Locale.ROOT);
        int colon = path.indexOf(':');
        if (colon >= 0) {
            path = path.substring(colon + 1);
        }

        return switch (path) {
            case "farmer", "fisherman", "shepherd", "fletcher" -> GossipCategory.FARMER;
            case "armorer", "weaponsmith", "toolsmith" -> GossipCategory.BLACKSMITH;
            case "cleric" -> GossipCategory.CLERIC;
            case "librarian", "cartographer" -> GossipCategory.LIBRARIAN;
            case "nitwit", "none", "unemployed" -> GossipCategory.NITWIT;
            case "butcher", "leatherworker", "mason" -> GossipCategory.GENERAL;
            default -> GossipCategory.GENERAL;
        };
    }

    // --- fromProfession overloads ---

    public static GossipCategory fromProfession(@Nullable Holder<VillagerProfession> holder) {
        return fromHolder(holder);
    }

    public static GossipCategory fromProfession(@Nullable ResourceKey<VillagerProfession> key) {
        return fromResourceKey(key);
    }

    public static GossipCategory fromProfession(@Nullable Identifier identifier) {
        return fromIdentifier(identifier);
    }

    public static GossipCategory fromProfession(@Nullable String name) {
        return fromProfessionName(name);
    }
}

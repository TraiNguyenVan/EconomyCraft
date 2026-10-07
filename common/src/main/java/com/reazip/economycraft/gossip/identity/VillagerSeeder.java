package com.reazip.economycraft.gossip.identity;

import com.reazip.economycraft.gossip.storage.VillagerProfile;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Deterministic procedural seeder that derives a persistent villager persona (name, traits, quirk, backstory)
 * directly from the Villager entity's UUID and biome.
 *
 * <p>Ensures that even if offline or before LLM enrichment, every single villager in the world has an
 * instantly recognizable, consistent, and unique identity.
 */
public final class VillagerSeeder {
    private VillagerSeeder() {}

    private static final List<String> PLAINS_NAMES = List.of(
            "Barnaby", "Garrick", "Elspeth", "Cedric", "Rowan", "Aldous", "Martha", "Tobias",
            "Beatrice", "Jasper", "Winifred", "Clement", "Oswald", "Gwendolyn", "Bertram", "Simon",
            "Arthur", "Matilda", "Bartholomew", "Constance", "Edmund", "Harriet", "Walter", "Prudence"
    );

    private static final List<String> DESERT_NAMES = List.of(
            "Tariq", "Faris", "Samira", "Soraya", "Zayn", "Jamila", "Nadir", "Rayan",
            "Malik", "Aziza", "Khalid", "Layla", "Qasim", "Yasmin", "Hamza", "Salma"
    );

    private static final List<String> SNOW_TAIGA_NAMES = List.of(
            "Olaf", "Astrid", "Bjorn", "Freya", "Sigrid", "Gunnar", "Helga", "Leif",
            "Ingrid", "Thorin", "Sven", "Signe", "Einar", "Kari", "Magnus", "Dagny"
    );

    private static final List<String> SAVANNA_NAMES = List.of(
            "Kofi", "Nia", "Jelani", "Ayana", "Zuri", "Jabari", "Tendai", "Amara",
            "Kwame", "Sade", "Farai", "Zola", "Enzi", "Simba", "Mandla", "Nala"
    );

    private static final List<String> SWAMP_JUNGLE_NAMES = List.of(
            "Burl", "Moss", "Fern", "Willow", "Bramble", "Jasper", "Finch", "Reed",
            "Thistle", "Clover", "Silas", "Hazel", "Flint", "Ivy", "Briar", "Orson"
    );

    private static final List<String> TRAITS_POOL = List.of(
            "shrewd", "perfectionist", "grumpy", "optimistic", "anxious", "cynical",
            "curious", "proud", "gullible", "stoic", "meticulous", "jovial", "frugal",
            "superstitious", "philosophical", "blunt"
    );

    private static final List<String> QUIRKS_POOL = List.of(
            "Inspects every emerald under direct sunlight before accepting it.",
            "Counts inventory items three times before committing to a deal.",
            "Always sighs deeply before quoting a trade price.",
            "Mumbles arithmetic calculations and profit margins under their breath.",
            "Claims their ancestors supplied goods to the royal court.",
            "Blames the server inflation multiplier on wandering traders.",
            "Polishes their workstation counter obsessively between customers.",
            "Checks the sky for rain after every transaction.",
            "Taps their nose mysteriously when asked about wholesale suppliers.",
            "Swears their wares have a secret blessing from the village iron golem.",
            "Keeps a tiny lucky pebble behind their ear.",
            "Frowns heavily whenever paper bills or bank balances are mentioned."
    );

    private static final List<String> BACKSTORY_TEMPLATES = List.of(
            "A veteran %s who moved from the northern provinces after a trade dispute. Known for exacting standards.",
            "Inherited the family %s workshop in this village. Fiercely protective of local tradition.",
            "A self-taught %s with a shrewd eye for commodities. Constantly planning their next expansion.",
            "Traveled across distant realms as an apprentice before establishing this stall. Has seen everything.",
            "A humble %s who dreams of retiring to an island estate once the inflation cools down.",
            "Took over this post after the previous artisan vanished into the Nether. Takes no chances."
    );

    /**
     * Deterministically generates a base profile from the villager's UUID, profession, and biome.
     */
    public static VillagerProfile createSeededProfile(
            UUID uuid,
            @Nullable String rawProfession,
            @Nullable String rawBiome,
            long creationTime
    ) {
        long seed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        Random rng = new Random(seed);

        String biome = (rawBiome != null && !rawBiome.isBlank()) ? rawBiome.toLowerCase(Locale.ROOT) : "plains";
        String profession = (rawProfession != null && !rawProfession.isBlank()) ? rawProfession.toLowerCase(Locale.ROOT) : "general";

        // Pick name list based on biome
        List<String> namesList = PLAINS_NAMES;
        if (biome.contains("desert")) {
            namesList = DESERT_NAMES;
        } else if (biome.contains("taiga") || biome.contains("snow") || biome.contains("frozen") || biome.contains("ice")) {
            namesList = SNOW_TAIGA_NAMES;
        } else if (biome.contains("savanna")) {
            namesList = SAVANNA_NAMES;
        } else if (biome.contains("swamp") || biome.contains("jungle") || biome.contains("mangrove")) {
            namesList = SWAMP_JUNGLE_NAMES;
        }

        String name = namesList.get(rng.nextInt(namesList.size()));

        // Pick 2-3 distinct traits
        List<String> traitPoolCopy = new ArrayList<>(TRAITS_POOL);
        Collections.shuffle(traitPoolCopy, rng);
        int traitCount = 2 + rng.nextInt(2); // 2 or 3 traits
        List<String> traits = new ArrayList<>(traitPoolCopy.subList(0, traitCount));

        // Pick quirk
        String quirk = QUIRKS_POOL.get(rng.nextInt(QUIRKS_POOL.size()));

        // Pick backstory
        String template = BACKSTORY_TEMPLATES.get(rng.nextInt(BACKSTORY_TEMPLATES.size()));
        String backstory = String.format(Locale.ROOT, template, profession);

        return new VillagerProfile(
                uuid,
                name,
                profession,
                biome,
                traits,
                quirk,
                backstory,
                creationTime,
                creationTime
        );
    }
}

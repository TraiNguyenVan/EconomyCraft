package com.reazip.economycraft;

import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.faction.FactionId;
import com.reazip.economycraft.faction.FactionStore;
import com.reazip.economycraft.profession.ProfessionId;
import com.reazip.economycraft.profession.ProfessionStore;
import com.reazip.economycraft.time.CooldownService;
import com.reazip.economycraft.time.OnlineTimeService;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a failed {@link EconomyManager#save()} leaves behind for the next one.
 *
 * <p>{@code save()} writes every document in one transaction, and the dirty-flagged stores used to
 * clear their flag before that transaction committed. A later document failing then rolled the
 * earlier rows back while their stores believed they were saved, so the retry skipped them — the
 * change was in memory only and a restart lost it. These tests fail a document after the flagged
 * stores have written, retry without touching the stores again, and read the documents back.
 */
class SaveRollbackRetryTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @TempDir Path world;

    private MinecraftServer server;
    private EconomyManager eco;
    private boolean previousScoreboardSetting;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(components -> components.apply());
    }

    @BeforeEach
    void setUp() throws SQLException {
        server = mock(MinecraftServer.class);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        when(server.registryAccess())
                .thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        PlayerList players = mock(PlayerList.class);
        when(players.getPlayers()).thenReturn(List.of());
        when(server.getPlayerList()).thenReturn(players);
        when(server.getScoreboard()).thenReturn(mock(ServerScoreboard.class));
        when(server.isSameThread()).thenReturn(true);

        previousScoreboardSetting = EconomyConfig.get().scoreboardEnabled;
        EconomyConfig.get().scoreboardEnabled = false;

        eco = new EconomyManager(server);
        // Create every document, so the forced failure below is an UPDATE and the trigger fires.
        eco.save();
    }

    @AfterEach
    void tearDown() throws SQLException {
        dropFailingTrigger();
        EconomyConfig.get().scoreboardEnabled = previousScoreboardSetting;
        if (eco != null) eco.getDatabase().close();
    }

    @Test
    @DisplayName("A retry after a failed save writes the stores the rollback undid")
    void retryWritesStoresFlushedBeforeTheFailure() throws Exception {
        eco.getFactions().select(PLAYER, FactionId.COMMUNISM);
        eco.getProfessions().select(PLAYER, ProfessionId.MINER);
        eco.getCooldowns().start(PLAYER, "test:retry", 60_000L);
        eco.getOnlineTime().addOnlineTime(PLAYER, 60_000L);

        failUpdatesTo("quests.json");
        assertThrows(RuntimeException.class, eco::save);

        // No new player change: the second save must still carry the earlier stores.
        dropFailingTrigger();
        eco.save();

        Path dataDir = EconomyPaths.dataDir(server);
        EconomyDatabase db = eco.getDatabase();
        assertEquals(FactionId.COMMUNISM, new FactionStore(db, dataDir.resolve("parties.json")).factionOf(PLAYER));
        assertEquals(ProfessionId.MINER, new ProfessionStore(db, dataDir.resolve("professions.json")).professionOf(PLAYER));
        assertTrue(new CooldownService(db, dataDir.resolve("cooldowns.json"))
                .remainingMillis(PLAYER, "test:retry") > 0L, "the cooldown was persisted");
        assertEquals(60_000L, new OnlineTimeService(db, dataDir.resolve("online_time.json")).totalMillis(PLAYER));
    }

    private void failUpdatesTo(String key) throws SQLException {
        Path file = EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("CREATE TRIGGER fail_document_update BEFORE UPDATE ON documents " +
                    "WHEN OLD.name = '" + key + "' BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END");
        }
    }

    private void dropFailingTrigger() throws SQLException {
        Path file = EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS fail_document_update");
        }
    }
}

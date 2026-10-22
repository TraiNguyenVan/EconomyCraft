package com.reazip.economycraft.orders;

import com.reazip.economycraft.db.EconomyDatabase;
import com.reazip.economycraft.util.EconomyPaths;
import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * SQLite fault injection for the order-escrow tests: a trigger that aborts one document's write
 * the way a real disk-level SQLite failure does.
 *
 * <p>The failure has to be a real one. A document write that only looks like it failed — a mock
 * that throws, a flag that a mocked seam never reads — cannot show that the write is reported to
 * the caller instead of swallowed.
 */
final class SqliteDocumentFault {

    private SqliteDocumentFault() {}

    /** Fails one document's write the way a real SQLite write failure aborts the save. */
    static void failWritesTo(MinecraftServer server, String key) throws Exception {
        try (Connection conn = open(server);
             Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS fail_document_update");
            st.execute("CREATE TRIGGER fail_document_update BEFORE UPDATE ON documents "
                    + "WHEN OLD.name = '" + key + "' BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END");
        }
    }

    /** Removes the trigger, so the next write of that document commits again. */
    static void dropFailingTrigger(MinecraftServer server) throws Exception {
        try (Connection conn = open(server);
             Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS fail_document_update");
        }
    }

    private static Connection open(MinecraftServer server) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:"
                + EconomyPaths.dataDir(server).resolve(EconomyDatabase.DB_FILE_NAME).toAbsolutePath());
    }
}

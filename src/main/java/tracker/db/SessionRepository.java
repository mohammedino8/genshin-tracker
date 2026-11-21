package tracker.db;

import tracker.model.Models.Session;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.List;
import org.sqlite.SQLiteConfig;
import static tracker.db.Database.*;

public final class SessionRepository {
    private final Database db;

    public SessionRepository(Database db) { this.db = db; }

    public void recover() throws SQLException {
        db.write(c -> {
            update(c, "UPDATE play_sessions SET end_at=last_seen_at WHERE end_at IS NULL");
            return null;
        });
    }

    public void observe(boolean running, Instant now, ZoneId zone, Duration maximumGap) throws SQLException {
        db.write(c -> {
            Session active = query(c, "SELECT * FROM play_sessions WHERE end_at IS NULL", SessionRepository::row)
                    .stream().findFirst().orElse(null);
            long stamp = now.toEpochMilli();
            if (active != null) {
                long gap = stamp - active.lastSeen().toEpochMilli();
                if (running && gap >= 0 && gap <= maximumGap.toMillis()) {
                    if (gap > 0) {
                        update(c, "UPDATE play_sessions SET last_seen_at=? WHERE id=?", stamp, active.id());
                        Ledger.recordPlaytime(c, active.id(), active.lastSeen(), now, zone);
                    }
                    return null;
                }
                update(c, "UPDATE play_sessions SET end_at=last_seen_at WHERE id=?", active.id());
            }
            if (running) {
                // A backward wall-clock adjustment can revisit an already recorded interval.
                Long latest = query(c, "SELECT MAX(COALESCE(end_at,last_seen_at)) AS latest FROM play_sessions",
                        r -> optionalLong(r, "latest")).getFirst();
                if (latest == null || stamp >= latest)
                    update(c, "INSERT INTO play_sessions(start_at,last_seen_at) VALUES (?,?)", stamp, stamp);
            }
            return null;
        });
    }

    public List<Session> all() throws SQLException {
        return db.read(c -> query(c, "SELECT * FROM play_sessions ORDER BY start_at DESC,id DESC", SessionRepository::row));
    }

    private static Session row(ResultSet r) throws SQLException {
        Long end = optionalLong(r, "end_at");
        return new Session(r.getLong("id"), Instant.ofEpochMilli(r.getLong("start_at")),
                Instant.ofEpochMilli(r.getLong("last_seen_at")), end == null ? null : Instant.ofEpochMilli(end));
    }

    public int importLegacy(Path source) throws SQLException, java.io.IOException {
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Select an existing SQLite file.");
        if (Files.isSameFile(source, db.path())) return 0;
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(5000);
        try (var legacy = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath(), config.toProperties())) {
            var rows = query(legacy, "SELECT * FROM play_sessions ORDER BY id", SessionRepository::row);
            return db.write(c -> {
                int added = 0;
                for (Session row : rows) {
                    long start = row.start().toEpochMilli();
                    long seen = row.lastSeen().toEpochMilli();
                    long end = row.confirmedEnd().toEpochMilli();
                    if (start < 0 || seen < start || end < start || end > Instant.now().plus(Duration.ofDays(1)).toEpochMilli())
                        throw new IllegalArgumentException("Legacy session " + row.id() + " has invalid epoch-millisecond timestamps.");
                    added += update(c, """
                        INSERT INTO play_sessions(start_at,last_seen_at,end_at)
                        SELECT ?,?,? WHERE NOT EXISTS
                            (SELECT 1 FROM play_sessions WHERE start_at=? AND COALESCE(end_at,last_seen_at)=?)
                        """, start, seen, end, start, end);
                }
                return added;
            });
        }
    }
}

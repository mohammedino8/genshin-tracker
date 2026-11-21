package tracker.db;

import org.sqlite.SQLiteConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class Database {
    private final Path path;

    public Database(Path path) { this.path = path.toAbsolutePath(); }
    public Path path() { return path; }

    private Connection open() throws SQLException {
        var config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
    }

    public void initialize() throws SQLException, IOException {
        Files.createDirectories(path.getParent());
        try (var c = open(); var st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
        }
        try (var resource = Database.class.getResourceAsStream("/tracker/V1.sql")) {
            String schema = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            write(c -> {
                int version = query(c, "PRAGMA user_version", r -> r.getInt(1)).getFirst();
                if (version > 1) throw new SQLException("This database requires a newer tracker version.");
                if (version == 0) {
                    try (var st = c.createStatement()) {
                        for (String sql : schema.split(";")) if (!sql.isBlank()) st.execute(sql);
                        st.execute("PRAGMA user_version=1");
                    }
                }
                return null;
            });
        }
    }

    @FunctionalInterface public interface Work<T> { T run(Connection c) throws SQLException; }
    @FunctionalInterface interface Mapper<T> { T map(ResultSet r) throws SQLException; }

    public <T> T read(Work<T> work) throws SQLException {
        try (var c = open()) { return work.run(c); }
    }

    public <T> T write(Work<T> work) throws SQLException {
        try (var c = open()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { c.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    static int update(Connection c, String sql, Object... args) throws SQLException {
        try (var st = prepare(c, sql, args)) { return st.executeUpdate(); }
    }

    static long insert(Connection c, String sql, Object... args) throws SQLException {
        return query(c, sql + " RETURNING id", r -> r.getLong(1), args).getFirst();
    }

    static <T> List<T> query(Connection c, String sql, Mapper<T> map, Object... args) throws SQLException {
        try (var st = prepare(c, sql, args); var rows = st.executeQuery()) {
            var values = new ArrayList<T>();
            while (rows.next()) values.add(map.map(rows));
            return values;
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        var st = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) st.setObject(i + 1, args[i]);
            return st;
        } catch (SQLException e) {
            try { st.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    static Long optionalLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    static String setting(Connection c, String key) throws SQLException {
        return query(c, "SELECT value FROM settings WHERE key=?", r -> r.getString(1), key).stream().findFirst().orElse(null);
    }

    public String setting(String key) throws SQLException { return read(c -> setting(c, key)); }

    static void setting(Connection c, String key, String value) throws SQLException {
        update(c, "INSERT INTO settings VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value);
    }

    public void setting(String key, String value) throws SQLException {
        write(c -> { setting(c, key, value); return null; });
    }
}

package tracker.db;

import tracker.model.Models.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static tracker.db.Database.*;

public final class Ledger {
    public static final String PRIMOGEMS = "Primogems";
    private static final String CSV_PROVIDER = "Local CSV";
    private static final long LIMIT = 1_000_000_000_000L;
    private final Database db;

    public Ledger(Database db) { this.db = db; }

    public record IncomeSummary(SortedMap<LocalDate, Long> days, Map<String, Long> categories) {
        public long between(LocalDate first, LocalDate last) {
            return days.entrySet().stream().filter(e -> !e.getKey().isBefore(first) && !e.getKey().isAfter(last))
                    .mapToLong(Map.Entry::getValue).sum();
        }
        public long total() { return days.values().stream().mapToLong(Long::longValue).sum(); }
    }

    public List<Resource> resources() throws SQLException {
        return db.read(c -> query(c, "SELECT * FROM resource_types ORDER BY name", r -> new Resource(r.getString(1), r.getString(2))));
    }

    public void addResource(String name, String unit) throws SQLException {
        if (name.isBlank() || unit.isBlank()) throw new IllegalArgumentException("Enter a resource name and unit.");
        db.write(c -> {
            if (update(c, "INSERT OR IGNORE INTO resource_types VALUES (?,?)", name.strip(), unit.strip()) == 0)
                throw new IllegalArgumentException("That resource already exists.");
            return null;
        });
    }

    public List<Objective> objectives() throws SQLException {
        return db.read(c -> query(c, """
            SELECT o.*, t.unit, COALESCE((SELECT SUM(e.amount) FROM objective_events e
                WHERE e.objective_id=o.id AND e.generation=o.generation),0) AS progress
            FROM objectives o JOIN resource_types t ON t.name=o.resource
            WHERE o.status<>'DELETED' ORDER BY o.id DESC
            """, r -> new Objective(r.getLong("id"), r.getString("title"), r.getString("description"),
                new Resource(r.getString("resource"), r.getString("unit")), r.getLong("target"),
                r.getLong("progress"), Instant.ofEpochMilli(r.getLong("created_at")), date(r.getString("deadline")),
                Status.valueOf(r.getString("status")), Source.valueOf(r.getString("source")))));
    }

    public long saveObjective(long id, ObjectiveDraft draft, long baseline, Instant now) throws SQLException {
        if (draft.title().isBlank() || draft.target() <= 0 || draft.target() > LIMIT || baseline < 0 || baseline > LIMIT)
            throw new IllegalArgumentException("Enter a title, a positive target and a nonnegative starting amount.");
        String deadline = draft.deadline() == null ? null : draft.deadline().toString();
        return db.write(c -> {
            if (id != 0) {
                Position current = position(c, id);
                boolean metricChanged = !current.resource.equals(draft.resource().name());
                update(c, """
                    UPDATE objectives SET title=?,description=?,target=?,deadline=?,source=?,resource=?,
                        generation=generation+?, eligible_since=CASE WHEN ?=1 THEN ? ELSE eligible_since END
                    WHERE id=?
                    """, draft.title(), draft.description(), draft.target(), deadline, draft.source().name(),
                        draft.resource().name(), metricChanged ? 1 : 0,
                        metricChanged || current.source == Source.MANUAL && draft.source() != Source.MANUAL ? 1 : 0,
                        now.toEpochMilli(), id);
                if (metricChanged) objectiveEvent(c, id, baseline, "Resource changed", "BASELINE", now);
                return id;
            }
            long created = insert(c, """
                INSERT INTO objectives(title,description,resource,target,deadline,source,status,created_at,eligible_since)
                VALUES (?,?,?,?,?,?,'ACTIVE',?,?)
                """, draft.title(), draft.description(), draft.resource().name(), draft.target(), deadline,
                    draft.source().name(), now.toEpochMilli(), now.toEpochMilli());
            if (baseline != 0) objectiveEvent(c, created, baseline, "Starting amount", "BASELINE", now);
            return created;
        });
    }

    public void status(long id, Status status, Instant now) throws SQLException {
        db.write(c -> update(c, """
            UPDATE objectives SET status=?,eligible_since=? WHERE id=? AND status<>'DELETED' AND status<>?
            """, status.name(), now.toEpochMilli(), id, status.name()));
    }

    public void deleteObjective(long id) throws SQLException {
        db.write(c -> update(c, "UPDATE objectives SET status='DELETED' WHERE id=?", id));
    }

    public void reset(long id, long baseline, Instant now) throws SQLException {
        if (baseline < 0 || baseline > LIMIT) throw new IllegalArgumentException("Enter a nonnegative starting amount.");
        db.write(c -> {
            position(c, id);
            update(c, "UPDATE objectives SET generation=generation+1,eligible_since=? WHERE id=?", now.toEpochMilli(), id);
            objectiveEvent(c, id, baseline, "Progress reset", "BASELINE", now);
            return null;
        });
    }

    public void adjust(long id, long amount, String note, Instant now) throws SQLException {
        validateAmount(amount, false);
        db.write(c -> {
            objectiveEvent(c, id, amount, note, "ADJUSTMENT", now);
            return null;
        });
    }

    public long manual(String requestKey, Resource resource, LocalDate day, long amount,
                       String category, String note, Instant now, ZoneId zone) throws SQLException {
        validateDay(day, now, zone);
        validateAmount(amount, true);
        return db.write(c -> {
            var prior = query(c, "SELECT id FROM resource_events WHERE event_key=?", r -> r.getLong(1), requestKey);
            if (!prior.isEmpty()) return prior.getFirst();
            String source = normalizedCategory(category, "Uncategorized");
            if (PRIMOGEMS.equals(resource.name()) && !query(c, "SELECT id FROM daily_income WHERE income_day=?",
                    r -> r.getLong(1), day.toString()).isEmpty()) {
                throw new IllegalArgumentException("This day has a CSV total. Revise the CSV total to correct income for that day.");
            }
            long id = event(c, requestKey, resource.name(), day, now, amount, source, note, "MANUAL", null, null, null);
            applyShared(c, id, resource.name(), day, amount, false, zone);
            return id;
        });
    }

    public void reverse(long id, Instant now) throws SQLException {
        db.write(c -> {
            Event prior = query(c, "SELECT e.*, EXISTS(SELECT 1 FROM resource_events x WHERE x.reverses_id=e.id) AS reversed "
                            + "FROM resource_events e WHERE e.id=?", Ledger::eventRow, id)
                    .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Entry no longer exists."));
            if (!List.of("MANUAL", "ADJUSTMENT").contains(prior.kind()))
                throw new IllegalArgumentException("Select a manual entry or objective adjustment to reverse.");
            if (!prior.reversed()) reverse(c, prior, now, "Reversed entry #" + id);
            return null;
        });
    }

    private static void reverse(Connection c, Event prior, Instant now, String note) throws SQLException {
        long id = event(c, "reverse:" + prior.id(), prior.resource(), prior.day(), now, -prior.amount(),
                prior.category(), note, "REVERSAL", prior.objectiveId(), null, prior.id());
        update(c, """
            INSERT INTO objective_events(event_id,objective_id,generation,amount)
            SELECT ?,objective_id,generation,-amount FROM objective_events WHERE event_id=?
            """, id, prior.id());
    }

    public List<Event> events(boolean incomeOnly, Long objectiveId) throws SQLException {
        return db.read(c -> query(c, """
            SELECT e.*, COALESCE((SELECT SUM(a.amount) FROM objective_events a
                WHERE a.event_id=e.id AND a.objective_id=?),e.amount) AS displayed_amount,
                EXISTS(SELECT 1 FROM resource_events x WHERE x.reverses_id=e.id) AS reversed
            FROM resource_events e WHERE (?=0 OR (e.resource='Primogems' AND e.objective_id IS NULL))
                AND (? IS NULL OR EXISTS (SELECT 1 FROM objective_events a WHERE a.event_id=e.id AND a.objective_id=?))
            ORDER BY e.id DESC LIMIT 500
            """, r -> eventRow(r, r.getLong("displayed_amount")), objectiveId, incomeOnly ? 1 : 0, objectiveId, objectiveId));
    }

    private static Event eventRow(ResultSet r) throws SQLException {
        return eventRow(r, r.getLong("amount"));
    }

    private static Event eventRow(ResultSet r, long amount) throws SQLException {
        return new Event(r.getLong("id"), date(r.getString("event_day")), Instant.ofEpochMilli(r.getLong("created_at")),
                r.getString("resource"), amount, r.getString("category"), r.getString("note"),
                r.getString("kind"), optionalLong(r, "objective_id"), optionalLong(r, "reverses_id"),
                r.getBoolean("reversed"));
    }

    public IncomeSummary income() throws SQLException {
        return db.read(c -> {
            var days = new TreeMap<LocalDate, Long>();
            var categories = new TreeMap<String, Long>();
            query(c, """
                SELECT e.event_day, COALESCE(d.category,e.category), SUM(e.amount)
                FROM resource_events e LEFT JOIN daily_income d ON d.id=e.daily_id
                WHERE e.resource='Primogems' AND e.objective_id IS NULL
                GROUP BY e.event_day, COALESCE(d.category,e.category) HAVING SUM(e.amount)<>0
                """, r -> {
                days.merge(date(r.getString(1)), r.getLong(3), Long::sum);
                categories.merge(r.getString(2), r.getLong(3), Long::sum);
                return r.getLong(3);
            });
            return new IncomeSummary(Collections.unmodifiableSortedMap(days), Collections.unmodifiableMap(categories));
        });
    }

    public ImportInfo importInfo() throws SQLException {
        return db.read(c -> query(c, """
            SELECT account, MIN(income_day), MAX(income_day), COUNT(*)
            FROM daily_income GROUP BY account
            """, r -> new ImportInfo(r.getString(1), date(r.getString(2)), date(r.getString(3)), r.getInt(4),
                Instant.ofEpochMilli(Long.parseLong(setting(c, "last_csv_import"))))).stream().findFirst().orElse(null));
    }

    public enum Overlap { REJECT, REPLACE_MANUAL }

    public int importIncome(String account, List<IncomeDay> rows, Overlap overlap, Instant now, ZoneId zone) throws SQLException {
        if (account.isBlank() || rows.isEmpty()) throw new IllegalArgumentException("Enter an account label and provide daily rows.");
        Set<LocalDate> dates = new HashSet<>();
        for (IncomeDay row : rows) {
            validateDay(row.day(), now, zone);
            if (row.total() < 0 || row.total() > LIMIT || !dates.add(row.day()))
                throw new IllegalArgumentException("Use one nonnegative total per date. Check " + row.day() + ".");
        }
        return db.write(c -> {
            String boundAccount = setting(c, "income_account");
            if (boundAccount != null && !boundAccount.equals(account))
                throw new IllegalArgumentException("This tracker already imports income for " + boundAccount + ".");
            int changed = 0;
            for (IncomeDay row : rows.stream().sorted(Comparator.comparing(IncomeDay::day)).toList())
                changed += reconcile(c, account, row, overlap, now, zone);
            setting(c, "income_account", account);
            setting(c, "last_csv_import", Long.toString(now.toEpochMilli()));
            return changed;
        });
    }

    private record Daily(long id, long total, String category) {}

    private static int reconcile(Connection c, String account, IncomeDay row, Overlap overlap, Instant now, ZoneId zone)
            throws SQLException {
        String day = row.day().toString();
        String category = normalizedCategory(row.category(), "Imported daily total");
        var prior = query(c, "SELECT id,total,category FROM daily_income WHERE income_day=?",
                r -> new Daily(r.getLong(1), r.getLong(2), r.getString(3)), day).stream().findFirst().orElse(null);
        if (prior != null && prior.total == row.total() && prior.category.equals(category)) return 0;
        var manual = query(c, """
            SELECT e.*, 0 AS reversed FROM resource_events e WHERE resource='Primogems' AND event_day=?
                AND kind='MANUAL' AND NOT EXISTS (SELECT 1 FROM resource_events x WHERE x.reverses_id=e.id)
            ORDER BY id
            """, Ledger::eventRow, day);
        if (!manual.isEmpty() && overlap == Overlap.REJECT)
            throw new IllegalArgumentException(day + " has manual Primogem entries. Review them before replacing them with a full daily total.");
        long dailyId = prior == null ? insert(c, """
            INSERT INTO daily_income(provider,account,income_day,total,category,updated_at) VALUES (?,?,?,?,?,?)
            """, CSV_PROVIDER, account, day, row.total(), category, now.toEpochMilli()) : prior.id;
        if (prior != null) update(c, "UPDATE daily_income SET total=?,category=?,updated_at=? WHERE id=?",
                row.total(), category, now.toEpochMilli(), dailyId);

        long difference = row.total() - (prior == null ? 0 : prior.total);
        long change = event(c, uuid(), PRIMOGEMS, row.day(), now, difference, category,
                "Daily total set to " + row.total(), "IMPORT", null, dailyId, null);
        if (difference > 0) {
            long remaining = difference;
            for (Event entry : manual) {
                reverse(c, entry, now, "Replaced by CSV total for " + day);
                long transferred = Math.min(remaining, entry.amount());
                if (transferred > 0) {
                    long lot = event(c, uuid(), PRIMOGEMS, row.day(), now, 0, category,
                            "Transferred credit from manual entry #" + entry.id(), "IMPORT", null, null, null);
                    update(c, """
                        INSERT INTO objective_events(event_id,objective_id,generation,amount)
                        SELECT ?,objective_id,generation,MIN(amount,?) FROM objective_events
                        WHERE event_id=?
                        """, lot, transferred, entry.id());
                    update(c, "INSERT INTO income_lots(event_id,daily_id,remaining) VALUES (?,?,?)", lot, dailyId, transferred);
                    // Automatic-only objectives never received the manual entry.
                    applyShared(c, lot, PRIMOGEMS, row.day(), transferred, true, zone, true);
                    remaining -= transferred;
                }
            }
            if (remaining > 0) {
                applyShared(c, change, PRIMOGEMS, row.day(), remaining, true, zone);
                update(c, "INSERT INTO income_lots(event_id,daily_id,remaining) VALUES (?,?,?)", change, dailyId, remaining);
            }
        } else if (difference < 0) {
            unwind(c, dailyId, change, -difference);
        } else {
            for (Event entry : manual) reverse(c, entry, now, "Replaced by CSV total for " + day);
        }
        return 1;
    }

    private record Lot(long event, long remaining) {}
    private static void unwind(Connection c, long dailyId, long correction, long remaining) throws SQLException {
        for (Lot lot : query(c, "SELECT event_id,remaining FROM income_lots WHERE daily_id=? AND remaining>0 ORDER BY sequence DESC",
                r -> new Lot(r.getLong(1), r.getLong(2)), dailyId)) {
            long take = Math.min(remaining, lot.remaining);
            update(c, """
                INSERT INTO objective_events(event_id,objective_id,generation,amount)
                SELECT ?,objective_id,generation,-MAX(0, MIN(amount,?) - (? - ?))
                FROM objective_events WHERE event_id=?
                ON CONFLICT(event_id,objective_id,generation) DO UPDATE SET amount=amount+excluded.amount
                """, correction, lot.remaining, lot.remaining, take, lot.event);
            update(c, "UPDATE income_lots SET remaining=remaining-? WHERE event_id=?", take, lot.event);
            remaining -= take;
            if (remaining == 0) return;
        }
        throw new SQLException("Daily income reconciliation has a missing credit.");
    }

    private static void applyShared(Connection c, long event, String resource, LocalDate day, long amount,
                                    boolean imported, ZoneId zone) throws SQLException {
        applyShared(c, event, resource, day, amount, imported, zone, false);
    }

    private static void applyShared(Connection c, long event, String resource, LocalDate day, long amount,
                                    boolean imported, ZoneId zone, boolean autoOnly) throws SQLException {
        update(c, """
            INSERT OR IGNORE INTO objective_events(event_id,objective_id,generation,amount)
            SELECT ?,id,generation,? FROM objectives WHERE resource=? AND status='ACTIVE'
                AND (?=0 OR (source='AUTOMATIC' AND NOT EXISTS (SELECT 1 FROM objective_events prior
                    WHERE prior.event_id=? AND prior.objective_id=objectives.id)))
                AND source<>? AND (deadline IS NULL OR deadline>=?)
                AND eligible_since<?
            """, event, amount, resource, autoOnly ? 1 : 0, event, imported ? "MANUAL" : "AUTOMATIC", day.toString(),
                day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
    }

    static void recordPlaytime(Connection c, long sessionId, Instant from, Instant to, ZoneId zone) throws SQLException {
        var targets = query(c, """
            SELECT id,generation,eligible_since,deadline FROM objectives
            WHERE resource='Playtime' AND status='ACTIVE' AND source<>'MANUAL'
            """, r -> new PlayTarget(r.getLong(1),r.getLong(2),r.getLong(3),date(r.getString(4))));
        if (targets.isEmpty()) return;
        long event = 0;
        for (PlayTarget goal : targets) {
            long start = Math.max(from.toEpochMilli(), goal.since);
            long end = to.toEpochMilli();
            if (goal.deadline != null) end = Math.min(end, goal.deadline.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
            if (start >= end) continue;
            if (event == 0) event = event(c, "play:" + sessionId + ":" + to.toEpochMilli(), "Playtime",
                    to.atZone(zone).toLocalDate(), to, Duration.between(from, to).toMillis(),
                    "Tracked process", "Session #" + sessionId, "PLAYTIME", null, null, null);
            allocate(c, event, goal.id, goal.generation, end - start);
        }
    }

    private record PlayTarget(long id, long generation, long since, LocalDate deadline) {}
    private record Position(String resource, long generation, Source source) {}

    private static Position position(Connection c, long id) throws SQLException {
        return query(c, "SELECT resource,generation,source FROM objectives WHERE id=? AND status<>'DELETED'",
                r -> new Position(r.getString(1),r.getLong(2),Source.valueOf(r.getString(3))), id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That objective no longer exists."));
    }

    private static void objectiveEvent(Connection c, long id, long amount, String note, String kind, Instant now) throws SQLException {
        Position position = position(c, id);
        long event = event(c, uuid(), position.resource, now.atZone(ZoneId.systemDefault()).toLocalDate(), now,
                amount, "Objective progress", note, kind, id, null, null);
        allocate(c, event, id, position.generation, amount);
    }

    private static void allocate(Connection c, long event, long id, long generation, long amount) throws SQLException {
        update(c, "INSERT INTO objective_events VALUES (?,?,?,?)", event, id, generation, amount);
    }

    private static long event(Connection c, String key, String resource, LocalDate day, Instant now, long amount,
                              String category, String note, String kind, Long objective, Long daily, Long reverses) throws SQLException {
        return insert(c, """
            INSERT INTO resource_events(event_key,resource,event_day,created_at,amount,category,note,kind,objective_id,daily_id,reverses_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, key, resource, day.toString(), now.toEpochMilli(), amount, category, note, kind, objective, daily, reverses);
    }

    private static String uuid() { return UUID.randomUUID().toString(); }
    private static LocalDate date(String raw) { return raw == null ? null : LocalDate.parse(raw); }
    private static String normalizedCategory(String value, String fallback) { return value.isBlank() ? fallback : value.strip(); }

    private static void validateDay(LocalDate day, Instant now, ZoneId zone) {
        if (day == null || day.getYear() < 2000 || day.isAfter(now.atZone(zone).toLocalDate()))
            throw new IllegalArgumentException("Enter an income date from 2000 through today.");
    }

    private static void validateAmount(long amount, boolean positive) {
        if (amount == 0 || amount > LIMIT || amount < -LIMIT || positive && amount < 0)
            throw new IllegalArgumentException(positive ? "Enter a positive amount within the supported range."
                    : "Enter a signed nonzero amount within the supported range.");
    }
}

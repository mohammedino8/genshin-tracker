package tracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tracker.db.*;
import tracker.model.Models.*;
import tracker.service.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlaytimeTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final Duration LIMIT = Duration.ofSeconds(16);
    private static final Instant START = Instant.parse("2026-03-01T23:59:55Z");
    @TempDir Path directory;
    private SessionRepository sessions;
    private Database db;

    @BeforeEach void setup() throws Exception {
        db = new Database(directory.resolve("tracker.db"));
        db.initialize();
        sessions = new SessionRepository(db);
    }

    @Test void detectsStartHeartbeatAndExitWithoutDuplicateSessions() throws Exception {
        sessions.observe(false, START, UTC, LIMIT);
        assertTrue(sessions.all().isEmpty());
        sessions.observe(true, START, UTC, LIMIT);
        sessions.observe(true, START, UTC, LIMIT);
        sessions.observe(true, START.plusSeconds(10), UTC, LIMIT);
        var open = sessions.all().getFirst();
        assertNull(open.end());
        assertEquals(10_000, open.millis());
        sessions.observe(false, START.plusSeconds(15), UTC, LIMIT);
        assertEquals(1, sessions.all().size());
        assertEquals(START.plusSeconds(10), sessions.all().getFirst().end());
    }

    @Test void freshMonitorDetectsAlreadyRunningGameAndReportsDetectorFailures() throws Exception {
        var clock = Clock.fixed(START, UTC);
        var monitor = new PlayMonitor(sessions, () -> true, clock);
        monitor.poll();
        assertEquals("Running", monitor.state().label());
        assertEquals(START, sessions.all().getFirst().start());
        new PlayMonitor(sessions, () -> false, clock).poll();
        assertNotNull(sessions.all().getFirst().end());
        var failed = new PlayMonitor(sessions, () -> { throw new java.io.IOException("fake timeout"); }, clock);
        failed.poll();
        assertEquals("fake timeout", failed.state().error());
    }

    @Test void restartAndSleepCloseAtLastConfirmation() throws Exception {
        sessions.observe(true, START, UTC, LIMIT);
        sessions.observe(true, START.plusSeconds(5), UTC, LIMIT);
        sessions.recover();
        sessions.observe(true, START.plusSeconds(100), UTC, LIMIT);
        sessions.observe(true, START.plusSeconds(105), UTC, LIMIT);
        sessions.observe(true, START.plusSeconds(500), UTC, LIMIT);
        var history = sessions.all();
        assertEquals(3, history.size());
        assertEquals(10_000, PlayStats.calculate(history, UTC).lifetime());
        assertEquals(START.plusSeconds(105), history.get(1).end());
        assertEquals(START.plusSeconds(5), history.get(2).end());
    }

    @Test void backwardsClockDoesNotDoubleCountConfirmedIntervals() throws Exception {
        sessions.observe(true, START, UTC, LIMIT);
        sessions.observe(true, START.plusSeconds(5), UTC, LIMIT);
        sessions.observe(true, START.minusSeconds(20), UTC, LIMIT);
        assertEquals(1, sessions.all().size());
        assertNotNull(sessions.all().getFirst().end());
        sessions.observe(true, START.plusSeconds(5), UTC, LIMIT);
        sessions.observe(true, START.plusSeconds(10), UTC, LIMIT);
        assertEquals(10_000, PlayStats.calculate(sessions.all(), UTC).lifetime());
    }

    @Test void partitionsMidnightWeekMonthAndHours() {
        var stats = PlayStats.calculate(List.of(
                session(1, "2026-03-01T23:40:00Z", "2026-03-02T01:15:00Z"),
                session(2, "2026-03-31T23:50:00Z", "2026-04-01T00:10:00Z")), UTC);
        assertEquals(20 * 60_000L, stats.day(date("2026-03-01")));
        assertEquals(75 * 60_000L, stats.day(date("2026-03-02")));
        assertEquals(105 * 60_000L, stats.between(date("2026-03-01"), date("2026-03-31")));
        assertEquals(75 * 60_000L, stats.between(PlayStats.weekStart(date("2026-03-04")), date("2026-03-08")));
        assertEquals(70 * 60_000L, stats.hours().get(0));
        assertEquals(15 * 60_000L, stats.hours().get(1));
        assertEquals(30 * 60_000L, stats.hours().get(23));
        assertEquals(115 * 60_000L, stats.lifetime());
    }

    @Test void repeatedDstHourCountsBothOccurrencesAndSpringHourCountsZero() {
        ZoneId newYork = ZoneId.of("America/New_York");
        var fall = PlayStats.calculate(List.of(session(1, "2026-11-01T04:30:00Z", "2026-11-01T07:30:00Z")), newYork);
        assertEquals(7_200_000L, fall.hours().get(1));
        assertEquals(10_800_000L, fall.day(date("2026-11-01")));
        var spring = PlayStats.calculate(List.of(session(2, "2026-03-08T06:30:00Z", "2026-03-08T07:30:00Z")), newYork);
        assertEquals(0L, spring.hours().get(2));
        assertEquals(1_800_000L, spring.hours().get(1));
        assertEquals(1_800_000L, spring.hours().get(3));
    }

    @Test void halfHourDstTransitionUsesTheActualLocalHourBoundary() {
        var stats = PlayStats.calculate(List.of(session(1, "2026-04-04T14:30:00Z", "2026-04-04T16:00:00Z")),
                ZoneId.of("Australia/Lord_Howe"));
        assertEquals(3_600_000L, stats.hours().get(1));
        assertEquals(1_800_000L, stats.hours().get(2));
    }

    @Test void overlappingLegacySessionsArePreservedAndAggregatedOnce() {
        var stats = PlayStats.calculate(List.of(
                session(1, "2026-03-01T23:40:00Z", "2026-03-02T01:15:00Z"),
                session(2, "2026-03-02T00:50:00Z", "2026-03-02T01:30:00Z")), UTC);
        assertEquals(2, stats.count());
        assertEquals(110 * 60_000L, stats.lifetime());
    }

    @Test void migrationKeepsExistingIdsAndClosesLegacyOpenRows() throws Exception {
        Path legacy = directory.resolve("in-place.db");
        createLegacy(legacy);
        var target = new Database(legacy);
        target.initialize();
        target.initialize();
        var all = new SessionRepository(target).all();
        assertEquals(1, all.size());
        assertEquals(27, all.getFirst().id());
        assertEquals(START.plusSeconds(5), all.getFirst().end());
        assertFalse(new Ledger(target).resources().isEmpty());
    }

    @Test void importsReadOnlyLegacyFileIdempotently() throws Exception {
        Path legacy = directory.resolve("old.db");
        createLegacy(legacy);
        assertEquals(1, sessions.importLegacy(legacy));
        assertEquals(0, sessions.importLegacy(legacy));
        assertEquals(5_000, sessions.all().getFirst().millis());
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + legacy); var st = c.createStatement();
             var r = st.executeQuery("SELECT end_at FROM play_sessions")) {
            assertTrue(r.next());
            assertNull(r.getObject(1));
        }
    }

    @Test void exclusiveInstanceLockCanBeReacquiredAfterExit() throws Exception {
        try (var ignored = InstanceLock.acquire(directory)) {
            assertThrows(java.io.IOException.class, () -> InstanceLock.acquire(directory));
        }
        try (var ignored = InstanceLock.acquire(directory)) {
            assertNotNull(ignored);
        }
    }

    private static void createLegacy(Path legacy) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + legacy); var st = c.createStatement()) {
            st.execute("CREATE TABLE play_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, start_at INTEGER NOT NULL, last_seen_at INTEGER NOT NULL,end_at INTEGER)");
            try (var insert = c.prepareStatement("INSERT INTO play_sessions VALUES(27,?,?,NULL)")) {
                insert.setLong(1, START.toEpochMilli());
                insert.setLong(2, START.plusSeconds(5).toEpochMilli());
                insert.executeUpdate();
            }
        }
    }

    private static Session session(long id, String first, String last) {
        return new Session(id, Instant.parse(first), Instant.parse(last), Instant.parse(last));
    }

    private static LocalDate date(String date) { return LocalDate.parse(date); }
}

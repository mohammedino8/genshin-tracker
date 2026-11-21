package tracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tracker.db.*;
import tracker.integration.CsvIncomeProvider;
import tracker.model.Models.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LedgerTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);
    private static final Instant NOW = Instant.parse("2026-03-02T12:00:00Z");
    private static final Resource GEMS = new Resource("Primogems", "Primogems");
    @TempDir Path directory;
    private Database db;
    private Ledger ledger;

    @BeforeEach void setup() throws Exception {
        db = new Database(directory.resolve("tracker.db"));
        db.initialize();
        ledger = new Ledger(db);
    }

    @Test void sharedAmountsIncreaseEachActiveObjectiveOnceAndKeepBaselines() throws Exception {
        long a = objective("A", 1_000, Source.MIXED, NOW);
        long b = objective("B", 2_500, Source.MANUAL, NOW);
        long auto = objective("Automatic", 0, Source.AUTOMATIC, NOW);
        long income = manual("daily", 100);
        assertEquals(income, manual("daily", 100));
        assertEquals(1_100, progress(a));
        assertEquals(2_600, progress(b));
        assertEquals(0, progress(auto));
        long newGoal = objective("Later", 0, Source.MIXED, NOW.plusSeconds(60));
        assertEquals(0, progress(newGoal));
        assertEquals(100, ledger.income().total());
    }

    @Test void validatesTargetsAndCanCreateCustomResources() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ledger.saveObjective(0,
                new ObjectiveDraft("Invalid", "", GEMS, 0, null, Source.MIXED), 0, NOW));
        ledger.addResource("Boss drops", "items");
        assertTrue(ledger.resources().contains(new Resource("Boss drops", "items")));
        assertThrows(IllegalArgumentException.class, () -> ledger.addResource("boss drops", "items"));
    }

    @Test void objectiveAdjustmentsAndReversalsLeaveIncomeAndOtherObjectivesAlone() throws Exception {
        long a = objective("A", 100, Source.MIXED, NOW);
        long b = objective("B", 200, Source.MIXED, NOW);
        ledger.adjust(a, -30, "Fix starting amount", NOW);
        long id = ledger.events(false, a).getFirst().id();
        assertEquals(70, progress(a));
        assertEquals(200, progress(b));
        assertEquals(0, ledger.income().total());
        ledger.reverse(id, NOW);
        ledger.reverse(id, NOW);
        assertEquals(100, progress(a));
    }

    @Test void changingAnObjectiveResourceCreatesASeparateBaseline() throws Exception {
        long id = objective("Old", 100, Source.MIXED, NOW);
        long oldEntry = manual("old-earnings", 50);
        Resource quests = new Resource("Quests", "quests");
        ledger.saveObjective(id, new ObjectiveDraft("Quests", "", quests, 15, null, Source.MANUAL), 2, NOW);
        ledger.manual("new-quest", quests, TODAY, 3, "", "", NOW, UTC);
        ledger.reverse(oldEntry, NOW);
        assertEquals(5, progress(id));
        assertEquals(quests, ledger.objectives().getFirst().resource());
        assertEquals(5, ledger.events(false, id).size());
    }

    @Test void backdatedManualEntriesDoNotCreditLaterObjectives() throws Exception {
        long id = objective("Later", 0, Source.MIXED, NOW);
        ledger.manual("historical", GEMS, TODAY.minusDays(1), 50, "", "", NOW, UTC);
        assertEquals(0, progress(id));
        assertEquals(50, ledger.income().total());
    }

    @Test void completedArchivedAndDeletedGoalsDoNotAccumulateNewEntries() throws Exception {
        long active = objective("Active", 0, Source.MIXED, NOW);
        long done = objective("Done", 0, Source.MIXED, NOW);
        long archive = objective("Archive", 0, Source.MIXED, NOW);
        long removed = objective("Remove", 0, Source.MIXED, NOW);
        manual("first", 20);
        ledger.status(done, Status.COMPLETED, NOW);
        ledger.status(archive, Status.ARCHIVED, NOW);
        ledger.deleteObjective(removed);
        manual("second", 100);
        assertEquals(120, progress(active));
        assertEquals(20, progress(done));
        assertEquals(20, progress(archive));
        assertTrue(ledger.objectives().stream().noneMatch(o -> o.id() == removed));
        ledger.status(archive, Status.ACTIVE, NOW);
        manual("third", 15);
        assertEquals(35, progress(archive));
    }

    @Test void reversingEarlierEntryAfterResetDoesNotModifyNewBaseline() throws Exception {
        long a = objective("A", 100, Source.MIXED, NOW);
        long event = manual("old", 50);
        ledger.reset(a, 20, NOW.plusSeconds(20));
        ledger.reverse(event, NOW.plusSeconds(25));
        assertEquals(20, progress(a));
        assertEquals(0, ledger.income().total());
        assertEquals(4, ledger.events(false, a).size());
    }

    @Test void importsDailyTotalsIdempotentlyAndReconcilesUpwardAndDownward() throws Exception {
        long a = objective("A", 0, Source.MIXED, NOW);
        long manual = objective("Manual only", 0, Source.MANUAL, NOW);
        assertEquals(1, load(500, Ledger.Overlap.REJECT));
        assertEquals(0, load(500, Ledger.Overlap.REJECT));
        assertEquals(500, progress(a));
        assertEquals(0, progress(manual));
        assertEquals(1, load(550, Ledger.Overlap.REJECT));
        assertEquals(550, progress(a));
        ledger.status(a, Status.ARCHIVED, NOW);
        assertEquals(1, load(450, Ledger.Overlap.REJECT));
        assertEquals(450, progress(a));
        assertEquals(450, ledger.income().total());
        assertEquals(1, ledger.importInfo().coveredDays());
        assertEquals(List.of(-100L, 50L, 500L), ledger.events(true, null).stream().map(Event::amount).toList());
    }

    @Test void historicalImportCreditsOnlyObjectivesEligibleOnTheIncomeDate() throws Exception {
        long earlier = objective("Earlier", 0, Source.MIXED, NOW.minus(Duration.ofDays(2)));
        long later = objective("Later", 0, Source.MIXED, NOW);
        ledger.importIncome("main", List.of(new IncomeDay(TODAY.minusDays(1), 200, "")), Ledger.Overlap.REJECT, NOW, UTC);
        assertEquals(200, progress(earlier));
        assertEquals(0, progress(later));
        assertEquals(200, ledger.income().total());
    }

    @Test void replacingManualOverlapPreservesOriginalCreditsAndAddsOnlyNewIncome() throws Exception {
        long old = objective("Old", 0, Source.MIXED, NOW);
        long auto = objective("Auto", 0, Source.AUTOMATIC, NOW);
        manual("same-earnings", 100);
        long newGoal = objective("New", 0, Source.MIXED, NOW.plusSeconds(5));
        assertThrows(IllegalArgumentException.class, () -> load(150, Ledger.Overlap.REJECT));
        assertNull(ledger.importInfo());
        assertEquals(100, ledger.income().total());

        assertEquals(1, load(150, Ledger.Overlap.REPLACE_MANUAL));
        assertEquals(150, progress(old));
        assertEquals(150, progress(auto));
        assertEquals(50, progress(newGoal));
        assertEquals(150, ledger.income().total());
        assertEquals(0, load(150, Ledger.Overlap.REPLACE_MANUAL));
        assertEquals(1, load(90, Ledger.Overlap.REPLACE_MANUAL));
        assertEquals(90, progress(old));
        assertEquals(90, progress(auto));
        assertEquals(0, progress(newGoal));
        assertEquals(90, ledger.income().total());
        assertThrows(IllegalArgumentException.class, () -> manual("overlap-reintroduced", 25));
    }

    @Test void batchRollsBackEarlierDaysWhenALaterDayConflicts() throws Exception {
        manual("overlap", 100);
        var rows = List.of(new IncomeDay(TODAY.minusDays(1), 70, ""), new IncomeDay(TODAY, 200, ""));
        assertThrows(IllegalArgumentException.class, () -> ledger.importIncome("main", rows, Ledger.Overlap.REJECT, NOW, UTC));
        assertEquals(100, ledger.income().total());
        assertNull(ledger.importInfo());
        assertThrows(IllegalArgumentException.class, () ->
                ledger.importIncome("main", List.of(rows.getFirst(), rows.getFirst()), Ledger.Overlap.REPLACE_MANUAL, NOW, UTC));
        assertEquals(100, ledger.income().total());
    }

    @Test void concurrentImportAttemptsAreSerializedAndOnlyOneCreditIsApplied() throws Exception {
        long a = objective("A", 0, Source.MIXED, NOW);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> importDay = () -> { start.await(); return load(100, Ledger.Overlap.REJECT); };
            var first = executor.submit(importDay);
            var second = executor.submit(importDay);
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(100, ledger.income().total());
        assertEquals(100, progress(a));
    }

    @Test void playtimeObjectivesCreditConfirmedIntervalsAfterEligibility() throws Exception {
        var playtime = new Resource("Playtime", "hours");
        long active = ledger.saveObjective(0, new ObjectiveDraft("Hours", "", playtime, 3_600_000,
                TODAY, Source.AUTOMATIC), 0, NOW.plusSeconds(3));
        var sessions = new SessionRepository(db);
        sessions.observe(true, NOW, UTC, Duration.ofSeconds(16));
        sessions.observe(true, NOW.plusSeconds(6), UTC, Duration.ofSeconds(16));
        assertEquals(3_000, progress(active));
        sessions.observe(true, NOW.plusSeconds(600), UTC, Duration.ofSeconds(16));
        assertEquals(3_000, progress(active));
    }

    @Test void readsUtf8CsvWithQuotedCategoriesAndRejectsMalformedAmounts() throws Exception {
        var path = directory.resolve("income.csv");
        Files.writeString(path, "\ufeffdate,total,category\n2026-03-01,90,\"Events, commissions\"\n");
        assertEquals(List.of(new IncomeDay(TODAY.minusDays(1), 90, "Events, commissions")), new CsvIncomeProvider(path).dailyTotals());
        Files.writeString(path, "date,total,category\n2026-03-01,nope,Events");
        assertThrows(java.io.IOException.class, () -> new CsvIncomeProvider(path).dailyTotals());
    }

    @Test void tasksPersistAndCanBeEditedCompletedAndDeleted() throws Exception {
        var repository = new Tasks(db);
        repository.save(new Task(0, "Spend resin", "Daily", Priority.HIGH, TODAY, false));
        long id = repository.all().getFirst().id();
        repository.save(new Task(id, "Farm materials", "", Priority.NORMAL, null, true));
        assertEquals(new Task(id, "Farm materials", "", Priority.NORMAL, null, true), new Tasks(db).all().getFirst());
        repository.delete(id);
        assertTrue(repository.all().isEmpty());
    }

    private long objective(String title, long baseline, Source source, Instant time) throws Exception {
        return ledger.saveObjective(0, new ObjectiveDraft(title, "", GEMS, 10_000, null, source), baseline, time);
    }

    private long progress(long id) throws Exception {
        return ledger.objectives().stream().filter(o -> o.id() == id).findFirst().orElseThrow().progress();
    }

    private long manual(String key, long amount) throws Exception {
        return ledger.manual(key, GEMS, TODAY, amount, "Events", "", NOW, UTC);
    }

    private int load(long total, Ledger.Overlap overlap) throws Exception {
        return ledger.importIncome("main", List.of(new IncomeDay(TODAY, total, "Diary")), overlap, NOW.plusSeconds(10), UTC);
    }
}

package tracker.model;

import java.time.Instant;
import java.time.LocalDate;

public final class Models {
    private Models() {}

    public enum Status { ACTIVE, COMPLETED, ARCHIVED }
    public enum Source { MANUAL, AUTOMATIC, MIXED }
    public enum Priority { LOW, NORMAL, HIGH }

    public record Session(long id, Instant start, Instant lastSeen, Instant end) {
        public Instant confirmedEnd() { return end == null ? lastSeen : end; }
        public long millis() { return Math.max(0, confirmedEnd().toEpochMilli() - start.toEpochMilli()); }
    }
    public record Resource(String name, String unit) {
        @Override public String toString() { return name; }
        public long scale() { return "Playtime".equals(name) ? 3_600_000L : 1L; }
    }
    public record Objective(long id, String title, String description, Resource resource, long target,
                            long progress, Instant createdAt, LocalDate deadline, Status status, Source source) {}
    public record ObjectiveDraft(String title, String description, Resource resource, long target,
                                 LocalDate deadline, Source source) {}
    public record Event(long id, LocalDate day, Instant recorded, String resource, long amount,
                        String category, String note, String kind, Long objectiveId, Long reversesId,
                        boolean reversed) {}
    public record Task(long id, String title, String category, Priority priority, LocalDate due, boolean completed) {}
    public record IncomeDay(LocalDate day, long total, String category) {}
    public record ImportInfo(String account, LocalDate first, LocalDate last, int coveredDays, Instant importedAt) {}
}

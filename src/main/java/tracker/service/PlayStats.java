package tracker.service;

import tracker.model.Models.Session;
import java.time.*;
import java.time.temporal.*;
import java.util.*;

public record PlayStats(NavigableMap<LocalDate, Long> days, List<Long> hours, long lifetime,
                        int count, long averageSession, long longestSession) {

    public static PlayStats calculate(List<Session> sessions, ZoneId zone) {
        var days = new TreeMap<LocalDate, Long>();
        long[] hours = new long[24];
        long lifetime = 0;
        long longest = 0;
        Instant previousEnd = Instant.MIN;
        var sorted = sessions.stream().sorted(Comparator.comparing(Session::start)
                .thenComparing(Session::confirmedEnd, Comparator.reverseOrder())).toList();
        for (Session session : sorted) {
            Instant end = session.confirmedEnd();
            Instant cursor = session.start().isAfter(previousEnd) ? session.start() : previousEnd;
            if (!end.isAfter(cursor)) continue;
            long duration = Duration.between(cursor, end).toMillis();
            lifetime += duration;
            longest = Math.max(longest, session.millis());
            previousEnd = end;
            while (cursor.isBefore(end)) {
                var local = cursor.atZone(zone);
                Instant next = local.toOffsetDateTime().truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant();
                var transition = zone.getRules().nextTransition(cursor);
                if (transition != null && transition.getInstant().isBefore(next)) next = transition.getInstant();
                if (next.isAfter(end)) next = end;
                long millis = Duration.between(cursor, next).toMillis();
                days.merge(local.toLocalDate(), millis, Long::sum);
                hours[local.getHour()] += millis;
                cursor = next;
            }
        }
        int count = sessions.size();
        return new PlayStats(Collections.unmodifiableNavigableMap(days),
                Arrays.stream(hours).boxed().toList(), lifetime, count, count == 0 ? 0 : lifetime / count, longest);
    }

    public static LocalDate weekStart(LocalDate day) { return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }

    public long between(LocalDate first, LocalDate last) {
        return days.subMap(first, true, last, true).values().stream().mapToLong(Long::longValue).sum();
    }

    public long day(LocalDate date) { return days.getOrDefault(date, 0L); }
}

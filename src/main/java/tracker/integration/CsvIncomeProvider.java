package tracker.integration;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.DuplicateHeaderMode;
import tracker.model.Models.IncomeDay;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public record CsvIncomeProvider(Path file) implements IncomeProvider {
    @Override public String name() { return "Local CSV"; }

    @Override public List<IncomeDay> dailyTotals() throws IOException {
        if (Files.size(file) > 2_000_000) throw new IOException("Choose a CSV under 2 MB.");
        var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setTrim(true).get();
        var result = new ArrayList<IncomeDay>();
        try (var reader = new PushbackReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
            int first = reader.read();
            if (first != -1 && first != 0xfeff) reader.unread(first);
            try (var parser = format.parse(reader)) {
                if (!parser.getHeaderMap().containsKey("date") || !parser.getHeaderMap().containsKey("total"))
                    throw new IOException("CSV headers must include date and total. category is optional.");
                for (var row : parser) {
                    if (!row.isConsistent()) throw new IOException("CSV record " + row.getRecordNumber() + " has the wrong number of columns.");
                    try {
                        result.add(new IncomeDay(LocalDate.parse(row.get("date")), Long.parseLong(row.get("total")),
                                row.isMapped("category") ? row.get("category") : ""));
                    } catch (DateTimeParseException | NumberFormatException e) {
                        throw new IOException("CSV record " + row.getRecordNumber() + " needs a date (YYYY-MM-DD) and a whole-number total.", e);
                    }
                }
            }
        } catch (UncheckedIOException e) {
            throw new IOException("The CSV has invalid quoting or encoding.", e);
        }
        return List.copyOf(result);
    }
}

package tracker.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import tracker.db.Ledger;
import tracker.integration.CsvIncomeProvider;
import tracker.integration.IncomeProvider;
import tracker.model.Models.*;
import tracker.model.Models.Priority;
import tracker.service.PlayStats;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

final class TrackerViews {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("MMM d, uuuu · HH:mm:ss");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM uuuu");
    private final TrackerApp app;
    private String chartPeriod = "Daily";
    private String sessionPeriod = "All time";
    private Status objectiveStatus = Status.ACTIVE;
    private String taskFilter = "Pending";
    private YearMonth calendarMonth;
    private Long selectedIncome;
    private Long selectedSession;
    private final Map<Long, GoalProgress> visibleGoals = new HashMap<>();

    TrackerViews(TrackerApp app) { this.app = app; }

    Node create(TrackerApp.View view, TrackerApp.Snapshot data) {
        return switch (view) {
            case DASHBOARD -> dashboard(data);
            case ANALYTICS -> analytics(data);
            case SESSIONS -> sessions(data);
            case PRIMOGEMS -> primogems(data);
            case OBJECTIVES -> objectives(data);
            case TODO -> tasks(data);
            case SETTINGS -> settings();
        };
    }

    private Node dashboard(TrackerApp.Snapshot data) {
        var current = data.sessions().stream().filter(s -> s.end() == null).findFirst().orElse(null);
        var hero = card(label("CURRENT SESSION", "hero-kicker"),
                label(timer(current == null ? 0 : current.millis()), "hero-value"),
                label(current == null ? "Tracking begins when Genshin Impact opens." :
                        "Started " + format(current.start(), data.zone()), "hero-caption"));
        hero.getStyleClass().add("hero");
        if (getClass().getResource("/tracker/hero.jpg") != null)
            hero.setStyle("-fx-background-image: url('" + getClass().getResource("/tracker/hero.jpg").toExternalForm() + "');");
        var clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(hero.widthProperty());
        clip.heightProperty().bind(hero.heightProperty());
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        hero.setClip(clip);
        hero.setAlignment(Pos.CENTER_LEFT);

        var today = data.today();
        var stats = data.stats();
        var goals = card(label("Active objectives", "section-title"));
        data.objectives().stream().filter(o -> o.status() == Status.ACTIVE).limit(3)
                .forEach(o -> goals.getChildren().add(progress(o)));
        if (goals.getChildren().size() == 1)
            goals.getChildren().add(empty("No active objectives", "Create an objective to start tracking progress."));
        var tasks = card(label("Upcoming To-do", "section-title"));
        data.tasks().stream().filter(t -> !t.completed()).limit(4).forEach(t ->
                tasks.getChildren().add(line(t.title(), due(t.due(), today))));
        if (tasks.getChildren().size() == 1)
            tasks.getChildren().add(empty("No pending tasks", "Add a task in To-do."));
        var activity = card(label("Recent sessions", "section-title"));
        data.sessions().stream().limit(4).forEach(s ->
                activity.getChildren().add(line(format(s.start(), data.zone()), duration(s.millis()))));
        if (activity.getChildren().size() == 1)
            activity.getChildren().add(empty("No sessions yet", "Confirmed playtime will appear here."));
        return new VBox(16, hero,
                columns(metric("Today", duration(stats.day(today))),
                        metric("This week", duration(stats.between(PlayStats.weekStart(today), today))),
                        metric("This month", duration(stats.between(today.withDayOfMonth(1), today))),
                        metric("Lifetime", duration(stats.lifetime()))),
                columns(metric("Primogems today", count(data.income().between(today, today))),
                        metric("Sessions", count(stats.count())),
                        metric("Average session", duration(stats.averageSession())),
                        metric("Longest session", duration(stats.longestSession()))),
                columns(goals, tasks), activity);
    }

    private Node analytics(TrackerApp.Snapshot data) {
        var stats = data.stats();
        var today = data.today();
        var selector = choose(List.of("Daily", "Weekly", "Monthly"), chartPeriod);
        selector.setOnAction(e -> { chartPeriod = selector.getValue(); app.redraw(); });
        var series = new LinkedHashMap<String, Long>();
        switch (chartPeriod) {
            case "Weekly" -> {
                LocalDate first = PlayStats.weekStart(today.minusWeeks(7));
                for (int i = 0; i < 8; i++) {
                    LocalDate start = first.plusWeeks(i);
                    series.put(start.format(SHORT_DATE), stats.between(start, start.plusDays(6)));
                }
            }
            case "Monthly" -> {
                YearMonth first = YearMonth.from(today).minusMonths(5);
                for (int i = 0; i < 6; i++) {
                    var month = first.plusMonths(i);
                    series.put(month.format(DateTimeFormatter.ofPattern("MMM uuuu")), stats.between(month.atDay(1), month.atEndOfMonth()));
                }
            }
            default -> {
                for (int i = 13; i >= 0; i--) series.put(today.minusDays(i).format(SHORT_DATE), stats.day(today.minusDays(i)));
            }
        }
        long previous = stats.between(today.minusDays(13), today.minusDays(7));
        long current = stats.between(today.minusDays(6), today);
        var longest = stats.days().entrySet().stream().max(Map.Entry.comparingByValue());
        long trackedDays = stats.days().isEmpty() ? 0 : java.time.temporal.ChronoUnit.DAYS.between(stats.days().firstKey(), today) + 1;
        long averageDay = trackedDays <= 0 ? 0 : stats.lifetime() / trackedDays;
        var hours = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 24; i++) hours.put("%02d".formatted(i), stats.hours().get(i));
        var peak = hours.entrySet().stream().filter(e -> e.getValue() > 0).max(Map.Entry.comparingByValue());
        var chart = card(sectionRow("Playtime history", selector), chart(series, true, 240),
                label("Last 7 days " + duration(current) + "   ·   Previous 7 days " + duration(previous), "muted"));
        var hourCard = card(label("Hours of day", "section-title"), chart(hours, true, 250),
                label(peak.map(e -> "Busiest hour " + e.getKey() + ":00 · " + duration(e.getValue()))
                        .orElse("No hourly playtime yet"), "muted"));
        var periods = card(label("Time of day", "section-title"));
        periods.getChildren().addAll(line("Morning · 06:00–11:59", duration(hours(stats, 6, 12))),
                line("Afternoon · 12:00–17:59", duration(hours(stats, 12, 18))),
                line("Evening · 18:00–23:59", duration(hours(stats, 18, 24))),
                line("Night · 00:00–05:59", duration(hours(stats, 0, 6))));
        var weekdays = card(label("Days of the week", "section-title"));
        var sums = new EnumMap<DayOfWeek, Long>(DayOfWeek.class);
        stats.days().forEach((day, ms) -> sums.merge(day.getDayOfWeek(), ms, Long::sum));
        Arrays.stream(DayOfWeek.values()).sorted(Comparator.comparingLong(
                (DayOfWeek day) -> sums.getOrDefault(day, 0L)).reversed()).forEach(day -> weekdays.getChildren().add(
                        line(day.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()), duration(sums.getOrDefault(day, 0L)))));
        return new VBox(16,
                columns(metric("Average per day", duration(averageDay)),
                        metric("Longest gaming day", longest.map(e -> duration(e.getValue())).orElse("—")),
                        metric("Longest day date", longest.map(e -> e.getKey().format(SHORT_DATE)).orElse("—"))),
                chart, calendar(data), hourCard, columns(periods, weekdays));
    }

    private Node calendar(TrackerApp.Snapshot data) {
        if (calendarMonth == null) calendarMonth = YearMonth.from(data.today());
        var previous = button("‹", () -> { calendarMonth = calendarMonth.minusMonths(1); app.redraw(); });
        previous.setAccessibleText("Previous month");
        var next = button("›", () -> { calendarMonth = calendarMonth.plusMonths(1); app.redraw(); });
        next.setAccessibleText("Next month");
        next.setDisable(!calendarMonth.isBefore(YearMonth.from(data.today())));
        var grid = new GridPane(6, 6);
        for (int i = 0; i < 7; i++) {
            var col = new ColumnConstraints();
            col.setPercentWidth(100.0 / 7);
            grid.getColumnConstraints().add(col);
            grid.add(label(DayOfWeek.of(i + 1).getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault()), "muted"), i, 0);
        }
        int offset = calendarMonth.atDay(1).getDayOfWeek().getValue() - 1;
        for (int day = 1; day <= calendarMonth.lengthOfMonth(); day++) {
            LocalDate date = calendarMonth.atDay(day);
            long value = data.stats().day(date);
            var cell = new VBox(4, label(Integer.toString(day), "cal-num"), label(value > 0 ? duration(value) : "—", "cal-duration"));
            cell.getStyleClass().add("calendar-day");
            if (value > 0) cell.getStyleClass().add("calendar-played");
            if (date.equals(data.today())) cell.getStyleClass().add("calendar-today");
            Tooltip.install(cell, new Tooltip(date + " · " + duration(value)));
            grid.add(cell, (offset + day - 1) % 7, (offset + day - 1) / 7 + 1);
        }
        return card(sectionRow("Calendar · " + calendarMonth.format(MONTH), previous, next), grid);
    }

    private Node sessions(TrackerApp.Snapshot data) {
        var filter = choose(List.of("All time", "Today", "This week", "This month"), sessionPeriod);
        filter.setOnAction(e -> { sessionPeriod = filter.getValue(); app.redraw(); });
        LocalDate first = switch (sessionPeriod) {
            case "Today" -> data.today();
            case "This week" -> PlayStats.weekStart(data.today());
            case "This month" -> data.today().withDayOfMonth(1);
            default -> null;
        };
        var rows = data.sessions().stream().filter(s -> first == null
                || s.start().atZone(data.zone()).toLocalDate().equals(first)
                || s.confirmedEnd().isAfter(first.atStartOfDay(data.zone()).toInstant())).toList();
        TableView<Session> table = table(rows, "No sessions for this period.");
        column(table, "Started", s -> format(s.start(), data.zone()), 220);
        column(table, "Ended", s -> s.end() == null ? "In progress" : format(s.end(), data.zone()), 220);
        column(table, "Last confirmed", s -> format(s.lastSeen(), data.zone()), 190);
        column(table, "Duration", s -> duration(s.millis()), 105);
        table.setPrefHeight(550);
        restoreSelection(table, selectedSession, Session::id);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (item != null) selectedSession = item.id();
        });
        return new VBox(15, sectionRow(count(rows.size()) + " sessions", filter), table);
    }

    private Node primogems(TrackerApp.Snapshot data) {
        LocalDate today = data.today();
        var income = data.income();
        var add = primary("Log income", () -> manual(data, data.resources().stream()
                .filter(r -> r.name().equals(Ledger.PRIMOGEMS)).findFirst().orElseThrow()));
        var importButton = button("Import daily CSV", () -> importCsv(data));
        var bars = new LinkedHashMap<String, Long>();
        for (int i = 13; i >= 0; i--) {
            var day = today.minusDays(i);
            bars.put(day.format(SHORT_DATE), income.days().getOrDefault(day, 0L));
        }
        var categories = card(label("Income by source", "section-title"));
        income.categories().entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(e -> categories.getChildren().add(line(e.getKey(), count(e.getValue()))));
        if (categories.getChildren().size() == 1)
            categories.getChildren().add(empty("No sources recorded", "Add a category when recording income."));
        var goals = card(label("Active Primogem objectives", "section-title"));
        data.objectives().stream().filter(o -> o.resource().name().equals(Ledger.PRIMOGEMS) && o.status() == Status.ACTIVE)
                .forEach(o -> goals.getChildren().add(progress(o)));
        if (goals.getChildren().size() == 1)
            goals.getChildren().add(empty("No active Primogem objectives", "Add a Primogem target in Objectives."));
        String coverage = data.importInfo() == null ? "No daily totals imported."
                : data.importInfo().account() + " · " + data.importInfo().coveredDays() + " dated rows between "
                    + data.importInfo().first() + " and " + data.importInfo().last()
                    + " · Last import " + format(data.importInfo().importedAt(), data.zone());
        var history = eventTable(data.incomeEvents(), data, false);
        restoreSelection(history, selectedIncome, Event::id);
        history.getSelectionModel().selectedItemProperty().addListener((o, old, item) -> {
            if (item != null) selectedIncome = item.id();
        });
        return new VBox(16, sectionRow("Income ledger", importButton, add),
                columns(metric("Today", count(income.between(today, today))),
                        metric("This week", count(income.between(PlayStats.weekStart(today), today))),
                        metric("This month", count(income.between(today.withDayOfMonth(1), today))),
                        metric("Total tracked", count(income.total()))),
                card(label("Daily income · last 14 days", "section-title"), chart(bars, false, 230)),
                columns(categories, goals),
                card(label("HoYoLAB automatic sync unavailable", "section-title"), label(coverage, "muted")),
                card(sectionRow("Recent income entries", reverseButton(history)), history));
    }

    private Node objectives(TrackerApp.Snapshot data) {
        visibleGoals.clear();
        var filter = choose(List.of(Status.values()), objectiveStatus);
        filter.setOnAction(e -> { objectiveStatus = filter.getValue(); app.redraw(); });
        var items = data.objectives().stream().filter(o -> o.status() == objectiveStatus).toList();
        var block = new VBox(12, sectionRow("Objectives", filter, button("Add resource type", this::addResource),
                button("Log shared progress", () -> manual(data, null)), primary("New objective", () -> objective(data, null))));
        if (items.isEmpty()) block.getChildren().add(card(empty("No " + human(objectiveStatus).toLowerCase(Locale.ROOT) + " objectives",
                "Create an objective or choose another status.")));
        for (var o : items) {
            var actions = new MenuButton("Actions");
            if (o.status() != Status.COMPLETED) actions.getItems().add(menu("Mark completed", () -> setStatus(o, Status.COMPLETED)));
            if (o.status() != Status.ARCHIVED) actions.getItems().add(menu("Archive", () -> setStatus(o, Status.ARCHIVED)));
            if (o.status() != Status.ACTIVE) actions.getItems().add(menu("Reopen", () -> setStatus(o, Status.ACTIVE)));
            actions.getItems().addAll(menu("Edit", () -> objective(data, o)), menu("Adjust progress", () -> adjust(o)),
                    menu("Progress history", () -> history(data, o)), menu("Reset progress", () -> reset(o)),
                    menu("Delete", () -> deleteObjective(o)));
            var item = card(sectionRow(o.title(), actions),
                    label(o.resource().name() + " · " + human(o.source()) + " · " + due(o.deadline(), data.today()), "muted"));
            if (!o.description().isBlank()) item.getChildren().add(wrapped(o.description()));
            var progress = new GoalProgress(o);
            visibleGoals.put(o.id(), progress);
            item.getChildren().add(progress);
            block.getChildren().add(item);
        }
        block.getChildren().add(button("Shared resource history", () -> history(data, null)));
        return block;
    }

    private Node tasks(TrackerApp.Snapshot data) {
        var filter = choose(List.of("Pending", "Completed", "All tasks"), taskFilter);
        filter.setOnAction(e -> { taskFilter = filter.getValue(); app.redraw(); });
        var list = data.tasks().stream().filter(t -> "All tasks".equals(taskFilter)
                || t.completed() == "Completed".equals(taskFilter)).toList();
        var block = new VBox(12, sectionRow(count(list.size()) + " tasks", filter, primary("New task", () -> task(null))));
        if (list.isEmpty()) block.getChildren().add(card(empty("No tasks to show", "Create a task or choose another filter.")));
        for (var t : list) {
            var checkbox = new CheckBox(t.title());
            checkbox.setSelected(t.completed());
            checkbox.setWrapText(true);
            checkbox.getStyleClass().add("value-title");
            checkbox.setOnAction(e -> app.perform(() -> {
                app.tasks.save(new Task(t.id(), t.title(), t.category(), t.priority(), t.due(), !t.completed()));
                return t.completed() ? "Task reopened." : "Task completed.";
            }));
            var controls = new MenuButton("Actions", null, menu("Edit", () -> task(t)), menu("Delete", () -> {
                if (confirm("Delete task?", t.title())) app.perform(() -> { app.tasks.delete(t.id()); return "Task deleted."; });
            }));
            var heading = new HBox(12, checkbox, spacer(), controls);
            heading.setAlignment(Pos.CENTER_LEFT);
            var caption = label(human(t.priority()) + " priority · " + due(t.due(), data.today())
                    + (t.category().isBlank() ? "" : " · " + t.category()), "muted");
            block.getChildren().add(card(heading, caption));
        }
        return block;
    }

    private Node settings() {
        return new VBox(16, card(label("Local database", "section-title"),
                wrapped(app.database.path().toString()),
                wrapped("Import sessions from an earlier genshin_tracker.db. The original file stays available."),
                button("Import legacy sessions", () -> {
                    var selected = file("Choose a legacy database", "SQLite database", "*.db", "*.sqlite", "*.sqlite3");
                    if (selected != null) app.perform(() -> "Imported " + app.sessions.importLegacy(selected) + " legacy sessions.");
                })),
                card(label("System tray", "section-title"),
                        wrapped(app.trayReady() ? "Closing the window hides it in the tray. The tracker keeps running." :
                            "The tray is unavailable in this desktop session. Closing the window exits the tracker."),
                        button("Exit completely", app::exit)),
                card(label("HoYoLAB", "section-title"), wrapped("Automatic synchronization is currently unavailable. "
                        + "Use manual income entries or import a CSV of daily totals in Primogems.")),
                card(label("Playtime", "section-title"), wrapped("The tracker polls every five seconds while open or in the tray. "
                        + "Totals use confirmed process observations and the current local time zone. Weeks start on Monday.")));
    }

    private void manual(TrackerApp.Snapshot data, Resource fixed) {
        var resource = choose(data.resources(), fixed);
        var amount = field("Amount earned");
        var day = new DatePicker(data.today());
        var source = field("Optional");
        var note = field("Optional");
        ask(fixed == null ? "Log shared progress" : "Log Primogem income",
                form("Resource", resource, "Amount", amount, "Date earned", day, "Source", source, "Note", note), () -> {
                    Resource type = required(resource.getValue(), "Choose a resource.");
                    long value = quantity(amount.getText(), type);
                    if (value <= 0) throw new IllegalArgumentException("Enter a positive amount.");
                    LocalDate earned = required(readDate(day), "Choose the date earned.");
                    if (earned.isAfter(data.today()) || earned.getYear() < 2000)
                        throw new IllegalArgumentException("Choose a date from 2000 through today.");
                    String request = UUID.randomUUID().toString();
                    String category = source.getText();
                    String detail = note.getText();
                    return () -> {
                        app.ledger.manual(request, type, earned, value, category, detail, Instant.now(), data.zone());
                        return "Shared progress recorded.";
                    };
                });
    }

    private void importCsv(TrackerApp.Snapshot data) {
        var selected = file("Choose a daily totals CSV", "CSV", "*.csv");
        if (selected == null) return;
        previewImport(data, new CsvIncomeProvider(selected));
    }

    private void previewImport(TrackerApp.Snapshot data, IncomeProvider provider) {
        app.load(provider::dailyTotals, rows -> {
            var label = field("e.g. Europe main");
            if (data.importInfo() != null) label.setText(data.importInfo().account());
            var replace = new CheckBox("Replace manual entries on imported dates");
            var minimum = rows.stream().map(IncomeDay::day).min(LocalDate::compareTo);
            var maximum = rows.stream().map(IncomeDay::day).max(LocalDate::compareTo);
            var text = wrapped(provider.name() + " · " + rows.size() + " daily rows" + minimum.map(d -> " · " + d + " through " + maximum.orElseThrow()).orElse("")
                    + ". A row is the full earned total for its date. Check manual income entries before enabling replacement.");
            ask("Import Primogem daily totals", new VBox(12, text, form("Account label", label), replace), () -> {
                String account = label.getText().strip();
                Ledger.Overlap overlap = replace.isSelected() ? Ledger.Overlap.REPLACE_MANUAL : Ledger.Overlap.REJECT;
                if (account.isEmpty()) throw new IllegalArgumentException("Enter a local account label.");
                return () -> {
                    int changed = app.ledger.importIncome(account, rows, overlap, Instant.now(), ZoneId.systemDefault());
                    return "CSV imported. " + changed + " daily totals changed.";
                };
            });
        });
    }

    private void addResource() {
        var name = field("e.g. Boss drops");
        var unit = field("e.g. items");
        ask("Add resource type", form("Name", name, "Unit", unit), () -> {
            String n = requiredText(name, "Enter a name.");
            String u = requiredText(unit, "Enter a unit.");
            return () -> { app.ledger.addResource(n, u); return "Resource type added."; };
        });
    }

    private void objective(TrackerApp.Snapshot data, Objective old) {
        var title = field("e.g. Collect 5,000 Primogems");
        var detail = field("Optional");
        var resource = choose(data.resources(), old == null ? null : old.resource());
        var target = field("Positive amount");
        var baseline = field("0");
        var deadline = new DatePicker();
        var source = choose(List.of(Source.values()), old == null ? Source.MIXED : old.source());
        if (old != null) {
            title.setText(old.title());
            detail.setText(old.description());
            target.setText(raw(old.target(), old.resource()));
            deadline.setValue(old.deadline());
        }
        var layout = form("Title", title, "Description", detail, "Resource", resource, "Target", target,
                "Starting amount", baseline, "Deadline", deadline, "Progress source", source);
        if (old != null) {
            baseline.setDisable(true);
            resource.valueProperty().addListener((obs, previous, selected) -> {
                boolean changed = !old.resource().equals(selected);
                baseline.setDisable(!changed);
                target.setText(changed ? "" : raw(old.target(), old.resource()));
            });
        }
        String hint = old == null ? "Automatic progress comes from Playtime or imported Primogem totals. Mixed also accepts manual entries."
                : "Changing the resource starts a new baseline. Previous entries stay in the progress history.";
        ask(old == null ? "New objective" : "Edit objective", new VBox(11, layout, wrapped(hint)), () -> {
            var type = required(resource.getValue(), "Choose a resource.");
            var draft = new ObjectiveDraft(requiredText(title, "Enter a title."), detail.getText(), type,
                    quantity(target.getText(), type), readDate(deadline), source.getValue());
            if (draft.target() <= 0) throw new IllegalArgumentException("Enter a positive target.");
            boolean newBaseline = old == null || !old.resource().equals(type);
            long start = newBaseline && !baseline.getText().isBlank() ? quantity(baseline.getText(), type) : 0;
            if (start < 0) throw new IllegalArgumentException("Enter a nonnegative starting amount.");
            return () -> {
                app.ledger.saveObjective(old == null ? 0 : old.id(), draft, start, Instant.now());
                return old == null ? "Objective created." : "Objective updated.";
            };
        });
    }

    private void setStatus(Objective o, Status status) {
        app.perform(() -> { app.ledger.status(o.id(), status, Instant.now()); return "Objective status updated."; });
    }

    private void deleteObjective(Objective o) {
        if (confirm("Delete objective?", o.title() + " will be removed from your objectives. Its audit entries will remain."))
            app.perform(() -> { app.ledger.deleteObjective(o.id()); return "Objective deleted."; });
    }

    private void adjust(Objective o) {
        var amount = field("e.g. 100 or -100");
        var reason = field("Optional");
        ask("Adjust " + o.title(), form("Adjustment (" + o.resource().unit() + ")", amount, "Reason", reason), () -> {
            long value = quantity(amount.getText(), o.resource());
            if (value == 0) throw new IllegalArgumentException("Enter a nonzero adjustment.");
            String note = reason.getText();
            return () -> { app.ledger.adjust(o.id(), value, note, Instant.now()); return "Objective adjusted."; };
        });
    }

    private void reset(Objective o) {
        var amount = field("0");
        ask("Reset " + o.title(), new VBox(10, wrapped("This starts a new progress baseline. The audit history keeps the previous entries."),
                form("Starting amount (" + o.resource().unit() + ")", amount)), () -> {
            long value = amount.getText().isBlank() ? 0 : quantity(amount.getText(), o.resource());
            if (value < 0) throw new IllegalArgumentException("Enter a nonnegative starting amount.");
            return () -> { app.ledger.reset(o.id(), value, Instant.now()); return "Objective progress reset."; };
        });
    }

    private void history(TrackerApp.Snapshot data, Objective objective) {
        app.load(() -> app.ledger.events(false, objective == null ? null : objective.id()), items -> {
            var history = eventTable(items, data, true);
            var dialog = new Dialog<Void>();
            prepare(dialog, objective == null ? "Resource event history" : objective.title() + " · history");
            var reverse = reverseButton(history);
            reverse.setOnAction(e -> {
                var chosen = history.getSelectionModel().getSelectedItem();
                if (chosen != null && requestReversal(chosen)) dialog.close();
            });
            dialog.getDialogPane().setPrefWidth(850);
            dialog.getDialogPane().setContent(new VBox(10, reverse, history));
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
            dialog.showAndWait();
        });
    }

    private boolean requestReversal(Event event) {
        if (!confirm("Reverse entry #" + event.id() + "?", "This records an offsetting entry and corrects the original objective allocations."))
            return false;
        app.perform(() -> { app.ledger.reverse(event.id(), Instant.now()); return "Entry reversed."; });
        return true;
    }

    private Button reverseButton(TableView<Event> history) {
        var reverse = button("Reverse selected", () -> {
            var selected = history.getSelectionModel().getSelectedItem();
            if (selected != null) requestReversal(selected);
        });
        reverse.setDisable(true);
        history.getSelectionModel().selectedItemProperty().addListener((o, old, item) ->
                reverse.setDisable(item == null || item.reversed() || !List.of("MANUAL", "ADJUSTMENT").contains(item.kind())));
        var selected = history.getSelectionModel().getSelectedItem();
        if (selected != null) reverse.setDisable(selected.reversed() || !List.of("MANUAL", "ADJUSTMENT").contains(selected.kind()));
        return reverse;
    }

    private TableView<Event> eventTable(List<Event> items, TrackerApp.Snapshot data, boolean showResource) {
        var history = table(items, "No entries recorded.");
        column(history, "ID", e -> Long.toString(e.id()), 65);
        column(history, "Earned date", e -> e.day().toString(), 115);
        if (showResource) column(history, "Resource", Event::resource, 125);
        column(history, "Amount", e -> e.resource().equals("Playtime") ? raw(e.amount(), new Resource("Playtime", "hours"))
                : count(e.amount()), 83);
        column(history, "Entry type", e -> e.reversed() ? human(e.kind()) + " (reversed)" : human(e.kind()), 150);
        column(history, "Source", Event::category, 120);
        column(history, "Note", Event::note, 200);
        history.setPrefHeight(285);
        return history;
    }

    private void task(Task old) {
        var title = field("e.g. Spend Original Resin");
        var category = field("Optional");
        var priority = choose(List.of(Priority.values()), old == null ? Priority.NORMAL : old.priority());
        var due = new DatePicker(old == null ? null : old.due());
        if (old != null) { title.setText(old.title()); category.setText(old.category()); }
        ask(old == null ? "New task" : "Edit task", form("Title", title, "Category", category, "Priority", priority, "Due date", due), () -> {
            var task = new Task(old == null ? 0 : old.id(), requiredText(title, "Enter a task title."), category.getText(),
                    priority.getValue(), readDate(due), old != null && old.completed());
            return () -> { app.tasks.save(task); return "Task saved."; };
        });
    }

    private void ask(String title, Node form, Supplier<TrackerApp.Action> validate) {
        var dialog = new Dialog<TrackerApp.Action>();
        prepare(dialog, title);
        var error = label("", "message-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        dialog.getDialogPane().setContent(new VBox(10, form, error));
        dialog.getDialogPane().setPrefWidth(500);
        var save = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, save);
        var result = new java.util.concurrent.atomic.AtomicReference<TrackerApp.Action>();
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try { result.set(validate.get()); }
            catch (IllegalArgumentException e) {
                error.setText(e.getMessage());
                error.setManaged(true);
                error.setVisible(true);
                event.consume();
            }
        });
        dialog.setResultConverter(type -> type == save ? result.get() : null);
        dialog.showAndWait().ifPresent(app::perform);
    }

    private boolean confirm(String title, String text) {
        var alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.CANCEL, ButtonType.OK);
        prepare(alert, title);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void prepare(Dialog<?> dialog, String title) {
        dialog.setTitle("Genshin Tracker");
        dialog.setHeaderText(title);
        dialog.initOwner(app.stage());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/tracker/theme.css").toExternalForm());
    }

    private Path file(String title, String description, String... globs) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, globs));
        var selected = chooser.showOpenDialog(app.stage());
        return selected == null ? null : selected.toPath();
    }

    private static GridPane form(Object... pairs) {
        var grid = new GridPane(9, 11);
        var left = new ColumnConstraints();
        left.setMinWidth(120);
        var right = new ColumnConstraints();
        right.setHgrow(javafx.scene.layout.Priority.ALWAYS);
        grid.getColumnConstraints().addAll(left, right);
        for (int i = 0; i < pairs.length; i += 2) {
            var name = label(pairs[i].toString(), "muted");
            Node control = (Node) pairs[i + 1];
            name.setLabelFor(control);
            if (control instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
            grid.addRow(i / 2, name, control);
        }
        return grid;
    }

    private static <T> ComboBox<T> choose(List<T> values, T selected) {
        var control = new ComboBox<>(FXCollections.observableArrayList(values));
        control.setVisibleRowCount(10);
        control.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : value instanceof Enum<?> ? human(value) : value.toString(); }
            @Override public T fromString(String text) { return values.stream().filter(v -> toString(v).equals(text)).findFirst().orElse(null); }
        });
        control.setValue(selected);
        return control;
    }

    private static <T> TableView<T> table(List<T> rows, String placeholder) {
        var table = new TableView<>(FXCollections.observableArrayList(rows));
        table.setPlaceholder(label(placeholder, "muted"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    private static <T> void column(TableView<T> table, String name, Function<T, String> extract, int width) {
        var column = new TableColumn<T, String>(name);
        column.setCellValueFactory(row -> new ReadOnlyStringWrapper(extract.apply(row.getValue())));
        column.setPrefWidth(width);
        column.setMinWidth(Math.min(65, width));
        column.setSortable(false);
        table.getColumns().add(column);
    }

    private static <T> void restoreSelection(TableView<T> table, Long id, ToLongFunction<T> key) {
        if (id != null) table.getItems().stream().filter(item -> key.applyAsLong(item) == id).findFirst()
                .ifPresent(item -> table.getSelectionModel().select(item));
    }

    private static BarChart<String, Number> chart(Map<String, Long> data, boolean playtime, int height) {
        var x = new CategoryAxis();
        x.setTickLabelRotation(data.size() > 12 ? -35 : 0);
        var y = new NumberAxis();
        y.setLabel(playtime ? "Hours" : "Primogems");
        y.setMinorTickVisible(false);
        var chart = new BarChart<String, Number>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setCategoryGap(data.size() > 12 ? 6 : 15);
        chart.setPrefHeight(height);
        chart.setMinHeight(height);
        var series = new XYChart.Series<String, Number>();
        data.forEach((name, value) -> {
            var item = new XYChart.Data<String, Number>(name, playtime ? value / 3_600_000.0 : value);
            item.nodeProperty().addListener((o, old, node) -> {
                if (node != null) Tooltip.install(node, new Tooltip(name + " · " + (playtime ? duration(value) : count(value))));
            });
            series.getData().add(item);
        });
        chart.getData().add(series);
        return chart;
    }

    private static VBox progress(Objective o) {
        var block = new VBox(6, label(o.title(), "value-title"), new GoalProgress(o));
        block.getStyleClass().add("row-detail");
        return block;
    }

    void updateObjectiveProgress(List<Objective> values) {
        for (Objective goal : values) {
            GoalProgress row = visibleGoals.get(goal.id());
            if (row != null) row.update(goal);
        }
    }

    private static final class GoalProgress extends VBox {
        private final ProgressBar bar = new ProgressBar();
        private final Label detail = wrapped("");

        GoalProgress(Objective goal) {
            super(6);
            bar.setMaxWidth(Double.MAX_VALUE);
            getChildren().addAll(bar, detail);
            update(goal);
        }

        void update(Objective goal) {
            double fraction = (double) goal.progress() / goal.target();
            bar.setProgress(Math.clamp(fraction, 0, 1));
            bar.setAccessibleText(goal.title() + " progress");
            long remaining = Math.max(0, goal.target() - goal.progress());
            detail.setText(display(goal.progress(), goal.resource()) + " / " + display(goal.target(), goal.resource())
                    + " " + goal.resource().unit() + "   ·   " + Math.round(fraction * 100) + "%   ·   "
                    + display(remaining, goal.resource()) + " remaining");
        }
    }

    static Label label(String text, String style) {
        Label value = new Label(text);
        value.getStyleClass().add(style);
        return value;
    }
    static VBox empty(String title, String detail) {
        var body = new VBox(5, label(title, "empty-title"), wrapped(detail));
        body.getStyleClass().add("empty-block");
        return body;
    }
    private static Label wrapped(String text) {
        var label = label(text, "muted");
        label.setWrapText(true);
        return label;
    }
    private static VBox card(Node... children) {
        var card = new VBox(10, children);
        card.getStyleClass().add("card");
        return card;
    }
    private static VBox metric(String title, String number) {
        var value = label(number, "metric");
        value.setWrapText(true);
        return card(label(title, "muted"), value);
    }
    private static GridPane columns(Node... items) {
        var row = new GridPane(12, 12);
        for (int i = 0; i < items.length; i++) {
            var col = new ColumnConstraints();
            col.setPercentWidth(100.0 / items.length);
            col.setFillWidth(true);
            row.getColumnConstraints().add(col);
            row.add(items[i], i, 0);
        }
        return row;
    }
    private static HBox sectionRow(String title, Node... actions) {
        var row = new HBox(8, label(title, "section-title"), spacer());
        row.getChildren().addAll(actions);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
    private static HBox line(String left, String right) {
        var row = new HBox(8, wrapped(left), spacer(), label(right, "muted"));
        row.getStyleClass().add("list-line");
        return row;
    }
    private static Region spacer() {
        var spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        return spacer;
    }
    private static Button button(String title, Runnable action) {
        var button = new Button(title);
        button.setOnAction(e -> action.run());
        return button;
    }
    private static Button primary(String title, Runnable action) {
        var button = button(title, action);
        button.getStyleClass().add("primary");
        return button;
    }
    private static MenuItem menu(String title, Runnable action) {
        var item = new MenuItem(title);
        item.setOnAction(e -> action.run());
        return item;
    }
    private static TextField field(String prompt) {
        var field = new TextField();
        field.setPromptText(prompt);
        return field;
    }
    private static LocalDate readDate(DatePicker date) {
        String entered = date.getEditor().getText();
        if (entered == null || entered.isBlank()) return null;
        try { return date.getConverter().fromString(entered); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Enter a valid date or select one from the calendar."); }
    }
    private static <T> T required(T item, String message) {
        if (item == null) throw new IllegalArgumentException(message);
        return item;
    }
    private static String requiredText(TextField field, String message) {
        String text = field.getText().strip();
        if (text.isBlank()) throw new IllegalArgumentException(message);
        return text;
    }
    private static long quantity(String value, Resource resource) {
        try {
            BigDecimal scaled = new BigDecimal(value.strip()).multiply(BigDecimal.valueOf(resource.scale()));
            if (resource.scale() > 1) scaled = scaled.setScale(0, java.math.RoundingMode.HALF_UP);
            long result = scaled.longValueExact();
            if (result < -1_000_000_000_000L || result > 1_000_000_000_000L)
                throw new IllegalArgumentException("Enter a smaller amount.");
            return result;
        }
        catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid amount in " + resource.unit() + ".");
        }
    }
    private static String raw(long value, Resource type) {
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(type.scale()), 6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
    private static String display(long value, Resource type) {
        if (type.scale() == 1) return count(value);
        NumberFormat number = NumberFormat.getNumberInstance();
        number.setMaximumFractionDigits(2);
        return number.format((double) value / type.scale());
    }
    private static String count(long value) { return NumberFormat.getIntegerInstance().format(value); }
    private static String human(Object value) {
        String raw = value.toString().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }
    private static String due(LocalDate date, LocalDate today) {
        if (date == null) return "No due date";
        if (date.equals(today)) return "Due today";
        if (date.isBefore(today)) return "Overdue · " + date.format(SHORT_DATE);
        return "Due " + date.format(SHORT_DATE);
    }
    private static String format(Instant time, ZoneId zone) { return TIMESTAMP.withZone(zone).format(time); }
    private static String timer(long ms) {
        var d = Duration.ofMillis(ms);
        return "%02d:%02d:%02d".formatted(d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }
    private static String duration(long ms) {
        var d = Duration.ofMillis(ms);
        if (d.toHours() > 0) return d.toHours() + "h " + d.toMinutesPart() + "m";
        if (d.toMinutes() > 0) return d.toMinutesPart() + "m " + d.toSecondsPart() + "s";
        return d.toSecondsPart() + "s";
    }
    private static long hours(PlayStats stats, int start, int end) {
        return stats.hours().subList(start, end).stream().mapToLong(Long::longValue).sum();
    }
}

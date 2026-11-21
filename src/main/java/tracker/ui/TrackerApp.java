package tracker.ui;

import javafx.application.*;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.*;
import tracker.db.*;
import tracker.model.Models.*;
import tracker.service.*;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.*;

public final class TrackerApp extends Application {
    private static final Logger LOG = Logger.getLogger(TrackerApp.class.getName());
    enum View {
        DASHBOARD("Dashboard", "Overview"), ANALYTICS("Playtime Analytics", "Playtime analytics"),
        SESSIONS("Sessions", "Session history"), PRIMOGEMS("Primogems", "Primogems"),
        OBJECTIVES("Objectives", "Objectives"), TODO("To-do", "To-do"), SETTINGS("Settings", "Settings");
        final String nav;
        final String heading;
        View(String nav, String heading) { this.nav = nav; this.heading = heading; }
    }

    record Snapshot(ZoneId zone, LocalDate today, PlayMonitor.State state, List<Session> sessions,
                    PlayStats stats, List<Objective> objectives, List<Task> tasks, List<Resource> resources,
                    Ledger.IncomeSummary income, List<Event> incomeEvents, ImportInfo importInfo) {}

    @FunctionalInterface interface Action { String run() throws Exception; }

    final Path directory = AppPaths.dataDirectory();
    final Database database = new Database(directory.resolve("genshin_tracker.db"));
    final SessionRepository sessions = new SessionRepository(database);
    final Ledger ledger = new Ledger(database);
    final Tasks tasks = new Tasks(database);
    final PlayMonitor monitor = new PlayMonitor(sessions, AppPaths.windows() ? GameDetector.windows() : null, Clock.systemUTC());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "tracker-database");
        thread.setDaemon(true);
        return thread;
    });
    private final Tray tray = new Tray();
    private final ToggleGroup navigation = new ToggleGroup();
    private final BorderPane layout = new BorderPane();
    private final VBox canvas = new VBox(18);
    private final ScrollPane scroll = new ScrollPane(canvas);
    private final Label message = new Label();
    private final Label detectorMessage = new Label();
    private final Label header = new Label();
    private final Label context = new Label();
    private final Label status = new Label();
    private Stage stage;
    private TrackerViews views;
    private InstanceLock lock;
    private FileHandler logFile;
    private boolean trayReady;
    private volatile boolean ready;
    private volatile boolean foreground = true;
    private volatile boolean closing;
    private int pendingActions;
    private View view = View.DASHBOARD;
    private Snapshot snapshot;
    private Snapshot rendered;

    @Override public void start(Stage stage) {
        this.stage = stage;
        views = new TrackerViews(this);
        stage.setTitle("Genshin Tracker");
        stage.getIcons().add(new Image(getClass().getResourceAsStream("/tracker/icon.png")));
        stage.setMinWidth(900);
        stage.setMinHeight(640);

        var sidebar = new VBox(7);
        sidebar.getStyleClass().add("sidebar");
        var product = TrackerViews.label("Genshin\nTracker", "product");
        var mark = new javafx.scene.image.ImageView(stage.getIcons().getFirst());
        mark.setFitHeight(35);
        mark.setFitWidth(35);
        var brand = new HBox(11, mark, product);
        brand.getStyleClass().add("brand");
        brand.setAlignment(Pos.CENTER_LEFT);
        sidebar.getChildren().add(brand);
        String[] symbols = {"⌂", "▥", "≡", "✦", "◎", "✓", "◇"};
        for (View item : View.values()) {
            var link = new ToggleButton(symbols[item.ordinal()] + "   " + item.nav);
            link.getStyleClass().add("nav-link");
            link.setMaxWidth(Double.MAX_VALUE);
            link.setToggleGroup(navigation);
            link.setSelected(item == view);
            link.setOnAction(e -> {
                link.setSelected(true);
                if (view != item) {
                    view = item;
                    scroll.setVvalue(0);
                    render(true);
                }
            });
            sidebar.getChildren().add(link);
        }
        var spacer = new Region();
        VBox.setVgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        canvas.getStyleClass().add("canvas");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("page-scroll");
        header.getStyleClass().add("page-heading");
        context.getStyleClass().add("muted");
        status.getStyleClass().add("status");
        var topSpacer = new Region();
        HBox.setHgrow(topSpacer, javafx.scene.layout.Priority.ALWAYS);
        var top = new HBox(16, new VBox(3, header, context), topSpacer, status);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getStyleClass().add("topbar");
        for (Label feedback : List.of(message, detectorMessage)) {
            feedback.setWrapText(true);
            feedback.setMaxWidth(Double.MAX_VALUE);
            feedback.setManaged(false);
            feedback.setVisible(false);
        }
        var body = new VBox(top, message, detectorMessage, scroll);
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        layout.setLeft(sidebar);
        layout.setCenter(body);
        Scene scene = new Scene(layout, 1230, 840);
        scene.getStylesheets().add(getClass().getResource("/tracker/theme.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnShown(e -> {
            foreground = true;
            if (ready && !closing) worker.execute(this::refresh);
        });
        stage.setOnHidden(e -> foreground = false);
        stage.setOnCloseRequest(e -> {
            e.consume();
            if (trayReady) stage.hide();
            else exit();
        });
        Platform.setImplicitExit(false);
        stage.show();
        render(true);
        worker.execute(this::initialize);
    }

    private void initialize() {
        try {
            lock = InstanceLock.acquire(directory);
            logFile = new FileHandler(directory.resolve("tracker.log").toString(), 500_000, 3, true);
            logFile.setFormatter(new XMLFormatter());
            Logger.getLogger("tracker").addHandler(logFile);
            database.initialize();
            sessions.recover();
            attemptLegacyDiscovery();
            Platform.runLater(() -> {
                if (closing) return;
                ready = true;
                tray.install(this::open, () -> monitor.state().label(), this::exit, () -> {
                    trayReady = true;
                    if (view == View.SETTINGS) render(true);
                });
            });
            pollAndRefresh();
            worker.scheduleWithFixedDelay(this::pollAndRefresh, 5, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            report("startup_failed", e);
            Platform.runLater(() -> {
                showMessage("Unable to open tracker. " + explain(e), true);
                canvas.getChildren().setAll(TrackerViews.empty("Tracker unavailable", "Check the message above for details."));
            });
        }
    }

    private void attemptLegacyDiscovery() {
        try {
            if ("done".equals(database.setting("legacy_discovery"))) return;
            String custom = System.getProperty("tracker.legacyDb");
            for (Path candidate : custom == null
                    ? List.of(Path.of("genshin_tracker.db"), Path.of(System.getProperty("user.home"), "genshin_tracker.db"))
                    : List.of(Path.of(custom))) {
                if (Files.isRegularFile(candidate) && !Files.isSameFile(candidate, database.path())) {
                    int added = sessions.importLegacy(candidate);
                    database.setting("legacy_discovery", "done");
                    Platform.runLater(() -> showMessage("Imported " + added + " legacy sessions.", false));
                    return;
                }
            }
            if (custom != null) throw new IllegalArgumentException("The configured legacy database could not be found.");
        } catch (Exception e) {
            report("legacy_import_failed", e);
            Platform.runLater(() -> showMessage("Legacy import failed. " + explain(e), true));
        }
    }

    private void pollAndRefresh() {
        monitor.poll(LocalZone.refresh());
        if (foreground) refresh();
    }

    private void refresh() {
        try {
            ZoneId zone = ZoneId.systemDefault();
            var history = sessions.all();
            var data = new Snapshot(zone, LocalDate.now(zone), monitor.state(), history, PlayStats.calculate(history, zone),
                    ledger.objectives(), tasks.all(), ledger.resources(), ledger.income(), ledger.events(true, null), ledger.importInfo());
            Platform.runLater(() -> {
                if (closing) return;
                snapshot = data;
                render(false);
            });
        } catch (Exception e) {
            report("refresh_failed", e);
            Platform.runLater(() -> showMessage("Could not refresh data. " + explain(e), true));
        }
    }

    void perform(Action action) {
        if (!ready || closing) return;
        if (pendingActions > 0) {
            showMessage("A save is already running. Retry when it finishes.", false);
            return;
        }
        pendingActions++;
        showMessage("Saving…", false);
        worker.execute(() -> {
            String result;
            boolean error;
            try {
                result = action.run();
                error = false;
                refresh();
            } catch (Exception e) {
                report("action_failed", e);
                result = explain(e);
                error = true;
            }
            String finalResult = result;
            boolean failed = error;
            Platform.runLater(() -> {
                pendingActions--;
                if (!closing) {
                    showMessage(finalResult, failed);
                    if (failed && view == View.TODO) render(true);
                }
            });
        });
    }

    <T> void load(Callable<T> fetch, java.util.function.Consumer<T> display) {
        if (!ready || closing) return;
        worker.execute(() -> {
            try {
                T result = fetch.call();
                Platform.runLater(() -> { if (!closing) display.accept(result); });
            } catch (Exception e) {
                report("read_failed", e);
                Platform.runLater(() -> showMessage(explain(e), true));
            }
        });
    }

    private void render(boolean force) {
        header.setText(view.heading);
        status.setText(snapshot == null ? "Starting" : snapshot.state.label());
        status.getStyleClass().remove("status-running");
        if (snapshot != null && "Running".equals(snapshot.state.label())) status.getStyleClass().add("status-running");
        String issue = snapshot == null ? null : snapshot.state.error();
        detectorMessage.setText(issue == null ? "" : "Game detection paused. " + issue);
        detectorMessage.getStyleClass().setAll("message", "message-error");
        detectorMessage.setVisible(issue != null);
        detectorMessage.setManaged(issue != null);
        if (snapshot == null) {
            context.setText("Your playtime, earnings and goals");
            canvas.getChildren().setAll(new ProgressIndicator());
            return;
        }
        context.setText(switch (view) {
            case DASHBOARD -> snapshot.today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d"));
            case ANALYTICS -> "Playtime by local date and hour";
            case SESSIONS -> "Confirmed time with the game process open";
            case PRIMOGEMS -> "Earned income";
            case OBJECTIVES -> "Shared resources and individual progress";
            case TODO -> "Your personal Genshin tasks";
            case SETTINGS -> "Storage and background behavior";
        });
        if (!force && rendered != null && !changed()) {
            if (view == View.OBJECTIVES) views.updateObjectiveProgress(snapshot.objectives);
            return;
        }
        rendered = snapshot;
        canvas.getChildren().setAll(views.create(view, snapshot));
    }

    private boolean changed() {
        return switch (view) {
            case DASHBOARD -> !snapshot.equals(rendered);
            case ANALYTICS -> !snapshot.stats.equals(rendered.stats) || !snapshot.today.equals(rendered.today);
            case SESSIONS -> !snapshot.sessions.equals(rendered.sessions) || !snapshot.zone.equals(rendered.zone)
                    || !snapshot.today.equals(rendered.today);
            case PRIMOGEMS -> !snapshot.income.equals(rendered.income) || !snapshot.incomeEvents.equals(rendered.incomeEvents)
                    || !java.util.Objects.equals(snapshot.importInfo, rendered.importInfo) || !snapshot.today.equals(rendered.today)
                    || !primogemGoals(snapshot).equals(primogemGoals(rendered));
            case OBJECTIVES -> !snapshot.resources.equals(rendered.resources) || !snapshot.today.equals(rendered.today)
                    || !withoutProgress(snapshot).equals(withoutProgress(rendered));
            case TODO -> !snapshot.tasks.equals(rendered.tasks) || !snapshot.today.equals(rendered.today);
            case SETTINGS -> false;
        };
    }

    private List<Objective> primogemGoals(Snapshot data) {
        return data.objectives.stream().filter(o -> o.resource().name().equals(Ledger.PRIMOGEMS)).toList();
    }

    private List<Objective> withoutProgress(Snapshot data) {
        return data.objectives.stream().map(o -> new Objective(o.id(), o.title(), o.description(), o.resource(),
                o.target(), 0, o.createdAt(), o.deadline(), o.status(), o.source())).toList();
    }

    void redraw() { render(true); }
    Stage stage() { return stage; }
    boolean trayReady() { return trayReady; }

    void showMessage(String text, boolean error) {
        message.setText(text);
        message.getStyleClass().setAll("message", error ? "message-error" : "message-ok");
        message.setManaged(true);
        message.setVisible(true);
    }

    void open() {
        stage.show();
        stage.setIconified(false);
        stage.toFront();
    }

    void exit() {
        if (closing) return;
        closing = true;
        tray.close();
        stage.hide();
        try {
            worker.execute(() -> {
                if (ready) {
                    try { sessions.recover(); }
                    catch (Exception e) { report("shutdown_recovery_failed", e); }
                }
                release();
                Platform.runLater(Platform::exit);
            });
        } finally {
            worker.shutdown();
        }
    }

    private void release() {
        if (logFile != null) {
            Logger.getLogger("tracker").removeHandler(logFile);
            logFile.close();
        }
        if (lock != null) {
            try { lock.close(); } catch (Exception e) { report("lock_release_failed", e); }
        }
    }

    private static String explain(Exception e) {
        if (e instanceof java.sql.SQLException sql &&
                (sql.getErrorCode() == 5 || sql.getErrorCode() == 6))
            return "The database is busy. Retry after the other operation finishes.";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void report(String event, Exception e) { LOG.log(Level.WARNING, event, e); }
}

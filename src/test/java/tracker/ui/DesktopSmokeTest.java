package tracker.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tracker.db.Database;
import tracker.db.Ledger;
import tracker.db.Tasks;
import tracker.model.Models.Resource;
import tracker.service.InstanceLock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "tracker.uiSmoke", matches = "true")
class DesktopSmokeTest {
    @TempDir Path folder;

    @Test void navigatesAllViewsAndPersistsObjectiveIncomeAndTaskThroughUi() throws Exception {
        System.setProperty("tracker.dataDir", folder.toString());
        var initialized = new CountDownLatch(1);
        Platform.startup(initialized::countDown);
        assertTrue(initialized.await(10, TimeUnit.SECONDS));
        TrackerApp app = new TrackerApp();
        try {
            fx(() -> { app.start(new Stage()); return null; });
            await(() -> fx(() -> label(app, "CURRENT SESSION")));
            for (String nav : new String[]{"Playtime Analytics", "Sessions", "Primogems", "Objectives", "To-do", "Settings", "Dashboard"})
                fx(() -> { nav(app, nav); return null; });
            fx(() -> { nav(app, "Objectives"); return null; });
            edit(app, "New objective", form -> {
                field(form, "Title", TextField.class).setText("Smoke goal");
                field(form, "Target", TextField.class).setText("500");
                chooseResource(form);
            });
            var db = new Database(folder.resolve("genshin_tracker.db"));
            var ledger = new Ledger(db);
            await(() -> ledger.objectives().size() == 1 && fx(() -> label(app, "Smoke goal")));
            fx(() -> { nav(app, "Primogems"); return null; });
            edit(app, "Log income", form -> {
                field(form, "Amount", TextField.class).setText("125");
                field(form, "Source", TextField.class).setText("Smoke source");
            });
            await(() -> ledger.income().total() == 125 && ledger.objectives().getFirst().progress() == 125
                    && fx(() -> label(app, "Smoke goal")));
            fx(() -> { nav(app, "To-do"); return null; });
            edit(app, "New task", form -> field(form, "Title", TextField.class).setText("Smoke task"));
            await(() -> new Tasks(db).all().size() == 1 && fx(() ->
                    app.stage().getScene().getRoot().lookupAll(".check-box").stream().anyMatch(n -> ((CheckBox)n).getText().equals("Smoke task"))));
            fx(() -> {
                find(app.stage().getScene().getRoot(), ".check-box", n -> ((CheckBox)n).getText().equals("Smoke task"), CheckBox.class).fire();
                return null;
            });
            await(() -> new Tasks(db).all().getFirst().completed());
        } finally {
            fx(() -> { app.exit(); return null; });
            await(() -> {
                try (var ignored = InstanceLock.acquire(folder)) { return true; }
                catch (java.io.IOException e) { return false; }
            });
            System.clearProperty("tracker.dataDir");
        }
    }

    private static void edit(TrackerApp app, String action, java.util.function.Consumer<DialogPane> edit) throws Exception {
        var pressed = new CompletableFuture<Void>();
        Platform.runLater(() -> {
            try {
                find(app.stage().getScene().getRoot(), ".button",
                        n -> n instanceof Button b && b.getText().equals(action), Button.class).fire();
                pressed.complete(null);
            } catch (Throwable e) { pressed.completeExceptionally(e); }
        });
        await(() -> fx(() -> dialog() != null));
        fx(() -> {
            var form = dialog();
            edit.accept(form);
            var save = form.getButtonTypes().stream().filter(b -> b.getButtonData().isDefaultButton()).findFirst().orElseThrow();
            ((Button) form.lookupButton(save)).fire();
            return null;
        });
        pressed.get(10, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static void chooseResource(DialogPane form) {
        ComboBox<Resource> type = field(form, "Resource", ComboBox.class);
        type.setValue(type.getItems().stream().filter(r -> r.name().equals("Primogems")).findFirst().orElseThrow());
    }

    private static <T extends Node> T field(DialogPane form, String text, Class<T> kind) {
        return kind.cast(find(form, ".label", n -> n instanceof Label l && text.equals(l.getText()) && l.getLabelFor() != null,
                Label.class).getLabelFor());
    }

    private static DialogPane dialog() {
        return Window.getWindows().stream().filter(Window::isShowing).map(w -> w.getScene().getRoot())
                .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElse(null);
    }

    private static void nav(TrackerApp app, String text) {
        find(app.stage().getScene().getRoot(), ".nav-link",
                n -> ((ToggleButton)n).getText().endsWith("   " + text), ToggleButton.class).fire();
    }

    private static boolean label(TrackerApp app, String text) {
        return app.stage().getScene().getRoot().lookupAll(".label").stream()
                .anyMatch(n -> n instanceof Label l && text.equals(l.getText()));
    }

    private static <T> T find(Parent scene, String selector, Predicate<Node> predicate, Class<T> kind) {
        return kind.cast(scene.lookupAll(selector).stream().filter(predicate).findFirst().orElseThrow());
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var result = new FutureTask<>(work);
        Platform.runLater(result);
        return result.get(10, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long limit = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        while (System.nanoTime() < limit) {
            if (condition.call()) return;
            Thread.sleep(40);
        }
        fail("Timed out waiting for the desktop operation.");
    }
}

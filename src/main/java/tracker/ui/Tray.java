package tracker.ui;

import java.awt.*;
import java.io.IOException;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import java.util.logging.*;

final class Tray {
    private static final Logger LOG = Logger.getLogger(Tray.class.getName());
    private volatile TrayIcon icon;
    private volatile boolean stopped;

    void install(Runnable open, Supplier<String> status, Runnable exit, Runnable installed) {
        if (!SystemTray.isSupported()) return;
        EventQueue.invokeLater(() -> {
            if (stopped) return;
            try {
                var image = ImageIO.read(Tray.class.getResource("/tracker/icon.png"));
                var menu = new PopupMenu();
                var openItem = new MenuItem("Open Tracker");
                openItem.addActionListener(e -> Platform.runLater(open));
                var statusItem = new MenuItem("Show current game status");
                statusItem.addActionListener(e -> icon.displayMessage("Genshin Tracker", status.get(), TrayIcon.MessageType.INFO));
                var exitItem = new MenuItem("Exit completely");
                exitItem.addActionListener(e -> Platform.runLater(exit));
                menu.add(openItem);
                menu.add(statusItem);
                menu.addSeparator();
                menu.add(exitItem);
                var candidate = new TrayIcon(image, "Genshin Tracker", menu);
                candidate.setImageAutoSize(true);
                candidate.addActionListener(e -> Platform.runLater(open));
                SystemTray.getSystemTray().add(candidate);
                icon = candidate;
                Platform.runLater(installed);
            } catch (IOException | AWTException | RuntimeException e) {
                LOG.log(Level.WARNING, "tray_install_failed", e);
            }
        });
    }

    void close() {
        stopped = true;
        if (!SystemTray.isSupported()) return;
        EventQueue.invokeLater(() -> {
            if (icon != null) SystemTray.getSystemTray().remove(icon);
        });
    }
}

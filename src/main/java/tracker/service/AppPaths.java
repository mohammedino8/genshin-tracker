package tracker.service;

import java.nio.file.Path;
import java.util.Locale;

public final class AppPaths {
    private AppPaths() {}

    public static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"); }

    public static Path dataDirectory() {
        String override = System.getProperty("tracker.dataDir");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
        String appData = System.getenv("LOCALAPPDATA");
        if (windows() && appData != null && !appData.isBlank()) return Path.of(appData, "GenshinTracker");
        return Path.of(System.getProperty("user.home"), ".genshin-tracker");
    }
}

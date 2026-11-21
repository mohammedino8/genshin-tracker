package tracker.service;

import java.lang.management.ManagementFactory;
import java.time.ZoneId;
import java.util.TimeZone;

public final class LocalZone {
    private static final boolean EXPLICIT = ManagementFactory.getRuntimeMXBean().getInputArguments()
            .stream().anyMatch(arg -> arg.startsWith("-Duser.timezone="));

    private LocalZone() {}

    public static ZoneId refresh() {
        if (!EXPLICIT) {
            // Java caches the host zone after first use. Rediscover it when Windows settings change.
            System.clearProperty("user.timezone");
            TimeZone.setDefault(null);
        }
        return ZoneId.systemDefault();
    }
}

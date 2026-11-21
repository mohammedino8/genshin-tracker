package tracker.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@FunctionalInterface
public interface GameDetector {
    boolean running() throws IOException, InterruptedException;

    static GameDetector windows() {
        return () -> {
            var output = Files.createTempFile("tracker-tasklist-", ".csv");
            try {
                var process = new ProcessBuilder("tasklist.exe", "/FI", "IMAGENAME eq GenshinImpact.exe",
                        "/FO", "CSV", "/NH").redirectErrorStream(true).redirectOutput(output.toFile()).start();
                try {
                    if (!process.waitFor(Duration.ofSeconds(4).toMillis(), TimeUnit.MILLISECONDS))
                        throw new IOException("Windows tasklist timed out.");
                    if (process.exitValue() != 0)
                        throw new IOException("Windows tasklist exited with code " + process.exitValue() + ".");
                    try (var lines = Files.lines(output, Charset.forName("windows-1252"))) {
                        return lines.anyMatch(GameDetector::matches);
                    }
                } finally {
                    if (process.isAlive()) process.destroyForcibly();
                }
            } finally {
                Files.deleteIfExists(output);
            }
        };
    }

    static boolean matches(String line) {
        return line.stripLeading().toLowerCase(Locale.ROOT).startsWith("\"genshinimpact.exe\",");
    }
}

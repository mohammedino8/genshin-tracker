package tracker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class GameDetectorTest {
    @Test void matchesExactCsvImageNameCaseInsensitively() {
        assertTrue(GameDetector.matches("\"GenshinImpact.exe\",\"5120\",\"Console\",\"1\",\"100 K\""));
        assertTrue(GameDetector.matches("  \"GENSHINIMPACT.EXE\",\"16\""));
        assertFalse(GameDetector.matches("INFO: No tasks are running which match the specified criteria."));
        assertFalse(GameDetector.matches("\"NotGenshinImpact.exe\",\"5120\""));
        assertFalse(GameDetector.matches("\"GenshinImpact.exe.bak\",\"5120\""));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void windowsDetectorSeesAnActualMatchingProcess(@TempDir Path temp) throws Exception {
        var detector = GameDetector.windows();
        var ping = Path.of(System.getenv("SystemRoot"), "System32", "ping.exe");
        var named = Files.copy(ping, temp.resolve("GenshinImpact.exe"));
        var process = new ProcessBuilder(named.toString(), "-t", "127.0.0.1")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.isAlive());
            assertTrue(detector.running());
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        }
    }
}

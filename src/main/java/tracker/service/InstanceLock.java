package tracker.service;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import static java.nio.file.StandardOpenOption.*;

public final class InstanceLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static InstanceLock acquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        var channel = FileChannel.open(directory.resolve("tracker.lock"), CREATE, WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Genshin Tracker is already running. Look for its icon in the system tray.");
            return new InstanceLock(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Genshin Tracker is already running.", e);
        } catch (IOException | RuntimeException e) {
            try { channel.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    @Override public void close() throws IOException {
        try (channel) { lock.release(); }
    }
}

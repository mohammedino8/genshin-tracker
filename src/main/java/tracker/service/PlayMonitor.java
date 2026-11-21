package tracker.service;

import tracker.db.SessionRepository;
import java.time.*;
import java.util.logging.*;

public final class PlayMonitor {
    public record State(String label, String error) {}
    private static final Logger LOG = Logger.getLogger(PlayMonitor.class.getName());
    private final SessionRepository sessions;
    private final GameDetector detector;
    private final Clock clock;
    private volatile State state = new State("Checking game status…", null);

    public PlayMonitor(SessionRepository sessions, GameDetector detector, Clock clock) {
        this.sessions = sessions;
        this.detector = detector;
        this.clock = clock;
        if (detector == null) state = new State("Windows detection unavailable", null);
    }

    public State state() { return state; }

    public void poll() { poll(ZoneId.systemDefault()); }

    public void poll(ZoneId zone) {
        if (detector == null) return;
        try {
            boolean running = detector.running();
            sessions.observe(running, clock.instant(), zone, Duration.ofSeconds(16));
            state = new State(running ? "Running" : "Not running", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            state = new State("Status unavailable", e.getMessage());
            LOG.log(Level.WARNING, "game_poll_failed", e);
        }
    }
}

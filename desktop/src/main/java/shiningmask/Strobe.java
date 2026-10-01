package shiningmask;

import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Brightness strobe. Each cycle the mask is set to the "on" level for {@code duty} of the period
 * and to the "off" level for the rest. The period is either a fixed rate or a beat division
 * of the Ableton Link session.
 *
 * <p>Only state changes are written. Writes block until the mask acknowledges them, so if
 * Bluetooth is slower than the strobe, flashes are skipped instead of queueing up and drifting
 * off the beat.
 */
public final class Strobe {
    public volatile boolean linkSync;
    public volatile double hz = 8;
    public volatile double beatsPerCycle = 0.5;
    public volatile double duty = 0.5;
    public volatile int onLevel = 100;
    public volatile int offLevel = 1;
    public volatile int latencyMs = 40;
    public volatile boolean onlyWhilePlaying;

    private final Supplier<MaskConnection> mask;
    private final LinkClient link;
    private final Consumer<String> log;
    private volatile boolean running;
    private volatile double writeMs;
    private volatile Boolean lit;
    private Thread thread;
    private int restoreLevel;

    public Strobe(Supplier<MaskConnection> mask, LinkClient link, Consumer<String> log) {
        this.mask = mask;
        this.link = link;
        this.log = log;
    }

    public synchronized void start(int restoreLevel) {
        if (running) {
            return;
        }
        this.restoreLevel = restoreLevel;
        running = true;
        thread = new Thread(this::loop, "strobe");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        MaskConnection m = mask.get();
        if (m != null) {
            try {
                m.commandQuiet(Protocol.light(restoreLevel));
            } catch (RuntimeException e) {
                log.accept("Strobe restore failed: " + e.getMessage());
            }
        }
        lit = null;
    }

    public boolean isRunning() {
        return running;
    }

    /** Average time a brightness write takes to be acknowledged, in ms. */
    public double writeMs() {
        return writeMs;
    }

    /** Last state sent: true = on, false = off, null = idle. */
    public Boolean lit() {
        return lit;
    }

    /** Desired state at a host time, or null when there is no beat to follow. */
    Boolean desired(long hostMicros) {
        long t = hostMicros + latencyMs * 1000L;
        double phase;
        if (linkSync) {
            if (onlyWhilePlaying && !link.isPlaying()) {
                return null;
            }
            double beats = link.beatsAt(t);
            if (Double.isNaN(beats)) {
                return null;
            }
            double cycle = Math.max(1e-3, beatsPerCycle);
            phase = LinkClient.floorMod(beats, cycle) / cycle;
        } else {
            double periodMicros = 1e6 / Math.max(0.1, hz);
            phase = LinkClient.floorMod(t, periodMicros) / periodMicros;
        }
        return phase < duty;
    }

    private void loop() {
        Boolean sent = null;
        int errors = 0;
        while (running) {
            Boolean want = desired(LinkClient.hostMicros());
            // Without a beat (Link not synced / transport stopped) hold the normal brightness.
            int level = want == null ? restoreLevel : (want ? onLevel : offLevel);
            Boolean state = want;
            MaskConnection m = mask.get();
            if (m != null && !java.util.Objects.equals(state, sent)) {
                long t0 = System.nanoTime();
                try {
                    m.commandQuiet(Protocol.lightRaw(level));
                    double ms = (System.nanoTime() - t0) / 1e6;
                    writeMs = writeMs == 0 ? ms : writeMs * 0.9 + ms * 0.1;
                    sent = state;
                    lit = state;
                    errors = 0;
                } catch (RuntimeException e) {
                    if (++errors == 1) {
                        log.accept("Strobe write failed: " + e.getMessage());
                    }
                    LockSupport.parkNanos(50_000_000L);
                }
            }
            LockSupport.parkNanos(500_000L);
        }
    }
}

package shiningmask;

import java.util.function.Consumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

/**
 * Captures the default microphone and turns it into 24 bars of level 0..9, similar to the
 * app's VisualizerUtil (FFT magnitude, rolling max, 9 steps).
 */
public final class AudioRhythm {
    private static final float RATE = 16000f;
    private static final int N = 256;
    private static final int BARS = 24;
    private static final long PERIOD_MS = 120;

    private volatile boolean running;
    private Thread thread;
    private TargetDataLine line;
    private double maxLevel = 1;

    public synchronized void start(Consumer<int[]> sink, Consumer<String> log) throws LineUnavailableException {
        if (running) {
            return;
        }
        AudioFormat fmt = new AudioFormat(RATE, 16, 1, true, false);
        line = AudioSystem.getTargetDataLine(fmt);
        line.open(fmt, N * 2 * 8);
        line.start();
        running = true;
        thread = new Thread(() -> loop(sink, log), "rhythm");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        if (line != null) {
            line.stop();
            line.close();
            line = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void loop(Consumer<int[]> sink, Consumer<String> log) {
        byte[] buf = new byte[N * 2];
        long last = 0;
        try {
            while (running) {
                TargetDataLine l = line;
                if (l == null) {
                    break;
                }
                int read = 0;
                while (read < buf.length && running) {
                    int r = l.read(buf, read, buf.length - read);
                    if (r <= 0) {
                        break;
                    }
                    read += r;
                }
                long now = System.currentTimeMillis();
                if (now - last < PERIOD_MS) {
                    continue;
                }
                last = now;
                sink.accept(levels(buf));
            }
        } catch (RuntimeException e) {
            log.accept("rhythm stopped: " + e);
            running = false;
        }
    }

    int[] levels(byte[] pcm) {
        double[] re = new double[N];
        double[] im = new double[N];
        for (int i = 0; i < N; i++) {
            short s = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
            re[i] = s * w;
        }
        fft(re, im);
        // Log-spaced bands between ~120 Hz and ~7 kHz.
        int[] out = new int[BARS];
        double[] mag = new double[BARS];
        double lo = 2;
        double hi = N / 2.0 - 1;
        double frameMax = 0;
        for (int b = 0; b < BARS; b++) {
            int from = (int) Math.floor(lo * Math.pow(hi / lo, (double) b / BARS));
            int to = Math.max(from + 1, (int) Math.floor(lo * Math.pow(hi / lo, (double) (b + 1) / BARS)));
            double sum = 0;
            for (int k = from; k < to; k++) {
                sum += Math.hypot(re[k], im[k]);
            }
            mag[b] = sum / (to - from);
            frameMax = Math.max(frameMax, mag[b]);
        }
        maxLevel = Math.max(frameMax, maxLevel * 0.98);
        double step = Math.max(maxLevel / 9.0, 2000);
        for (int b = 0; b < BARS; b++) {
            out[b] = Protocol.clamp((int) Math.ceil(mag[b] / step - 0.5), 0, 9);
        }
        return out;
    }

    /** In-place radix-2 FFT. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang);
            double wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }
}

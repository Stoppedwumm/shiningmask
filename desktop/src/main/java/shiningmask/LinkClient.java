package shiningmask;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Minimal, listen-only Ableton Link peer in pure Java.
 *
 * <p>It follows the session tempo and beat phase but never announces itself, so it cannot
 * change the tempo. Wire format (from the Link sources):
 *
 * <ul>
 *   <li>Discovery: UDP multicast 224.76.78.75:20808. "_asdp_v\1", type (1 alive, 2 response,
 *       3 bye-bye), ttl, group id (u16), node id (8 bytes), then entries [key u32][size u32][value].
 *       Entries: 'tmln' (micros per beat i64, beat origin in micro-beats i64, time origin in ghost
 *       micros i64), 'sess' (session id, 8 bytes), 'stst' (playing u8, beats i64, time i64),
 *       'mep4' (IPv4 u32, port u16).
 *   <li>Measurement: unicast to a peer's 'mep4'. Ping = "_link_v\1", 1, '__ht' host time. The pong
 *       carries 'sess', '__gt' ghost time, then echoes our payload. The median offset of ~100
 *       samples gives ghost = host + offset.
 * </ul>
 */
public final class LinkClient {
    private static final String MULTICAST = "224.76.78.75";
    private static final int PORT = 20808;
    private static final byte[] DISCOVERY_HEADER = {'_', 'a', 's', 'd', 'p', '_', 'v', 1};
    private static final byte[] LINK_HEADER = {'_', 'l', 'i', 'n', 'k', '_', 'v', 1};
    private static final int KEY_TMLN = 0x746d6c6e;
    private static final int KEY_SESS = 0x73657373;
    private static final int KEY_STST = 0x73747374;
    private static final int KEY_MEP4 = 0x6d657034;
    private static final int KEY_HT = 0x5f5f6874;
    private static final int KEY_GT = 0x5f5f6774;
    private static final int KEY_PGT = 0x5f706774;
    private static final int DATA_POINTS = 100;
    private static final long REMEASURE_MS = 30_000;

    /** Session timeline: everything in ghost microseconds and micro-beats. */
    public record Timeline(long microsPerBeat, long beatOrigin, long timeOrigin) {
        public double tempo() {
            return 60e6 / microsPerBeat;
        }

        /** Beats since the timeline origin (what Link uses for phase). */
        public double beatsFromOrigin(long ghostMicros) {
            return (double) (ghostMicros - timeOrigin) / microsPerBeat;
        }
    }

    private record Peer(long sessionId, Timeline timeline, boolean playing, InetSocketAddress mep4,
                        long expiresAtMs) {}

    private final Map<Long, Peer> peers = new ConcurrentHashMap<>();
    private final Consumer<String> log;
    private volatile boolean running;
    private DatagramChannel discovery;
    private DatagramSocket measure;
    private final List<Thread> threads = new ArrayList<>();

    // measurement state, guarded by this
    private long measuredSession;
    private long ghostOffset;
    private boolean synced;
    private long lastMeasureMs;
    private final List<Double> samples = new ArrayList<>();
    private long measuringSession;

    public LinkClient(Consumer<String> log) {
        this.log = log;
    }

    public static long hostMicros() {
        return System.nanoTime() / 1000;
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        // IPv4 channel: a dual-stack socket misses IPv4 multicast on some systems.
        discovery = DatagramChannel.open(StandardProtocolFamily.INET);
        discovery.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        discovery.bind(new InetSocketAddress(PORT));
        InetAddress group = InetAddress.getByName(MULTICAST);
        int joined = 0;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            try {
                // Loopback too: Link announces on it, which is how a DAW on the same machine shows up.
                if (!ni.isUp() || (!ni.supportsMulticast() && !ni.isLoopback())) {
                    continue;
                }
                boolean v4 = ni.getInterfaceAddresses().stream().anyMatch(a -> a.getAddress() instanceof Inet4Address);
                if (!v4) {
                    continue;
                }
                discovery.join(group, ni);
                joined++;
            } catch (IOException | UnsupportedOperationException e) {
                // interface without multicast support; skip it
            }
        }
        if (joined == 0) {
            discovery.close();
            throw new IOException("no network interface could join the Link multicast group");
        }
        measure = new DatagramSocket(0);
        measure.setSoTimeout(500);
        running = true;
        threads.clear();
        threads.add(thread("link-discovery", this::discoveryLoop));
        threads.add(thread("link-measure-rx", this::measureLoop));
        threads.add(thread("link-housekeeping", this::housekeeping));
        log.accept("Link: listening on " + joined + " interface(s)");
    }

    public synchronized void stop() {
        running = false;
        if (discovery != null) {
            try {
                discovery.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        if (measure != null) {
            measure.close();
        }
        peers.clear();
        synced = false;
        samples.clear();
    }

    public boolean isRunning() {
        return running;
    }

    private Thread thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ------------------------------------------------------------------ public state

    /** Number of peers in the session we follow. */
    public int numPeers() {
        long s = currentSession();
        return (int) peers.values().stream().filter(p -> p.sessionId == s).count();
    }

    public synchronized boolean isSynced() {
        return synced && measuredSession == currentSession();
    }

    /** Most recent timeline of the followed session, or null. */
    public Timeline timeline() {
        long s = currentSession();
        Peer best = null;
        for (Peer p : peers.values()) {
            if (p.sessionId == s && p.timeline != null && (best == null || p.expiresAtMs > best.expiresAtMs)) {
                best = p;
            }
        }
        return best == null ? null : best.timeline;
    }

    /** True if any peer of the session reports transport playing (needs Start/Stop Sync). */
    public boolean isPlaying() {
        long s = currentSession();
        return peers.values().stream().anyMatch(p -> p.sessionId == s && p.playing);
    }

    public synchronized long ghostOffset() {
        return ghostOffset;
    }

    /** Session beats (from the timeline origin) at a host time, or NaN if not synced. */
    public double beatsAt(long hostMicros) {
        Timeline tl = timeline();
        if (tl == null || !isSynced()) {
            return Double.NaN;
        }
        return tl.beatsFromOrigin(hostMicros + ghostOffset());
    }

    /** Phase within a quantum, matching Link's phaseAtTime. */
    public double phaseAt(long hostMicros, double quantum) {
        double b = beatsAt(hostMicros);
        return Double.isNaN(b) ? Double.NaN : floorMod(b, quantum);
    }

    static double floorMod(double x, double m) {
        double r = x % m;
        return r < 0 ? r + m : r;
    }

    /** The session with the most peers. */
    private long currentSession() {
        Map<Long, Integer> counts = new HashMap<>();
        for (Peer p : peers.values()) {
            counts.merge(p.sessionId, 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0L);
    }

    // ------------------------------------------------------------------ discovery

    private void discoveryLoop() {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        while (running) {
            try {
                buf.clear();
                InetSocketAddress from = (InetSocketAddress) discovery.receive(buf);
                buf.flip();
                handleDiscovery(buf, from.getAddress());
            } catch (java.nio.channels.ClosedChannelException e) {
                return;
            } catch (IOException | RuntimeException e) {
                if (running) {
                    log.accept("Link discovery: " + e.getMessage());
                }
            }
        }
    }

    void handleDiscovery(ByteBuffer b, InetAddress from) {
        if (!startsWith(b, DISCOVERY_HEADER) || b.remaining() < 8 + 12) {
            return;
        }
        b.position(b.position() + 8);
        int type = b.get() & 0xFF;
        int ttl = b.get() & 0xFF;
        b.getShort(); // group id
        long nodeId = b.getLong();
        if (type == 3) {
            peers.remove(nodeId);
            return;
        }
        if (type != 1 && type != 2) {
            return;
        }
        Long session = null;
        Timeline tl = null;
        boolean playing = false;
        InetSocketAddress mep4 = null;
        while (b.remaining() >= 8) {
            int key = b.getInt();
            int size = b.getInt();
            if (size < 0 || size > b.remaining()) {
                return;
            }
            int end = b.position() + size;
            switch (key) {
                case KEY_TMLN -> {
                    if (size >= 24) {
                        tl = new Timeline(b.getLong(), b.getLong(), b.getLong());
                    }
                }
                case KEY_SESS -> {
                    if (size >= 8) {
                        session = b.getLong();
                    }
                }
                case KEY_STST -> {
                    if (size >= 1) {
                        playing = b.get() != 0;
                    }
                }
                case KEY_MEP4 -> {
                    if (size >= 6) {
                        byte[] ip = new byte[4];
                        b.get(ip);
                        int port = b.getShort() & 0xFFFF;
                        try {
                            InetAddress addr = InetAddress.getByAddress(ip);
                            mep4 = new InetSocketAddress(addr.isAnyLocalAddress() ? from : addr, port);
                        } catch (IOException ignored) {
                            // malformed address
                        }
                    }
                }
                default -> { }
            }
            b.position(end);
        }
        if (session == null || tl == null || tl.microsPerBeat <= 0) {
            return;
        }
        Peer before = peers.put(nodeId, new Peer(session, tl, playing, mep4,
                System.currentTimeMillis() + Math.max(1, ttl) * 1000L));
        if (before == null) {
            log.accept(String.format("Link: peer %016x, session %016x, %.2f BPM", nodeId, session, tl.tempo()));
        }
    }

    private static boolean startsWith(ByteBuffer b, byte[] header) {
        if (b.remaining() < header.length) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (b.get(b.position() + i) != header[i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ measurement

    private void housekeeping() {
        while (running) {
            long now = System.currentTimeMillis();
            peers.values().removeIf(p -> p.expiresAtMs < now);
            long session = currentSession();
            boolean need;
            synchronized (this) {
                need = session != 0 && (measuredSession != session || now - lastMeasureMs > REMEASURE_MS)
                        && (measuringSession != session || now - lastMeasureMs > 3000);
            }
            if (need) {
                startMeasurement(session);
            }
            sleep(250);
        }
    }

    private void startMeasurement(long session) {
        Peer target = peers.values().stream().filter(p -> p.sessionId == session && p.mep4 != null)
                .findFirst().orElse(null);
        if (target == null) {
            return;
        }
        synchronized (this) {
            measuringSession = session;
            samples.clear();
            lastMeasureMs = System.currentTimeMillis();
        }
        // Like Link: one ping, then a new ping for every pong; resend a few times on silence.
        for (int i = 0; i < 5 && running; i++) {
            synchronized (this) {
                if (measuringSession != session || samples.size() > 0) {
                    return;
                }
            }
            sendPing(target.mep4, hostMicros(), 0);
            sleep(50);
        }
    }

    private void sendPing(InetSocketAddress to, long hostTime, long prevGhost) {
        ByteBuffer b = ByteBuffer.allocate(64);
        b.put(LINK_HEADER).put((byte) 1);
        b.putInt(KEY_HT).putInt(8).putLong(hostTime);
        if (prevGhost != 0) {
            b.putInt(KEY_PGT).putInt(8).putLong(prevGhost);
        }
        try {
            measure.send(new DatagramPacket(b.array(), b.position(), to));
        } catch (IOException e) {
            log.accept("Link ping failed: " + e.getMessage());
        }
    }

    private void measureLoop() {
        byte[] buf = new byte[512];
        while (running) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                measure.receive(pkt);
                handlePong(ByteBuffer.wrap(pkt.getData(), 0, pkt.getLength()),
                        (InetSocketAddress) pkt.getSocketAddress());
            } catch (SocketTimeoutException e) {
                // loop
            } catch (IOException | RuntimeException e) {
                if (running) {
                    log.accept("Link measurement: " + e.getMessage());
                }
            }
        }
    }

    private void handlePong(ByteBuffer b, InetSocketAddress from) {
        if (!startsWith(b, LINK_HEADER) || b.remaining() < 9) {
            return;
        }
        b.position(b.position() + 8);
        if (b.get() != 2) {
            return;
        }
        long session = 0;
        long ghost = 0;
        long prevGhost = 0;
        long prevHost = 0;
        while (b.remaining() >= 8) {
            int key = b.getInt();
            int size = b.getInt();
            if (size < 0 || size > b.remaining()) {
                return;
            }
            int end = b.position() + size;
            if (size >= 8) {
                switch (key) {
                    case KEY_SESS -> session = b.getLong();
                    case KEY_GT -> ghost = b.getLong();
                    case KEY_PGT -> prevGhost = b.getLong();
                    case KEY_HT -> prevHost = b.getLong();
                    default -> { }
                }
            }
            b.position(end);
        }
        long now = hostMicros();
        synchronized (this) {
            if (session == 0 || session != measuringSession) {
                return;
            }
            if (ghost != 0 && prevHost != 0) {
                samples.add(ghost - (now + prevHost) * 0.5);
                if (prevGhost != 0) {
                    samples.add((ghost + prevGhost) * 0.5 - prevHost);
                }
            }
            if (samples.size() > DATA_POINTS) {
                double[] d = samples.stream().mapToDouble(Double::doubleValue).toArray();
                Arrays.sort(d);
                double median = d.length % 2 == 1 ? d[d.length / 2] : (d[d.length / 2 - 1] + d[d.length / 2]) / 2;
                boolean first = !synced || measuredSession != session;
                ghostOffset = Math.round(median);
                measuredSession = session;
                synced = true;
                measuringSession = 0;
                lastMeasureMs = System.currentTimeMillis();
                samples.clear();
                if (first) {
                    log.accept("Link: clock synced to session " + String.format("%016x", session));
                }
                return;
            }
        }
        sendPing(from, now, ghost);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

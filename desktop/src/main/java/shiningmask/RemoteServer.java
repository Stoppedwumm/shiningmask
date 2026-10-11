package shiningmask;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lets a remote (the Innioasis Y1 app) drive the desktop app over the local network.
 *
 * <ul>
 *   <li>Discovery: every second a UDP broadcast "SMASK1 &lt;port&gt; &lt;name&gt;" goes to port 47321.
 *   <li>HTTP on port 47320: {@code GET /api/state} returns JSON, {@code POST /api/do} runs an action
 *       given as form fields (a=action plus parameters).
 *   <li>Every request carries X-Remote-Id / X-Remote-Name. Unknown ids get 403 until the user
 *       approves them on the computer.
 * </ul>
 */
public final class RemoteServer {
    public static final int HTTP_PORT = 47320;
    public static final int DISCOVERY_PORT = 47321;

    /** Implemented by the app. */
    public interface Handler {
        /** Current state as a JSON object. */
        String stateJson();

        /** Run an action; returns an error message or null. */
        String act(Map<String, String> params);

        boolean isApproved(String remoteId);

        /** Ask the user (asynchronously) whether this remote may control the mask. */
        void requestApproval(String remoteId, String name, String address);
    }

    private final Handler handler;
    private final Consumer<String> log;
    private HttpServer http;
    private DatagramSocket beacon;
    private volatile boolean running;

    public RemoteServer(Handler handler, Consumer<String> log) {
        this.handler = handler;
        this.log = log;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        http = HttpServer.create(new InetSocketAddress(HTTP_PORT), 16);
        http.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "remote-http");
            t.setDaemon(true);
            return t;
        }));
        http.createContext("/api/state", ex -> handle(ex, false));
        http.createContext("/api/do", ex -> handle(ex, true));
        http.createContext("/", ex -> reply(ex, 200, "{\"app\":\"shiningmask\"}"));
        http.start();
        beacon = new DatagramSocket();
        beacon.setBroadcast(true);
        running = true;
        Thread t = new Thread(this::beaconLoop, "remote-beacon");
        t.setDaemon(true);
        t.start();
        log.accept("Remote control: listening on port " + HTTP_PORT + " (" + addresses() + ")");
    }

    public synchronized void stop() {
        running = false;
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (beacon != null) {
            beacon.close();
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** IPv4 addresses of this computer, for the user to type into the remote if discovery fails. */
    public static String addresses() {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof java.net.Inet4Address) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(ia.getAddress().getHostAddress());
                    }
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return sb.length() == 0 ? "no network" : sb.toString();
    }

    private void beaconLoop() {
        String name;
        try {
            name = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            name = "computer";
        }
        byte[] msg = ("SMASK1 " + HTTP_PORT + " " + name).getBytes(StandardCharsets.UTF_8);
        while (running) {
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) {
                        continue;
                    }
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (ia.getBroadcast() != null) {
                            beacon.send(new DatagramPacket(msg, msg.length, ia.getBroadcast(), DISCOVERY_PORT));
                        }
                    }
                }
                beacon.send(new DatagramPacket(msg, msg.length,
                        InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
            } catch (IOException e) {
                // network may be down; keep trying
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void handle(HttpExchange ex, boolean action) throws IOException {
        try {
            String id = ex.getRequestHeaders().getFirst("X-Remote-Id");
            String name = ex.getRequestHeaders().getFirst("X-Remote-Name");
            if (id == null || id.isBlank()) {
                reply(ex, 400, "{\"error\":\"missing X-Remote-Id\"}");
                return;
            }
            if (!handler.isApproved(id)) {
                handler.requestApproval(id, name == null ? "Remote" : name,
                        ex.getRemoteAddress().getAddress().getHostAddress());
                reply(ex, 403, "{\"error\":\"approval\"}");
                return;
            }
            if (!action) {
                reply(ex, 200, handler.stateJson());
                return;
            }
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> params = parseForm(body);
            if (ex.getRequestURI().getRawQuery() != null) {
                params.putAll(parseForm(ex.getRequestURI().getRawQuery()));
            }
            String err = handler.act(params);
            if (err != null) {
                reply(ex, 400, "{\"error\":" + json(err) + "}");
            } else {
                reply(ex, 200, handler.stateJson());
            }
        } catch (RuntimeException e) {
            reply(ex, 500, "{\"error\":" + json(String.valueOf(e.getMessage())) + "}");
        }
    }

    static Map<String, String> parseForm(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void reply(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    /** Minimal JSON string escaping. */
    public static String json(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}

package com.shiningmask.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Talks to the Shining Mask desktop app (RemoteServer.java on the computer).
 *
 * <p>Finds the computer through its UDP broadcast on port 47321 (or a manually entered address),
 * polls {@code /api/state} and sends actions to {@code /api/do}. Actions are queued by key and
 * only the newest value per key is sent, so spinning the wheel never floods the network.
 */
final class RemoteClient {
    static final int HTTP_PORT = 47320;
    static final int DISCOVERY_PORT = 47321;

    interface Listener {
        void onUpdate();
    }

    enum Status { SEARCHING, CONNECTING, NEEDS_APPROVAL, CONNECTED, OFFLINE }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final String remoteId;
    private final Map<String, String> pending = new LinkedHashMap<String, String>();
    private final Object lock = new Object();

    private volatile boolean running;
    private volatile String host;
    private volatile String hostName = "";
    private volatile Status status = Status.SEARCHING;
    private volatile String lastError = "";
    private volatile JSONObject state;
    private volatile long stateAtMs;
    private DatagramSocket discoverySocket;
    private WifiManager.MulticastLock multicastLock;

    RemoteClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
        String id = prefs.getString("id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("id", id).commit();
        }
        remoteId = id;
        String manual = manualHost();
        host = manual.length() > 0 ? manual : prefs.getString("lastHost", null);
    }

    // ------------------------------------------------------------------ public API

    Status status() { return status; }
    String host() { return host; }
    String hostName() { return hostName; }
    String lastError() { return lastError; }
    JSONObject state() { return state; }
    long stateAgeMs() { return System.currentTimeMillis() - stateAtMs; }

    String manualHost() {
        return prefs.getString("manualHost", "");
    }

    void setManualHost(String h) {
        prefs.edit().putString("manualHost", h == null ? "" : h.trim()).commit();
        if (h != null && h.trim().length() > 0) {
            host = h.trim();
            status = Status.CONNECTING;
        } else {
            host = prefs.getString("lastHost", null);
        }
        notifyUi();
    }

    /** Queue an action. A later action with the same key replaces an unsent earlier one. */
    void send(String key, String form) {
        synchronized (lock) {
            pending.remove(key);
            pending.put(key, form);
            lock.notifyAll();
        }
    }

    void send(String form) {
        send(form + "#" + System.nanoTime(), form);
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (IOException e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        if (running) {
            return;
        }
        running = true;
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("mask-remote");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        thread("discovery", new Runnable() { public void run() { discoveryLoop(); } });
        thread("poll", new Runnable() { public void run() { pollLoop(); } });
        thread("actions", new Runnable() { public void run() { actionLoop(); } });
    }

    void stop() {
        running = false;
        if (discoverySocket != null) {
            discoverySocket.close();
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void thread(String name, Runnable r) {
        Thread t = new Thread(r, "remote-" + name);
        t.setDaemon(true);
        t.start();
    }

    private void notifyUi() {
        main.post(new Runnable() {
            public void run() {
                listener.onUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ discovery

    private void discoveryLoop() {
        byte[] buf = new byte[256];
        while (running) {
            try {
                if (discoverySocket == null || discoverySocket.isClosed()) {
                    discoverySocket = new DatagramSocket(null);
                    discoverySocket.setReuseAddress(true);
                    discoverySocket.setBroadcast(true);
                    discoverySocket.bind(new InetSocketAddress(DISCOVERY_PORT));
                    discoverySocket.setSoTimeout(2000);
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                discoverySocket.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                String[] parts = msg.split(" ", 3);
                if (parts.length >= 2 && "SMASK1".equals(parts[0]) && manualHost().length() == 0) {
                    String found = p.getAddress().getHostAddress();
                    hostName = parts.length > 2 ? parts[2] : found;
                    if (!found.equals(host)) {
                        host = found;
                        prefs.edit().putString("lastHost", found).commit();
                        notifyUi();
                    }
                }
            } catch (SocketTimeoutException e) {
                // keep listening
            } catch (IOException e) {
                sleep(2000);
            }
        }
    }

    // ------------------------------------------------------------------ polling

    private void pollLoop() {
        while (running) {
            String h = host;
            if (h == null) {
                setStatus(Status.SEARCHING, "");
                sleep(500);
                continue;
            }
            try {
                String body = http(h, "GET", "/api/state", null);
                state = new JSONObject(body);
                stateAtMs = System.currentTimeMillis();
                setStatus(Status.CONNECTED, "");
                sleep(state.optJSONObject("link") != null
                        && state.optJSONObject("link").optBoolean("enabled") ? 300 : 700);
            } catch (HttpError e) {
                if (e.code == 403) {
                    setStatus(Status.NEEDS_APPROVAL, "");
                } else {
                    setStatus(Status.OFFLINE, e.getMessage());
                }
                sleep(1000);
            } catch (Exception e) {
                setStatus(status == Status.CONNECTED ? Status.OFFLINE : Status.CONNECTING, String.valueOf(e.getMessage()));
                sleep(1000);
            }
        }
    }

    private void setStatus(Status s, String err) {
        boolean changed = s != status || !err.equals(lastError);
        status = s;
        lastError = err;
        if (changed || s == Status.CONNECTED) {
            notifyUi();
        }
    }

    // ------------------------------------------------------------------ actions

    private void actionLoop() {
        while (running) {
            String form;
            synchronized (lock) {
                while (running && pending.isEmpty()) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!running) {
                    return;
                }
                String key = pending.keySet().iterator().next();
                form = pending.remove(key);
            }
            String h = host;
            if (h == null) {
                continue;
            }
            try {
                String body = http(h, "POST", "/api/do", form);
                state = new JSONObject(body);
                stateAtMs = System.currentTimeMillis();
                lastError = "";
            } catch (HttpError e) {
                lastError = errorText(e.body, e.getMessage());
            } catch (Exception e) {
                lastError = String.valueOf(e.getMessage());
            }
            notifyUi();
        }
    }

    private static String errorText(String body, String fallback) {
        try {
            return new JSONObject(body).optString("error", fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ HTTP

    static final class HttpError extends IOException {
        final int code;
        final String body;

        HttpError(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }

    private String http(String h, String method, String path, String form) throws IOException {
        URL url = new URL("http://" + h + ":" + HTTP_PORT + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setRequestProperty("X-Remote-Id", remoteId);
            c.setRequestProperty("X-Remote-Name", deviceName());
            c.setRequestProperty("Connection", "close");
            if (form != null) {
                byte[] b = form.getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            if (code >= 400) {
                throw new HttpError(code, body);
            }
            return body;
        } finally {
            c.disconnect();
        }
    }

    private static String deviceName() {
        String m = Build.MODEL == null ? "" : Build.MODEL;
        return "Y1".equalsIgnoreCase(m) ? "Innioasis Y1" : (Build.MANUFACTURER + " " + m).trim();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toString("UTF-8");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

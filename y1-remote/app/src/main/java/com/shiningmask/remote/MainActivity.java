package com.shiningmask.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Wheel-driven remote for the Shining Mask desktop app, made for the Innioasis Y1
 * (Android 4.2, 480x360, click wheel = D-pad keys). Also works with touch.
 */
public class MainActivity extends Activity implements RemoteClient.Listener {

    // ------------------------------------------------------------------ menu model

    interface Text { String get(); }
    interface IntGet { int get(); }
    interface IntSet { void set(int v); }
    interface Builder { void build(Menu m); }

    static final int ACTION = 0;
    static final int SUBMENU = 1;
    static final int NUMBER = 2;
    static final int HOLD = 3;
    static final int INFO = 4;

    static final class Item {
        int type = ACTION;
        Text label;
        Text value;
        Runnable action;
        Builder submenu;
        Text submenuTitle;
        int min, max, step = 1;
        IntGet get;
        IntSet set;
        Text format;          // for NUMBER: how to show the value being edited (uses editValue)
        String key;           // action key for latest-wins sending
        String param = "v";   // form field that carries the value
        Runnable holdStart, holdEnd;
    }

    static final class Menu {
        final String title;
        final Builder builder;
        final List<Item> items = new ArrayList<Item>();
        int selected;
        int scroll;

        Menu(String title, Builder builder) {
            this.title = title;
            this.builder = builder;
        }

        void rebuild() {
            items.clear();
            builder.build(this);
            if (selected >= items.size()) {
                selected = Math.max(0, items.size() - 1);
            }
        }

        Item add(String label) {
            return add(constant(label));
        }

        Item add(Text label) {
            Item i = new Item();
            i.label = label;
            items.add(i);
            return i;
        }
    }

    static Text constant(final String s) {
        return new Text() { public String get() { return s; } };
    }

    // ------------------------------------------------------------------ state

    private RemoteClient client;
    private MenuView view;
    private final List<Menu> stack = new ArrayList<Menu>();
    private boolean editing;
    private int editValue;
    private boolean holding;
    private String toast = "";
    private long toastUntil;
    private String lastPresetKind = "image";
    private int lastPresetIndex = -1;
    private SharedPreferences prefs;
    private final int[] manualIp = {192, 168, 1, 2};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ui", Context.MODE_PRIVATE);
        client = new RemoteClient(this, this);
        String manual = client.manualHost();
        if (manual.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            String[] p = manual.split("\\.");
            for (int i = 0; i < 4; i++) {
                manualIp[i] = Integer.parseInt(p[i]);
            }
        } else {
            prefillFromOwnAddress();
        }
        view = new MenuView(this);
        setContentView(view);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        stack.add(new Menu("Mask Remote", new Builder() { public void build(Menu m) { buildMain(m); } }));
        rebuild();
        applyKeepScreenOn();
    }

    /** The computer is usually on the same /24 network, so start from our own Wi-Fi address. */
    @SuppressWarnings("deprecation")
    private void prefillFromOwnAddress() {
        try {
            android.net.wifi.WifiManager wifi =
                    (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip = wifi == null ? 0 : wifi.getConnectionInfo().getIpAddress();
            if (ip != 0) {
                for (int i = 0; i < 4; i++) {
                    manualIp[i] = (ip >> (8 * i)) & 0xFF;
                }
            }
        } catch (RuntimeException ignored) {
            // keep the default
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        client.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (holding) {
            holdEnd();
        }
        client.stop();
    }

    @Override
    public void onUpdate() {
        // Rebuild so dynamic lists (texts, devices) follow the computer's state.
        rebuild();
    }

    private Menu current() {
        return stack.get(stack.size() - 1);
    }

    private void rebuild() {
        if (!editing) {
            current().rebuild();
        }
        view.invalidate();
    }

    private void applyKeepScreenOn() {
        if (prefs.getBoolean("keepOn", true)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void showToast(String s) {
        toast = s;
        toastUntil = System.currentTimeMillis() + 2500;
        view.invalidate();
        view.postDelayed(new Runnable() { public void run() { view.invalidate(); } }, 2600);
    }

    // ------------------------------------------------------------------ state helpers

    private JSONObject st() {
        return client.state();
    }

    private JSONObject strobeState() {
        JSONObject s = st();
        return s == null ? null : s.optJSONObject("strobe");
    }

    private JSONObject linkState() {
        JSONObject s = st();
        return s == null ? null : s.optJSONObject("link");
    }

    private int stInt(String key, int def) {
        JSONObject s = st();
        return s == null ? def : s.optInt(key, def);
    }

    private int strobeInt(String key, int def) {
        JSONObject s = strobeState();
        return s == null ? def : s.optInt(key, def);
    }

    private boolean strobeBool(String key) {
        JSONObject s = strobeState();
        return s != null && s.optBoolean(key);
    }

    private boolean maskConnected() {
        JSONObject s = st();
        return s != null && s.optBoolean("connected");
    }

    private String[] divisions() {
        JSONObject s = strobeState();
        JSONArray a = s == null ? null : s.optJSONArray("divs");
        if (a == null) {
            return new String[] {"1/32 note", "1/16 note", "1/8 note", "1/4 note (1 beat)", "1/2 note", "1 bar", "2 bars"};
        }
        String[] out = new String[a.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = a.optString(i);
        }
        return out;
    }

    private void act(String form) {
        if (client.status() != RemoteClient.Status.CONNECTED) {
            showToast("Not connected to the computer");
            return;
        }
        client.send(form);
    }

    private void actLatest(String key, String form) {
        if (client.status() == RemoteClient.Status.CONNECTED) {
            client.send(key, form);
        }
    }

    // ------------------------------------------------------------------ menus

    private void buildMain(Menu m) {
        Item strobe = m.add("Strobe");
        strobe.value = new Text() { public String get() { return strobeBool("latched") ? "ON" : "OFF"; } };
        strobe.action = new Runnable() { public void run() { toggleStrobe(); } };

        Item hold = m.add("Hold to strobe");
        hold.type = HOLD;
        hold.value = new Text() { public String get() { return holding ? "●" : "hold ○"; } };

        submenu(m, "Strobe settings", new Builder() { public void build(Menu s) { buildStrobe(s); } });
        number(m, "Brightness", "light", 1, 100, 5,
                new IntGet() { public int get() { return stInt("brightness", 50); } });
        number(m, "Speed", "speed", 1, 100, 5,
                new IntGet() { public int get() { return stInt("speed", 50); } });
        submenu(m, "Images", new Builder() { public void build(Menu s) { buildPresets(s, "image", "Image", stInt("images", 70)); } });
        submenu(m, "Animations", new Builder() { public void build(Menu s) { buildAnimations(s); } });
        submenu(m, "My images", new Builder() { public void build(Menu s) { buildPresets(s, "slot", "Slot", stInt("slots", 20)); } });
        submenu(m, "Text", new Builder() { public void build(Menu s) { buildTexts(s); } });
        submenu(m, "Mask", new Builder() { public void build(Menu s) { buildMask(s); } });
        submenu(m, "Settings", new Builder() { public void build(Menu s) { buildSettings(s); } });
        Item exit = m.add("Exit to music player");
        exit.action = new Runnable() { public void run() { exitToPlayer(); } };
    }

    private void buildStrobe(Menu m) {
        Item sync = m.add("Timing");
        sync.value = new Text() { public String get() { return strobeBool("link") ? "Ableton Link" : "Free"; } };
        sync.action = new Runnable() { public void run() { act("a=sync&on=" + (strobeBool("link") ? 0 : 1)); } };

        Item rate = number(m, "Rate", "hz", 1, 25, 1,
                new IntGet() { public int get() { return strobeInt("hz", 8); } });
        rate.format = new Text() { public String get() { return editValue + " Hz"; } };
        final Item div = number(m, "Flash every", "div", 0, divisions().length - 1, 1,
                new IntGet() { public int get() { return strobeInt("div", 2); } });
        div.format = new Text() {
            public String get() {
                String[] d = divisions();
                return d[Math.max(0, Math.min(d.length - 1, editValue))];
            }
        };
        div.param = "n";
        Item duty = number(m, "Flash length", "duty", 5, 95, 5,
                new IntGet() { public int get() { return strobeInt("duty", 50); } });
        duty.format = new Text() { public String get() { return editValue + "%"; } };
        number(m, "On brightness", "onLevel", 1, 100, 5,
                new IntGet() { public int get() { return strobeInt("onLevel", 100); } });
        number(m, "Off brightness", "offLevel", 0, 100, 1,
                new IntGet() { public int get() { return strobeInt("offLevel", 1); } });

        Item link = m.add("Ableton Link");
        link.value = new Text() {
            public String get() {
                JSONObject l = linkState();
                return l != null && l.optBoolean("enabled") ? "On" : "Off";
            }
        };
        link.action = new Runnable() {
            public void run() {
                JSONObject l = linkState();
                act("a=link&on=" + (l != null && l.optBoolean("enabled") ? 0 : 1));
            }
        };
        Item info = m.add(new Text() {
            public String get() {
                JSONObject l = linkState();
                if (l == null || !l.optBoolean("enabled")) {
                    return "Link is off";
                }
                if (l.optInt("peers") == 0) {
                    return "No Link peers - turn on Link in Live";
                }
                return String.format("%d peer%s, %.1f BPM%s", l.optInt("peers"), l.optInt("peers") == 1 ? "" : "s",
                        l.optDouble("bpm"), l.optBoolean("playing") ? ", playing" : "");
            }
        });
        info.type = INFO;
    }

    private void buildPresets(Menu m, final String kind, String label, int count) {
        int first = "slot".equals(kind) ? 1 : 0;
        for (int i = 0; i < count; i++) {
            final int n = i + first;
            Item it = m.add(label + " " + (i + 1));
            it.action = new Runnable() { public void run() { sendPreset(kind, n); } };
        }
    }

    private void buildAnimations(Menu m) {
        Item loop = m.add("Loop all animations");
        loop.action = new Runnable() { public void run() { act("a=loop"); } };
        buildPresets(m, "anim", "Animation", stInt("anims", 44));
    }

    private void buildTexts(Menu m) {
        JSONObject s = st();
        JSONArray texts = s == null ? null : s.optJSONArray("texts");
        if (texts == null || texts.length() == 0) {
            Item none = m.add("Send text from the computer first");
            none.type = INFO;
            return;
        }
        for (int i = 0; i < texts.length(); i++) {
            final String t = texts.optString(i);
            Item it = m.add(t);
            it.action = new Runnable() {
                public void run() {
                    act("a=text&s=" + RemoteClient.enc(t));
                    showToast("Sending \"" + t + "\"...");
                }
            };
        }
        Item hint = m.add("Add texts on the computer's Text tab");
        hint.type = INFO;
    }

    private void buildMask(Menu m) {
        Item status = m.add(new Text() {
            public String get() {
                JSONObject s = st();
                if (s == null) {
                    return "Computer not connected";
                }
                return s.optBoolean("connected") ? "Connected: " + s.optString("mask", "mask") : s.optString("status", "Not connected");
            }
        });
        status.type = INFO;
        if (maskConnected()) {
            Item d = m.add("Disconnect");
            d.action = new Runnable() { public void run() { act("a=disconnect"); } };
        } else {
            JSONObject s = st();
            JSONArray devices = s == null ? null : s.optJSONArray("devices");
            if (devices != null) {
                for (int i = 0; i < devices.length(); i++) {
                    final int n = i;
                    String name = devices.optString(i);
                    if (name.contains("  (")) {
                        name = name.substring(0, name.indexOf("  ("));
                    }
                    Item it = m.add("Connect " + name);
                    it.action = new Runnable() { public void run() { act("a=connect&n=" + n); } };
                }
            }
            Item scan = m.add("Scan for masks");
            scan.action = new Runnable() { public void run() { act("a=scan"); showToast("Scanning..."); } };
        }
    }

    private void buildSettings(Menu m) {
        Item comp = m.add(new Text() {
            public String get() {
                String h = client.host();
                return h == null ? "Computer: searching..." : "Computer: " + (client.hostName().length() > 0 ? client.hostName() + " " : "") + h;
            }
        });
        comp.type = INFO;
        Item mode = m.add("Find computer");
        mode.value = new Text() { public String get() { return client.manualHost().length() > 0 ? "Manual" : "Automatic"; } };
        mode.action = new Runnable() {
            public void run() {
                if (client.manualHost().length() > 0) {
                    client.setManualHost("");
                    showToast("Searching automatically");
                } else {
                    push("Computer address", new Builder() { public void build(Menu s) { buildIpEntry(s); } });
                }
            }
        };
        Item ip = m.add("Enter address...");
        ip.type = SUBMENU;
        ip.submenuTitle = constant("Computer address");
        ip.submenu = new Builder() { public void build(Menu s) { buildIpEntry(s); } };
        Item keep = m.add("Keep screen on");
        keep.value = new Text() { public String get() { return prefs.getBoolean("keepOn", true) ? "On" : "Off"; } };
        keep.action = new Runnable() {
            public void run() {
                prefs.edit().putBoolean("keepOn", !prefs.getBoolean("keepOn", true)).commit();
                applyKeepScreenOn();
            }
        };
        Item home = m.add("Choose home screen...");
        home.action = new Runnable() { public void run() { chooseHome(); } };
        Item about = m.add("Mask Remote 1.0 - needs the desktop app");
        about.type = INFO;
    }

    private void buildIpEntry(Menu m) {
        String[] names = {"First part", "Second part", "Third part", "Fourth part"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Item it = m.add(names[i]);
            it.type = NUMBER;
            it.min = 0;
            it.max = 255;
            it.step = 1;
            it.get = new IntGet() { public int get() { return manualIp[idx]; } };
            it.set = new IntSet() { public void set(int v) { manualIp[idx] = v; } };
        }
        Item preview = m.add(new Text() {
            public String get() {
                return "Address: " + manualIp[0] + "." + manualIp[1] + "." + manualIp[2] + "." + manualIp[3];
            }
        });
        preview.type = INFO;
        Item use = m.add("Use this address");
        use.action = new Runnable() {
            public void run() {
                client.setManualHost(manualIp[0] + "." + manualIp[1] + "." + manualIp[2] + "." + manualIp[3]);
                showToast("Using " + client.manualHost());
                pop();
            }
        };
    }

    // ------------------------------------------------------------------ item factories

    private void submenu(Menu m, final String title, Builder b) {
        Item it = m.add(title);
        it.type = SUBMENU;
        it.submenu = b;
        it.submenuTitle = constant(title);
    }

    /** A value edited with the wheel and sent live (latest value wins). */
    private Item number(Menu m, String label, final String actionKey, int min, int max, int step, IntGet get) {
        final Item it = m.add(label);
        it.type = NUMBER;
        it.min = min;
        it.max = max;
        it.step = step;
        it.get = get;
        it.key = actionKey;
        it.set = new IntSet() {
            public void set(int v) {
                actLatest(actionKey, "a=" + actionKey + "&" + it.param + "=" + v);
            }
        };
        return it;
    }

    // ------------------------------------------------------------------ actions

    private void push(String title, Builder b) {
        Menu m = new Menu(title, b);
        stack.add(m);
        m.rebuild();
        view.invalidate();
    }

    private void pop() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            rebuild();
        }
    }

    private void toggleStrobe() {
        if (!maskConnected()) {
            showToast("Mask not connected");
            return;
        }
        act("a=strobe&on=" + (strobeBool("latched") ? 0 : 1));
    }

    private void holdStart() {
        if (!maskConnected()) {
            showToast("Mask not connected");
            return;
        }
        holding = true;
        client.send("hold", "a=hold&on=1");
        view.invalidate();
    }

    private void holdEnd() {
        holding = false;
        client.send("hold", "a=hold&on=0");
        view.invalidate();
    }

    private void sendPreset(String kind, int n) {
        lastPresetKind = kind;
        lastPresetIndex = n;
        act("a=" + kind + "&n=" + n);
    }

    private void stepPreset(int delta) {
        int count;
        int first = 0;
        if ("anim".equals(lastPresetKind)) {
            count = stInt("anims", 44);
        } else if ("slot".equals(lastPresetKind)) {
            count = stInt("slots", 20);
            first = 1;
        } else {
            count = stInt("images", 70);
        }
        int idx = lastPresetIndex < 0 ? first : lastPresetIndex + delta;
        if (idx < first) {
            idx = first + count - 1;
        }
        if (idx > first + count - 1) {
            idx = first;
        }
        sendPreset(lastPresetKind, idx);
        String name = "anim".equals(lastPresetKind) ? "Animation" : "slot".equals(lastPresetKind) ? "Slot" : "Image";
        showToast(name + " " + (idx - first + 1));
    }

    private void exitToPlayer() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.innioasis.y1");
        if (i != null) {
            startActivity(i);
        } else {
            chooseHome();
        }
    }

    private void chooseHome() {
        // Only our own preference can be cleared; that is enough to bring the chooser back.
        getPackageManager().clearPackagePreferredActivities(getPackageName());
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(Intent.createChooser(home, "Home screen"));
    }

    // ------------------------------------------------------------------ input

    private long lastMoveMs;
    private int fastMoves;

    private void move(int delta) {
        Menu m = current();
        if (m.items.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        fastMoves = now - lastMoveMs < 90 ? fastMoves + 1 : 0;
        lastMoveMs = now;
        if (editing) {
            Item it = m.items.get(m.selected);
            // Spinning the wheel fast takes bigger steps on long ranges (e.g. 0-255).
            int accel = (it.max - it.min) / it.step > 40 ? (fastMoves > 8 ? 10 : fastMoves > 3 ? 5 : 1) : 1;
            int v = Math.max(it.min, Math.min(it.max, editValue + delta * it.step * accel));
            if (v != editValue) {
                editValue = v;
                it.set.set(v);
            }
        } else {
            int n = m.items.size();
            m.selected = (m.selected + delta + n) % n;
        }
        view.invalidate();
    }

    private void select() {
        Menu m = current();
        if (m.items.isEmpty()) {
            return;
        }
        Item it = m.items.get(m.selected);
        if (editing) {
            editing = false;
            rebuild();
            return;
        }
        switch (it.type) {
            case SUBMENU:
                push(it.submenuTitle.get(), it.submenu);
                break;
            case NUMBER:
                editing = true;
                editValue = it.get.get();
                break;
            case ACTION:
                if (it.action != null) {
                    it.action.run();
                }
                break;
            default:
                break;
        }
        view.invalidate();
    }

    private void back() {
        if (editing) {
            editing = false;
            rebuild();
        } else {
            pop();
        }
    }

    private boolean selectedIsHold() {
        Menu m = current();
        return !editing && !m.items.isEmpty() && m.items.get(m.selected).type == HOLD;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        boolean first = down && e.getRepeatCount() == 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) {
                    move(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    move(1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (selectedIsHold() || holding) {
                    if (first) {
                        holdStart();
                    } else if (up && holding) {
                        holdEnd();
                    }
                } else if (first) {
                    select();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                if (first) {
                    back();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                if (first) {
                    toggleStrobe();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (first) {
                    stepPreset(1);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (first) {
                    stepPreset(-1);
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    // ------------------------------------------------------------------ drawing

    private final class MenuView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float downY;
        private int downIndex = -1;

        MenuView(Context c) {
            super(c);
            setBackgroundColor(Color.rgb(12, 12, 16));
            // Repaint a few times a second for the beat lights.
            postDelayed(ticker, 100);
        }

        private final Runnable ticker = new Runnable() {
            public void run() {
                JSONObject l = linkState();
                if (l != null && l.optBoolean("enabled")) {
                    invalidate();
                }
                postDelayed(this, 60);
            }
        };

        private int rowHeight() {
            return Math.max(28, getHeight() / 8);
        }

        private int headerHeight() {
            return rowHeight();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            int row = rowHeight();
            int header = headerHeight();
            float pad = row * 0.35f;

            // Header: connection + Link status
            paint.setColor(Color.rgb(28, 28, 36));
            c.drawRect(0, 0, w, header, paint);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(row * 0.42f);
            paint.setColor(statusColor());
            c.drawCircle(pad, header / 2f, row * 0.14f, paint);
            paint.setColor(Color.rgb(230, 230, 236));
            c.drawText(ellipsize(statusText(), w * 0.62f), pad * 2.2f, header / 2f + row * 0.15f, paint);
            drawBeats(c, w, header, row);

            // Title
            Menu m = current();
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(row * 0.5f);
            paint.setColor(Color.rgb(255, 170, 60));
            float titleY = header + row * 0.72f;
            c.drawText(stack.size() > 1 ? "‹ " + m.title : m.title, pad, titleY, paint);

            // Items
            int top = header + row;
            int footer = row;
            int visible = Math.max(1, (h - top - footer) / row);
            if (m.selected < m.scroll) {
                m.scroll = m.selected;
            }
            if (m.selected >= m.scroll + visible) {
                m.scroll = m.selected - visible + 1;
            }
            paint.setTypeface(Typeface.DEFAULT);
            for (int i = m.scroll; i < m.items.size() && i < m.scroll + visible; i++) {
                Item it = m.items.get(i);
                int y = top + (i - m.scroll) * row;
                boolean sel = i == m.selected;
                if (sel) {
                    paint.setColor(editing ? Color.rgb(255, 120, 40) : Color.rgb(60, 110, 230));
                    rect.set(pad * 0.4f, y + 2, w - pad * 0.4f, y + row - 2);
                    c.drawRoundRect(rect, row * 0.18f, row * 0.18f, paint);
                }
                paint.setTextSize(row * 0.46f);
                paint.setColor(it.type == INFO ? Color.rgb(150, 150, 160) : Color.WHITE);
                String value = valueText(it, sel);
                float valueW = value.length() > 0 ? paint.measureText(value) + pad : 0;
                c.drawText(ellipsize(it.label.get(), w - pad * 2.5f - valueW), pad, y + row * 0.66f, paint);
                if (value.length() > 0) {
                    paint.setColor(sel ? Color.WHITE : Color.rgb(170, 200, 255));
                    c.drawText(value, w - pad - paint.measureText(value), y + row * 0.66f, paint);
                }
            }
            // Scroll indicator
            if (m.items.size() > visible) {
                float trackH = visible * row;
                float thumbH = Math.max(row * 0.5f, trackH * visible / m.items.size());
                float thumbY = top + (trackH - thumbH) * m.scroll / Math.max(1, m.items.size() - visible);
                paint.setColor(Color.rgb(90, 90, 100));
                c.drawRect(w - 4, thumbY, w - 1, thumbY + thumbH, paint);
            }

            // Footer: toast / error / hint
            paint.setTextSize(row * 0.38f);
            String foot;
            int footColor = Color.rgb(140, 140, 150);
            if (System.currentTimeMillis() < toastUntil) {
                foot = toast;
                footColor = Color.rgb(255, 220, 120);
            } else if (client.lastError().length() > 0 && client.status() == RemoteClient.Status.CONNECTED) {
                foot = client.lastError();
                footColor = Color.rgb(255, 120, 120);
            } else if (editing) {
                foot = "Turn the wheel to change, press to finish";
            } else if (selectedIsHold()) {
                foot = "Hold the centre button to strobe";
            } else {
                foot = "Play: strobe   Prev/Next: preset";
            }
            paint.setColor(footColor);
            c.drawText(ellipsize(foot, w - pad * 2), pad, h - row * 0.35f, paint);
        }

        private String valueText(Item it, boolean sel) {
            if (it.type == NUMBER) {
                int v = editing && sel ? editValue : it.get.get();
                String s;
                if (it.format != null) {
                    int saved = editValue;
                    editValue = v;
                    s = it.format.get();
                    editValue = saved;
                } else {
                    s = String.valueOf(v);
                }
                return editing && sel ? "‹ " + s + " ›" : s;
            }
            if (it.type == SUBMENU) {
                return "›";
            }
            return it.value == null ? "" : it.value.get();
        }

        private void drawBeats(Canvas c, int w, int header, int row) {
            JSONObject l = linkState();
            if (l == null || !l.optBoolean("enabled") || l.optInt("beat", -1) < 0) {
                return;
            }
            double bpm = l.optDouble("bpm", 0);
            double phase = l.optDouble("phase", l.optInt("beat"));
            phase = (phase + client.stateAgeMs() / 60000.0 * bpm) % 4;
            int beat = (int) phase;
            float size = row * 0.32f;
            float gap = size * 0.4f;
            float x0 = w - 4 * (size + gap) - gap;
            for (int i = 0; i < 4; i++) {
                paint.setColor(i == beat ? (i == 0 ? Color.rgb(255, 120, 40) : Color.rgb(80, 200, 120)) : Color.rgb(70, 70, 78));
                float x = x0 + i * (size + gap);
                c.drawRect(x, (header - size) / 2f, x + size, (header + size) / 2f, paint);
            }
        }

        private int statusColor() {
            switch (client.status()) {
                case CONNECTED:
                    return maskConnected() ? Color.rgb(80, 220, 120) : Color.rgb(255, 200, 60);
                case NEEDS_APPROVAL:
                    return Color.rgb(255, 200, 60);
                default:
                    return Color.rgb(220, 70, 70);
            }
        }

        private String statusText() {
            switch (client.status()) {
                case SEARCHING:
                    return "Looking for the computer...";
                case CONNECTING:
                    return "Connecting to " + client.host() + "...";
                case NEEDS_APPROVAL:
                    return "Approve on the computer";
                case OFFLINE:
                    return "Computer unreachable";
                default:
                    JSONObject s = st();
                    JSONObject l = linkState();
                    String bpm = l != null && l.optBoolean("enabled") && l.optDouble("bpm") > 0
                            ? String.format("  %.1f BPM", l.optDouble("bpm")) : "";
                    if (s == null || !s.optBoolean("connected")) {
                        return "No mask" + bpm;
                    }
                    return s.optString("mask", "Mask") + bpm;
            }
        }

        private String ellipsize(String s, float max) {
            if (paint.measureText(s) <= max) {
                return s;
            }
            while (s.length() > 1 && paint.measureText(s + "…") > max) {
                s = s.substring(0, s.length() - 1);
            }
            return s + "…";
        }

        // Touch support for phones: tap a row to select/activate, tap the header to go back.
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int row = rowHeight();
            int top = headerHeight() + row;
            Menu m = current();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    downIndex = e.getY() >= top ? m.scroll + (int) ((e.getY() - top) / row) : -1;
                    if (downIndex >= 0 && downIndex < m.items.size() && !editing) {
                        m.selected = downIndex;
                        if (m.items.get(downIndex).type == HOLD) {
                            holdStart();
                        }
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (holding) {
                        holdEnd();
                        return true;
                    }
                    if (e.getY() < top) {
                        back();
                    } else if (editing) {
                        move(e.getX() < getWidth() / 2f ? -1 : 1);
                    } else if (Math.abs(e.getY() - downY) < row / 2f && downIndex == m.selected) {
                        select();
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!editing && Math.abs(e.getY() - downY) > row) {
                        move(e.getY() < downY ? 1 : -1);
                        downY = e.getY();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}

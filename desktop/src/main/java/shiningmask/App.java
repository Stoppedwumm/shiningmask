package shiningmask;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.simplejavable.Adapter;
import org.simplejavable.Peripheral;

/** Desktop replacement for the Shining Mask Android app. */
public final class App {
    private static final int PRESET_IMAGES = 70;
    private static final int PRESET_ANIMATIONS = 44;
    private static final String[] TEXT_MODES = {"Static", "Scroll left", "Scroll right", "Blink"};
    private static final int[] TEXT_MODE_VALUES = {
        Protocol.MODE_STATIC, Protocol.MODE_SCROLL_LEFT, Protocol.MODE_SCROLL_RIGHT, Protocol.MODE_BLINK
    };
    private static final String[] COLOR_MODES = {"Solid colour", "Gradient 1", "Gradient 2", "Gradient 3", "Gradient 4"};

    private final Preferences prefs = Preferences.userNodeForPackage(App.class);
    private final ExecutorService ble = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ble");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Peripheral> found = new LinkedHashMap<>();
    private final AudioRhythm audio = new AudioRhythm();

    private JFrame frame;
    private JTextArea logArea;
    private JLabel status;
    private JComboBox<String> deviceBox;
    private JButton scanButton;
    private JButton connectButton;
    private JProgressBar progress;
    private final List<JComponent> needsConnection = new ArrayList<>();

    private Adapter adapter;
    private volatile MaskConnection mask;

    // text state
    private Color textColor = Color.WHITE;
    private Color bgColor = new Color(0, 0, 255);
    private byte[] textBitmap = new byte[0];
    private LedView textPreview;

    // DIY state
    private BufferedImage diyImage;
    private LedView diyPreview;

    // rhythm state
    private LedView rhythmPreview;
    private int rhythmMode;

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Shining Mask");
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        SwingUtilities.invokeLater(() -> new App().show());
    }

    private void show() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        frame = new JFrame("Shining Mask");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                audio.stop();
                MaskConnection m = mask;
                if (m != null) {
                    m.disconnect();
                }
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(buildTopBar(), BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Images", buildImagesTab());
        tabs.addTab("Animations", buildAnimationsTab());
        tabs.addTab("Text", buildTextTab());
        tabs.addTab("DIY photo", buildDiyTab());
        tabs.addTab("Rhythm", buildRhythmTab());
        tabs.addTab("Advanced", buildAdvancedTab());

        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.add(buildSliders(), BorderLayout.NORTH);
        center.add(tabs, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        logArea = new JTextArea(8, 80);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        progress = new JProgressBar(0, 100);
        progress.setStringPainted(true);
        progress.setVisible(false);
        bottom.add(progress, BorderLayout.NORTH);
        bottom.add(new JScrollPane(logArea), BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setSize(900, 760);
        frame.setLocationRelativeTo(null);
        setConnected(false);
        frame.setVisible(true);

        ble.submit(this::initAdapter);
    }

    // ---------------------------------------------------------------- top bar / connection

    private JComponent buildTopBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        deviceBox = new JComboBox<>(new DefaultComboBoxModel<>());
        deviceBox.setPrototypeDisplayValue("MASK-XXXXXX  (00000000-0000-0000-0000-000000000000)");
        scanButton = new JButton("Scan");
        scanButton.addActionListener(e -> scan());
        connectButton = new JButton("Connect");
        connectButton.addActionListener(e -> toggleConnect());
        status = new JLabel("Not connected");
        p.add(new JLabel("Mask:"));
        p.add(deviceBox);
        p.add(scanButton);
        p.add(connectButton);
        p.add(Box.createHorizontalStrut(12));
        p.add(status);
        return p;
    }

    private void initAdapter() {
        try {
            if (!Adapter.isBluetoothEnabled()) {
                log("Bluetooth is turned off.");
            }
            List<Adapter> adapters = Adapter.getAdapters();
            if (adapters.isEmpty()) {
                log("No Bluetooth adapter found.");
                return;
            }
            adapter = adapters.get(0);
            log("Using adapter " + adapter.getIdentifier());
            adapter.setEventListener(new Adapter.EventListener() {
                @Override
                public void onScanFound(Peripheral p) {
                    onPeripheral(p);
                }

                @Override
                public void onScanUpdated(Peripheral p) {
                    onPeripheral(p);
                }
            });
            ui(this::scan);
        } catch (Throwable t) {
            log("Bluetooth init failed: " + t);
        }
    }

    private void onPeripheral(Peripheral p) {
        String name;
        String addr;
        Map<Integer, byte[]> mfg;
        try {
            name = p.getIdentifier();
            addr = String.valueOf(p.getAddress());
            mfg = p.manufacturerData();
        } catch (Exception e) {
            return;
        }
        boolean isMask = Protocol.isMask(mfg) || (name != null && name.toUpperCase().startsWith("MASK"));
        if (!isMask) {
            return;
        }
        String label = (name == null || name.isEmpty() ? "?" : name) + "  (" + addr + ")";
        ui(() -> {
            if (found.containsKey(label)) {
                found.put(label, p);
                return;
            }
            found.put(label, p);
            deviceBox.addItem(label);
            log("Found " + label);
            if (addr.equals(prefs.get("lastAddress", "")) || deviceBox.getItemCount() == 1) {
                deviceBox.setSelectedItem(label);
            }
        });
    }

    private void scan() {
        if (adapter == null) {
            log("No Bluetooth adapter yet.");
            return;
        }
        scanButton.setEnabled(false);
        status.setText("Scanning...");
        found.clear();
        deviceBox.removeAllItems();
        ble.submit(() -> {
            try {
                adapter.scanFor(4000);
            } catch (Throwable t) {
                log("Scan failed: " + t);
            }
            ui(() -> {
                scanButton.setEnabled(true);
                if (mask == null) {
                    status.setText(found.isEmpty() ? "No mask found - is it switched on?" : "Not connected");
                }
            });
        });
    }

    private void toggleConnect() {
        if (mask != null) {
            MaskConnection m = mask;
            mask = null;
            ble.submit(m::disconnect);
            setConnected(false);
            return;
        }
        String label = (String) deviceBox.getSelectedItem();
        Peripheral p = label == null ? null : found.get(label);
        if (p == null) {
            log("Pick a mask first (Scan).");
            return;
        }
        connectButton.setEnabled(false);
        status.setText("Connecting...");
        ble.submit(() -> {
            MaskConnection m = new MaskConnection(p, this::log);
            try {
                m.connect(() -> ui(() -> {
                    if (mask == m) {
                        mask = null;
                        log("Disconnected.");
                        setConnected(false);
                    }
                }));
                mask = m;
                prefs.put("lastAddress", String.valueOf(p.getAddress()));
                log("Connected to " + label);
                ui(() -> setConnected(true));
            } catch (Throwable t) {
                log("Connect failed: " + t);
                m.disconnect();
                ui(() -> setConnected(false));
            }
        });
    }

    private void setConnected(boolean c) {
        connectButton.setEnabled(true);
        connectButton.setText(c ? "Disconnect" : "Connect");
        status.setText(c ? "Connected" : "Not connected");
        for (JComponent comp : needsConnection) {
            comp.setEnabled(c);
        }
        if (!c && audio.isRunning()) {
            audio.stop();
        }
    }

    private <T extends JComponent> T gated(T c) {
        needsConnection.add(c);
        return c;
    }

    /** Run a BLE action on the BLE thread with the current connection. */
    private void withMask(String what, MaskAction action) {
        MaskConnection m = mask;
        if (m == null) {
            log("Not connected.");
            return;
        }
        ble.submit(() -> {
            try {
                action.run(m);
            } catch (Throwable t) {
                log(what + " failed: " + t.getMessage());
            } finally {
                ui(() -> progress.setVisible(false));
            }
        });
    }

    private void send(byte[] plaintext) {
        withMask("Command", m -> m.command(plaintext));
    }

    private interface MaskAction {
        void run(MaskConnection m) throws Exception;
    }

    // ---------------------------------------------------------------- brightness / speed

    private JComponent buildSliders() {
        JPanel p = new JPanel(new GridLayout(1, 2, 16, 0));
        p.add(slider("Brightness", "brightness", 50, v -> send(Protocol.light(v))));
        p.add(slider("Speed", "speed", 50, v -> send(Protocol.speed(v))));
        return p;
    }

    private JComponent slider(String title, String key, int def, java.util.function.IntConsumer onSet) {
        JSlider s = gated(new JSlider(1, 100, prefs.getInt(key, def)));
        JLabel value = new JLabel(String.valueOf(s.getValue()));
        value.setPreferredSize(new Dimension(32, 16));
        s.addChangeListener(e -> {
            value.setText(String.valueOf(s.getValue()));
            if (!s.getValueIsAdjusting()) {
                prefs.putInt(key, s.getValue());
                onSet.accept(s.getValue());
            }
        });
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(title), BorderLayout.WEST);
        p.add(s, BorderLayout.CENTER);
        p.add(value, BorderLayout.EAST);
        return p;
    }

    // ---------------------------------------------------------------- presets

    private JComponent buildImagesTab() {
        JPanel grid = new JPanel(new GridLayout(0, 10, 4, 4));
        for (int i = 0; i < PRESET_IMAGES; i++) {
            int n = i;
            JButton b = gated(new JButton(String.valueOf(i + 1)));
            b.addActionListener(e -> send(Protocol.image(n)));
            grid.add(b);
        }
        return wrap(grid, "Built-in images stored on the mask.");
    }

    private JComponent buildAnimationsTab() {
        JPanel grid = new JPanel(new GridLayout(0, 10, 4, 4));
        for (int i = 0; i < PRESET_ANIMATIONS; i++) {
            int n = i;
            JButton b = gated(new JButton(String.valueOf(i + 1)));
            b.addActionListener(e -> send(Protocol.animationByPosition(n)));
            grid.add(b);
        }
        JButton loop = gated(new JButton("Loop all animations"));
        loop.addActionListener(e -> send(Protocol.loopAnimations()));
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(grid, BorderLayout.CENTER);
        p.add(row(loop), BorderLayout.SOUTH);
        return wrap(p, "Built-in animations stored on the mask.");
    }

    // ---------------------------------------------------------------- text

    private JComponent buildTextTab() {
        JTextField input = new JTextField(prefs.get("text", "HELLO"), 30);
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> fontBox = new JComboBox<>(fonts);
        fontBox.setSelectedItem(prefs.get("font", Font.SANS_SERIF));
        if (fontBox.getSelectedIndex() < 0) {
            fontBox.setSelectedItem(Font.SANS_SERIF);
        }
        JCheckBox bold = new JCheckBox("Bold", prefs.getBoolean("bold", true));
        JComboBox<String> modeBox = new JComboBox<>(TEXT_MODES);
        modeBox.setSelectedIndex(prefs.getInt("textMode", 1));

        textPreview = new LedView(6);
        Runnable refresh = () -> {
            int style = bold.isSelected() ? Font.BOLD : Font.PLAIN;
            Font f = new Font((String) fontBox.getSelectedItem(), style, 16);
            BufferedImage img = Protocol.renderText(input.getText(), f);
            textBitmap = Protocol.textBitmap(img);
            int[][] px = new int[img.getWidth()][Protocol.TEXT_HEIGHT];
            for (int x = 0; x < img.getWidth(); x++) {
                for (int y = 0; y < Protocol.TEXT_HEIGHT; y++) {
                    px[x][y] = (img.getRGB(x, y) & 0xFFFFFF) != 0 ? textColor.getRGB() & 0xFFFFFF : 0;
                }
            }
            textPreview.setPixels(px);
        };
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh.run(); }
        });
        fontBox.addActionListener(e -> refresh.run());
        bold.addActionListener(e -> refresh.run());

        JButton fg = new JButton("Text colour");
        fg.addActionListener(e -> {
            Color c = JColorChooser.showDialog(frame, "Text colour", textColor);
            if (c != null) {
                textColor = c;
                refresh.run();
            }
        });
        JButton bg = new JButton("Background colour");
        bg.addActionListener(e -> {
            Color c = JColorChooser.showDialog(frame, "Background colour", bgColor);
            if (c != null) {
                bgColor = c;
            }
        });
        JComboBox<String> fgMode = new JComboBox<>(COLOR_MODES);
        JComboBox<String> bgMode = new JComboBox<>(COLOR_MODES);
        JCheckBox fgOn = new JCheckBox("Override text colour", false);
        JCheckBox bgOn = new JCheckBox("Background on", false);

        JButton sendText = gated(new JButton("Send text"));
        sendText.addActionListener(e -> {
            prefs.put("text", input.getText());
            prefs.put("font", (String) fontBox.getSelectedItem());
            prefs.putBoolean("bold", bold.isSelected());
            prefs.putInt("textMode", modeBox.getSelectedIndex());
            if (textBitmap.length == 0 || input.getText().isEmpty()) {
                log("Nothing to send.");
                return;
            }
            byte[] bitmap = textBitmap;
            Color color = textColor;
            int mode = TEXT_MODE_VALUES[modeBox.getSelectedIndex()];
            int speed = prefs.getInt("speed", 50);
            progress.setValue(0);
            progress.setVisible(true);
            withMask("Text upload", m -> {
                m.uploadText(bitmap, color, mode, speed, pct -> ui(() -> progress.setValue(pct)));
                log("Text sent (" + bitmap.length / 2 + " columns).");
            });
        });
        JButton applyMode = gated(new JButton("Apply mode only"));
        applyMode.addActionListener(e -> send(Protocol.mode(TEXT_MODE_VALUES[modeBox.getSelectedIndex()])));
        JButton applyColors = gated(new JButton("Apply colours"));
        applyColors.addActionListener(e -> {
            int fm = fgMode.getSelectedIndex();
            int bm = bgMode.getSelectedIndex();
            boolean fOn = fgOn.isSelected();
            boolean bOn = bgOn.isSelected();
            Color fc = textColor;
            Color bc = bgColor;
            withMask("Colours", m -> {
                m.command(fm == 0 ? Protocol.textColor(fc, fOn) : Protocol.gradient(fm - 1, fOn));
                Thread.sleep(80);
                m.command(bm == 0 ? Protocol.textBackground(bc, bOn) : Protocol.gradient(bm + 3, bOn));
            });
        });

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(row(new JLabel("Text"), input, new JLabel("Font"), fontBox, bold));
        form.add(row(new JLabel("Mode"), modeBox, fg, sendText, applyMode));
        form.add(row(new JLabel("Text colour mode"), fgMode, fgOn));
        form.add(row(new JLabel("Background"), bgMode, bg, bgOn, applyColors));
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(form, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(textPreview);
        sp.setPreferredSize(new Dimension(600, 130));
        p.add(sp, BorderLayout.CENTER);
        refresh.run();
        return wrap(p, "Text is rendered at 16 px high and uploaded as a column bitmap.");
    }

    // ---------------------------------------------------------------- DIY

    private JComponent buildDiyTab() {
        diyPreview = new LedView(7);
        diyPreview.setPixels(new int[Protocol.DIY_WIDTH][Protocol.DIY_HEIGHT]);
        JSpinner slot = new JSpinner(new SpinnerNumberModel(1, 1, 20, 1));
        JButton open = new JButton("Open image...");
        open.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
            fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp"));
            if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            File f = fc.getSelectedFile();
            prefs.put("lastDir", f.getParent());
            try {
                BufferedImage src = ImageIO.read(f);
                if (src == null) {
                    throw new IllegalArgumentException("unsupported image");
                }
                diyImage = Protocol.fitDiy(src);
                int[][] px = new int[Protocol.DIY_WIDTH][Protocol.DIY_HEIGHT];
                for (int x = 0; x < Protocol.DIY_WIDTH; x++) {
                    for (int y = 0; y < Protocol.DIY_HEIGHT; y++) {
                        px[x][y] = diyImage.getRGB(x, y) & 0xFFFFFF;
                    }
                }
                diyPreview.setPixels(px);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Could not open image: " + ex.getMessage());
            }
        });
        JButton upload = gated(new JButton("Upload to slot"));
        upload.addActionListener(e -> {
            if (diyImage == null) {
                log("Open an image first.");
                return;
            }
            byte[] payload = Protocol.diyPayload(diyImage);
            int s = (Integer) slot.getValue();
            progress.setValue(0);
            progress.setVisible(true);
            withMask("DIY upload", m -> {
                m.uploadDiy(payload, s, pct -> ui(() -> progress.setValue(pct)));
                m.request(Protocol.slotList("PLAY", new int[] {s}), "PLAYOK");
                log("Image uploaded to slot " + s + ".");
            });
        });
        JButton play = gated(new JButton("Show slot"));
        play.addActionListener(e -> {
            int s = (Integer) slot.getValue();
            withMask("Play", m -> m.request(Protocol.slotList("PLAY", new int[] {s}), "PLAYOK"));
        });
        JButton delete = gated(new JButton("Delete slot"));
        delete.addActionListener(e -> {
            int s = (Integer) slot.getValue();
            withMask("Delete", m -> m.request(Protocol.slotList("DELE", new int[] {s}), "DELEOK"));
        });
        JButton count = gated(new JButton("Count stored"));
        count.addActionListener(e -> withMask("Count", m -> {
            byte[] r = m.request(Protocol.diyCount(), "CHEC");
            log("Mask holds " + (r[5] & 0xFF) + " DIY image(s).");
        }));
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(row(open, new JLabel("Slot"), slot, upload, play, delete, count), BorderLayout.NORTH);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.CENTER));
        holder.add(diyPreview);
        p.add(holder, BorderLayout.CENTER);
        return wrap(p, "Photos are cropped to 46 x 58 LEDs.");
    }

    // ---------------------------------------------------------------- rhythm

    private JComponent buildRhythmTab() {
        rhythmPreview = new LedView(10);
        rhythmPreview.setPixels(new int[24][9]);
        JComboBox<String> mode = new JComboBox<>(new String[] {"Mode 1", "Mode 2", "Mode 3", "Mode 4", "Mode 5"});
        mode.addActionListener(e -> rhythmMode = mode.getSelectedIndex());
        JButton toggle = gated(new JButton("Start microphone"));
        toggle.addActionListener(e -> {
            if (audio.isRunning()) {
                audio.stop();
                toggle.setText("Start microphone");
                send(Protocol.stopRhythm());
                return;
            }
            try {
                audio.start(this::onLevels, this::log);
                toggle.setText("Stop microphone");
            } catch (Exception ex) {
                log("Microphone unavailable: " + ex.getMessage());
            }
        });
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(row(new JLabel("Pattern"), mode, toggle), BorderLayout.NORTH);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.CENTER));
        holder.add(rhythmPreview);
        p.add(holder, BorderLayout.CENTER);
        return wrap(p, "Streams microphone levels to the mask, like the app's music rhythm.");
    }

    private void onLevels(int[] levels) {
        MaskConnection m = mask;
        if (m != null) {
            byte[] f = Protocol.rhythm(rhythmMode, levels);
            ble.submit(() -> {
                try {
                    m.rhythm(f);
                } catch (Throwable t) {
                    log("Rhythm write failed: " + t.getMessage());
                }
            });
        }
        int[][] px = new int[24][9];
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < levels[x]; y++) {
                px[x][8 - y] = Color.HSBtoRGB(0.33f - y * 0.04f, 1f, 1f) & 0xFFFFFF;
            }
        }
        ui(() -> rhythmPreview.setPixels(px));
    }

    // ---------------------------------------------------------------- advanced

    private JComponent buildAdvancedTab() {
        JTextField opcode = new JTextField("LIGHT", 10);
        JTextField args = new JTextField("80", 20);
        JButton sendRaw = gated(new JButton("Send command"));
        sendRaw.addActionListener(e -> {
            try {
                String[] parts = args.getText().trim().isEmpty() ? new String[0] : args.getText().trim().split("[,\\s]+");
                int[] a = new int[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    a[i] = parts[i].startsWith("0x") ? Integer.parseInt(parts[i].substring(2), 16) : Integer.parseInt(parts[i]);
                }
                send(Protocol.frame(opcode.getText().trim(), a));
            } catch (Exception ex) {
                log("Bad command: " + ex.getMessage());
            }
        });
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(row(new JLabel("Opcode"), opcode, new JLabel("Args (decimal or 0x..)"), args, sendRaw));
        p.add(row(new JLabel("<html>Sends <code>[len][opcode][args][random]</code>, AES-encrypted, to the command "
                + "characteristic. Replies from the mask appear in the log.<br>See PROTOCOL.md for the opcode list.</html>")));
        return wrap(p, "Raw command console.");
    }

    // ---------------------------------------------------------------- helpers

    private static JPanel row(JComponent... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        for (JComponent c : cs) {
            p.add(c);
        }
        return p;
    }

    private static JComponent wrap(JComponent content, String hint) {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JLabel h = new JLabel(hint);
        h.setForeground(Color.GRAY);
        p.add(h, BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        return new JScrollPane(p);
    }

    private static void ui(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private void log(String msg) {
        String line = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + msg;
        System.out.println(line);
        ui(() -> {
            if (logArea != null) {
                logArea.append(line + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }
}

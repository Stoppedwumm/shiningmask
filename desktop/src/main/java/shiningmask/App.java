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
    private final LinkClient link = new LinkClient(this::log);
    private final Strobe strobe = new Strobe(() -> this.mask, link, this::log);

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
    private DrawCanvas drawCanvas;
    private JTabbedPane tabs;
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
                strobe.stop();
                link.stop();
                MaskConnection m = mask;
                if (m != null) {
                    m.disconnect();
                }
            }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(buildTopBar(), BorderLayout.NORTH);

        tabs = new JTabbedPane();
        tabs.addTab("Images", buildImagesTab());
        tabs.addTab("Animations", buildAnimationsTab());
        tabs.addTab("Text", buildTextTab());
        tabs.addTab("Draw", buildDrawTab());
        tabs.addTab("DIY photo", buildDiyTab());
        tabs.addTab("Strobe", buildStrobeTab());
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
        frame.setSize(920, 860);
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
        if (!c && strobeToggle != null && strobeToggle.isSelected()) {
            strobeToggle.setSelected(false);
            strobe.stop();
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
            uploadImage(diyImage, (Integer) slot.getValue());
        });
        JButton edit = new JButton("Edit in Draw");
        edit.addActionListener(e -> {
            if (diyImage == null) {
                log("Open an image first.");
                return;
            }
            drawCanvas.setImage(diyImage);
            tabs.setSelectedIndex(tabs.indexOfTab("Draw"));
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
        p.add(row(open, edit, new JLabel("Slot"), slot, upload, play, delete, count), BorderLayout.NORTH);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.CENTER));
        holder.add(diyPreview);
        p.add(holder, BorderLayout.CENTER);
        return wrap(p, "Photos are cropped to 46 x 58 LEDs.");
    }

    /** Upload a 46x58 image to a DIY slot and show it. */
    private void uploadImage(BufferedImage img, int slot) {
        byte[] payload = Protocol.diyPayload(img);
        progress.setValue(0);
        progress.setVisible(true);
        withMask("Image upload", m -> {
            m.uploadDiy(payload, slot, pct -> ui(() -> progress.setValue(pct)));
            m.request(Protocol.slotList("PLAY", new int[] {slot}), "PLAYOK");
            log("Image uploaded to slot " + slot + ".");
        });
    }

    // ---------------------------------------------------------------- draw

    private static final Color[] PALETTE = {
        Color.WHITE, new Color(255, 0, 0), new Color(255, 128, 0), new Color(255, 230, 0),
        new Color(0, 255, 0), new Color(0, 255, 200), new Color(0, 128, 255), new Color(0, 0, 255),
        new Color(160, 0, 255), new Color(255, 0, 200), new Color(255, 120, 160), new Color(128, 64, 0),
        new Color(128, 128, 128), new Color(40, 40, 40)
    };

    private JComponent buildDrawTab() {
        drawCanvas = new DrawCanvas();
        File autosave = new File(System.getProperty("user.home"), ".shiningmask-drawing.png");
        try {
            if (autosave.isFile()) {
                BufferedImage img = ImageIO.read(autosave);
                if (img != null) {
                    drawCanvas.setImage(Protocol.fitDiy(img));
                }
            }
        } catch (Exception ignored) {
            // start blank
        }

        JButton colorButton = new JButton("  ");
        colorButton.setOpaque(true);
        colorButton.setBorderPainted(false);
        colorButton.setBackground(drawCanvas.getColor());
        colorButton.setToolTipText("Current colour (click to choose)");
        java.util.function.Consumer<Color> setColor = c -> {
            drawCanvas.setColor(c);
            colorButton.setBackground(c);
        };
        colorButton.addActionListener(e -> {
            Color c = JColorChooser.showDialog(frame, "Pen colour", drawCanvas.getColor());
            if (c != null) {
                setColor.accept(c);
            }
        });
        drawCanvas.setOnPick(setColor);

        JPanel palette = new JPanel(new GridLayout(1, PALETTE.length, 2, 2));
        for (Color c : PALETTE) {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(22, 22));
            b.setOpaque(true);
            b.setBorderPainted(false);
            b.setBackground(c);
            b.addActionListener(e -> setColor.accept(c));
            palette.add(b);
        }

        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        String[][] toolDefs = {
            {"PEN", "Pen"}, {"ERASER", "Eraser"}, {"FILL", "Fill"}, {"LINE", "Line"}, {"RECT", "Rectangle"},
            {"PICKER", "Pick colour"}
        };
        for (String[] t : toolDefs) {
            javax.swing.JToggleButton b = new javax.swing.JToggleButton(t[1], t[0].equals("PEN"));
            b.addActionListener(e -> drawCanvas.setTool(DrawCanvas.Tool.valueOf(t[0])));
            group.add(b);
            tools.add(b);
        }

        JSpinner brush = new JSpinner(new SpinnerNumberModel(1, 1, 6, 1));
        brush.addChangeListener(e -> drawCanvas.setBrush((Integer) brush.getValue()));
        JCheckBox mirror = new JCheckBox("Mirror");
        mirror.setToolTipText("Paint both halves of the face at once");
        mirror.addActionListener(e -> drawCanvas.setMirror(mirror.isSelected()));
        JCheckBox dots = new JCheckBox("LED dots", true);
        dots.addActionListener(e -> drawCanvas.setGrid(dots.isSelected()));
        JSlider zoom = new JSlider(4, 14, 7);
        zoom.setPreferredSize(new Dimension(100, 20));
        zoom.addChangeListener(e -> drawCanvas.setCell(zoom.getValue()));

        JButton undo = new JButton("Undo");
        undo.addActionListener(e -> drawCanvas.undo());
        JButton redo = new JButton("Redo");
        redo.addActionListener(e -> drawCanvas.redo());
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> drawCanvas.clear());
        JButton fillAll = new JButton("Fill all");
        fillAll.addActionListener(e -> drawCanvas.fillAll());
        JButton flip = new JButton("Flip");
        flip.addActionListener(e -> drawCanvas.flipHorizontal());
        JButton left = new JButton("\u2190");
        left.addActionListener(e -> drawCanvas.shift(-1, 0));
        JButton right = new JButton("\u2192");
        right.addActionListener(e -> drawCanvas.shift(1, 0));
        JButton up = new JButton("\u2191");
        up.addActionListener(e -> drawCanvas.shift(0, -1));
        JButton down = new JButton("\u2193");
        down.addActionListener(e -> drawCanvas.shift(0, 1));

        JButton open = new JButton("Import image...");
        open.addActionListener(e -> {
            File f = chooseFile(false);
            if (f == null) {
                return;
            }
            try {
                BufferedImage src = ImageIO.read(f);
                if (src == null) {
                    throw new IllegalArgumentException("unsupported image");
                }
                drawCanvas.setImage(Protocol.fitDiy(src));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Could not open image: " + ex.getMessage());
            }
        });
        JButton save = new JButton("Save PNG...");
        save.addActionListener(e -> {
            File f = chooseFile(true);
            if (f == null) {
                return;
            }
            if (!f.getName().toLowerCase().endsWith(".png")) {
                f = new File(f.getParentFile(), f.getName() + ".png");
            }
            try {
                ImageIO.write(drawCanvas.getImage(), "png", f);
                log("Saved " + f);
            } catch (Exception ex) {
                log("Save failed: " + ex.getMessage());
            }
        });

        JSpinner slot = new JSpinner(new SpinnerNumberModel(prefs.getInt("drawSlot", 20), 1, 20, 1));
        JButton send = gated(new JButton("Send to mask"));
        send.setToolTipText("Uploads the drawing to the chosen DIY slot and shows it");
        send.addActionListener(e -> {
            int s = (Integer) slot.getValue();
            prefs.putInt("drawSlot", s);
            uploadImage(drawCanvas.getImage(), s);
        });

        drawCanvas.setOnChange(() -> {
            try {
                ImageIO.write(drawCanvas.getImage(), "png", autosave);
            } catch (Exception ignored) {
                // autosave is best effort
            }
        });

        // Keyboard shortcuts: Ctrl/Cmd+Z undo, Ctrl/Cmd+Shift+Z redo.
        int menu = java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        javax.swing.InputMap im = drawCanvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, menu), "undo");
        im.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z,
                menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK), "redo");
        drawCanvas.getActionMap().put("undo", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { drawCanvas.undo(); }
        });
        drawCanvas.getActionMap().put("redo", new javax.swing.AbstractAction() {
            public void actionPerformed(java.awt.event.ActionEvent e) { drawCanvas.redo(); }
        });

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(row(tools, new JLabel("Size"), brush, mirror));
        controls.add(row(colorButton, palette));
        controls.add(row(undo, redo, clear, fillAll, flip, left, right, up, down, new JLabel("Zoom"), zoom, dots));
        controls.add(row(open, save, new JLabel("Slot"), slot, send));

        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(controls, BorderLayout.NORTH);
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.CENTER));
        holder.add(drawCanvas);
        p.add(holder, BorderLayout.CENTER);
        return wrap(p, "Draw 46 x 58 pixels. Left click paints, right click erases. Your drawing is autosaved.");
    }

    private File chooseFile(boolean save) {
        JFileChooser fc = new JFileChooser(prefs.get("lastDir", System.getProperty("user.home")));
        fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp"));
        int r = save ? fc.showSaveDialog(frame) : fc.showOpenDialog(frame);
        if (r != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        prefs.put("lastDir", fc.getSelectedFile().getParent());
        return fc.getSelectedFile();
    }

    // ---------------------------------------------------------------- strobe / Ableton Link

    private static final String[] DIVISIONS = {
        "1/32 note", "1/16 note", "1/8 note", "1/4 note (1 beat)", "1/2 note", "1 bar", "2 bars"
    };
    private static final double[] DIVISION_BEATS = {0.125, 0.25, 0.5, 1, 2, 4, 8};

    private javax.swing.JToggleButton strobeToggle;

    private JComponent buildStrobeTab() {
        strobeToggle = gated(new javax.swing.JToggleButton("Strobe OFF"));
        strobeToggle.setFont(strobeToggle.getFont().deriveFont(Font.BOLD, 16f));
        strobeToggle.setPreferredSize(new Dimension(180, 44));
        strobeToggle.addActionListener(e -> {
            if (strobeToggle.isSelected()) {
                strobe.start(prefs.getInt("brightness", 50));
            } else {
                strobe.stop();
            }
            strobeToggle.setText(strobeToggle.isSelected() ? "Strobe ON" : "Strobe OFF");
        });
        JButton hold = gated(new JButton("Hold to strobe"));
        hold.setPreferredSize(new Dimension(160, 44));
        hold.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (hold.isEnabled() && !strobe.isRunning()) {
                    strobe.start(prefs.getInt("brightness", 50));
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (!strobeToggle.isSelected()) {
                    strobe.stop();
                }
            }
        });

        javax.swing.JRadioButton free = new javax.swing.JRadioButton("Free rate", !prefs.getBoolean("strobeLink", false));
        javax.swing.JRadioButton synced = new javax.swing.JRadioButton("Sync to Ableton Link", prefs.getBoolean("strobeLink", false));
        javax.swing.ButtonGroup g = new javax.swing.ButtonGroup();
        g.add(free);
        g.add(synced);
        strobe.linkSync = synced.isSelected();
        java.awt.event.ActionListener syncChanged = e -> {
            strobe.linkSync = synced.isSelected();
            prefs.putBoolean("strobeLink", synced.isSelected());
        };
        free.addActionListener(syncChanged);
        synced.addActionListener(syncChanged);

        JSlider hz = new JSlider(1, 25, prefs.getInt("strobeHz", 8));
        JLabel hzLabel = new JLabel();
        hz.addChangeListener(e -> {
            strobe.hz = hz.getValue();
            hzLabel.setText(hz.getValue() + " Hz");
            prefs.putInt("strobeHz", hz.getValue());
        });
        strobe.hz = hz.getValue();
        hzLabel.setText(hz.getValue() + " Hz");

        JComboBox<String> division = new JComboBox<>(DIVISIONS);
        division.setSelectedIndex(prefs.getInt("strobeDiv", 2));
        strobe.beatsPerCycle = DIVISION_BEATS[division.getSelectedIndex()];
        division.addActionListener(e -> {
            strobe.beatsPerCycle = DIVISION_BEATS[division.getSelectedIndex()];
            prefs.putInt("strobeDiv", division.getSelectedIndex());
        });

        JPanel tuning = new JPanel(new GridLayout(0, 1, 0, 2));
        tuning.add(labeledSlider("Flash length (duty %)", "strobeDuty", 5, 95, 50, v -> strobe.duty = v / 100.0));
        tuning.add(labeledSlider("On brightness", "strobeOn", 1, 100, 100, v -> strobe.onLevel = v));
        tuning.add(labeledSlider("Off brightness (try 0 for blackout)", "strobeOff", 0, 100, 1, v -> strobe.offLevel = v));
        tuning.add(labeledSlider("Latency compensation (ms)", "strobeLatency", 0, 250, 40, v -> strobe.latencyMs = v));

        // Link panel
        JCheckBox linkOn = new JCheckBox("Enable Ableton Link", prefs.getBoolean("linkOn", false));
        JCheckBox onlyPlaying = new JCheckBox("Only while Live is playing (needs Start/Stop Sync in Live)",
                prefs.getBoolean("strobePlaying", false));
        strobe.onlyWhilePlaying = onlyPlaying.isSelected();
        onlyPlaying.addActionListener(e -> {
            strobe.onlyWhilePlaying = onlyPlaying.isSelected();
            prefs.putBoolean("strobePlaying", onlyPlaying.isSelected());
        });
        JLabel linkStatus = new JLabel("Link off");
        linkStatus.setFont(linkStatus.getFont().deriveFont(Font.BOLD));
        BeatLights beats = new BeatLights();
        Runnable applyLink = () -> {
            prefs.putBoolean("linkOn", linkOn.isSelected());
            if (linkOn.isSelected()) {
                ble.submit(() -> {
                    try {
                        link.start();
                    } catch (Exception ex) {
                        log("Link failed to start: " + ex.getMessage());
                        ui(() -> linkOn.setSelected(false));
                    }
                });
            } else {
                link.stop();
            }
        };
        linkOn.addActionListener(e -> applyLink.run());
        if (linkOn.isSelected()) {
            applyLink.run();
        }

        JLabel writeLabel = new JLabel(" ");
        writeLabel.setForeground(Color.GRAY);
        JLabel lamp = new JLabel("\u25CF");
        lamp.setFont(lamp.getFont().deriveFont(28f));
        new javax.swing.Timer(30, e -> {
            if (!link.isRunning()) {
                linkStatus.setText("Link off");
                beats.set(-1, false);
            } else {
                LinkClient.Timeline tl = link.timeline();
                int peers = link.numPeers();
                if (tl == null) {
                    linkStatus.setText("Link on - no peers. Turn on Link in Live (Preferences > Link/Tempo/MIDI).");
                    beats.set(-1, false);
                } else {
                    boolean ok = link.isSynced();
                    linkStatus.setText(String.format("%d peer%s  \u00B7  %.2f BPM  \u00B7  %s%s", peers, peers == 1 ? "" : "s",
                            tl.tempo(), ok ? "in sync" : "syncing clock...", link.isPlaying() ? "  \u00B7  playing" : ""));
                    double phase = link.phaseAt(LinkClient.hostMicros(), 4);
                    beats.set(Double.isNaN(phase) ? -1 : (int) phase, link.isPlaying());
                }
            }
            double w = strobe.writeMs();
            writeLabel.setText(strobe.isRunning() && w > 0
                    ? String.format("Bluetooth write takes ~%.0f ms, so about %.0f flashes/s at most", w, 1000 / (2 * w))
                    : " ");
            Boolean lit = strobe.lit();
            lamp.setForeground(lit == null ? Color.GRAY : lit ? new Color(255, 230, 80) : new Color(60, 60, 60));
        }).start();

        JPanel linkPanel = new JPanel();
        linkPanel.setLayout(new BoxLayout(linkPanel, BoxLayout.Y_AXIS));
        linkPanel.setBorder(BorderFactory.createTitledBorder("Ableton Link"));
        linkPanel.add(row(linkOn, onlyPlaying));
        linkPanel.add(row(beats, linkStatus));

        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(row(strobeToggle, hold, lamp, writeLabel));
        p.add(row(free, hz, hzLabel));
        p.add(row(synced, new JLabel("Flash every"), division));
        p.add(capHeight(tuning));
        p.add(capHeight(linkPanel));
        return wrap(p, "Flashes the brightness. Synced mode follows the beat of Ableton Live or any Link app on your network.");
    }

    private JComponent labeledSlider(String title, String key, int min, int max, int def,
                                     java.util.function.IntConsumer onChange) {
        JSlider s = new JSlider(min, max, prefs.getInt(key, def));
        JLabel value = new JLabel(String.valueOf(s.getValue()));
        value.setPreferredSize(new Dimension(36, 16));
        onChange.accept(s.getValue());
        s.addChangeListener(e -> {
            value.setText(String.valueOf(s.getValue()));
            prefs.putInt(key, s.getValue());
            onChange.accept(s.getValue());
        });
        JPanel p = new JPanel(new BorderLayout(6, 0));
        JLabel l = new JLabel(title);
        l.setPreferredSize(new Dimension(260, 16));
        p.add(l, BorderLayout.WEST);
        p.add(s, BorderLayout.CENTER);
        p.add(value, BorderLayout.EAST);
        return p;
    }

    /** Four squares showing the current beat of the bar. */
    private static final class BeatLights extends JComponent {
        private int beat = -1;
        private boolean playing;

        BeatLights() {
            setPreferredSize(new Dimension(4 * 22, 18));
        }

        void set(int beat, boolean playing) {
            if (beat != this.beat || playing != this.playing) {
                this.beat = beat;
                this.playing = playing;
                repaint();
            }
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            for (int i = 0; i < 4; i++) {
                Color on = i == 0 ? new Color(255, 120, 40) : new Color(80, 200, 120);
                g.setColor(i == beat ? on : new Color(70, 70, 78));
                g.fillRect(i * 22, 0, 18, 18);
            }
        }
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
        return capHeight(p);
    }

    /** Stop BoxLayout from stretching a panel vertically. */
    private static <T extends JComponent> T capHeight(T c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
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

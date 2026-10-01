package shiningmask;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Pixel editor for a 46 x 58 mask image. Left button paints, right button erases. */
public final class DrawCanvas extends JPanel {
    public enum Tool { PEN, ERASER, FILL, LINE, RECT, PICKER }

    private static final int W = Protocol.DIY_WIDTH;
    private static final int H = Protocol.DIY_HEIGHT;
    private static final int UNDO_LIMIT = 100;

    private BufferedImage image = blank();
    private final Deque<BufferedImage> undo = new ArrayDeque<>();
    private final Deque<BufferedImage> redo = new ArrayDeque<>();
    private Tool tool = Tool.PEN;
    private Color color = Color.RED;
    private int brush = 1;
    private boolean mirror;
    private boolean grid = true;
    private int cell = 7;
    private Point last;
    private Point anchor;
    private Point hover;
    private Consumer<Color> onPick = c -> { };
    private Runnable onChange = () -> { };

    public DrawCanvas() {
        setBackground(new Color(18, 18, 22));
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                Point p = toPixel(e);
                if (p == null) {
                    return;
                }
                boolean erase = SwingUtilities.isRightMouseButton(e) || tool == Tool.ERASER;
                Color c = erase ? Color.BLACK : color;
                switch (tool) {
                    case PICKER -> {
                        color = new Color(image.getRGB(p.x, p.y));
                        onPick.accept(color);
                    }
                    case FILL -> {
                        snapshot();
                        fill(p.x, p.y, c.getRGB());
                        if (mirror) {
                            fill(W - 1 - p.x, p.y, c.getRGB());
                        }
                        changed();
                    }
                    case LINE, RECT -> {
                        snapshot();
                        anchor = p;
                    }
                    default -> {
                        snapshot();
                        stamp(p.x, p.y, c);
                        last = p;
                        changed();
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = toPixel(e);
                hover = p;
                if (p == null) {
                    repaint();
                    return;
                }
                boolean erase = SwingUtilities.isRightMouseButton(e) || tool == Tool.ERASER;
                if ((tool == Tool.PEN || tool == Tool.ERASER) && last != null) {
                    line(last, p, erase ? Color.BLACK : color);
                    last = p;
                    changed();
                } else {
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                Point p = toPixel(e);
                if (anchor != null && p != null) {
                    Color c = SwingUtilities.isRightMouseButton(e) ? Color.BLACK : color;
                    if (tool == Tool.LINE) {
                        line(anchor, p, c);
                    } else if (tool == Tool.RECT) {
                        rect(anchor, p, c);
                    }
                    changed();
                }
                anchor = null;
                last = null;
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hover = toPixel(e);
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = null;
                repaint();
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    private static BufferedImage blank() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, H);
        g.dispose();
        return img;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = c.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return c;
    }

    // ------------------------------------------------------------- public API

    public BufferedImage getImage() {
        return copy(image);
    }

    public void setImage(BufferedImage img46x58) {
        snapshot();
        image = copy(img46x58);
        changed();
    }

    public void clear() {
        snapshot();
        image = blank();
        changed();
    }

    public void fillAll() {
        snapshot();
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, W, H);
        g.dispose();
        changed();
    }

    public void flipHorizontal() {
        snapshot();
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                out.setRGB(W - 1 - x, y, image.getRGB(x, y));
            }
        }
        image = out;
        changed();
    }

    /** Shift the drawing by dx, dy pixels (wrapping around). */
    public void shift(int dx, int dy) {
        snapshot();
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                out.setRGB(Math.floorMod(x + dx, W), Math.floorMod(y + dy, H), image.getRGB(x, y));
            }
        }
        image = out;
        changed();
    }

    public void undo() {
        if (!undo.isEmpty()) {
            redo.push(image);
            image = undo.pop();
            changed();
        }
    }

    public void redo() {
        if (!redo.isEmpty()) {
            undo.push(image);
            image = redo.pop();
            changed();
        }
    }

    public void setTool(Tool t) { tool = t; }
    public void setColor(Color c) { color = c; }
    public Color getColor() { return color; }
    public void setBrush(int b) { brush = Math.max(1, b); }
    public void setMirror(boolean m) { mirror = m; }
    public void setGrid(boolean g) { grid = g; repaint(); }
    public void setOnPick(Consumer<Color> c) { onPick = c; }
    public void setOnChange(Runnable r) { onChange = r; }

    public void setCell(int c) {
        cell = Math.max(3, c);
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------- drawing primitives

    private void snapshot() {
        undo.push(copy(image));
        if (undo.size() > UNDO_LIMIT) {
            undo.removeLast();
        }
        redo.clear();
    }

    private void changed() {
        repaint();
        onChange.run();
    }

    private void set(int x, int y, int rgb) {
        if (x >= 0 && y >= 0 && x < W && y < H) {
            image.setRGB(x, y, rgb);
        }
    }

    private void stamp(int cx, int cy, Color c) {
        int rgb = c.getRGB();
        int r0 = -(brush - 1) / 2;
        for (int dx = 0; dx < brush; dx++) {
            for (int dy = 0; dy < brush; dy++) {
                set(cx + r0 + dx, cy + r0 + dy, rgb);
                if (mirror) {
                    set(W - 1 - (cx + r0 + dx), cy + r0 + dy, rgb);
                }
            }
        }
    }

    /** Bresenham line made of brush stamps. */
    private void line(Point a, Point b, Color c) {
        int x0 = a.x;
        int y0 = a.y;
        int dx = Math.abs(b.x - x0);
        int dy = -Math.abs(b.y - y0);
        int sx = x0 < b.x ? 1 : -1;
        int sy = y0 < b.y ? 1 : -1;
        int err = dx + dy;
        while (true) {
            stamp(x0, y0, c);
            if (x0 == b.x && y0 == b.y) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    private void rect(Point a, Point b, Color c) {
        Point tl = new Point(Math.min(a.x, b.x), Math.min(a.y, b.y));
        Point br = new Point(Math.max(a.x, b.x), Math.max(a.y, b.y));
        line(tl, new Point(br.x, tl.y), c);
        line(new Point(br.x, tl.y), br, c);
        line(br, new Point(tl.x, br.y), c);
        line(new Point(tl.x, br.y), tl, c);
    }

    private void fill(int sx, int sy, int rgb) {
        int target = image.getRGB(sx, sy);
        if (target == rgb) {
            return;
        }
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {sx, sy});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int x = p[0];
            int y = p[1];
            if (x < 0 || y < 0 || x >= W || y >= H || image.getRGB(x, y) != target) {
                continue;
            }
            image.setRGB(x, y, rgb);
            stack.push(new int[] {x + 1, y});
            stack.push(new int[] {x - 1, y});
            stack.push(new int[] {x, y + 1});
            stack.push(new int[] {x, y - 1});
        }
    }

    // ------------------------------------------------------------- rendering

    private int originX() {
        return Math.max(4, (getWidth() - W * cell) / 2);
    }

    private int originY() {
        return Math.max(4, (getHeight() - H * cell) / 2);
    }

    private Point toPixel(MouseEvent e) {
        int x = Math.floorDiv(e.getX() - originX(), cell);
        int y = Math.floorDiv(e.getY() - originY(), cell);
        return x >= 0 && y >= 0 && x < W && y < H ? new Point(x, y) : null;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(W * cell + 8, H * cell + 8);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        int ox = originX();
        int oy = originY();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color off = new Color(36, 36, 44);
        for (int x = 0; x < W; x++) {
            for (int y = 0; y < H; y++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                g.setColor(rgb == 0 ? off : new Color(rgb));
                if (grid) {
                    g.fillOval(ox + x * cell + 1, oy + y * cell + 1, cell - 2, cell - 2);
                } else {
                    g.fillRect(ox + x * cell, oy + y * cell, cell, cell);
                }
            }
        }
        if (mirror) {
            g.setColor(new Color(255, 255, 255, 40));
            g.drawLine(ox + W * cell / 2, oy, ox + W * cell / 2, oy + H * cell);
        }
        Point h = hover;
        if (h != null) {
            g.setColor(new Color(255, 255, 255, 140));
            g.setStroke(new BasicStroke(1f));
            if (anchor != null && (tool == Tool.LINE || tool == Tool.RECT)) {
                int ax = ox + anchor.x * cell + cell / 2;
                int ay = oy + anchor.y * cell + cell / 2;
                int hx = ox + h.x * cell + cell / 2;
                int hy = oy + h.y * cell + cell / 2;
                if (tool == Tool.LINE) {
                    g.drawLine(ax, ay, hx, hy);
                } else {
                    g.drawRect(Math.min(ax, hx), Math.min(ay, hy), Math.abs(hx - ax), Math.abs(hy - ay));
                }
            }
            int r0 = -(brush - 1) / 2;
            g.drawRect(ox + (h.x + r0) * cell, oy + (h.y + r0) * cell, brush * cell - 1, brush * cell - 1);
        }
    }
}

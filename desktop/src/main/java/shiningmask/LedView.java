package shiningmask;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;

/** Draws a grid of LED dots. The model is a width x height array of RGB ints (0 = off). */
public final class LedView extends JPanel {
    private int[][] pixels = new int[1][1];
    private final int dot;

    public LedView(int dot) {
        this.dot = dot;
        setBackground(new Color(18, 18, 22));
    }

    public void setPixels(int[][] columnsByRows) {
        this.pixels = columnsByRows;
        revalidate();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(pixels.length * dot + 8, (pixels.length == 0 ? 1 : pixels[0].length) * dot + 8);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color off = new Color(40, 40, 48);
        for (int x = 0; x < pixels.length; x++) {
            for (int y = 0; y < pixels[x].length; y++) {
                int rgb = pixels[x][y];
                g.setColor(rgb == 0 ? off : new Color(rgb));
                g.fillOval(4 + x * dot, 4 + y * dot, dot - 1, dot - 1);
            }
        }
    }
}

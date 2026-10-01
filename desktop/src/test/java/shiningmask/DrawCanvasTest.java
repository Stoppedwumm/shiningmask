package shiningmask;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import org.junit.jupiter.api.Test;

class DrawCanvasTest {
    @Test
    void fillShiftFlipUndo() {
        DrawCanvas c = new DrawCanvas();
        c.setColor(Color.RED);
        c.fillAll();
        assertEquals(0xFF0000, c.getImage().getRGB(10, 10) & 0xFFFFFF);
        c.clear();
        assertEquals(0, c.getImage().getRGB(10, 10) & 0xFFFFFF);
        c.undo();
        assertEquals(0xFF0000, c.getImage().getRGB(10, 10) & 0xFFFFFF);
        c.redo();
        assertEquals(0, c.getImage().getRGB(10, 10) & 0xFFFFFF);

        java.awt.image.BufferedImage img = c.getImage();
        img.setRGB(0, 0, 0x00FF00);
        c.setImage(img);
        c.shift(1, 2);
        assertEquals(0x00FF00, c.getImage().getRGB(1, 2) & 0xFFFFFF);
        c.flipHorizontal();
        assertEquals(0x00FF00, c.getImage().getRGB(Protocol.DIY_WIDTH - 2, 2) & 0xFFFFFF);
        assertEquals(8004, Protocol.diyPayload(c.getImage()).length);
    }
}

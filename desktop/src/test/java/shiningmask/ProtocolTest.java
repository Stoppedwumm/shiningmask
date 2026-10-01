package shiningmask;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    @Test
    void encryptMatchesPythonReference() {
        // Same vector as the Python client: LIGHT 50 with zero padding.
        byte[] f = Protocol.frame("LIGHT", false, 50);
        assertEquals("064c4947485432000000000000000000", Protocol.hex(f));
        assertEquals("9dd90e66e39a3cc320be9e45d7bca9f8", Protocol.hex(Protocol.encrypt(f)));
        assertArrayEquals(f, Protocol.decrypt(Protocol.encrypt(f)));
    }

    @Test
    void framesMatchApp() {
        assertEquals("05494d414707", Protocol.hex(Protocol.frame("IMAG", false, 7)).substring(0, 12));
        // App skips animation id 4.
        assertEquals(3, Protocol.animationByPosition(3)[5]);
        assertEquals(5, Protocol.animationByPosition(4)[5]);
        assertEquals("09444154531f44000101000000000000", Protocol.hex(Protocol.dataStartDiy(8004, 1)));
        assertEquals("06504c4159010700000000000000000000".substring(0, 32),
                Protocol.hex(Protocol.slotList("PLAY", new int[] {7})));
        assertEquals("0944415443505f000000000000000000".substring(0, 20),
                Protocol.hex(Protocol.dataCommit(0x5f000000)).substring(0, 20));
    }

    @Test
    void chunking() {
        byte[] payload = new byte[40];
        for (int i = 0; i < 40; i++) {
            payload[i] = (byte) i;
        }
        List<byte[]> c = Protocol.chunk(payload, 18);
        assertEquals(3, c.size());
        assertEquals("1300000102030405060708090a0b0c0d0e0f1011", Protocol.hex(c.get(0)));
        assertEquals("0502242526270000000000000000000000000000", Protocol.hex(c.get(2)));
    }

    @Test
    void textBitmapLayout() {
        BufferedImage img = new BufferedImage(2, 16, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xFFFFFF);
        img.setRGB(1, 15, 0xFFFFFF);
        assertEquals("80000001", Protocol.hex(Protocol.textBitmap(img)));
        byte[] payload = Protocol.textPayload(Protocol.textBitmap(img), Color.RED);
        assertEquals("80000001ff0000ff0000", Protocol.hex(payload));
        assertTrue(Protocol.renderText("HI", new Font(Font.SANS_SERIF, Font.BOLD, 16)).getWidth() > 4);
    }

    @Test
    void diyPayloadIsColumnMajor() {
        BufferedImage img = new BufferedImage(46, 58, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 1, 0x123456);
        img.setRGB(1, 0, 0xABCDEF);
        byte[] p = Protocol.diyPayload(img);
        assertEquals(8004, p.length);
        assertEquals("123456", Protocol.hex(java.util.Arrays.copyOfRange(p, 3, 6)));
        assertEquals("abcdef", Protocol.hex(java.util.Arrays.copyOfRange(p, 58 * 3, 58 * 3 + 3)));
    }

    @Test
    void rhythmPacking() {
        int[] levels = new int[24];
        levels[0] = 9;
        levels[1] = 3;
        levels[23] = 12;
        byte[] f = Protocol.rhythm(2, levels);
        assertEquals(15, f[0]);
        assertEquals(2, f[1]);
        assertEquals((byte) 0x93, f[2]);
        assertEquals((byte) 0x09, f[13]);
    }

    @Test
    void maskDetection() {
        assertTrue(Protocol.isMask(Map.of(0x5254, new byte[] {0, 'J', 1, 2})));
        assertEquals("ffff0000-0000-1000-8000-00805f9b34fb".length(), MaskConnection.normalize("FFF0").length());
        assertTrue(MaskConnection.sameUuid("FFF0", Protocol.SERVICE));
    }
}

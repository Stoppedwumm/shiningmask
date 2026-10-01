package shiningmask;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Wire format of the Shining Mask, see PROTOCOL.md. Pure functions, no BLE. */
public final class Protocol {
    public static final String SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb";
    public static final String CMD_CHAR = "d44bc439-abfd-45a2-b575-925416129600";
    public static final String NOTIFY_CHAR = "d44bc439-abfd-45a2-b575-925416129601";
    public static final String DATA_CHAR = "d44bc439-abfd-45a2-b575-92541612960a";
    public static final String RHYTHM_CHAR = "d44bc439-abfd-45a2-b575-92541612960b";

    /** Manufacturer data prefix "TR\0J"; "TR" is the little-endian company id 0x5254. */
    public static final int COMPANY_ID = 0x5254;
    public static final byte[] MANUFACTURER_PREFIX = {'T', 'R', 0, 'J'};

    public static final int DIY_WIDTH = 46;
    public static final int DIY_HEIGHT = 58;
    public static final int TEXT_HEIGHT = 16;
    public static final int CHUNK = 18;

    /** MODE values; the app shows them as static, scroll left, scroll right, blink. */
    public static final int MODE_STATIC = 1;
    public static final int MODE_BLINK = 2;
    public static final int MODE_SCROLL_LEFT = 3;
    public static final int MODE_SCROLL_RIGHT = 4;

    private static final byte[] KEY = hex("32672f7974ad43451d9c6c894a0e8764");
    private static final SecureRandom RANDOM = new SecureRandom();

    private Protocol() {}

    public static byte[] encrypt(byte[] block) {
        return aes(Cipher.ENCRYPT_MODE, block);
    }

    public static byte[] decrypt(byte[] block) {
        return aes(Cipher.DECRYPT_MODE, block);
    }

    private static byte[] aes(int mode, byte[] block) {
        if (block.length != 16) {
            throw new IllegalArgumentException("AES block must be 16 bytes, got " + block.length);
        }
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(mode, new SecretKeySpec(KEY, "AES"));
            return c.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 16-byte plaintext: [len][opcode][args][padding]. */
    public static byte[] frame(String opcode, boolean randomPad, int... args) {
        byte[] op = opcode.getBytes(StandardCharsets.US_ASCII);
        int len = op.length + args.length;
        if (len > 15) {
            throw new IllegalArgumentException("command too long: " + opcode);
        }
        byte[] out = new byte[16];
        if (randomPad) {
            RANDOM.nextBytes(out);
        } else {
            java.util.Arrays.fill(out, (byte) 0);
        }
        out[0] = (byte) len;
        System.arraycopy(op, 0, out, 1, op.length);
        for (int i = 0; i < args.length; i++) {
            out[1 + op.length + i] = (byte) args[i];
        }
        return out;
    }

    public static byte[] frame(String opcode, int... args) {
        return frame(opcode, true, args);
    }

    public static byte[] light(int v) { return frame("LIGHT", clamp(v, 1, 100)); }
    /** Brightness without the app's lower bound of 1; used for the strobe "off" level. */
    public static byte[] lightRaw(int v) { return frame("LIGHT", clamp(v, 0, 100)); }
    public static byte[] speed(int v) { return frame("SPEED", clamp(v, 1, 100)); }
    public static byte[] image(int n) { return frame("IMAG", n); }
    public static byte[] mode(int m) { return frame("MODE", m); }
    public static byte[] loopAnimations() { return frame("LOOA"); }
    public static byte[] stopRhythm() { return frame("SOUT"); }

    /** Animation by list position as shown in the app; the app skips firmware id 4. */
    public static byte[] animationByPosition(int position) {
        return frame("ANIM", position >= 4 ? position + 1 : position);
    }

    public static byte[] textColor(Color c, boolean enabled) {
        return frame("FC", enabled ? 1 : 0, c.getRed(), c.getGreen(), c.getBlue());
    }

    public static byte[] textBackground(Color c, boolean enabled) {
        return frame("BC", enabled ? 1 : 0, c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Gradient presets: 0..3 for the text, 4..7 for the background. */
    public static byte[] gradient(int preset, boolean enabled) {
        return frame("M", enabled ? 1 : 0, preset);
    }

    public static byte[] dataStartText(int total, int bitmapLen) {
        return frame("DATS", false, total >> 8, total, bitmapLen >> 8, bitmapLen, 0);
    }

    public static byte[] dataStartDiy(int total, int slot) {
        return frame("DATS", false, total >> 8, total, 0, slot, 1);
    }

    public static byte[] dataCommit() {
        return frame("DATCP", false);
    }

    public static byte[] dataCommit(int unixSeconds) {
        return frame("DATCP", false, unixSeconds >>> 24, unixSeconds >>> 16, unixSeconds >>> 8, unixSeconds);
    }

    /** PLAY / DELE with up to 10 slot ids (the app sends a continuation block beyond that). */
    public static byte[] slotList(String opcode, int[] slots) {
        int n = Math.min(slots.length, 10);
        int[] args = new int[n + 1];
        args[0] = slots.length;
        for (int i = 0; i < n; i++) {
            args[i + 1] = slots[i];
        }
        return frame(opcode, false, args);
    }

    public static byte[] diyCount() {
        return frame("CHEC", false);
    }

    /** Split bulk data into [len+1][index][data...] packets (unencrypted). */
    public static List<byte[]> chunk(byte[] payload, int chunk) {
        List<byte[]> out = new ArrayList<>();
        for (int idx = 0, off = 0; off < payload.length; idx++, off += chunk) {
            int n = Math.min(chunk, payload.length - off);
            byte[] p = new byte[chunk + 2];
            p[0] = (byte) (n + 1);
            p[1] = (byte) idx;
            System.arraycopy(payload, off, p, 2, n);
            out.add(p);
        }
        return out;
    }

    /** Render text into a 1-bit, 16-row bitmap image (white on black). */
    public static BufferedImage renderText(String text, Font font) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D pg = probe.createGraphics();
        Font f = fitFont(pg, font);
        FontMetrics fm = pg.getFontMetrics(f);
        pg.dispose();
        int width = Math.max(1, fm.stringWidth(text));
        BufferedImage img = new BufferedImage(width, TEXT_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(Color.WHITE);
        g.setFont(f);
        int baseline = (TEXT_HEIGHT - (fm.getAscent() + fm.getDescent())) / 2 + fm.getAscent();
        g.drawString(text, 0, baseline);
        g.dispose();
        return img;
    }

    /** Shrink the font until ascent + descent fit into 16 rows. */
    private static Font fitFont(Graphics2D g, Font font) {
        float size = 18f;
        Font f = font.deriveFont(size);
        while (size > 6 && g.getFontMetrics(f).getAscent() + g.getFontMetrics(f).getDescent() > TEXT_HEIGHT + 2) {
            size -= 0.5f;
            f = font.deriveFont(size);
        }
        return f;
    }

    /** Column bitmap: 2 bytes per column, row 0 = MSB of the first byte. */
    public static byte[] textBitmap(BufferedImage img) {
        byte[] out = new byte[img.getWidth() * 2];
        for (int x = 0; x < img.getWidth(); x++) {
            int hi = 0;
            int lo = 0;
            for (int y = 0; y < TEXT_HEIGHT; y++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != 0) {
                    if (y < 8) {
                        hi |= 0x80 >> y;
                    } else {
                        lo |= 0x80 >> (y - 8);
                    }
                }
            }
            out[2 * x] = (byte) hi;
            out[2 * x + 1] = (byte) lo;
        }
        return out;
    }

    /** Text payload: bitmap followed by one RGB triple per column. */
    public static byte[] textPayload(byte[] bitmap, Color color) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bitmap);
        for (int i = 0; i < bitmap.length / 2; i++) {
            out.write(color.getRed());
            out.write(color.getGreen());
            out.write(color.getBlue());
        }
        return out.toByteArray();
    }

    /** Scale an image to 46x58 (cover + center crop), like the app's fixed-aspect cropper. */
    public static BufferedImage fitDiy(BufferedImage src) {
        double scale = Math.max((double) DIY_WIDTH / src.getWidth(), (double) DIY_HEIGHT / src.getHeight());
        int w = (int) Math.round(src.getWidth() * scale);
        int h = (int) Math.round(src.getHeight() * scale);
        BufferedImage out = new BufferedImage(DIY_WIDTH, DIY_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, (DIY_WIDTH - w) / 2, (DIY_HEIGHT - h) / 2, w, h, null);
        g.dispose();
        return out;
    }

    /** DIY payload: column-major RGB, 46*58*3 = 8004 bytes. */
    public static byte[] diyPayload(BufferedImage img46x58) {
        byte[] out = new byte[DIY_WIDTH * DIY_HEIGHT * 3];
        int i = 0;
        for (int x = 0; x < DIY_WIDTH; x++) {
            for (int y = 0; y < DIY_HEIGHT; y++) {
                int rgb = img46x58.getRGB(x, y);
                out[i++] = (byte) (rgb >> 16);
                out[i++] = (byte) (rgb >> 8);
                out[i++] = (byte) rgb;
            }
        }
        return out;
    }

    /** Rhythm frame: 0F mode + 24 levels (0..9) packed two per byte into 12 bytes. */
    public static byte[] rhythm(int mode, int[] levels24) {
        int[] args = new int[13];
        args[0] = mode;
        for (int i = 0; i < 12; i++) {
            int a = clamp(levels24[2 * i], 0, 9);
            int b = clamp(levels24[2 * i + 1], 0, 9);
            args[i + 1] = (a << 4) | b;
        }
        byte[] f = frame("", false, args);
        f[0] = 15;
        return f;
    }

    /** True if a decrypted reply carries the given ASCII tag right after the length byte. */
    public static boolean replyIs(byte[] reply, String tag) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        if (reply.length < 1 + t.length) {
            return false;
        }
        for (int i = 0; i < t.length; i++) {
            if (reply[1 + i] != t[i]) {
                return false;
            }
        }
        return true;
    }

    public static String replyText(byte[] reply) {
        int len = Math.min(reply[0] & 0xFF, reply.length - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= len; i++) {
            int c = reply[i] & 0xFF;
            sb.append(c >= 0x20 && c < 0x7F ? String.valueOf((char) c) : String.format("<%02x>", c));
        }
        return sb.toString();
    }

    public static boolean isMask(java.util.Map<Integer, byte[]> manufacturerData) {
        if (manufacturerData == null) {
            return false;
        }
        byte[] d = manufacturerData.get(COMPANY_ID);
        return d != null && d.length >= 2 && d[0] == 0 && d[1] == 'J';
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    public static byte[] hex(String s) {
        s = s.replaceAll("\\s", "");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

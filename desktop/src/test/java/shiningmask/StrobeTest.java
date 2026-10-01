package shiningmask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StrobeTest {
    private final LinkClient link = new LinkClient(s -> { });

    @Test
    void freeRateDuty() {
        Strobe s = new Strobe(() -> null, link, m -> { });
        s.hz = 10;          // 100 ms period
        s.duty = 0.25;      // on for the first 25 ms
        s.latencyMs = 0;
        assertTrue(s.desired(1_000_000));
        assertTrue(s.desired(1_024_000));
        assertFalse(s.desired(1_026_000));
        assertFalse(s.desired(1_099_000));
        s.latencyMs = 80;   // look 80 ms ahead
        assertTrue(s.desired(1_020_000 + 80_000 - 80_000));
        assertFalse(s.desired(1_050_000 - 80_000 + 80_000));
    }

    @Test
    void linkWithoutPeersIsIdle() {
        Strobe s = new Strobe(() -> null, link, m -> { });
        s.linkSync = true;
        assertNull(s.desired(LinkClient.hostMicros()));
    }

    @Test
    void lightRawAllowsZero() {
        assertEquals(0, Protocol.lightRaw(0)[6]);
        assertEquals(1, Protocol.light(0)[6]);
        assertEquals(100, Protocol.lightRaw(250)[6]);
    }

    @Test
    void linkTimelineMath() {
        LinkClient.Timeline tl = new LinkClient.Timeline(500_000, 0, 1_000_000); // 120 BPM
        assertEquals(120.0, tl.tempo(), 1e-9);
        assertEquals(2.0, tl.beatsFromOrigin(2_000_000), 1e-9);
        assertEquals(3.5, LinkClient.floorMod(-0.5, 4), 1e-9);
    }
}

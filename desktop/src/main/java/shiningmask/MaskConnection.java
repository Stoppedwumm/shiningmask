package shiningmask;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.simplejavable.BluetoothUUID;
import org.simplejavable.Characteristic;
import org.simplejavable.Peripheral;
import org.simplejavable.Service;

/** One connected mask. Methods block; call them off the UI thread. */
public final class MaskConnection {
    private static final BluetoothUUID SERVICE = new BluetoothUUID(Protocol.SERVICE);
    private static final long REPLY_TIMEOUT_MS = 5000;

    private final Peripheral peripheral;
    private final Consumer<String> log;
    private final BlockingQueue<byte[]> replies = new LinkedBlockingQueue<>();
    private final Object writeLock = new Object();
    private boolean cmdWithResponse = true;
    private boolean dataWithResponse = true;
    private boolean rhythmWithResponse = false;

    public MaskConnection(Peripheral peripheral, Consumer<String> log) {
        this.peripheral = peripheral;
        this.log = log;
    }

    public Peripheral peripheral() {
        return peripheral;
    }

    public void connect(Runnable onDisconnected) {
        peripheral.setEventListener(new Peripheral.EventListener() {
            @Override
            public void onDisconnected() {
                onDisconnected.run();
            }
        });
        peripheral.connect();
        inspectServices();
        peripheral.notify(SERVICE, new BluetoothUUID(Protocol.NOTIFY_CHAR), data -> {
            if (data != null && data.length >= 16) {
                byte[] plain = Protocol.decrypt(java.util.Arrays.copyOf(data, 16));
                log.accept("<- " + Protocol.replyText(plain));
                replies.offer(plain);
            }
        });
    }

    /**
     * Pick write types from the characteristic properties. The official Android app uses the
     * default (with-response) write type everywhere, so prefer that when it is supported.
     */
    private void inspectServices() {
        boolean found = false;
        List<Service> services = peripheral.services();
        for (Service s : services) {
            if (!sameUuid(s.uuid(), Protocol.SERVICE)) {
                continue;
            }
            found = true;
            for (Characteristic c : s.characteristics()) {
                String u = normalize(c.uuid());
                boolean withResponse = c.canWriteRequest() || !c.canWriteCommand();
                log.accept("   char " + u + " req=" + c.canWriteRequest() + " cmd=" + c.canWriteCommand()
                        + " notify=" + c.canNotify());
                if (u.equals(Protocol.CMD_CHAR)) {
                    cmdWithResponse = withResponse;
                } else if (u.equals(Protocol.DATA_CHAR)) {
                    dataWithResponse = withResponse;
                } else if (u.equals(Protocol.RHYTHM_CHAR)) {
                    rhythmWithResponse = !c.canWriteCommand();
                }
            }
        }
        if (!found) {
            log.accept("warning: service " + Protocol.SERVICE + " not listed; trying anyway");
        }
    }

    /** Expand 16/32-bit short forms (CoreBluetooth reports "FFF0") to the full 128-bit form. */
    static String normalize(String uuid) {
        String u = uuid.trim().toLowerCase();
        if (u.length() == 4) {
            u = "0000" + u;
        }
        if (u.length() == 8) {
            u = u + "-0000-1000-8000-00805f9b34fb";
        }
        return u;
    }

    static boolean sameUuid(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    public void disconnect() {
        try {
            peripheral.disconnect();
        } catch (Exception e) {
            log.accept("disconnect: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        try {
            return peripheral.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void write(String charUuid, byte[] value, boolean withResponse) {
        synchronized (writeLock) {
            BluetoothUUID c = new BluetoothUUID(charUuid);
            if (withResponse) {
                peripheral.writeRequest(SERVICE, c, value);
            } else {
                peripheral.writeCommand(SERVICE, c, value);
            }
        }
    }

    /** Encrypt and send a 16-byte plaintext command. */
    public void command(byte[] plaintext) {
        log.accept("-> " + Protocol.replyText(plaintext) + "  [" + Protocol.hex(plaintext) + "]");
        write(Protocol.CMD_CHAR, Protocol.encrypt(plaintext), cmdWithResponse);
    }

    /** Like {@link #command} but without logging, for high-rate effects like the strobe. */
    public void commandQuiet(byte[] plaintext) {
        write(Protocol.CMD_CHAR, Protocol.encrypt(plaintext), cmdWithResponse);
    }

    public void rhythm(byte[] plaintext) {
        write(Protocol.RHYTHM_CHAR, Protocol.encrypt(plaintext), rhythmWithResponse);
    }

    /** Send a command and wait for a reply that starts with the given tag. */
    public byte[] request(byte[] plaintext, String expectedTag) throws InterruptedException {
        replies.clear();
        command(plaintext);
        return expect(expectedTag);
    }

    private byte[] expect(String tag) throws InterruptedException {
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            byte[] r = left > 0 ? replies.poll(left, TimeUnit.MILLISECONDS) : null;
            if (r == null) {
                throw new IllegalStateException("Timed out waiting for " + tag);
            }
            if (Protocol.replyIs(r, tag)) {
                return r;
            }
            if (Protocol.replyIs(r, "ERROR")) {
                throw new IllegalStateException("Mask replied ERROR while waiting for " + tag);
            }
        }
    }

    /** DATS -> chunks (each acked with REOK) -> DATCP, as in TextAgreement / DiyAgreement. */
    public void upload(byte[] announce, byte[] payload, byte[] commit, Consumer<Integer> progress)
            throws InterruptedException {
        request(announce, "DATSOK");
        List<byte[]> packets = Protocol.chunk(payload, Protocol.CHUNK);
        for (int i = 0; i < packets.size(); i++) {
            replies.clear();
            write(Protocol.DATA_CHAR, packets.get(i), dataWithResponse);
            expect("REOK");
            progress.accept((i + 1) * 100 / packets.size());
        }
        request(commit, "DATCPOK");
    }

    public void uploadText(byte[] bitmap, java.awt.Color color, int mode, int speed, Consumer<Integer> progress)
            throws InterruptedException {
        byte[] payload = Protocol.textPayload(bitmap, color);
        upload(Protocol.dataStartText(payload.length, bitmap.length), payload, Protocol.dataCommit(), progress);
        command(Protocol.mode(mode));
        Thread.sleep(150);
        command(Protocol.speed(speed));
    }

    public void uploadDiy(byte[] payload, int slot, Consumer<Integer> progress) throws InterruptedException {
        int now = (int) (System.currentTimeMillis() / 1000);
        upload(Protocol.dataStartDiy(payload.length, slot), payload, Protocol.dataCommit(now), progress);
    }
}

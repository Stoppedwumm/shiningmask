# shiningmask

Desktop control for the **Shining Mask** LED face mask, made by reverse-engineering the
official Android app (`cn.com.heaton.shiningmask` 1.2.6). The protocol is in
[PROTOCOL.md](PROTOCOL.md).

## Desktop app (single jar)

Download [`dist/shiningmask.jar`](dist/shiningmask.jar) and run:

```sh
java -jar shiningmask.jar
```

You need Java 17 or newer. On macOS, `brew install openjdk` or any JDK from adoptium.net works.
The jar works on macOS 13+ (Apple Silicon and Intel), Windows x64 and Linux (BlueZ).

The first time you run it, macOS asks to allow Bluetooth for the app that launched Java
(Terminal, iTerm, …). Allow it in **System Settings → Privacy & Security → Bluetooth**.

Features:

- scan, connect, and remember the last mask
- brightness and speed
- the 70 built-in images and 44 built-in animations, plus "loop all"
- text: any installed font, live LED preview, static/scroll/blink, colours and gradients
- drawing: paint your own 46×58 face with pen, eraser, fill, line, rectangle, colour picker,
  mirror mode for symmetrical faces, undo/redo, PNG import/export and autosave, then send it
  to a DIY slot
- DIY photos: open any image, crop to 46×58, upload to slots 1–20, show, delete
- strobe: free-running (1–25 Hz) or locked to **Ableton Link** (1/32 note … 2 bars), with
  flash length, on/off brightness, latency compensation, a hold-to-strobe button and an option
  to strobe only while Live is playing
- music rhythm from the microphone
- raw command console and a log of every command and reply

### Ableton Link

The app has a small, listen-only Link implementation in pure Java (`LinkClient.java`). It joins
the Link multicast group (224.76.78.75:20808), follows the session's tempo, beat phase and
start/stop state, and syncs its clock with a peer using Link's ping/pong measurement. Because it
never announces itself it can't change the tempo, and it doesn't show up in Live's peer count.

In Live, turn on Link under *Preferences → Link/Tempo/MIDI*. To use "only while Live is playing",
also turn on *Start Stop Sync* there. The Mac and Live can be the same machine.

The strobe works by switching the brightness. Each change is a Bluetooth write the mask has to
acknowledge, which caps the rate; the Strobe tab shows the measured write time. Use
*Latency compensation* to move the flashes earlier until they land on the beat.

### Build from source

```sh
cd desktop
mvn package          # -> desktop/target/shiningmask.jar
```

## Innioasis Y1 remote

[`dist/MaskRemote.apk`](dist/MaskRemote.apk) turns an Innioasis Y1 into a handheld remote with
click-wheel controls. It works with Live, Link sync and everything else in the desktop app.

The Y1 can't talk to the mask itself. It runs Android 4.2.2 with MediaTek's own Bluetooth
stack, which has no Bluetooth LE API (no `BluetoothGatt`, no `android.hardware.bluetooth_le`).
So the computer keeps the Bluetooth connection to the mask, and the Y1 controls the computer
over Wi-Fi.

**Setup**

1. On the computer, open the desktop app's **Remote** tab and tick *Allow remote control over
   the network*, then connect to the mask as usual.
2. Install the APK on the Y1. Either drag `MaskRemote.apk` onto the
   [Innioasis Updater](https://innioasis.app) window, or run `adb install -r MaskRemote.apk`.
   Both need USB debugging on the Y1.
3. The stock Y1 software has no app drawer. Open **Settings → Launcher**, choose
   **Mask Remote** and tap *Just once*. "Exit to music player" in the remote's menu takes you
   back.
4. Put the Y1 on the same Wi-Fi as the computer; a phone hotspot is fine. The remote finds the
   computer by itself. If it doesn't, enter the computer's address under *Settings → Enter
   address* (the Remote tab on the computer shows it).
5. The first time, the computer asks whether to allow the Y1. Click *Yes*.

**Controls**

| Y1 control | Does |
|---|---|
| Wheel | move through the menu; in edit mode, change the value (spinning fast takes bigger steps) |
| Centre | open / select; on a value, start or finish editing |
| Centre held on *Hold to strobe* | strobe while held |
| Menu / Back | go back |
| Play/Pause | strobe on/off |
| Prev / Next | previous / next image (or animation / DIY slot, whichever you used last) |

The menu has: strobe and hold-to-strobe, strobe settings (free or Ableton Link timing, rate,
division, flash length, on/off brightness, Link on/off), brightness, speed, the 70 images, the 44
animations, the 20 DIY slots, recent texts (sent with the Text tab's font and colours), mask
connect/scan, and settings.

The remote speaks plain HTTP on port 47320 and is found by a UDP broadcast on port 47321. Each
remote has to be approved once on the computer, and approvals can be revoked on the Remote tab.

Build it with `cd y1-remote && ./gradlew assembleRelease` (needs the Android SDK).

## Python client

`shiningmask.py` is a small command-line client built on bleak:

```sh
pip install bleak cryptography pillow
python3 shiningmask.py scan
python3 shiningmask.py --addr <address> light 80
```

## Licences

The desktop jar bundles [SimpleJavaBLE](https://github.com/simpleble/simpleble), which is
licensed under BUSL-1.1. That licence is free for personal and non-commercial use; its text
is included in the jar at `META-INF/LICENSE.md`.

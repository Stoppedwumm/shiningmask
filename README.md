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

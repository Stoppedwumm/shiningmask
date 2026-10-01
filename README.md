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
- DIY photos: open any image, crop to 46×58, upload to slots 1–20, show, delete
- music rhythm from the microphone
- raw command console and a log of every command and reply

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

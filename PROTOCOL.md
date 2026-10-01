# Shining Mask BLE protocol

Reverse-engineered from the official Android app **Shining Mask**
(`cn.com.heaton.shiningmask`, v1.2.6 / versionCode 126, XAPK with
`config.arm64_v8a` + `config.xxxhdpi` splits).

| File | SHA-256 |
|---|---|
| `cn.com.heaton.shiningmask.apk` | `eb738d4672e27bcbc87a4f1162358821c61681d5b682c296e2a77b9422ac93f1` |
| `config.arm64_v8a.apk` (contains `libAES.so`) | `58fc02b5a47a6c17f0a9f2184c9110d33872d9432812a5829da7dc3eb123e131` |

Relevant app classes:

- `cn.com.heaton.shiningmask.model.data.Agreement`: command builders
- `…model.data.TextAgreement`, `DiyAgreement`, `DiyMutiAgreement`: bulk upload state machines
- `…model.data.Text1664Bold`: text → 16-row bitmap
- `com.cdbwsoft.library.ble.BleManager` / `BleDevice`: UUIDs and write paths
- `csh.tiro.cc.aes` → `libAES.so`: encryption (JNI)
- `…base.app.BleConfig`: advertisement filter

## Discovery

The app accepts a device only if its advertisement contains a manufacturer-specific
AD structure (type `0xFF`) whose data starts with:

```
54 52 00 4A        "TR\0J"
```

Masks typically advertise a name like `MASK-xxxxxx`, but the app does not filter on the name.

## GATT

| Role | UUID | Encrypted? |
|---|---|---|
| Service | `0000fff0-0000-1000-8000-00805f9b34fb` | |
| Command write | `d44bc439-abfd-45a2-b575-925416129600` | yes |
| Notify (responses) | `d44bc439-abfd-45a2-b575-925416129601` | yes |
| Bulk data write (text/DIY upload) | `d44bc439-abfd-45a2-b575-92541612960a` | **no** |
| Music-rhythm stream write | `d44bc439-abfd-45a2-b575-92541612960b` | yes |
| OTA service (Panchip) | `0000fd00-…`, data `fd01`, ctrl `fd02` | n/a |

All writes use Android's default write type, i.e. **write with response**. A
write-without-response to the command characteristic is silently dropped (seen on macOS).

`BleDevice.onEncrypt()` is the identity function. Encryption happens only where the app
explicitly calls `Agreement.getEncryptData()`, so bulk data packets go out in plaintext.

## Encryption

`libAES.so` is a plain textbook AES-128 implementation: 44-word key schedule,
10 rounds, standard S-box. `App.onCreate()` calls `aes.keyExpansionDefault()`, which
expands the key hard-coded at `.data:0x13008`:

```
AES-128-ECB key = 32 67 2f 79 74 ad 43 45 1d 9c 6c 89 4a 0e 87 64
```

Every command is exactly one 16-byte block encrypted with AES-ECB (no padding, no IV).
Notifications from the mask are decrypted the same way.

## Command frame (plaintext, 16 bytes)

```
[0]      length of the meaningful part that follows (opcode + args)
[1..n]   ASCII opcode followed by arguments
[n+1..]  padding: random bytes (most commands) or zeros
```

| Command | Plaintext | Notes |
|---|---|---|
| Brightness | `06 'LIGHT' v` | v = 1..100 (app slider; 0 is sent as 1) |
| Speed | `06 'SPEED' v` | v = 1..100 |
| Preset image | `05 'IMAG' n` | n = list position, 70 presets |
| Preset animation | `05 'ANIM' n` | app skips id 4 (positions ≥ 4 are sent as n+1) |
| Loop all animations | `04 'LOOA'` | |
| Text mode | `05 'MODE' m` | 1 = static, 2 = blink, 3 = scroll left, 4 = scroll right |
| Text colour | `06 'FC' en r g b` | en = 1 enables the solid colour |
| Text background colour | `06 'BC' en r g b` | |
| Colour/gradient preset | `03 'M' en n` | n 0..3 = text gradients, 4..7 = background gradients |
| Stop music rhythm | `04 'SOUT'` | |
| Enter DIY | `06 'SMVEW' 01` / `03` | |
| Exit DIY | `06 'SMVEW' 00` (no data) / `02` (save) | |
| DIY image slot query | `04 'CHEC'` | → notify `CHEC` + count at byte 5 |
| DIY image timestamp check | `09 'TIME' slot t3 t2 t1 t0` | big-endian unix seconds → `TIMEOK` / `TIMEERR` |
| Play DIY images | `len 'PLAY' count id…` | len = count+5. Up to 10 ids, then a continuation block `len id…` |
| Delete DIY images | `len 'DELE' count id…` | same layout as PLAY |
| Show DIY slot | `04 'DIY' n` | |
| Gesture "face change" | `len 'FACE' …` | see `Agreement.getGestureSettings` |
| Multi-image animation | `06 'MANY' n 01` → … → `07 'MANCPOK'` | `DiyMutiAgreement` |

## Bulk upload (text and DIY images)

1. **Announce:** command `09 'DATS' L_hi L_lo X_hi X_lo F`
   - text: L = total payload length, X = bitmap length, F = 0
   - DIY image: L = payload length, X = slot (`00 slot`), F = 1
   - Wait for the notify `DATSOK`.
2. **Stream:** split the payload into chunks of 18 bytes (98 if the MTU was raised).
   Each chunk is written **unencrypted** to `…960a` as a 20-byte packet (100 with a raised MTU):
   ```
   [0] chunk_len + 1    [1] chunk_index (0,1,2…)    [2..] data, zero-padded
   ```
   Wait for `REOK` after each packet.
3. **Commit:**
   - text: `05 'DATCP'`
   - DIY image: `09 'DATCP' t3 t2 t1 t0` (big-endian unix seconds, used later by `TIME`)
   - Wait for `DATCPOK`. `ERROR` aborts.
4. For text, the app then sends `MODE` and, 150 ms later, `SPEED`.

### Text payload

```
bitmap  : 2 bytes per column, 16 rows high.
          byte0 = rows 0..7  (row 0 = MSB), byte1 = rows 8..15 (row 8 = MSB)
colours : 3 bytes (R,G,B) per column, appended after the whole bitmap
```

Each character is rendered into a 16×16 cell (built-in glyph table for Latin-1 accents,
otherwise drawn with the bundled `bold16.TTF`), and all columns are concatenated.

### DIY image payload

The crop is fixed at **46 × 58 px**. Pixels are emitted **column-major** (`for x: for y:`),
3 bytes RGB each, for 8004 bytes in total.

## Music rhythm

Written to `…960b`, encrypted, one block per FFT frame:

```
0F mode b0 b1 … b11 00 00
```

mode = 0..4 (pattern). The 12 bytes hold 24 bars of level 0..9, two per byte
(high nibble first). The app computes them from a 128-point FFT with a rolling maximum
(`VisualizerUtil.getWaveFormData`), and sends a frame roughly every 120 ms.

## Firmware

The APK bundles `TR1906R04-1-10_OTA.bin` and `TR1906R04-10_OTA.bin` (Panchip OTA over
service `fd00`), so the mask MCU appears to be a Panchip part.

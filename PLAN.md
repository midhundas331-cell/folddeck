> **Historical design document.** This is the architecture FoldDeck was planned
> around before the phase-0 spike. The spike turned out fast enough to become the
> app itself (see [README.md](README.md) and [docs/NOTES.md](docs/NOTES.md)), so the
> Kotlin/Compose client, UDP transport and X25519 pairing described here were never
> built. Kept for the reasoning.

# FoldDeck — laptop screen + laptop keyboard on a Galaxy Z Fold 7

Turn the Fold 7 into a real laptop: the desktop renders above the crease, a
physical-feeling keyboard renders below it, and the hinge itself is the split
line when the device is half-opened (tabletop posture).

---

## 0. Build vs. reuse — read this first

You are building a low-latency remote desktop client. That stack is hard: capture,
hardware encode, congestion control, packet loss recovery, hardware decode,
input injection. Three honest options:

| Path | Effort | Latency | When to pick |
|---|---|---|---|
| **A. Custom stack** (this document) | 4-8 weeks | 25-50 ms LAN | You want full control of the wire format, the keyboard, and the Fold layout. Best learning value. |
| **B. Sunshine (host) + `moonlight-common-c` (client)** | 1-2 weeks | 15-35 ms | **Recommended shortcut.** Sunshine already does VAAPI/NVENC capture, FEC, adaptive bitrate and gamepad/keyboard injection on Linux. You write *only* the Fold UI + keyboard, and link the battle-tested C streaming core via JNI. |
| **C. VNC / RDP / Deskreen** | days | 150-600 ms | Unusable for typing. Do not. |

This plan describes **A** because it's what was asked for, but every section
marks which parts **B** hands you for free. If the goal is "working laptop mode
this month", fork the Moonlight Android client, rip out its game UI, and drop in
`FoldScaffold.kt` + `LaptopKeyboard.kt` from this repo.

---

## 1. Architecture

```
 LAPTOP (Linux Mint / Cinnamon, X11)              FOLD 7 (Android 16, SD 8 Elite)
 ┌───────────────────────────────────┐            ┌────────────────────────────────────┐
 │ capture.py                        │            │ VideoChannel.kt                    │
 │  X11 XShm / PipeWire ──► VAAPI    │            │  UDP reassembly ──► ring buffer    │
 │  h264_vaapi, zerolatency, no B    │            │             │                      │
 │        │ Annex-B access units     │            │             ▼                      │
 │        ▼                          │  UDP 47990 │ VideoDecoder.kt                    │
 │ protocol.py  packetize + AEAD ────┼───────────►│  MediaCodec (LOW_LATENCY, async)   │
 │                                   │            │             │ → Surface            │
 │ server.py                         │  TCP 47989 │             ▼                      │
 │  mDNS advert, X25519 pairing,  ◄──┼────────────┤ ControlChannel.kt                  │
 │  control msgs, IDR req, modes     │    (TLS)   │  hello / pair / set_mode / input   │
 │        │                          │            │             ▲                      │
 │        ▼                          │            │             │                      │
 │ injector.py                       │            │ LaptopKeyboard.kt  RemoteSurface.kt│
 │  /dev/uinput virtual kbd + abs    │            │  evdev scancodes   abs pointer map │
 │  pointer (host XKB applies)       │            │             ▲                      │
 └───────────────────────────────────┘            │ FoldScaffold.kt                    │
                                                  │  WindowInfoTracker → hinge split   │
                                                  └────────────────────────────────────┘
```

**Two channels, deliberately:**

- **Control — TCP + TLS 1.3 (or Noise_XX).** Ordered, reliable, tiny. Keystrokes
  must never be lost or reordered; a dropped `KEY_UP` leaves a modifier stuck
  down on the host, which is the single most user-visible bug in this class of app.
- **Video — UDP.** Head-of-line blocking on a TCP video stream is what makes Wi-Fi
  mirroring feel like syrup: one lost packet stalls every frame behind it. UDP +
  "skip the broken frame, keep going" is strictly better for a display stream.

Send input on the *control* socket even though it's TCP — a keystroke is 12 bytes,
TCP_NODELAY makes it a single segment, and correctness beats 2 ms.

---

## 2. Transport & connection

### 2.1 Discovery
Host advertises `_folddeck._tcp.local` via `zeroconf` with TXT records
(`v=1`, `host=my-laptop`, `fp=<8 hex of host pubkey>`). Client uses
`NsdManager`. Manual IP entry as fallback — mDNS is unreliable on guest Wi-Fi
and on some Tailscale configurations.

### 2.2 Three transports, same protocol
| Mode | Setup | RTT | Notes |
|---|---|---|---|
| **Wi-Fi (LAN)** | mDNS discovery | 2-6 ms | Default. Requires 5 GHz / Wi-Fi 6; 2.4 GHz is unusable at 12 Mbps. |
| **USB** | `adb reverse tcp:47989 tcp:47989` (+ `47990` — note UDP isn't tunnelled by adb, so USB mode falls back to TCP video, which is fine at 0.3 ms RTT) | 0.3-1 ms | Lowest latency, charges the phone, no radio battery drain. This is how `scrcpy` does it. |
| **USB tethering** | Phone shares USB net; host reachable at `192.168.42.x` | 0.5-1.5 ms | No adb / no developer mode needed; real UDP works. |
| **Remote (WireGuard/Tailscale)** | Connect to the tailnet IP | 15-60 ms | Works, but drop to 30 fps / 6 Mbps and enable FEC. |

Client-side Wi-Fi tuning matters more than people expect:

```kotlin
wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "folddeck").acquire()
connectivityManager.bindProcessToNetwork(wifiNetwork)  // never route video over 5G
```
`WIFI_MODE_FULL_LOW_LATENCY` disables power-save polling on the Wi-Fi chip and
typically cuts p99 jitter from ~40 ms to ~8 ms. It costs battery; release it on pause.

### 2.3 Security
1. **First pair:** X25519 ephemeral+static handshake. Both ends derive a 6-digit
   SAS from `HKDF(handshake_transcript, "folddeck-sas")` and display it. User
   confirms they match → this defeats an active MITM, which raw ECDH alone does not.
2. Host's static public key is pinned in `EncryptedSharedPreferences` (Android
   Keystore-backed). Client key is stored in `~/.config/folddeck/authorized_keys`.
3. **Session keys:** `HKDF` → separate send/recv keys per channel.
   Video packets are sealed with **ChaCha20-Poly1305**, nonce =
   `channel_id(u32) || counter(u64)`. ChaCha is ~3 GB/s on the SD 8 Elite; AES-GCM
   is also fine (ARMv8 crypto extensions). Never reuse a nonce — the counter is
   monotonic and the session dies at rekey time, not wraparound.
4. Host binds only to explicitly allowed interfaces (`lan`, `tailscale0`), never `0.0.0.0` by default.
5. Client activity sets `FLAG_SECURE` so the mirrored desktop never lands in
   Recents thumbnails or a screenshot.
6. `uinput` access needs a udev rule, not root — see `host/99-folddeck.rules`.

---

## 3. Video pipeline

### 3.1 Host encode settings (the ones that actually matter)
```
-g 9999          # no periodic IDR — keyframes are 20x a P-frame and spike latency
-bf 0            # B-frames require reordering = +1 frame of latency. Never.
intra-refresh=1  # loss recovery without an IDR: each frame refreshes a column of MBs
-rc CBR          # VBR bursts blow the Wi-Fi jitter budget — but see below
-profile main    # Baseline has no CABAC; Main is fine and ~10% smaller. Avoid High 4:4:4.
sliced-threads   # encode + emit slices as they finish → sub-frame pipelining
```
Encoder ladder: `h264_vaapi` (Intel/AMD) → `h264_nvenc` (NVIDIA) → `libx264
-preset ultrafast -tune zerolatency` (fallback, ~8 ms/frame at 1080p on a
modern CPU, acceptable).

**Pick it by probing, not by feature detection.** `ffmpeg -encoders` lists what
was compiled in, not what works. Measured on the dev laptop: it advertises both
`h264_vaapi` and `h264_nvenc`, and both fail at runtime — NVENC can't load
`libcuda.so.1`, and VAAPI rejects `CBR` outright because the Intel iHD driver
exposes only the low-power entrypoint, which is **CQP-only**. `capture.py` runs
each candidate for a few frames and takes the first that emits bytes.

CQP-only hardware is not a corner case, and it quietly breaks §3.4: `-b:v` is
ignored, so every adaptive-bitrate decision becomes a no-op. `qp_for()` maps the
target bitrate to a quantiser via bits-per-pixel-per-frame (≈ +6 QP halves the
bitrate), which also makes a small cover-screen pane correctly get a lower QP
than a full inner-display one.

Measured headroom, Intel iGPU via VAAPI: **5.89 ms/frame at 1080p → 170 fps
sustained**, 35% of the 16.7 ms budget at 60 fps.

**Damage tracking is the biggest free win.** A desktop is static ~90% of the
time. Compare the XShm buffer hash per 64×64 tile; if nothing changed, skip the
frame entirely and send a 1-byte `IDLE` marker. Bitrate drops from 12 Mbps to
~0.1 Mbps while reading a PDF, and battery on both ends follows.

### 3.2 Client decode
`MediaCodec` in **async mode**, output straight to the `SurfaceView`'s `Surface`.

- `KEY_LOW_LATENCY = 1` (API 30+) — tells the decoder not to buffer for reordering.
- Vendor fallback on Qualcomm: `"vendor.qti-ext-dec-low-latency.enable" = 1`.
- `KEY_OPERATING_RATE = Short.MAX_VALUE`, `KEY_PRIORITY = 0` → realtime scheduling.
- `releaseOutputBuffer(index, true)` with **no** render timestamp. Passing a
  timestamp asks SurfaceFlinger to schedule the frame at a future vsync — that is
  correct for a media player and wrong for a remote desktop. Render now, always.
- `SurfaceHolder.setFixedSize(streamW, streamH)` so the decoder writes at native
  size and SurfaceFlinger does the scale in the display pipeline (free, in hardware).
- `SurfaceView`, never `TextureView`. TextureView goes through the app's GL
  context = one extra composite pass ≈ 8 ms plus GPU wakeups.

### 3.3 Latency budget (target: sub-50 ms glass-to-glass on Wi-Fi)
| Stage | LAN | USB |
|---|---|---|
| Capture + colour convert | 2-4 ms | 2-4 ms |
| Hardware encode | 3-8 ms | 3-8 ms |
| Network | 2-6 ms | 0.3-1 ms |
| Jitter buffer | 0-8 ms (adaptive, target 0) | 0 ms |
| Hardware decode | 4-10 ms | 4-10 ms |
| Composite + panel (120 Hz = 8.3 ms/vsync) | 8-16 ms | 8-16 ms |
| **Total** | **~25-50 ms** | **~20-40 ms** |

Anything under ~60 ms feels direct for typing. Above ~100 ms, users start
double-typing. Instrument it: stamp `t_capture` in the frame header, log
`t_render - t_capture` client-side, surface a p50/p99 HUD behind a debug toggle.
You cannot tune what you don't measure, and "it feels laggy" is not a bug report.

### 3.4 Adaptive bitrate
Client reports every 500 ms: frames received/decoded/dropped, reassembly
failures, p99 inter-arrival jitter. Host runs a simple AIMD:
loss > 2% or jitter > 25 ms → bitrate ×0.75; 3 clean seconds → +500 kbps,
clamped to `[1.5, 25] Mbps`. Don't over-engineer this before you have data.

---

## 4. Fold 7 layout — the actual hard part

### 4.1 The three geometries
| Posture | Window | Aspect | Layout policy |
|---|---|---|---|
| **Cover screen (folded)** | 2520×1080 | 2.33:1 | 50/50 gives a 2520×540 video strip — useless. **Full-bleed video** + translucent overlay keyboard (~45% height) toggled by a FAB, or a compact trackpad. |
| **Inner, flat (unfolded)** | 2184×1968 | 1.11:1 | The money case. 55/45 split: video gets 2184×1082 (2.02:1) — a 16:9 desktop letterboxes with tiny bars. Keyboard gets 2184×886, which is a genuinely typable ~9 mm key pitch. |
| **Inner, half-opened, HORIZONTAL fold** | 2184×1968 | 1.11:1 | **Laptop mode.** Split on `FoldingFeature.bounds`, not on 50%. Screen above the crease is the monitor, below is the keyboard. Physically identical to a laptop. |

Numbers above are Fold 7 nominals — **never hardcode them.** Read
`WindowMetricsCalculator.computeCurrentWindowMetrics(activity)` every layout pass.
A future Fold, DeX, a freeform window or split-screen will all hand you something else.

### 4.2 Reading the posture
`androidx.window:window` is the only correct source. `Configuration.screenWidthDp`
tells you size but not *posture*, and posture is what distinguishes "unfolded flat"
from "laptop mode":

```kotlin
WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)  // Flow<WindowLayoutInfo>
  → displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
      .state         // FLAT | HALF_OPENED
      .orientation   // HORIZONTAL | VERTICAL
      .isSeparating  // true → treat as two logical panes
      .occlusionType // NONE on Fold 7 inner (continuous panel) | FULL on a true dual-screen
      .bounds        // Rect in window coords — THE split line
```

Two details people get wrong:
- On the Fold 7 inner display `occlusionType == NONE` — the panel is continuous.
  So **do not** insert a physical gap for the hinge; just align the split to
  `bounds.centerY()`. Inserting a black gutter on a continuous display looks broken.
- `isSeparating` is `true` for a HALF_OPENED horizontal fold even with no
  occlusion. That's your "laptop mode" signal.

### 4.3 Surviving the fold without a hiccup
The default behaviour — activity recreation on fold — kills the Surface, the
MediaCodec and the sockets, producing a ~1.5 s black screen. Fix, in order:

1. **Manifest:**
   ```xml
   android:resizeableActivity="true"
   android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation|density|keyboardHidden|uiMode"
   ```
   Now folding is a resize, not a recreation.
2. **Keep one `SurfaceView` instance** across layouts. If the composable that
   hosts it is never removed from the tree (only re-measured), `surfaceDestroyed`
   never fires — only `surfaceChanged` — and the decoder keeps running through
   the fold. This single decision is the difference between a seamless transition
   and a visible reconnect.
3. If the Surface *does* die (activity restart, cover→inner display switch),
   don't reconfigure the codec: `codec.setOutputSurface(newSurface)` (API 23+),
   then `flush()` and request an IDR. ~1 frame of loss instead of ~1 second.
4. **Renegotiate stream resolution on posture change**, debounced 250 ms (the
   half-open sweep otherwise fires dozens of layout passes). Encoding 1080p for a
   540 px-tall cover viewport wastes ~60% of the bitrate and adds encode latency.
   Send `SET_MODE{w,h,fps}`; host restarts the encoder at the new size.

### 4.4 Aspect ratio reconciliation
The desktop is 16:9 (1.78). The video pane is 2.02 (unfolded) or 2.33 (cover).
Three strategies, expose as a setting:

- **Letterbox** (default) — no distortion, thin pillarbox bars. Boring and correct.
- **Fit-width + pan** — fill horizontally, crop vertically, one-finger vertical
  drag to pan. Good for reading code.
- **Reshape the host** (v2, best) — have the host create a virtual output at the
  *exact* viewport pixel dimensions (`xrandr --newmode` on an unused output, or a
  headless `Xvfb`/`wlr-randr` virtual display) and stream that. Pixel-perfect,
  zero wasted bits, no letterbox, and the laptop's own window manager tiles to
  the phone's shape. This is the feature that makes the app feel native rather
  than like a mirror.

---

## 5. Input

### 5.1 Send scancodes, not characters
The virtual keyboard emits **Linux evdev keycodes** (`KEY_A = 30`), and the host
injects them into `/dev/uinput`. The host's own XKB layout, compose key, dead
keys, `Ctrl+Alt+F2` and every application shortcut then work identically to a
real USB keyboard — because to the kernel it *is* one.

The naive alternative (send a Unicode string, host runs `xdotool type`) breaks
modifiers, key-repeat, games, terminal Ctrl-C, and anything holding a key. Don't.

Escape hatch for emoji/CJK: a "paste text" path that sets the host clipboard and
injects `Ctrl+Shift+V`. Unicode is the one thing scancodes can't express.

### 5.2 Keyboard behaviour that separates good from bad
- Fire on **key-down and key-up separately** via `pointerInput`/`awaitPointerEventScope`,
  not `onClick`. Held keys, chords and repeats all depend on this.
- **Modifier latching:** tap = one-shot (applies to next key, then clears),
  double-tap = lock (until tapped again). Show all three states visually.
- **Key repeat:** 400 ms initial delay, then 33 ms — match X11 defaults so it feels native.
- **Haptics:** `HapticFeedbackConstants.KEYBOARD_TAP` on key-down only. The Fold's
  actuator is good; this does most of the work of making a glass keyboard usable.
- **Slide-off cancel:** dragging off a key before release cancels it.
- **Multitouch:** track pointer IDs independently — real typists press the next
  key before releasing the last one. Single-pointer keyboards feel broken above ~40 wpm.

Layout: 60% ANSI + function row + arrows, key widths in `u` units (1u, Tab 1.5u,
Caps 1.75u, Enter 2.25u, Shift 2.25u, Space 6.25u) so any screen width divides cleanly.

### 5.3 Pointer
- **Absolute mode** (default): touch on the video pane maps directly to host
  coordinates via the letterbox-corrected rect. Tap = left click, long-press =
  right click, two-finger drag = scroll, pinch = `Ctrl+scroll`.
- **Trackpad mode:** a strip below the spacebar, relative movement with
  pointer acceleration and two-finger scroll. Better for precise work; ships as a toggle.
- Host renders its own cursor; also stream the cursor bitmap + hotspot over the
  control channel and draw it as a Compose overlay so hover targets are visible
  before you commit to a tap.

---

## 6. Repo layout

```
folddeck/
├── PLAN.md                     ← this file
├── host/
│   ├── server.py               main loop: mDNS, pairing, control, video fanout
│   ├── protocol.py             framing, message types, AEAD, packetizer
│   ├── capture.py              ffmpeg x11grab→VAAPI, Annex-B access-unit splitter
│   ├── injector.py             /dev/uinput virtual keyboard + absolute pointer
│   ├── requirements.txt
│   └── 99-folddeck.rules       udev rule for uinput access without root
└── android/app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        └── java/io/folddeck/
            ├── MainActivity.kt
            ├── ui/FoldScaffold.kt      posture detection + hinge-aware split
            ├── ui/RemoteSurface.kt     SurfaceView + pointer→host mapping
            ├── ui/LaptopKeyboard.kt    the keyboard
            ├── codec/VideoDecoder.kt   MediaCodec low-latency wrapper
            ├── net/ControlChannel.kt   TCP control + handshake
            ├── net/VideoChannel.kt     UDP reassembly
            └── input/EvdevKeys.kt      keycode table
```

---

## 7. Phases

| Phase | Deliverable | Proves |
|---|---|---|
| **0** (2 days) | `adb reverse` + ffmpeg → raw H.264 over TCP → `MediaCodec` → full-screen SurfaceView | The latency budget is real. Do this before anything else. |
| **1** (1 wk) | Hinge-aware split, keyboard renders, keys inject via uinput | The core UX |
| **2** (1 wk) | UDP video, reassembly, loss tolerance, mDNS discovery | Untethered use |
| **3** (4 days) | X25519 pairing + SAS PIN, AEAD, key pinning | Safe on a real network |
| **4** (1 wk) | Posture-driven mode renegotiation, adaptive bitrate, damage tracking | Feels finished |
| **5** | Virtual host display at exact viewport size, clipboard sync, cursor overlay, trackpad mode | The differentiators |

**Do phase 0 first and measure it.** If glass-to-glass is 150 ms on your hardware,
no amount of UI work saves the app, and you should switch to option B in §0.

---

## 8. Known risks

1. **Wi-Fi jitter dominates everything.** 2.4 GHz, a busy AP, or Android Wi-Fi
   power-save will each add 30-100 ms p99 spikes that no code fixes. Test on
   5 GHz/6 GHz with `WIFI_MODE_FULL_LOW_LATENCY`, and ship USB mode as the "it
   must work" path.
2. **Forcing an IDR through an `ffmpeg` subprocess isn't possible** — there's no
   control pipe for it. Mitigations: `intra-refresh` for loss recovery, restart
   the encoder on resolution change (the only time an IDR is truly required). If
   you need on-demand IDR, move to in-process encoding (PyAV with
   `frame.pict_type = 'I'`, or a Rust host over `libva`).
3. **Thermals.** Sustained 1080p60 decode + 120 Hz panel + Wi-Fi will warm the
   Fold and throttle. Cap 60 fps, drop to 30 on the cover screen, and honour
   `PowerManager.isPowerSaveMode`.
4. **One UI display switching.** Folding while the app is foreground can move it
   between physical displays. §4.3 covers it, but this is the #1 source of
   Fold-specific crash reports — test fold/unfold under load, repeatedly.
5. **`uinput` on Wayland.** Mint Cinnamon is X11 so `x11grab` is fine. If the
   session is ever Wayland, capture must move to the PipeWire portal
   (`xdg-desktop-portal` ScreenCast). `uinput` injection keeps working either way —
   that's why it beats `xdotool`.

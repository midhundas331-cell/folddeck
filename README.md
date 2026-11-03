# FoldDeck

Turn a foldable phone into your laptop: the laptop's screen streams onto the top
half of the phone, and a full ThinkPad-style keyboard, TrackPoint and touch pointer
on the bottom half drive the laptop back.

Built for the Galaxy Z Fold 7 and an [Omarchy](https://omarchy.org) (Arch +
Hyprland) laptop, with a refined workspace UI that **follows
the laptop's theme live** — change it with `omarchy theme set` and the phone
repaints within a second, without dropping the stream.

## Features

- **Low-latency desktop stream** — hardware H.264 (VA-API, with software fallback)
  captured with `wf-recorder` on Wayland or `ffmpeg x11grab` on X11, decoded by
  `MediaCodec` straight to a `SurfaceView`.
- **ThinkPad keyboard** — real key-down/key-up via `/dev/uinput`, multitouch
  chords, latching modifiers, auto-repeat, Fn layer for media keys, a working
  TrackPoint.
- **Pointer** — tap/long-press/double-tap-drag/two-finger scroll, direct or
  relative mode, plus an optional dedicated trackpad with tap-to-click
  (two fingers = right click), true held left/right buttons and a
  theme-matched scroll rail. Tap each button's **HOLD** section to latch it;
  tap **HELD** to release. Leaving the deck or losing the connection releases both.
- **Laptop audio** — while connected the laptop's sound plays on the phone and
  its speakers go quiet; disconnecting gives it back. Switchable in Settings.
- **Customisable deck** — per-posture layout presets (keys + pad, keys, pad,
  monitor), desktop position, keyboard height, trackpad size and side, key label
  size and spacing, TrackPoint and F-key row on/off, shortcut bar on/off and side.
- **Live Layout Studio** — tap **Resize** in the custom bar (or App settings →
  Display → Resize Workspace). Select Monitor, Keyboard, Trackpad or Bar, then
  drag or use the precision slider. The remaining controls reflow live; Done
  saves proportions for the current posture, Cancel/Back reverts, and Reset
  restores auto-fit. **Pad height**, **Wheel** and **Buttons** independently
  adjust trackpad height, wheel width and button height. A taller pad takes space
   from the monitor when needed and can exceed the keyboard's height without
   stretching the keys. With a split keyboard, it extends upward between the
   monitor and custom bar; both reflow around its column. Editing never sends
   gestures to the laptop.
- **Phone sharing** — App → Phone Sharing syncs text clipboard content both ways
   while Folddeck is focused (up to 64 KiB). Allow Android microphone/camera
   permissions once; from then on, whenever the Fold is connected, the enabled
   phone microphone and camera are the laptop's default source and camera
   (`folddeck_mic`, `folddeck_camera`) with no action on the phone, and the
   previous defaults come back on disconnect (including when the host is
   stopped). They keep working with Folddeck in the background or locked: the
   stream drops its video (the laptop stops capturing its screen) but keeps the
   connection, under a "Phone sharing" notification that Android requires. The
   phone records only while a laptop app actually reads the input; Android's
   own mic/camera indicators show when. There is no in-app live badge; the
   Settings switches are the only way to turn an input off. An app that keeps
   its input stream open (Hermes voice mode does) keeps the phone mic in use
   for as long as it runs. Apps with saved device choices may need
   **Folddeck-Phone-Microphone** or **Folddeck Phone** selected once. Host and
   phone need 0.13.0 (sharing protocol v2 plus the video-pause message).
- **Local and laptop controls** — the tinted Resize/App rail controls Folddeck;
  System opens the laptop's Omarchy menu. Laptop shortcuts show their chords.
- **Bluetooth mouse** — pair one with the phone and it drives the laptop's own
  cursor, kept inside the desktop image at any fold or rotation.
  Left+right together hands it back to the phone, and takes it back again.
  Adjustable pointer speed.
- **Physical keyboard** — pair a Bluetooth keyboard (or plug in a dongle) and the
  on-screen one steps aside: the desktop goes full width and the shortcuts fill
  the rest. Keys go across as raw scancodes, Super included on Samsung phones;
  a built-in trackpad works like the mouse.
- **Laptop posture** — unfolded in landscape, the full-width 16:9 desktop is sized
  first; shortcuts, keyboard and optional trackpad adapt into the remaining band.
- **Open portrait console** — the uncropped desktop takes the maximum full-width
  area at the top; a half-width trackpad and two-row synchronized shortcut shelf
  sit above a compact keyboard.
- **Cover monitor mode** — the closed Fold opens as a clean full-monitor viewer.
  A floating corner control reveals or hides the keyboard and trackpad with a
  fast spring transition while the desktop moves into the opposite corner.
- **Shortcut deck** — the space the 16:9 desktop leaves over holds Omarchy
  shortcuts (Hyprland-style chords like `SUPER+SHIFT+B`): a swipeable row upright, a
  two-column scrolling deck in landscape. Add, edit, delete, and long-press-drag to
  rearrange.
- **Premium workspace UI** — an adaptive dashboard, softly lit cards, bundled
  Inter and JetBrains Mono typography, tactile keycaps, and the live laptop
  palette. Shared-axis page transitions, staggered entrances, and animated
  switches and a brief blur-to-focus page entrance respect Android's animation
  setting. Key legends fit both the height and width of resized keycaps.
- **Security** — TLS 1.3 with the host certificate pinned on the phone and the
  phone's token pinned on the host; PIN + fingerprint app lock; locking the phone
  stops the laptop capturing.
- **Two routes** — a home-LAN address and a Tailscale address; the app measures
  which one answers.

## Requirements

**Laptop (host):** Linux, Python 3.10+, `python-cryptography`, access to
`/dev/uinput`, and `wf-recorder` (Wayland/wlroots) or `ffmpeg` (X11). Audio
needs PipeWire or PulseAudio with `pactl` and `parec`. See
[`host/requirements.txt`](host/requirements.txt). Theme sync reads Omarchy's
`~/.local/state/omarchy/current/theme/colors.toml`; on other desktops the app keeps
its default palette.

Phone sharing on Wayland uses `wl-copy`/`wl-paste`, `pactl`/`pacat`, `ffmpeg`,
and PipeWire's `pw-dump`, `pw-cli` and `pw-metadata`. Camera demand detection
requires v4l2loopback client-usage events (tested with 0.15.4).
The webcam sends JPEG frames at up to 10 fps, rendered to a 640×480 virtual camera;
the microphone sends 48 kHz mono PCM. For the webcam on Omarchy/Arch:

```bash
omarchy pkg add linux-headers v4l2loopback-dkms  # match headers to your kernel
sudo bash host/install-camera.sh
```

This creates `/dev/video10`, labelled **Folddeck Phone**, and loads it after reboot.
Restart the host when updating it, before installing the APK: a 0.13 phone sends
a video-pause message that an older host treats as a desync and disconnects on.

**Phone:** Android 12+ (API 31). Tuned for the Fold 7 but runs on any phone.

**Building the APK:** a JDK (17+) and the Android SDK (`build-tools` and a
`platforms/android-*`). No Gradle.

## Quick start

```bash
# 1. Let your user open /dev/uinput (skip if `getfacl /dev/uinput` already grants it)
sudo cp host/99-folddeck.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules && sudo udevadm trigger
sudo usermod -aG input "$USER"      # then log out and back in

# 2. Run the host
pip install -r host/requirements.txt
host/stream.py                      # serve securely on 0.0.0.0:5000
python3 host/security.py pair       # open a one-use 2-minute pairing window

# 3. Build and install the app
android/build.sh                    # ~2 s, writes android/build/folddeck.apk
adb install -r android/build/folddeck.apk
```

Open FoldDeck, set a PIN, enter the laptop's address (`host:port`) and connect. To
start the host at login: `host/install-autostart.sh`.

`android/build.sh` generates a debug signing key on first run
(`android/debug.keystore`, git-ignored). **Back it up**: Android will not install an
update signed with a different key, so losing it means uninstalling the app and
losing its PIN and pairing.

## Security model — read before exposing the port

- Pairing is **closed by default**. `host/security.py pair` opens a one-use,
  two-minute trust-on-first-use window; only open it while adding your phone.
- Each host certificate gets its own phone credential. The host accepts only
  explicitly paired credentials, and the phone stops on a certificate change.
  `host/security.py forget` removes old pairings; run `pair` to add one again.
- The app blocks Recents/screenshot capture, backups and cleartext traffic. Keep
  the host port off the public internet: bind/firewall it to LAN or Tailscale.
- The PIN is an app lock, not encryption. Details in
  [`docs/NOTES.md`](docs/NOTES.md#security).

## Layout

```
host/stream.py        capture -> H.264 -> TLS socket; theme sync; input back to uinput
host/security.py      certificate, pairing, token pinning
host/uinput.py        virtual keyboard and pointer via ioctl, no dependencies
host/audio.py         laptop audio -> phone: PipeWire null sink + parec
host/sharing.py       phone clipboard, microphone and virtual camera
host/demand.py        consumer detection and session-scoped input defaults
host/test_*.py        parser, input, TLS and theme-sync tests
android/src/          the app (plain Java, no AndroidX)
android/build.sh      aapt2 + javac + d8 + apksigner
android/test/         pure-JVM checks for layout, input, TLS, motion and parsing
android/device-test/  signed native UI smoke test, screenshots and frame timings
docs/NOTES.md         design notes, measurements, and every gotcha hit so far
PLAN.md               the original (larger, unbuilt) architecture
```

## Licence

MIT — see [LICENSE](LICENSE). The bundled fonts use the SIL Open Font License:
[JetBrains Mono](android/assets/fonts/OFL.txt) and
[Inter](android/assets/fonts/Inter-OFL.txt).
FoldDeck is not affiliated with Omarchy, Samsung or Lenovo.

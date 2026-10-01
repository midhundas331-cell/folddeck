# FoldDeck

Turn a foldable phone into your laptop: the laptop's screen streams onto the top
half of the phone, and a full ThinkPad-style keyboard, TrackPoint and touch pointer
on the bottom half drive the laptop back.

Built for the Galaxy Z Fold 7 and an [Omarchy](https://omarchy.org) (Arch +
Hyprland) laptop, and dressed to match: the app copies Omarchy's look and **follows
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
  trackpad mode.
- **Bluetooth mouse** — pair one with the phone and it drives the laptop's own
  cursor, kept inside the desktop image at any fold or rotation.
  Left+right together hands it back to the phone, and takes it back again.
  Adjustable pointer speed.
- **Physical keyboard** — pair a Bluetooth keyboard (or plug in a dongle) and the
  on-screen one steps aside: the desktop goes full width and the shortcuts fill
  the rest. Keys go across as raw scancodes, Super included on Samsung phones;
  a built-in trackpad works like the mouse.
- **Laptop posture** — unfolded in landscape, the lid and keyboard split exactly on
  the crease with a hinge between them.
- **Shortcut deck** — the space the 16:9 desktop leaves over holds Omarchy
  shortcuts (Hyprland-style chords like `SUPER+SHIFT+B`): a swipeable row upright, a
  two-column scrolling deck in landscape. Add, edit, delete, and long-press-drag to
  rearrange.
- **Omarchy look** — square controls, JetBrains Mono, an accent frame, and the
  palette of whatever theme the laptop is running.
- **Security** — TLS 1.3 with the host certificate pinned on the phone and the
  phone's token pinned on the host; PIN + fingerprint app lock; locking the phone
  stops the laptop capturing.
- **Two routes** — a home-LAN address and a Tailscale address; the app measures
  which one answers.

## Requirements

**Laptop (host):** Linux, Python 3.10+, `python-cryptography`, access to
`/dev/uinput`, and `wf-recorder` (Wayland/wlroots) or `ffmpeg` (X11). See
[`host/requirements.txt`](host/requirements.txt). Theme sync reads Omarchy's
`~/.local/state/omarchy/current/theme/colors.toml`; on other desktops the app keeps
its default palette.

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
host/stream.py --probe              # which encoder works here
host/stream.py                      # serve on 0.0.0.0:5000

# 3. Build and install the app
android/build.sh                    # ~2 s, writes android/build/folddeck-spike.apk
adb install -r android/build/folddeck-spike.apk
```

Open FoldDeck, set a PIN, enter the laptop's address (`host:port`) and connect. To
start the host at login: `host/install-autostart.sh`.

`android/build.sh` generates a debug signing key on first run
(`android/debug.keystore`, git-ignored). **Back it up**: Android will not install an
update signed with a different key, so losing it means uninstalling the app and
losing its PIN and pairing.

## Security model — read before exposing the port

- **The first device to connect is trusted** (trust-on-first-use) and can type and
  click on your laptop. Pair on a network you trust, then keep the port off the
  internet: bind to your LAN or `tailscale0`, and firewall everything else.
- After pairing, the host accepts only that phone's token, and the phone refuses a
  host whose certificate changed. Recovery for both is in Settings and
  `host/security.py forget`.
- The PIN is an app lock, not encryption. Details in
  [`docs/NOTES.md`](docs/NOTES.md#security).

## Layout

```
host/stream.py        capture -> H.264 -> TLS socket; theme sync; input back to uinput
host/security.py      certificate, pairing, token pinning
host/uinput.py        virtual keyboard and pointer via ioctl, no dependencies
host/test_*.py        parser, input, TLS and theme-sync tests
android/src/          the app (plain Java, no AndroidX)
android/build.sh      aapt2 + javac + d8 + apksigner
android/test/         JVM check for the shortcut parser
docs/NOTES.md         design notes, measurements, and every gotcha hit so far
PLAN.md               the original (larger, unbuilt) architecture
```

## Licence

MIT — see [LICENSE](LICENSE). The bundled JetBrains Mono font is under the SIL Open
Font License ([`android/assets/fonts/OFL.txt`](android/assets/fonts/OFL.txt)).
FoldDeck is not affiliated with Omarchy, Samsung or Lenovo.

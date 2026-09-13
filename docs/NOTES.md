# FoldDeck developer notes

How the working app is built, why it is built that way, and every trap already
paid for. Started as the phase-0 latency spike — can a raw H.264 stream over one
TCP socket into `MediaCodec` feel direct enough? — and grew into the app, so some
early sections still speak in spike terms. The original, larger architecture is
in [`../PLAN.md`](../PLAN.md); most of it was never needed.

```
host/stream.py                     x11grab -> H.264 -> TCP; reads key events back
host/uinput.py                     virtual keyboard via /dev/uinput, no dependencies
host/clock.py                      GTK ms clock to film for the latency measurement
host/test_input.py                 end-to-end test of the input channel
host/test_parser.py                access-unit splitter test
host/test_theme.py                 theme SEI splice + extract round trip
android/src/.../H264Stream.java    TCP -> Annex-B parser -> MediaCodec -> Surface
android/src/.../KeyboardView.java  the keyboard: multitouch, latching, repeat
android/src/.../Ev.java            evdev keycodes + 60% ANSI layout
android/src/.../MainActivity.java  screen routing, posture, stream lifecycle
android/src/.../Ui.java            Omarchy palette (live), JetBrains Mono, square shapes, wordmark
android/assets/fonts/              JetBrains Mono subset (OFL), Omarchy's font
android/src/.../Chassis.java       lid, hinge and base for the unfolded laptop
android/src/.../HomeView.java      connect screen: address, state, one button
android/src/.../AddressView.java   address editor, warns on the phone's own IP
android/src/.../SettingsView.java  settings, incl. both pairing recovery paths
android/src/.../GuideView.java     how to use: gestures, keyboard, troubleshooting
android/src/.../Panel.java         shared sheet: title bar + scrolling body
android/src/.../Shortcuts.java     shortcut model: Hyprland-style chords -> evdev codes
android/src/.../ShortcutBar.java   Omarchy shortcut chips in the video letterbox band
android/src/.../ShortcutsView.java edit / add / delete / reset the shortcuts
android/test/ShortcutsCheck.java   JVM check of the chord parser (run line in the file)
android/build.sh                   aapt2 + javac + d8 + apksigner (no Gradle)
```

## Screens

`MainActivity` is the only Activity. Home, Settings, the guide and the address
editor are sibling views in one `FrameLayout`, shown and hidden — not separate
Activities, because tearing the deck down would destroy its `SurfaceView`, drop
the socket and stop the laptop capturing every time you opened Settings.

Back always leads Home; from Home it leaves. That is the escape hatch: before
it existed the address could only be reached by long-pressing the stats HUD,
and a single tap hid that HUD permanently, so a wrong address was unfixable
without clearing app data — which regenerated the client token and got the
device refused as unknown by the host. Both halves of that trap are gone:
Settings can clear the pinned certificate, and the address is always one Back
away.

## Theme: follows Omarchy (2026-09-13)

The app wears the laptop's current Omarchy theme and changes with it, live.

- **Transport.** `stream.py` reads `~/.local/state/omarchy/current/theme/colors.toml`
  and sends every `key = "#rrggbb"` line as an H.264 SEI NAL
  (`user_data_unregistered`, UUID `FOLDDECK-THEME-1`) before the first video
  byte, then re-reads the file once a second and splices a fresh SEI in at the
  next start code when it changes. No new message type, no framing change. The
  phone (`H264Stream.themeFrom`) takes the SEI out before `MediaCodec`; an old
  APK just feeds it to the decoder, which ignores it — checked live on
  2026-09-13, the pre-update app kept streaming against the new host.
- **Applying.** `MainActivity.applyTheme` caches the palette in prefs (so the
  app opens in the last theme offline), calls `Ui.apply`, invalidates the deck
  (keyboard, chassis and pointer chip read `Ui` colours at draw time) and
  rebuilds Home, the lock screen and any open sheet in place. Not `recreate()`:
  that destroys the Surface, drops the socket and re-locks.
- **Look.** Omarchy's shell tokens, copied: corner radius 0, 1px borders at 40%
  foreground, fills at 4 / 8 / 18 / 22 % foreground (normal / hover / selected /
  pressed), accent for the one loud control, JetBrains Mono everywhere. Keys are
  square caps with a 1px edge; a pressed key fills with the accent. The video
  wears Hyprland's active border (2dp accent). The lock screen is Omarchy's:
  a 3dp accent-outlined field that goes red on a wrong PIN. The FOLDDECK
  wordmark is drawn in the block letters of Omarchy's branding art, as
  rectangles on a grid (TextView line metrics leave gaps between block rows).
- Before the first connection the palette is Omarchy's stock Tokyo Night.
- Font: `pyftsubset` of `JetBrainsMonoNerdFont-{Regular,Bold}.ttf` to Latin,
  punctuation, arrows, technical symbols, box/block/shapes (U+0020-26FF ranges
  in the build log) — 2.5 MB each down to ~135 KB. Emoji legends fall back to
  the system font.

## Frame and shortcut bar (2026-09-13)

- **Frame.** The root view draws a 2dp accent border round the whole app — the
  Hyprland active-window border at Omarchy's `border_size = 2` — and pads its
  children in by the same 2dp, so nothing is covered and the lid/base split stays
  symmetric about the crease. Recolours with the theme (`root.invalidate()`).
- **Shortcut deck.** A 16:9 desktop always leaves space over; the deck takes all
  of it. Upright (pane taller than 16:9): desktop flush at the top, deck fills
  the band below (52–72dp; the cap stops the cover screen's huge band becoming
  one giant row), one line of flush buttons, swiped sideways. Landscape (wider):
  desktop flush against the right screen edge — the lid lost its right bezel —
  and the deck fills the whole left side as two columns, scrolled down. Buttons
  are 1px-divided cells inside a 2dp accent border (`LinearLayout` dividers);
  `fillViewport` + weights stretch them to fill when few, minimum 92×52dp when
  many. A deck side lying on the app frame leaves its border off so the two 2dp
  lines never double. First button DIRECT/TRACKPAD (moved off the video, where it
  covered the laptop's tray icons), then the shortcuts, then `+`.
- **Rearranging.** Long-press a shortcut to pick it up. The deck is its own
  `ViewGroup` laying buttons out in slots by an order list; as the drag crosses a
  slot the list changes and the next layout pass animates every moved button from
  its old spot (FLIP, 220ms overshoot plus a 0.9→1 scale pop), so neighbours jump
  aside live. Drop saves the order; letting go outside the deck slides everything
  back. DIRECT stays first and `+` last; long-press either for the editor. The
  deck scrolls itself when a drag is within 48dp of its visible end.
- **Separator without the hinge.** With the laptop layout off nothing divided the
  desktop from the keyboard. `Chassis.Lid` now draws a 2dp accent line along its
  bottom over its children, landing exactly on the deck's bottom border; in the
  landscape column layout the image gives up those 2dp so the line never covers it.
- **Laptop layout off, landscape.** The desktop is always full height and flush
  right — no bars — and the deck takes whatever width is left. Narrower than
  176dp (it is ~69dp with the hinge off) it becomes one column of 11sp,
  two-line labels. A side band under 48dp would not get a column at all.
- **Landscape height match (2026-09-13).** The screen well's accent border is
  drawn *outside* the image, so it sat 2dp above and below the deck's (measured:
  5px at the Fold's density). In the laptop posture the image is now inset 2dp top
  and bottom so both borders share the pane's edges.
- **Shortcuts** are Hyprland-style chords (`SUPER+SHIFT+B`), parsed by reflecting
  over `Ev`'s key constants plus aliases (SUPER, CTRL, ALT, RETURN, PRINT, …).
  A chip sends every key down in order, then up in reverse. Stored in prefs
  `shortcuts` as `label\tchord` lines; defaults are ten stock Omarchy binds read
  from `hyprctl binds`. Long-press the bar, tap `+`, or Settings → Shortcuts to
  edit, add, delete or reset; nothing is kept until Save, and a chord that does
  not parse names the bad key inline and blocks the save.
- Verified on the Fold: deck row in portrait (overflow scrolls), two-column deck with
  the desktop flush right in the laptop posture,
  the Themes chip opened Omarchy's theme picker on the laptop, editor renders,
  Back from the editor returns to the desktop, light and dark themes both read.
  Not exercised on the phone: saving an edited list, and the chord parser's
  rejection messages (covered by `ShortcutsCheck` for the parser only).

## Posture: the laptop

Unfolded and held in landscape, the crease runs horizontally across the middle
of the inner display. `Chassis` makes it the hinge rather than fighting it: the
lid and the base take **equal** weights either side of a centred hinge strip, so
the seam lands *on* the crease. The old 58/42 split put it about a centimetre
above, which is why the keyboard looked short.

Detection is `smallestScreenWidthDp >= 600 && orientation == landscape` — the
Fold's cover screen is around 410dp and the inner display well over 800dp, so
the standard tablet breakpoint separates them cleanly and needs no
androidx.window (which would drag Gradle into a two-second build). Nothing in
`Chassis` applies to the cover screen: no bezels, no hinge, and the 58/42 split
stays, because there is no crease to align to and no height to spend.

## Security

**Transport: TLS 1.2+ (1.3 in practice), always on.** The host generates a
self-signed P-256 certificate on first run into `~/.config/folddeck/`
(`server.key` is 0600). We don't hand-roll a transport — TLS is the thing that
has actually been attacked for thirty years, and both ends ship it.

**The phone pins the certificate.** A self-signed cert means the CA chain is
meaningless, so `Secure.PinningTrustManager` records the SHA-256 of the first
certificate it sees for a given `host:port` and requires an exact match forever
after. A mismatch is **fatal, not a prompt** — the client stops and does not
retry, because a changed host certificate is precisely what interception looks
like, and an "accept anyway" button would discard the whole guarantee at the one
moment it matters. Clear it deliberately if the host is genuinely reinstalled.

**The host pins the phone.** After the handshake the client sends
`[u8 version][32-byte token]`; the first token seen is written to
`~/.config/folddeck/authorized_clients` and every later connection must match.

Trust-on-first-use, both directions. **The first connection is the vulnerable
one** — pair on a network you trust. After that a stranger who can reach the port
gets rejected before any video flows.

```bash
python3 host/security.py          # show cert fingerprint and paired devices
python3 host/security.py forget   # unpair everything; next device claims the slot
./host/stream.py --no-pairing     # refuse unknown devices instead of trusting one
./host/stream.py --no-tls         # debugging only; traffic goes out in the clear
```

Handshakes carry a 10s timeout. Without one, a connection that opens and says
nothing pins the single-client accept loop forever — a trivial denial of service
against an app whose entire job is to be available.

## App lock

Four-digit PIN plus fingerprint, using framework
`android.hardware.biometrics.BiometricPrompt` (API 28+) rather than
androidx.biometric — which keeps the build Gradle-free.

This is **a UI gate, by choice**: it stops someone who picks up your unlocked
phone, and does not pretend to stop someone with real access to the device. The
connection credentials are not wrapped by it. The PIN is still stored as a salted
PBKDF2-SHA256 hash (120k iterations), never in the clear.

Four digits is only 10,000 possibilities, so iteration count alone buys little —
**the ten-attempt lockout is the actual defence.**

Locking stops the stream, which is worth more than it looks: the host only
captures while a client is connected, so locking the phone also stops the laptop
capturing its own screen. The app re-locks in `onStop()`, so leaving it in
Recents is not the same as leaving it unlocked.

## Capture is lazy

The host captures **only while a client is connected**. `pump()` spawns ffmpeg on
accept and kills it in a `finally`. Verified by measurement: idle server → zero
`x11grab` processes; client connects → one; client disconnects → zero again.

## Auto-start

```bash
./host/install-autostart.sh
```

Two pieces, because neither alone works here:

- a **systemd user unit** for restart-on-failure and journald logs
- an **autostart `.desktop`** that starts the unit from inside the graphical
  session and hands it `DISPLAY`/`XAUTHORITY`

The obvious approach — a unit bound to `graphical-session.target` — silently
never runs on this machine: Cinnamon under lightdm never activates that target
(`systemctl --user is-active graphical-session.target` → `inactive`). The unit
also deliberately has no `WantedBy=default.target`, which would start it at
user-session boot before X exists, where it would fail and restart forever.

```bash
systemctl --user status folddeck
journalctl --user -u folddeck -f
rm ~/.config/autostart/folddeck.desktop   # disable
```

`PYTHONUNBUFFERED=1` is set in the unit — otherwise Python block-buffers stdout
when it isn't a tty and `journalctl` shows nothing until the buffer fills.

## Input

The keyboard sends **evdev scancodes**, not characters, over the same TCP socket
the video arrives on (TCP is full-duplex, so the reverse channel is free). The
host injects them into `/dev/uinput`, so the laptop's own XKB layout, compose
key, dead keys, `Ctrl+Alt+F2` and terminal `Ctrl-C` all behave exactly as with a
real USB keyboard — because to the kernel it is one.

Wire format is `[u8 type][payload]`, fixed-size per type, so no length field is
needed and an unknown type means the stream has desynced (which drops the
connection rather than injecting random scancodes into the desktop):

| type | name | payload |
|---|---|---|
| 1 | KEY | `u16 code`, `u8 down` |
| 2 | PTR_ABS | `u16 x‰`, `u16 y‰` |
| 3 | BUTTON | `u8 button` (1=L 2=R 3=M), `u8 down` |
| 4 | SCROLL | `i16 dv`, `i16 dh` |

Input rides the reliable stream deliberately: a dropped KEY_UP leaves a modifier
stuck down on the laptop, which is worth far more than saving a millisecond.

`host/uinput.py` binds the kernel ioctls directly with ctypes rather than using
python-evdev, which isn't installed here and is a C extension needing
python3-dev. Same kernel interface, one less build dependency.

**No sudo needed on this machine** — `/dev/uinput` already carries an ACL
granting the desktop user rw (`getfacl /dev/uinput` shows `user:<you>:rw-`).
The udev rule in `host/99-folddeck.rules` is only needed where that ACL
is absent. If injection fails, `stream.py` says so and streams video anyway.

Keyboard behaviour: key-down and key-up sent separately (so chords and held keys
work), independent pointer tracking (real typists press the next key before
releasing the last), modifier latching (tap = one-shot, tap again = locked,
third tap = off), 400ms/33ms auto-repeat matching X11 defaults, slide-off to
cancel, haptics on key-down.

### ThinkPad layout

Six rows, 16u wide, every row summing to exactly 16 so the columns line up —
which is what makes a keyboard read as a keyboard rather than rows of buttons.
The ThinkPad cues:

- **Fn to the left of Ctrl** in the bottom-left corner
- **Blue Fn-layer legends** on the F-row (volume, brightness, media transport).
  Fn is resolved entirely on the phone and never sent — on a real laptop the
  keyboard controller swallows it too — and swaps in the alternate scancode so
  the host sees a genuine `KEY_VOLUMEUP`, not F3.
- **Inverted-T arrows** with Up directly above Down and PgUp/PgDn/Home/End/Ins
  forming a navigation column down the right edge
- **Keycap profile**: small radius on the top corners, much larger on the bottom
  — the "smile" ThinkPad caps have had since the Classic
- **A working red TrackPoint** between G, H and B. It drives the same virtual
  cursor as the pointer pad, so the nub and the pad never disagree about where
  the pointer is.

Shifted legends are printed above the base ones. They are **decoration only**:
the host's own XKB layout decides what Shift+2 produces, which on a UK keyboard
is `"` and not `@`. The legends assume US ANSI because that is the shape modelled.

**Every code must be ≤ 247, not merely ≤ 255.** X11 keycodes cap at 255 and X
adds 8 to the evdev code, so evdev 248+ can never reach an X client. That rules
out `KEY_MICMUTE` (248), which is why Fn+F4 carries no alternate even though a
real ThinkPad mutes the mic there.

### Pointer

Two modes, toggled by the chip in the top-right of the video pane:

- **DIRECT** (default) — the cursor goes where you touch. The right model for a
  screen showing the desktop: touching a thing puts the cursor on that thing.
- **TRACKPAD** — relative movement with acceleration, for precise work.
  Accumulated on the phone and sent as an absolute position, which keeps the host
  to one uinput device. Declaring both `ABS_X/Y` and `REL_X/Y` on a single device
  makes libinput's classification ambiguous — it may decide you are a tablet, a
  touchscreen, or nothing at all.

Gestures: tap = left click, long press = right click, double-tap-and-hold = drag,
two-finger drag = scroll.

The video is letterboxed to the stream's real aspect ratio, which the decoder
reports via `INFO_OUTPUT_FORMAT_CHANGED` rather than being hardcoded. Without
that the SurfaceView stretches the buffer to the pane, which distorts the desktop
*and* makes every touch land off-target, increasingly so towards the edges.

## Measured on this laptop (2026-07-28)

| | |
|---|---|
| Encoder chosen | `h264_vaapi`, **CQP only** — the Intel iHD driver exposes just the low-power entrypoint, so `-rc_mode CBR` fails outright |
| NVENC | unavailable — `Cannot load libcuda.so.1` |
| Encode headroom | **5.89 ms/frame** at 1920×1080 → 170 fps sustained, 35% of the 16.7 ms budget at 60 fps |
| Idle desktop bitrate | 0.3 Mbps (damage tracking for free — a static screen encodes to almost nothing) |
| Stream validity | Main profile, 1920×1080, yuv420p, decodes clean; starts `SPS PPS SEI IDR` |
| Connect → first byte | ~230 ms (ffmpeg spawn; one-time, not steady-state latency) |

Encoder headroom is measured against a *synthetic moving source*, not the live
desktop. Measuring an idle desktop mostly measures how still your screen is and
reports a flattering number that collapses the moment you drag a window.

## Run it

```bash
./android/build.sh            # ~2s, no Gradle, no network
```

Built APK: `android/build/folddeck-spike.apk` (17 KB, signed, min API 31).

### With a USB cable — lowest latency, measure this first

```bash
adb install -r android/build/folddeck-spike.apk
adb reverse tcp:5000 tcp:5000
./host/stream.py --bind 127.0.0.1 --clock
adb shell am start -n io.folddeck.spike/.MainActivity --es host 127.0.0.1 --ei port 5000
```

### Without adb (sideload)

The app prompts for `host:port` on first launch and remembers it, so no intent
extras are needed. From the phone:

```bash
scp <you>@<laptop-ip>:~/folddeck/android/build/folddeck-spike.apk /sdcard/Download/
```

Then tap it in Files to install, launch, and enter one of:

| Path | Address | Expect |
|---|---|---|
| LAN (5 GHz) | `<laptop-lan-ip>:5000` | best untethered number |
| Tailscale | `<laptop-tailnet-ip>:5000` | ~8 ms RTT of overhead before anything else |

Host side: `./host/stream.py --bind 0.0.0.0 --clock`

The HUD shows Mbps, frames decoded/dropped, and decoder p50/p99. Tap it to hide
it before filming; long-press to change the address. Those numbers are the
*client* half only — they do not include capture, encode or network.

## Measuring glass-to-glass

`--clock` opens a large millisecond clock on the laptop. Point a phone camera in
240 fps slow-motion at both screens at once, record a few seconds, then step
through frames and read both clocks in a single video frame. **The difference is
the latency.** No instrumentation, no trusting either end's timers, no clock sync.

Do it three times and take the worst. Then:

| Result | Verdict |
|---|---|
| < 60 ms | Feels direct. Build the real thing. |
| 60–100 ms | Usable; typing is fine, dragging feels soft. Proceed, but budget time for §3.4. |
| > 100 ms | Users double-type. Go to PLAN.md §0 option B. |

Check the Wi-Fi number separately from USB — if USB is 40 ms and Wi-Fi is 180 ms,
the problem is the network, not the code, and `WIFI_MODE_FULL_LOW_LATENCY` plus
5 GHz is where to look first.

## Requirements

Host: `ffmpeg`, an X11 session, `xterm` for `--clock` (optional,
`sudo apt install xterm`). All present.

Client: JDK 21 (installed) + `~/Android/Sdk` build-tools 36.0.0 and platform
android-36 (present). `aapt2`/`zipalign` are native; `javac`, `d8`, `apksigner`
and `keytool` need the JVM.

## Verified vs. not

Verified by running it:
- encoder probe, encoder headroom, live capture — host works end to end
- the served stream is valid decodable H.264 with the expected NAL order
- **the APK builds clean and its signature verifies** (v3 scheme, 17 KB); both
  classes are present in the dex with lambdas desugared
- **the Annex-B access-unit splitter**: the exact `drainNals()`/`onNal()`
  algorithm ported to Python and run against a real 173 KB capture fed in 1-, 3-,
  7-, 4096- and 65536-byte chunks. 231 access units, exactly one VCL NAL each,
  first AU `[SPS PPS SEI IDR]` flagged keyframe, no unknown NAL types, no bytes
  lost across chunk boundaries. (`host/test_parser.py`)
- **the whole input path**: every ioctl number and struct size checked against
  the kernel headers; the keyboard registers as `Handlers=sysrq rfkill kbd` and
  X11 binds it as a `slave keyboard`, the pointer as a `slave pointer`. Driving
  `input_reader()` over a socketpair with the exact bytes the client emits
  produced 6 press / 6 release in X11, right keycodes, right order — including a
  message deliberately split across two TCP writes. Absolute pointer positions
  land within 3px of where asked across all four corners, read back from
  `xdotool getmouselocation`. (`host/test_input.py`)
- **every key on the ThinkPad layout**: all 65 distinct scancodes the layout can
  emit were driven through `input_reader()` into X and all 65 arrived. This is
  what caught the `KEY_MICMUTE` ceiling above.
- **the layout geometry**: all six rows sum to exactly 16u, and the arrow cluster
  is a true inverted-T (Up directly above Down, Left and Right flanking it).
- **the icon**: exactly two colours, opaque white and fully transparent, and
  aapt2 packages it at five densities plus the v26 adaptive XML.

Verified on the device:
- the app connects and streams: one connect, zero reconnects over several
  minutes. Since `H264Stream` retries on any exception, a sustained single
  connection means `MediaCodec` accepted the `csd-0` and is consuming frames.

Not verified:
- **the glass-to-glass number this spike exists to produce**
- the keyboard on the device: layout, hit targets and latching are untested on
  real glass
- that the Qualcomm vendor low-latency key is honoured rather than ignored
- **everything added on 2026-07-31**: the home, settings, guide and address
  screens, the lock screen restyle, and the laptop chassis. It compiles and the
  APK signs with the existing key, but none of it has been on the phone — in
  particular whether `HINGE_DP` actually lands on the crease, and whether the
  even split leaves the keyboard a comfortable key pitch.

Not verified (2026-09-13 theme update):
- **the new look on the phone.** It compiles, signs with the backed-up key
  (same certificate as the backed-up keystore), and the
  theme path is tested off-device: `host/test_theme.py` splices the SEI into a
  real x264 capture and checks every video NAL is byte-identical afterwards, and
  the real `H264Stream.themeFrom` was run on the JVM against the live Matrix
  palette (44 colours). No emulator here, so no screen has been rendered.
- ~~a live `omarchy theme set` while connected~~ — **done on the Fold 2026-09-13**
  over wireless adb: Tokyo Night default → Matrix on connect → 8 live switches
  (Matte Black among them), stream never dropped, no crash in logcat. Found and
  fixed on-device: colour-emoji F1–F3 legends (now Omarchy's OSD Nerd Font icons,
  added to the font subset), brightness icon overlapping its −/+ (space added),
  and a loose gap between the PIN field and keypad (hint padding 20→6dp).
- Emulator attempt, 2026-09-13: no SDK emulator (VT-x disabled in BIOS) and Waydroid
  crashed the kernel's Rust binder driver (NULL deref oops, container never booted).
  Waydroid was left installed with `waydroid-container.service` enabled but idle.

## Gotchas already hit here

- **Fingerprint unlock never worked until 2026-09-13.** The manifest lacked
  `android.permission.USE_BIOMETRIC`, so `BiometricManager.canAuthenticate()`
  threw `SecurityException`, `LockOverlay.biometricAvailable()` swallowed it as
  "unavailable", and neither the prompt nor the button ever appeared. The phone's
  `dumpsys biometric` showed no FoldDeck session at all. Fixed: permission added,
  the catch now logs, and a one-off prompt error no longer silently turns the
  fingerprint setting off for good. Verified: first FoldDeck biometric session
  logged, app unlocked by fingerprint.

- `-rc_mode CBR` fails on this Intel iGPU: the iHD driver exposes only the
  low-power entrypoint, which is CQP-only. `host/stream.py` probes a ladder of
  encoders by actually encoding and takes the first that works.
- `ffmpeg -encoders` lists `h264_nvenc` on a machine with no `libcuda.so.1`.
  Probe by encoding, never by parsing the listing.
- `setSystemUiVisibility()` is a no-op at targetSdk 35+; `WindowInsetsController`
  is the only thing that hides the bars. `setDecorFitsSystemWindows(false)` is
  deprecated too — edge-to-edge is already the default there.
- **`onBackPressed()` is never called at targetSdk 35+.** Predictive back is on
  by default there, and it routes Back through `OnBackInvokedDispatcher`
  instead. Overriding `onBackPressed()` alone would have left Back exiting the
  app straight from the deck — the exact route the home screen exists to
  provide. Both are registered; the dispatcher is the live one on this phone.
- **Folding does not recreate the Activity.** The manifest lists `screenSize`,
  `smallestScreenSize`, `screenLayout` and `orientation` in `configChanges` to
  keep the Surface and the socket alive across a fold, which means
  `onConfigurationChanged` is the *only* notification the layout gets that the
  posture moved. Without overriding it the laptop chassis never appears when you
  open the phone.
- **A `ViewGroup` does not call `onDraw`** unless you call `setWillNotDraw(false)`
  on it. The bezels are drawn behind the children of `Chassis.Lid`/`Base`, so
  both need it.
- **`android.permission.LOCAL_NETWORK_ACCESS` does not exist in the android-36
  jar.** Android 16's Local Network Protection is not enforced for targetSdk 36,
  so a LAN connection failure is never that — check the address instead.
- `keytool` has no `-quiet` flag.

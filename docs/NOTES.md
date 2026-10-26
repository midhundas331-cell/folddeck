# FoldDeck developer notes

How the working app is built, why it is built that way, and every trap already
paid for. Started as the phase-0 latency spike — can a raw H.264 stream over one
TCP socket into `MediaCodec` feel direct enough? — and grew into the app, so some
early sections still speak in spike terms. The original, larger architecture is
in [`../PLAN.md`](../PLAN.md); most of it was never needed.

```
host/stream.py                     KMS / wf-recorder / x11grab -> H.264 -> TLS; reads input back
host/uinput.py                     virtual keyboard via /dev/uinput, no dependencies
host/test_theme.py                 theme SEI splice + extract round trip
android/src/.../H264Stream.java    TCP -> Annex-B parser -> MediaCodec -> Surface
android/src/.../KeyboardView.java  the keyboard: multitouch, latching, repeat
android/src/.../DeckLayout.java    tested video-first geometry policy
android/src/.../InputDeckLayout.java keyboard + optional trackpad allocation
android/src/.../TrackpadDeck.java  held buttons, relative pad and scroll rail
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
  rebuilds Home, the lock screen and non-editing sheets in place. An open address
  or shortcut editor stays alive until save/back so a theme event cannot erase
  unsaved text or reorder work. Not `recreate()`: that destroys the Surface,
  drops the socket and re-locks.
- **Look.** Omarchy's shell tokens, copied: corner radius 0, restrained borders,
  fills at 4 / 8 / 18 / 22 % foreground (normal / hover / selected / pressed),
  and accent for the one loud control. Product copy uses Android's clean system
  sans; addresses, status data, shortcut controls and keys use JetBrains Mono.
  Keys are square caps with a quiet edge; a pressed key fills with the accent.
  The TrackPoint is the one deliberately round, ringed interruption. The video
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
  are softly 1px-divided cells inside a 1dp accent border;
  `fillViewport` + weights stretch them to fill when few, minimum 92×52dp when
  many. A deck side lying on the app frame leaves its border off so adjacent
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

## Posture: video first

Unfolded and held in landscape, `DeckLayout.solve()` allocates a full-width 16:9
desktop plus the 56dp shortcut row first. The keyboard and optional dedicated
trackpad receive every remaining pixel below the 22dp visual hinge. At the Fold's
measured 971×875dp inner landscape this produces roughly a 602dp lid and 251dp
control base instead of wasting width in an equal half-screen letterbox.

Detection is `smallestScreenWidthDp >= 600 && orientation == landscape` — the
Fold's cover screen is around 410dp and the inner display well over 800dp, so
the standard tablet breakpoint separates them cleanly and needs no
androidx.window (which would drag Gradle into a two-second build). Cover landscape,
portrait, keyboard-hidden and trackpad-only modes use their own bounded geometry;
the pure JVM `DeckLayoutCheck` guards the thresholds.

The dedicated trackpad is optional in Settings. On the open Fold it sits beside
the resized keyboard so enabling it does not shrink the video. In portrait it
stacks below the keyboard. Its left/right zones emit down on first contact and up
only after the last contact releases; source-aware aggregation prevents a mouse,
touch gesture or trackpad from releasing another source's held button. Cancel,
detach, mode changes and disconnect all release ownership. The scroll rail,
natural direction, button handedness, pad speed, TrackPoint speed, control scale,
keyboard haptics, touchscreen pointer mode and Bluetooth mouse capture are user
preferences.

The trackpad's side is a preference per orientation (`Prefs.trackpadLeft`):
landscape defaults to right of the keyboard, portrait to left of the shortcut
shelf. Its zones are inset 6dp so the accent outline shows, as on the keyboard
and shortcut deck. The cover screen is locked to landscape at runtime
(`setRequestedOrientation(USER_LANDSCAPE)` while `smallestScreenWidthDp < 600`),
so the manifest stays `fullUser` for the inner display. The cover's
monitor/keyboard button can be dragged anywhere (`Motion.draggable`); its
position is stored as a 0..1 fraction of its travel, so it survives resizes.

## Security

**Transport: TLS 1.3, always on.** The host generates a
self-signed P-256 certificate on first run into `~/.config/folddeck/`
(`server.key` is 0600). We don't hand-roll a transport — TLS is the thing that
has actually been attacked for thirty years, and both ends ship it.

**The phone pins the certificate.** A self-signed cert means the CA chain is
meaningless, so `Secure.PinningTrustManager` records the SHA-256 of the first
certificate it sees for a given `host:port` and requires an exact match forever
after. A mismatch is **fatal, not a prompt** — the client stops and does not
retry, because a changed host certificate is precisely what interception looks
like, and an "accept anyway" button would discard the whole guarantee at the one
moment it matters. Legacy bracketed IPv6 endpoint keys are migrated to the
normalized form without losing the existing pin. Clear it deliberately if the
host is genuinely reinstalled.

**The host pins the phone.** After the handshake the client sends
`[u8 version][32-byte token]`. The token is scoped to the host certificate, so a
new or malicious endpoint cannot learn a bearer credential valid at another
host. Accepted tokens live in `~/.config/folddeck/authorized_clients`.

Trust-on-first-use is explicit. Pairing is closed unless `security.py pair` has
opened a one-use two-minute window (or the foreground host was started with
`--pairing`). A stranger who merely reaches the port cannot claim a fresh host.

```bash
python3 host/security.py          # show cert fingerprint and paired devices
python3 host/security.py pair     # one unknown device may pair for 120 seconds
python3 host/security.py forget   # remove every paired credential
./host/stream.py --pairing        # foreground alternative for initial pairing
./host/stream.py --no-tls         # debugging only; traffic goes out in the clear
```

Handshakes carry a 5s timeout on both host and phone and run in a bounded worker
pool. Partially constructed phone sockets are closed on every failed connect.
The encoder and uinput path stay single-client, but a socket that opens and says
nothing no longer pins either endpoint or blocks every legitimate connection
behind it. `--pairing` opens the same atomic one-use window as `security.py pair`,
including when another phone is already authorized. The window marker is consumed
before credential persistence, so a crash or disk error fails closed rather than
leaving a second pairing claim available.

Malformed video is bounded twice: no incomplete Annex-B NAL may grow past 8 MiB,
and no assembled access unit may exceed 8 MiB or 4,096 NALs. Crossing either
limit closes the connection instead of allowing peer-controlled heap growth.

The Activity ignores external endpoint extras, excludes credential state from
backups, disallows cleartext traffic, and sets `FLAG_SECURE` so the streamed
desktop does not appear in screenshots or the Recents snapshot.

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

## Audio (2026-10-17)

The laptop's sound follows the deck. The phone sends `MSG_AUDIO` (type 6, u8
on/off) after connecting when "Play laptop audio here" is on; `host/audio.py`
then loads a PipeWire null sink `folddeck`, makes it the default, moves every
playing stream onto it (so the speakers go quiet) and records its monitor with
`parec` as raw 48 kHz s16le stereo. The PCM rides the video stream as an SEI NAL
with UUID `FOLDDECK-AUDIO-1`, spliced at a start code like the theme, so there
is no framing change. Unlike the theme it needs emulation-prevention bytes, which
`AudioOut.unescape()` strips. The phone plays it on a low-latency `AudioTrack`
with non-blocking writes: a full buffer drops audio instead of stalling video,
which also bounds drift between the two sound clocks.

Off, or on disconnect, the previous default sink comes back, streams move back
and the module is unloaded. A leftover `folddeck` sink from a crash is removed
at host start. Silence is not sent. Raw PCM is ~1.5 Mbps; Opus is the upgrade if
that matters over Tailscale.

Order of updates matters: an older host closes the connection on type 6, so
update the **host first**. An older APK never sends type 6 and never gets audio.

Verified 2026-10-17 on the Fold 7: the default sink switches to `folddeck` on
connect, and a test tone played on the laptop shows up as a started 48 kHz
`AudioTrack` owned by `io.folddeck.spike`; confirmed by ear the same day.

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
| 2 | PTR_ABS | `u16 x‰`, `u16 y‰` — legacy, still parsed |
| 3 | BUTTON | `u8 button` (1=L 2=R 3=M), `u8 down` |
| 4 | SCROLL | `i16 dv`, `i16 dh` |
| 5 | PTR_ABS16 | `u16 x`, `u16 y`, each 0..65535 |

**Type 5 replaced type 2 when the mouse landed (2026-09-20).** Permille is 1.9px
per step across a 1920px desktop — invisible under a finger, visibly steppy
under a mouse, and below 1.0x pointer speed a small movement rounds to no
movement at all. The host still parses type 2, so an **older APK keeps working
against a newer host**; the reverse does not — a current APK against a host
predating this gets `! bad input message type 5, closing` in the journal and the
phone disconnects the moment you move the pointer. Update the host first.

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

### Bluetooth mouse (2026-09-20)

A mouse paired to the *phone* drives the *laptop*. The mechanism is Android
**pointer capture** (`View.requestPointerCapture()`, API 26): it hides the
phone's own cursor and delivers raw relative deltas to
`PointerPad.onCapturedPointerEvent`, which is the difference between a mouse for
the phone and a mouse for the laptop.

- **Captured only on the deck screen**, and only unlocked and window-focused
  (`MainActivity.updatePointerCapture`). Everywhere else it is released, because
  capture hides the cursor and Settings has things to tap. Consequence the other
  way: **Android owns the pointer on the app's own screens, and its speed with
  it** — no app can change that, it lives in Android Settings → Accessibility.
- **Captured events go to the focused view**, so `pointerPad` is made focusable
  and takes focus before the request. Without that, capture succeeds and no
  event ever arrives.
- **Left + right together hands the mouse back** to the phone, and the same
  chord takes it back. `mouseHandedBack` is sticky on purpose — the chord is a
  toggle, and re-grabbing whenever the desktop reappears makes it impossible to
  keep. The re-take is read off `getButtonState()` in **both**
  `dispatchGenericMotionEvent` and `dispatchTouchEvent`, since an uncaptured
  mouse reports buttons on whichever path the device uses, and the event is
  consumed so the press does not also click something.
- **One stray click per hand-back.** The first of the two buttons has already
  gone down on the laptop and must be released or it stays down forever. Holding
  the press back until the chord window closed would remove it, at the cost of
  that delay on every drag — the wrong trade.
- **The cursor cannot leave the desktop image, at any posture.** It is stored as
  a *fraction of the host screen* (`PointerPad.cursorX/Y`), never in phone
  pixels, so folding or rotating relays the video out underneath a cursor that
  is still on the same part of the desktop. Clamping happens in `step()`, the
  one place all three inputs — touch, nub, mouse — route through.
- **Float, not permille.** A mouse delta smaller than one step must accumulate
  rather than truncate, which is what a low pointer-speed setting is made of.
  This fixed the same latent truncation in the TrackPoint nub.
- **No cursor is drawn on the phone** (removed in 0.3). Capture hides Android's
  cursor, and the laptop's own pointer is already in the stream; an accent arrow
  drawn on top only doubled it.

Pointer speed is Settings → Mouse, `Prefs.MOUSE_SENS`, 0.25x–3.0x, where 1.0x
tracks the desktop image one-to-one.

Checks: `android/test/MouseCheck.java` (travel, clamp, sub-pixel accumulation,
the chord state machine) and `host/test_pointer.py` (the wire format, including
a fragmented message and an old permille client).

The video is letterboxed to the stream's real aspect ratio, which the decoder
reports via `INFO_OUTPUT_FORMAT_CHANGED` rather than being hardcoded. Without
that the SurfaceView stretches the buffer to the pane, which distorts the desktop
*and* makes every touch land off-target, increasingly so towards the edges.

### Physical keyboard (2026-10-01)

A Bluetooth or USB keyboard attached to the phone types on the laptop, and the
on-screen keyboard gets out of the way.

- **Detection** is `Configuration.keyboard == KEYBOARD_QWERTY` with
  `hardKeyboardHidden == NO`. The Fold has no keyboard of its own, so any QWERTY
  is external. `keyboard` had to join `configChanges` in the manifest: pairing a
  keyboard is a configuration change, and without it the Activity is recreated,
  which drops the Surface, the socket and the lock.
- **Layout**: the base (and hinge, and chassis) goes `GONE`, the lid takes the full
  height, and the desktop fills as much as its aspect allows. The band it leaves
  is no longer capped at `ROW_MAX_DP` — the shortcut deck takes all of it, as a
  scrolling grid with `width / 208dp` columns once it is taller than one row.
  Side-deck shapes are unchanged.
- **Keys** go across as `KeyEvent.getScanCode()`, which is already the kernel's
  evdev code — no keycode table. Only from `InputDevice.isExternal()` devices
  (the phone's volume rocker has scancodes too), only codes 1–255 (above is mouse
  buttons), only on the unlocked deck — elsewhere the keyboard types into the
  phone. Android's auto-repeat DOWNs are dropped: a held key repeats on the
  laptop. Held keys are tracked so a release still goes out after leaving the
  deck mid-press.
- **Escape hatch**: Settings → Appearance → "Hide keyboard when one is connected"
  (`Prefs.HIDE_KEYBOARD`, default on), for a mouse that Android mistakes for a
  QWERTY keyboard — some multi-device mice/receivers describe a full keyboard.

- **Super/Meta**: One UI takes it for Home before any app sees it. Fixed with
  Samsung's DeX remote-desktop API, `SemWindowManager.requestMetaKeyEvent(component,
  true)`, called by reflection in `onResume` (off in `onPause`, so Super is Home
  again outside the app). Signature confirmed by `dexdump` of the phone's
  `framework.jar`; a non-Samsung phone logs a warning and carries on.
- **Trackpads** (e.g. a keyboard's built-in one) arrive under pointer capture as
  `SOURCE_MOUSE_RELATIVE` deltas with taps as `BUTTON_PRIMARY` press/release, so
  they ride the existing Bluetooth-mouse path unchanged.

Verified 2026-10-01 on the Fold 7 with a Cube Pocket Keyboard (BT, built-in
trackpad): layout swap, letters/modifiers, Super, trackpad move + tap-click.
Two-finger tap arrives as `BUTTON_SECONDARY` and works unchanged. Two-finger
scroll arrives as `ACTION_SCROLL` with *fractional* `AXIS_VSCROLL` (0.004-0.12
per event); the old per-event `Math.round` turned every one into 0. The
remainder is now carried between events and whole notches sent. Smooth scroll
would need `REL_WHEEL_HI_RES` units on the wire (a host change). Both verified.

## Latency pass (0.7, 2026-10-26)

Measured on the live desktop before changing anything: wf-recorder with `-r 60`
delivered frames in **bursts of 5-7 every 60-120 ms** (arrival lagged capture by
20-160 ms). Hyprland only renders ~10 fps on a still screen, and `-r` padded the
gaps with copies of the last frame, encoded 3 ms apiece *before* the new one.
Without `-r`, each real frame is encoded the moment the compositor produces it.

Two more frames were lost on the phone: an Annex-B NAL only ends at the next
start code, so each frame waited for the next frame's first NAL, and the access
unit was only flushed when that NAL completed -- two frame intervals. And the
decoder's output was drained only when the next frame was queued, so every
decoded frame sat a further frame before reaching the screen.

What changed:
- **Host captures FLV** (`-m flv`), purely for its framing: each tag states its
  length, so `flv_frames()` knows a frame is complete when its last byte lands,
  converts it to Annex-B (SPS/PPS re-inserted before keyframes) and sends it with
  an **AUD** (`00 00 00 01 09 F0`) after it. Every unit ends with an AUD: frames,
  the palette, audio.
- **Phone**: `AnnexB` (new, JVM-checked by `AnnexBCheck`) treats an AUD as "all
  before me is complete" and flushes the access unit at once. A `folddeck-render`
  thread releases decoded frames the moment they exist; input buffers wait up to
  20 ms rather than drop (a drop smears until the next keyframe).
- **Audio** has its own send thread (~10 ms slices from `parec --latency-msec=10`)
  instead of riding video frames, which on a still screen now come ~10/s. Host
  drops audio older than 100 ms. `AudioOut.steer()` keeps the track queue at a
  40 ms target: a drained queue is padded back to 40 ms with silence, a queue past
  100 ms drops the slice. Before, it only dropped once the 180 ms buffer was full.
- **Input**: the writer sends everything queued as one write (one TLS record),
  runs at URGENT_DISPLAY priority; touch views call `requestUnbufferedDispatch`
  so events arrive as the panel reports them, not once per frame.
- **DSCP**: host marks the socket CS4 (Wi-Fi video category), phone CS6 (voice
  category; upstream is only input).
- No wire-format change: an AUD is standard H.264, so a 0.6 APK still works
  against this host (it hands the AUD to its decoder), and 0.7 works against an
  old host (it falls back to the next-picture flush).

**0.7.1 — the phone end (2026-10-26).** Samsung's adaptive refresh dropped
the panel to **20-24 Hz** while FoldDeck showed a ~19 fps desktop, so new frames
waited up to ~42 ms for a refresh. `pinRefreshRate()` sets the window's
`preferredDisplayModeId` to the fastest mode at the current resolution (re-run on
fold/unfold): measured 120 Hz steady afterwards. A `WIFI_MODE_FULL_LOW_LATENCY`
lock while in the foreground took LAN ping to the phone from p50 7.4 / max 53 ms
to p50 4.8 / max 17 ms. The host status line now prints delivered fps. The laptop
panel is 60 Hz (only mode); Hyprland renders only on change, and every frame it
renders is sent, so the stream runs at the laptop's real frame rate, up to 60.

**KMS capture — host only (2026-10-26).** A 60 fps YouTube video still looked
choppy: the status line showed 9-17 fps. Hyprland's main thread was the limit
(~33% CPU idle, ~80% playing video, 94-98% once screencopy was added on top),
so wf-recorder got whatever frames were left. The host now reads the scanout
framebuffer directly with ffmpeg `kmsgrab` → `hwmap` to VA-API → `h264_vaapi`,
no compositor involvement: **59 fps steady, capture 16.8 ms/f, Hyprland down
from ~95% to ~52%**, ~11 Mbps with video playing. It sends every vblank, so a
still screen now costs a constant stream of tiny P-frames.
- Needs CAP_SYS_ADMIN. `host/install-kms.sh` copies ffmpeg to
  `~/.local/lib/folddeck/ffmpeg-kms` (mode 0700) and `setcap`s it (sudo in a
  terminal, pkexec dialog otherwise). Re-run after an ffmpeg upgrade.
- `kms_works()` grabs 2 frames at startup and falls back to wf-recorder with the
  reason in the journal, so a missing capability never breaks streaming.
- The unit had to lose `NoNewPrivileges`, `PrivateTmp`, `ProtectKernelTunables`,
  `ProtectControlGroups` and `RestrictRealtime`: in a *user* unit the middle
  three force a user namespace (a file capability then only counts inside it)
  and `RestrictRealtime` installs a seccomp filter, which forces no_new_privs.
  Check with `grep -E 'NoNewPrivs|Seccomp' /proc/<host pid>/status` (both 0).
- kmsgrab sees only the primary plane, so `~/.config/hypr/looknfeel.lua` sets
  `cursor.no_hardware_cursors = 1` (software cursor) or the pointer vanishes.
- Undo: `rm ~/.local/lib/folddeck/ffmpeg-kms` and restart; restore the unit lines.
- **Broke the picture, disabled same day.** Hyprland scans out with modifier
  `0x100000000000004` (I915 Y_TILED_CCS, render-compressed). kmsgrab exports
  only the main plane, so VA-API read compressed tiles as plain ones: blocky
  noise on the phone. The fps number was real, the frames were garbage; check a
  frame (`hwdownload` to PNG) before trusting a capture path. Disabled by
  renaming the binary to `ffmpeg-kms.off` (capability kept). Candidate fix:
  start Hyprland with `INTEL_DEBUG=norbc` so scanout is plain Y-tiled. Needs a
  Hyprland restart. Set in `~/.config/uwsm/env` (`export INTEL_DEBUG=norbc`,
  sourced by uwsm before Hyprland starts).
- **Fixed 2026-10-26 after re-login.** Hyprland's environ has `INTEL_DEBUG=norbc`,
  kmsgrab reports modifier `0x100000000000002` (plain Y_TILED), and a grabbed
  frame converted to PNG is a clean desktop. Binary renamed back to `ffmpeg-kms`.
  If the picture turns to noise again, check that env var survived first.

Expected saving is ~3 frame intervals plus the duplicate-frame queue. Not
measured glass-to-glass; the method below still applies.
`host/test_parser.py` (a port of the old splitter) and `DecoderSchedulingCheck`
(the drain-before-dequeue order) were deleted with the code they tested.

## Stream quality: QP 26 -> 20 (2026-10-26, host only)

QP 26 kept text sharp but made video (a 1440p YouTube clip, already downscaled
to the 1080p panel) soft and blocky. Now one constant, `QP` in `host/stream.py`,
covers the KMS, ffmpeg and wf-recorder VA-API paths, set to 20. Measured
with video playing: ~25-32 Mbps at 59.7 fps, send 0.2 ms/frame on home Wi-Fi,
against ~1-3 Mbps before on a mostly still desktop. On mobile data that is
roughly 11-14 GB an hour of video; raise `QP` if that matters.

**Per network (0.8.1):** the phone sends `MSG_NETWORK` (type 7, u8 1 = mobile
data) and the host streams QP 20 (`QP_WIFI`) on Wi-Fi or Ethernet, 26
(`QP_MOBILE`) on mobile data. It decides by the network, not the route,
because the tailnet is also used from other Wi-Fi. Detection
(`MainActivity.onMobileData`) reads the active network's transports (a VPN
carries its underlying one's) and falls back to "is any Wi-Fi/Ethernet up". A
default-network callback re-sends on change, because a tailnet stream survives
a move from someone's Wi-Fi to mobile data without reconnecting. The host
restarts its encoder on a change (logs "quality: QP n for ..."), and the next
keyframe carries the new SPS/PPS in-band. **Host first:** an older host drops
the connection on type 7. Connect sends it only when on mobile data, so Wi-Fi
use against an old host still works. Needs `ACCESS_NETWORK_STATE`. Checks:
`host/test_encoder_config.py`.

## Theatre mode and cover wheel (0.8)

- **Theatre** (unfolded only): the floating ▣ button (draggable, position
  remembered like the cover ⌨) hides keyboard, trackpad, shortcut deck, chassis,
  hinge, HUD and the accent app frame, and centres the desktop on black. The
  button stays at 25% opacity; it or Back leaves. Folding turns it off. Touches
  on the image still drive the pointer, so play/pause works.
- **Cover wheel**: in cover screen-only mode a 40dp wheel (26dp until 0.8.2, too
  slim) sits in the band beside the desktop (opposite its alignment), full height. It is `TrackpadDeck`
  in wheel-only mode, so it has the same detents, haptics, coasting and natural
  scroll setting as the trackpad wheel. Its listener ignores move/click, so a
  second finger does nothing.
- Not verified on the phone by eye (FLAG_SECURE blocks screencaps).

## Split layout and one keycap style (0.7)

- **Layout preset "Split"** (Settings → Layout, per posture): the keyboard breaks
  where touch typists' hands do (F6|F7, 6|7, T|Y, G|H, B|N, space bar cut in two)
  and the trackpad fills a centred channel (`InputDeckLayout.splitChannel`, 19%
  of the width scaled by the trackpad-size slider, 120dp minimum). Each half has
  its own key unit; a short row gives the slack to its widest key. The TrackPoint
  is hidden in Split. The keyboard must have **no elevation**: a raised keyboard
  draws over the channel and takes its touches (it did, first try).
- **`Ui.drawCap`** is the single raised-control look: lip below, 1px top
  highlight, hairline rim, sinks when pressed. Keys, the trackpad buttons/pad/wheel
  well and the shortcut chips (`Ui.cap` Drawable) all use it.
- **`Bounce`**: press eases to 0.93, release springs back past 1 (back-out). Keys
  and trackpad buttons use it on the canvas; `Motion.press` uses the same curve.
- **Scroll wheel**: a drum drawn edge-on (ridges at sin(angle), thinning with
  cos), rolls with the finger, ticks with `SEGMENT_FREQUENT_TICK` at every 32dp
  detent (was 42dp), coasts after a flick (tau 0.22s) and clicks into the nearest
  detent on release (`TrackpadState.settle`, past half a notch scrolls one more).

**Screenshots:** FLAG_SECURE blackens `screencap`. For a visual check, build once
with the flag commented out, capture with `screencap -d <inner display id>` (the
Fold has two displays; without `-d` the output is a warning, not a PNG), then
reinstall the normal build. After an incremental install `am force-stop` the app,
or the old process keeps running. Rendering views off-screen via `app_process`
does not work: system fonts never load there. A temporary intent-extra hook that
opens a screen in `onResume` must check the views exist and remove the extra after
use: unguarded, it crash-looped the app when Android relaunched it from recents
(2026-10-26).

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

## Versions

`android/AndroidManifest.xml` is the only place a version is written —
`versionName` for people, `versionCode` for Android (it refuses a downgrade, so
it must go up on every build you intend to install). Settings → About reads both
back off the PackageManager rather than repeating them, so what it shows is what
is actually installed.

| version | code | what landed |
|---|---|---|
| 0.1-spike | 1 | everything up to the Omarchy theme and shortcut deck |
| 0.2 | 2 | Bluetooth mouse: pointer capture, themed cursor, `MSG_PTR_ABS16` |
| 0.3 | 3 | Physical keyboard: hides the on-screen one, forwards evdev scancodes; full-height desktop + shortcut grid; drawn cursor removed |
| 0.4 | 4 | Explicit pairing window, per-host phone credentials, TLS 1.3 only, DoS/input/parser hardening, bounded decoder scheduling, premium motion/type hierarchy and TrackPoint polish |
| 0.5 | 5 | Video-first Fold geometry, open-portrait control shelf, cover monitor/input transition, floating depth hierarchy, personalization, dedicated trackpad, held source-safe buttons and themed scroll rail |
| 0.6 | 6 | Laptop audio on the phone, trackpad tap-to-click, layout presets per posture, keyboard/trackpad size, keyboard look, shortcut bar placement, landscape-only cover, movable cover button |
| 0.8.4 | 14 | Switches a tailnet stream back to home Wi-Fi when it returns (certificate-checked) |
| 0.8.5 | 15 | Split keyboard halves share one key size (pad takes the slack); floating buttons follow the theme; Settings split into Omarchy-style category pages |
| 0.8.6 | 16 | Settings labels rewritten in plain words (e.g. TrackPoint → Red pointer dot, Forget certificate → Reset laptop trust) |
| 0.9 | 17 | Settings redesigned iOS-style: laptop card, grouped rounded lists, colour icon tiles, pill switches, round-knob sliders, segmented controls; Apple-style wording (short Title Case names, explanations in group footers) |
| 0.9.1 | 18 | Settings back to Omarchy style, modelled on the Omarchy menu: 2px-bordered square card, "Settings…" heading, Nerd Font icon rows, cursor fill + accent label on press, 20% dividers, ToggleSwitch/PanelSlider/ButtonGroup controls; bigger (60dp rows, 17sp mono). Keeps 0.9's wording |
| 0.9.2 | 19 | Home and How to Use moved onto the Omarchy menu card (connection state + both routes in one section, actions as icon rows); guide rewritten in Apple-style wording; no more desktop flash between sheets |
| 0.8.3 | 13 | Audio errors cost the sound, not the stream. Ponytail pass: dead helpers removed |
| 0.8.2 | 12 | Cover wheel full height, 40dp wide |
| 0.8.1 | 11 | Tells the laptop when it is on mobile data; stream quality QP 20 on Wi-Fi, 26 on mobile (host first) |
| 0.8 | 10 | Theatre mode (unfolded: floating ▣ shows the desktop alone, centred on black, no frame or borders; Back or ▣ leaves), slim scroll wheel beside the cover desktop in screen-only mode |
| 0.7.2 | 9 | A dropped stream re-measures both routes, so leaving Wi-Fi fails over to the tailnet |
| 0.7.1 | 8 | Panel pinned to max refresh, low-latency Wi-Fi lock |
| 0.7 | 7 | Latency pass (frame-exact video, render thread, steered audio, batched input), Split layout, one keycap style for keys/trackpad/shortcuts, scroll wheel with detents |

**0.2 needs a host from 2026-09-20 or later** — see the wire-format table under
Input. Bump `versionCode` before building anything you plan to sideload.

## Run it

```bash
./android/build.sh            # ~2s, no Gradle, no network
```

Built APK: `android/build/folddeck.apk` (239,136 bytes, v3-signed, min API 31).

### With a USB cable — lowest latency, measure this first

```bash
adb install -r android/build/folddeck.apk
adb reverse tcp:5000 tcp:5000
./host/stream.py --bind 127.0.0.1
adb shell am start -n io.folddeck.spike/.MainActivity
```

### Without adb (sideload)

The app prompts for `host:port` on first launch and remembers it, so no intent
extras are needed. From the phone:

```bash
scp <you>@<laptop-ip>:~/folddeck/android/build/folddeck.apk /sdcard/Download/
```

Then tap it in Files to install, launch, and enter one of:

| Path | Address | Expect |
|---|---|---|
| LAN (5 GHz) | `<laptop-lan-ip>:5000` | best untethered number |
| Tailscale | `<laptop-tailnet-ip>:5000` | ~8 ms RTT of overhead before anything else |

Host side: `./host/stream.py --bind 0.0.0.0`

The optional HUD shows Mbps, frames decoded/dropped, and decoder p50/p99. Its
visibility is controlled in Settings; address editing lives on Home/Settings.
Those numbers are the *client* half only — they do not include capture, encode
or network.

## Measuring glass-to-glass

Put any large millisecond stopwatch on the laptop (a browser page will do; the
old `--clock` window was removed 2026-10-26, unused and X11-only). Point a phone
camera in 240 fps slow-motion at both screens at once, record a few seconds, then step
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

Host: `ffmpeg`, and `wf-recorder` on Wayland (or an X11 session). All present.

Client: JDK 21 (installed) + `~/Android/Sdk` build-tools 36.0.0 and platform
android-36 (present). `aapt2`/`zipalign` are native; `javac`, `d8`, `apksigner`
and `keytool` need the JVM.

## Verified vs. not

Verified by running it:
- encoder probe, encoder headroom, live capture — host works end to end
- the served stream is valid decodable H.264 with the expected NAL order
- **the APK builds clean and its signature verifies** (v3 scheme, 235,040 bytes); all
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
  `xdotool getmouselocation`. (`host/test_input.py`, removed 2026-10-26: it
  needed X11 and the retired permille message; `test_pointer.py` covers parsing.)
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
- that the Qualcomm vendor low-latency key is honoured rather than ignored
- live automated presses on the dedicated trackpad buttons and scroll rail were
  deliberately not injected against the active desktop; their ownership,
  release and accumulation state machines are covered by JVM checks instead.

Verified on the Fold for 0.5:
- inner landscape at 2184×1968/360dpi: full-width video, 56dp shortcut row,
  comfortable six-row keyboard, optional 271dp side trackpad and 44dp scroll rail;
- Settings scrolls without clipping and exposes the new personalization controls;
- secure and temporary visual artifacts were both exercised, then the secure
  artifact was reinstalled and screenshot protection re-confirmed;
- Home, Settings and deck depth/spacing were inspected in dark and live-stream states.

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

- **Ponytail pass + bug hunt (2026-10-26).** Removed: `--probe` (encoder
  headroom/cadence, numbers already recorded above), `--clock` + `clock.py`,
  the retired permille pointer message (type 2, pre-0.2 APKs) with
  `VirtualPointer.move` and the X11-only `test_input.py`, plus helpers that had no
  callers (`Ui.gap/title`, `Endpoints.summary`, `PointerPad.releaseButtonSource`,
  `Prefs.LAST`, `security.new_token`). Bugs fixed: (1) a desynced or closed
  input reader printed "closing" but left the stream running, with video on and
  input dead; it now shuts the socket down (`test_pointer.py`). (2) An AudioTrack
  the system refused threw out of the receive thread and dropped the whole
  stream into a reconnect loop; now it only drops that audio slice.
- **Settings as pages (0.8.5).** Settings was one ~40-row scroll. It is now an
  Omarchy-style menu (hero with version + laptop address, then Connection,
  Display, Keyboard, Trackpad & touch, Shortcuts, Mouse, Security), each opening
  its own page; Back steps up to the menu first (`Panel.up()`). Rows are square
  with a 1px border at 40% (Omarchy's panel item), a whole toggle row is the
  touch target, sliders and segment pickers sit full-width under their label.
  Split keyboard: both halves now use the step of the wider half (9.5 units),
  so keys match and the trackpad between them grows ~50% (`KeyboardView.splitEdges`).
  The theatre and cover floating buttons are repainted in `applyTheme`.
- **Settings, iOS style (0.9).** The user rejected the Omarchy-square pages and
  the long descriptive labels ("make it like Apple"). Now: a laptop card, then
  grouped rounded lists (12dp, `Ui.GROUP` fill, inset `Ui.SEPARATOR` hairlines),
  white-glyph icon tiles in the theme's named colours (`Ui.BLUE/CYAN/MAGENTA/ORANGE`,
  falling back to color1-6), animated pill switches, round-knob sliders and a
  segmented control in `Ui`. Row names are short Title Case; what a setting does
  lives once in the grey footer under its group. Verified on the phone with a
  temporary build (FLAG_SECURE off + an intent extra opening each page), then
  restored.
- **Settings, Omarchy menu style (0.9.1).** The user liked 0.9's wording but
  not the iOS look, and pointed at the Omarchy menu (`omarchy menu`, source
  `/usr/share/omarchy/shell/plugins/menu/Menu.qml`). Settings now copies it:
  a square card with a 2px foreground border, a dim "Settings…" heading, rows
  of Nerd Font icon + mono label + chevron, the pressed row filled with the
  selected colour and its label in the accent, 20% hairline dividers between
  sections. The icons are a 7 KB subset of JetBrainsMono Nerd Font Mono
  (`assets/fonts/Icons.ttf`, `pyftsubset --unicodes=...`; the codepoints are the
  ones `omarchy-menu.jsonc` uses). Controls follow Omarchy's own: square
  ToggleSwitch, PanelSlider (round foreground knob ringed in the background),
  ButtonGroup cells.
- **Desktop flash between sheets (fixed 0.9.2).** `showScreen` hid the old
  sheet at once and faded the new one in from alpha 0 (`Motion.enter`), so the
  live deck underneath showed through for the length of the fade. Sheets now
  only fade in when they rise over the deck; sheet-to-sheet is an instant swap.
- **One menu style everywhere (0.9.2).** `Ui.menuCard/menuHeading/menuSection/
  menuNote/divider/menuItem` are the Omarchy menu pieces; Home, Settings and
  How to Use are all built from them.
- **Never adb-tap while the user may be on the deck.** A test tap meant for
  Home's Settings button landed on the keyboard's "8" because the user had
  returned to the desktop between screenshot and tap. Screenshot, then tap in
  the same command, and only on screens without the keyboard.
- **Back to home Wi-Fi (0.8.4).** A tailnet stream never drops when home Wi-Fi
  returns, so it used to stay on the slower route. Now, every 15 s while on the
  tailnet and not on mobile data, `MainActivity.homeCheck` runs
  `Secure.presents()`, a TLS handshake to the home address that pins nothing and
  sends no hello (the host logs "closed during hello" and keeps the stream). It
  switches only if the home address presents the same certificate as the
  laptop already streaming, so a stranger's device at the same 192.168.x address
  on someone else's Wi-Fi is never picked. Tested against the live host: right
  certificate true, wrong one false, closed port false.
- **LAN path can break on its own.** 2026-10-26 ~06:00: phone on home Wi-Fi, ARP
  resolved, but ping, adb and Tailscale direct all failed (Tailscale went via
  the DERP relay), so the app correctly chose the tailnet. It came back by
  itself (or after a Wi-Fi toggle). Not an app bug; check `ping` and
  `tailscale status` (direct or relay) before debugging route choice.

- **Wi-Fi → mobile data locked the phone out (fixed 2026-10-26).** Leaving Wi-Fi
  never closes the old socket, so the host held the dead stream for TCP's
  ~15 min retry window and refused every tailnet reconnect with "another stream
  is active". The phone itself was fine; `tailscale ping` worked. Now an
  authenticated newcomer shuts the stale socket down and takes over (logged
  "takes over the stream"), and `TCP_USER_TIMEOUT` 10 s drops an orphaned stream
  on its own, so the laptop gets its audio back. Check: `host/test_accept.py`.
  Second half, on the phone (0.7.2): the route was chosen once per connect,
  so after the drop the retry loop redialled the dead LAN address until it gave
  up. Now each failed redial of a stream that had been open re-probes both
  addresses (`MainActivity.reroute`) and switches only if the other one answers,
  so a host restart still just retries.

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

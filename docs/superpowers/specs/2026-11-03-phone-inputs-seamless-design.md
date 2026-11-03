# Seamless phone mic + camera: design

Brief: `docs/BRIEF-phone-inputs-seamless.md` (decisions already made there; this records
how they are implemented and why). Target device: Galaxy Z Fold7 SM-F966B, Android 17
(API 37); app targetSdk 36, minSdk 31.

## Behaviour

- On connect, with Settings → Phone Sharing → microphone/camera enabled and permitted,
  the host creates `folddeck_mic` / `folddeck_camera` and makes them the session
  defaults (unchanged host code, `demand.Default`). This now happens whether or not
  FoldDeck has window focus: the mask the phone sends is
  `switch enabled & permission granted`, nothing else.
- Capture stays on demand: the phone opens the mic/camera only while the host reports a
  real consumer (link active on `folddeck_mic`, v4l2loopback client usage), under the
  existing 3 s revision-bound lease (`CaptureGate`).
- No in-app "Microphone live / Camera live" badge, and no single tap that disables
  inputs (`PhoneSharing.stopInputs()` is deleted). Android's privacy indicators and the
  mandatory foreground-service notification are the only indicators. Enable/disable
  lives only in Settings → Phone Sharing.
- Disconnect (stream stopped, app killed, host stopped) restores the previous defaults.

## Background: foreground service + sharing-only stream

**Android rules (official docs, checked 2026-11-03; nothing newer for API 36/37 on
these types):** targetSdk ≥ 34 must declare `foregroundServiceType` and hold
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` / `FOREGROUND_SERVICE_CAMERA`;
`RECORD_AUDIO` / `CAMERA` must be granted when `startForeground()` runs, else
`SecurityException`. Both are while-in-use permissions, so the service must be started
while the app has a visible activity; from the background it throws
(`ForegroundServiceStartNotAllowedException` / `SecurityException`). POST_NOTIFICATIONS
(API 33+) only controls whether the notification is shown in the drawer; the service
runs without it.

**Verification limit:** the only way to drive the PIN-locked app here is a
same-signature instrumentation, and Android treats an instrumented process as
foreground (`fg F/S/FGS (instrumentation)`, all capabilities). A build with the
service disabled still recorded in background under instrumentation, so on-device
tests prove the service is started correctly (types 0xC0, notification posted, no
refusal logged), not that it is what keeps a real background session recording.

**Service:** `SharingService`, `foregroundServiceType="microphone|camera"`. Started
(`startForegroundService`) by `PhoneSharing` when a stream is attached or the user
changes a switch / grants a permission, i.e. only from foreground UI paths, with the
type set to exactly the enabled-and-granted inputs. Stopped when the stream is dropped
(`PhoneSharing.disconnect()`) or no input remains enabled. It never restarts itself
from the background (`START_NOT_STICKY`); a background failure just costs the inputs.
Notification: low-importance channel "Phone sharing", ongoing, tap opens FoldDeck.

**The stream problem:** today `surfaceDestroyed()` and `onStop() → relock()` both call
`dropStream()`, which closes the TLS socket. Backgrounding therefore always killed
sharing, independent of the focus gate.

**Choice: keep the socket, pause video.** When the surface goes away or the app
relocks, and mic or camera sharing is enabled, the stream enters a *paused*
(sharing-only) state instead of being dropped:

- phone releases its decoder synchronously (the Surface is about to be destroyed) and
  sends `MSG_VIDEO off` (new message type 9) and `MSG_AUDIO off`;
- host kills the screen-capture/encoder process on `MSG_VIDEO off` and starts a fresh
  one on `MSG_VIDEO on`, so a resumed stream starts on an IDR immediately (fresh
  encoder = IDR first, same as a new connection);
- the TLS socket, input reader, sharing worker and `folddeck_mic`/`folddeck_camera`
  stay up, so a laptop app already recording keeps recording and defaults don't flap;
- resume (surface back and unlocked) re-attaches the surface, restores laptop audio per
  the user's preference and sends `MSG_VIDEO on`. The decoder is configured on the next
  SPS/PPS, which always precede an IDR.
- if no mic/camera input is enabled, behaviour is exactly as before (`dropStream()`).

Rejected: dropping and re-dialling a sharing-only connection (defaults and a live
recording would flap on every app switch); leaving video flowing while hidden (laptop
keeps capturing its screen and sending ~25 Mbps to a phone in a pocket, and breaks the
"locking stops the laptop capturing its screen" property).

**Locked.** FoldDeck relocks on every `onStop()`, so "background" always means
"FoldDeck-locked". While locked: the lock overlay is up; no video is captured on the
laptop; laptop audio returns to the laptop; no input can be sent (overlay); clipboard
sync is paused (focus-gated, laptop text received meanwhile is applied only once
FoldDeck is focused again, i.e. after unlock). **What keeps working while locked is
exactly phone→laptop mic/camera, on demand**, for the paired laptop. That is the
requested feature; it is a deliberate widening: before, a laptop app could only use
the phone mic/camera while FoldDeck was focused. Guards: per-input switch, real
consumer required, Android privacy dot + FGS notification. The phone screen being off
/ keyguard is the same state; it was not tested here (testing would need the user's
keyguard PIN).

## Startup latency

Measured baseline (0.12.2, `android/device-test/latency-check.py`, 5 sessions):
consumer opens `folddeck_mic` as it appears → first phone sample: median 216 ms
(202–253). That case is flattered: the host polls demand right after configuring, at
the same moment. A consumer opening mid-session waits for the 0.5 s `pw-dump` poll
first (0–500 ms, mean ~250 ms) on top.

Changes:
1. **Event-driven demand (host).** A `pactl subscribe` watcher wakes the sharing worker
   on any `source-output` event; the worker then polls demand back to back (10 ms
   waits) for 300 ms, since the link turns active tens of ms after the event. The 0.5 s
   poll stays as the fallback. Camera usage (a non-blocking v4l2 event ioctl) is read
   every worker pass (≤ 50 ms) instead of every 0.5 s. `client` events are ignored:
   `pw-dump` itself generates them, which would self-trigger.
2. **Mic start off the main thread (phone).** `AudioRecord` construction +
   `startRecording()` move onto the capture thread; DEMAND handling no longer blocks
   the UI thread. Timings are logged (`FoldDeck/Share`).
3. **Pre-warming AudioRecord: not done.** Measured on the Fold: construction 6–12 ms,
   `startRecording()` 4–7 ms, first PCM ~30 ms later (HAL buffer). Pre-creating could
   save ≤ 12 ms while holding a recorder (and possibly a HAL input) open when idle.
4. **Host zero-padding: not needed.** The consumer reads the monitor of a null sink fed
   by a running `pacat`; it already sees continuous (zero) samples before phone audio,
   so nothing to pad. `pacat --latency-msec=40` stays (lower risks underrun crackle).

Measured after (0.13.0): cold open→first phone audio median 96 ms (90–109, 4
sessions; was 216). Mid-session demand detection on a scratch `folddeck_mic` node:
open→DEMAND median 50 ms (max 55) vs 279 ms (max 534) with the 0.5 s poll, so a
mid-session cold start is now ~50 ms + ~100 ms instead of up to ~0.5 s + ~0.2 s.

Known limit: `hermes chat` keeps its PortAudio input stream open for the recorder's
lifetime and the stream follows the default source, so once FoldDeck connects the
phone mic is *in demand for the whole session while Hermes runs* (privacy dot on,
~0.8 Mbit/s). Ctrl+B is then instant. Making it on-demand needs Hermes to close its
stream between recordings; out of scope (not this repo).

## Gain

Host sets `folddeck_mic` to 250 % because VOICE_RECOGNITION PCM arrived at ~5 % full
scale. Digital gain is the same wherever it is applied, so only the `AudioSource`
choice (HAL gain/processing) can improve SNR. **Not resolved:** a controlled comparison
needs a stimulus; the laptop speakers were muted (left untouched) and the phone-speaker
route did not play the tone, and ambient-only readings showed no consistent difference
(VOICE_RECOGNITION / MIC / UNPROCESSED all −51…−61 dBFS RMS at 100 %). Kept
VOICE_RECOGNITION + 250 %. Note loud ambient sound already reaches 0 dBFS at 250 %, so
loud speech close to the phone may clip; needs a speech test by the user.

## Host stop

Found while checking acceptance B: `systemctl --user stop folddeck` (SIGTERM) killed
the host without running cleanup, leaving `folddeck_mic`, the laptop-audio null sink
and the default source pointing at the phone. The host now turns SIGTERM into a normal
exit and closes the live session (defaults restored to the recorded previous ones).
SIGKILL is still covered only by `cleanup_stale()` at the next start.

## Out of scope

Camera quality/transport (JPEG 640×480 @ 10 fps stays). Hermes changes. Testing with
the phone screen off.
